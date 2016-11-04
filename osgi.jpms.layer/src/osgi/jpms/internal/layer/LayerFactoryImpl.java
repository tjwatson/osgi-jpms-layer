/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package osgi.jpms.internal.layer;

import java.lang.module.Configuration;
import java.lang.module.ModuleFinder;
import java.lang.reflect.Layer;
import java.lang.reflect.Module;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock.ReadLock;
import java.util.concurrent.locks.ReentrantReadWriteLock.WriteLock;
import java.util.function.Consumer;
import java.util.function.Function;

import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleEvent;
import org.osgi.framework.Constants;
import org.osgi.framework.FrameworkEvent;
import org.osgi.framework.FrameworkListener;
import org.osgi.framework.SynchronousBundleListener;
import org.osgi.framework.hooks.weaving.WeavingHook;
import org.osgi.framework.hooks.weaving.WovenClass;
import org.osgi.framework.hooks.weaving.WovenClassListener;
import org.osgi.framework.namespace.BundleNamespace;
import org.osgi.framework.wiring.BundleCapability;
import org.osgi.framework.wiring.BundleRevision;
import org.osgi.framework.wiring.BundleWiring;
import org.osgi.framework.wiring.FrameworkWiring;
import org.osgi.resource.Requirement;
import org.osgi.resource.Resource;

import osgi.jpms.layer.LayerFactory;

public class LayerFactoryImpl implements LayerFactory, WovenClassListener, WeavingHook, SynchronousBundleListener, FrameworkListener {

	static void addReadsNest(Map<BundleWiring, Module> wiringToModule) {
		Set<Module> bootModules = Layer.boot().modules();
		Collection<Module> allBundleModules = wiringToModule.values();
		// Not checking for existing edges for simplicity.
		for (Module module : allBundleModules) {
			// First add reads to all boot modules.
			AddReadsUtil.addReads(module, bootModules);
			// Now ensure bidirectional read of all bundle modules.
			AddReadsUtil.addReads(module, allBundleModules);
			// Add read to the system.bundle module.
			AddReadsUtil.addReads(module, Collections.singleton(systemModule));
		}
	}

	class NamedLayerImpl implements NamedLayer {
		final Layer layer;
		final String name;
		final long id = nextLayerId.getAndIncrement();
		final AtomicReference<Consumer<Event>> consumers = new AtomicReference<>((e) -> {});
		final AtomicReference<Boolean> isValid = new AtomicReference<>(true);
		NamedLayerImpl(Layer layer, String name) {
			this.layer = layer;
			this.name = name;
		}
		@Override
		public Layer getLayer() {
			return layer;
		}

		@Override
		public String getName() {
			return name;
		}

		@Override
		public long getId() {
			return id;
		}

		@Override
		public boolean isValid() {
			return isValid.get();
		}
		public void invalidate() {
			isValid.updateAndGet((previous) -> {
				if (previous) {
					consumers.get().accept(Event.INVALID);
				}
				return false;
			});
		}
		@Override
		public void consumeEvents(Consumer<Event> consumer) {
			consumers.updateAndGet((previous) -> previous.andThen(consumer));
		}
		
	}

	enum LoaderType {
		OneLoader,
		ManyLoaders,
		MappedLoaders;
	}

	// requirement that finds all capabilities in the bundle namespace
	private static Requirement ALL_BUNDLES_REQUIREMENT = new Requirement() {
		@Override
		public String getNamespace() {
			return BundleNamespace.BUNDLE_NAMESPACE;
		}

		@Override
		public Map<String, String> getDirectives() {
			return Collections.emptyMap();
		}

		@Override
		public Map<String, Object> getAttributes() {
			return Collections.emptyMap();
		}

		@Override
		public Resource getResource() {
			return null;
		}	
	};

	private final static Module systemModule = LayerFactoryImpl.class.getModule().getLayer().findModule(Constants.SYSTEM_BUNDLE_SYMBOLICNAME).get();
	private final FrameworkWiring fwkWiring;
	private final WriteLock layersWrite;
	private final ReadLock layersRead;
	private final AtomicLong nextLayerId = new AtomicLong(0);

	private Map<Module, Collection<NamedLayerImpl>> moduleToNamedLayers = new HashMap<>();
	private Map<BundleWiring, Module> wiringToModule = new TreeMap<>((w1, w2) ->{
		String n1 = w1.getRevision().getSymbolicName();
		String n2 = w2.getRevision().getSymbolicName();
		n1 = n1 == null ? "" : n1;
		n2 = n2 == null ? "" : n2;
		int nameCompare = n1.compareTo(n2);
		if (nameCompare != 0) {
			return nameCompare;
		}
		int versionCompare = -(w1.getRevision().getVersion().compareTo(w1.getRevision().getVersion()));
		if (versionCompare != 0) {
			return versionCompare;
		}
		return w1.getBundle().compareTo(w2.getBundle());
	});


	public LayerFactoryImpl(BundleContext context) {
		fwkWiring = context.getBundle(Constants.SYSTEM_BUNDLE_LOCATION).adapt(FrameworkWiring.class);
		ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
		layersWrite = lock.writeLock();
		layersRead = lock.readLock();
	}

	private Set<BundleWiring> getInUseBundleWirings() {
		Set<BundleWiring> wirings = new HashSet<>();
		Collection<BundleCapability> bundles = fwkWiring.findProviders(ALL_BUNDLES_REQUIREMENT);
		for (BundleCapability bundleCap : bundles) {
			BundleRevision revision = bundleCap.getRevision();
			BundleWiring wiring = revision.getWiring();
			// Ignore system bundle, we assume the launcher created a layer for the system.bundle module
			if (wiring != null && wiring.isInUse() && revision.getBundle().getBundleId() != 0) {
				wirings.add(wiring);
			}
		}
		return wirings;
	}

	private void createNewWiringLayers() {
		layersWrite.lock();
		try {
			// first clean up layers that are not in use anymore
			for (Iterator<Entry<BundleWiring, Module>> wirings = wiringToModule.entrySet().iterator(); wirings.hasNext();) {
				Entry<BundleWiring, Module> wiringModule = wirings.next();
				if (!wiringModule.getKey().isInUse()) {
					// remove the wiring no long in use
					wirings.remove();
					// invalidate any named layers that used it
					Collection<NamedLayerImpl> namedLayers = moduleToNamedLayers.remove(wiringModule.getValue());
					if (namedLayers != null) {
						for (NamedLayerImpl namedLayer : namedLayers) {
							namedLayer.invalidate();
						}
					}
					AddReadsUtil.clearAddReadsCunsumer(wiringModule.getValue());
				}
			}
			Set<BundleWiring> currentWirings = getInUseBundleWirings();

			// create modules for current wirings that don't have layers yet
			for (BundleWiring wiring : currentWirings) {
				if (!wiringToModule.containsKey(wiring)) {
					BundleLayerFinder finder = new BundleLayerFinder(wiring);
					Configuration config = Layer.empty().configuration().resolveRequires(finder, ModuleFinder.of(), Collections.singleton(finder.name));
					// Map the module names to the wiring class loaders
					Layer layer = Layer.empty().defineModules(
							config, 
							(name) -> {
								return Optional.ofNullable(
										finder.name.equals(name) ? wiring : null).map(
												(w) -> {return w.getClassLoader();}).get();
							});
					AddReadsUtil.defineAddReadsConsumer(layer);
					wiringToModule.put(wiring, layer.modules().iterator().next());
				}
			}
			addReadsNest(wiringToModule);
		} finally {
			layersWrite.unlock();
		}
	}

	private NamedLayer createLayer(LoaderType type, String name, Set<Path> paths, Set<String> roots, ClassLoader parent, Function<String, ClassLoader> mappedLoaders) {
		ModuleFinder finder = ModuleFinder.of(paths.toArray(new Path[0]));
		layersWrite.lock();
		try {
			createNewWiringLayers();
			Collection<Module> modules = wiringToModule.values();
			List<Configuration> configurations = getConfigurations(modules);
			List<Layer> layers = getLayers(modules);
			Configuration config = Configuration.resolveRequires(finder, configurations, ModuleFinder.of(), roots);
			Layer layer;
			switch (type) {
				case OneLoader:
					layer = Layer.defineModulesWithOneLoader(config, layers, parent);
					break;
				case ManyLoaders :
					layer = Layer.defineModulesWithManyLoaders(config, layers, parent);
					break;
				case MappedLoaders :
					layer = Layer.defineModules(config, layers, mappedLoaders);
					break;
				default:
					throw new IllegalArgumentException(type.toString());
			}
			NamedLayerImpl result = new NamedLayerImpl(layer, name);
			for (Module m : modules) {
				Collection<NamedLayerImpl> children = moduleToNamedLayers.get(m);
				if (children == null) {
					children = new ArrayList<>();
					moduleToNamedLayers.put(m, children);
				}
				children.add(result);
			}
			return result;
		} finally {
			layersWrite.unlock();
		}
	}

	private static List<Configuration> getConfigurations(Collection<Module> modules) {
		List<Configuration> result = new ArrayList<>(modules.size());
		for (Module m : modules) {
			result.add(m.getLayer().configuration());
		}
		// always add the system.bundle configuration (which has boot as parent)
		result.add(systemModule.getLayer().configuration());
		return result;
	}

	private List<Layer> getLayers(Collection<Module> modules) {
		List<Layer> result = new ArrayList<>(modules.size() + 2);
		for (Module m : modules) {
			result.add(m.getLayer());
		}
		// always add the system.bundle layer (which has boot as parent)
		result.add(systemModule.getLayer());
		return result;
	}

	@Override
	public NamedLayer createLayerWithOneLoader(String name, Set<Path> paths, Set<String> roots, ClassLoader parent) {
		return createLayer(LoaderType.OneLoader, name, paths, roots, parent, null);
	}

	@Override
	public NamedLayer createLayerWithManyLoaders(String name, Set<Path> paths, Set<String> roots, ClassLoader parent) {
		return createLayer(LoaderType.ManyLoaders, name, paths, roots, parent, null);
	}

	@Override
	public NamedLayer createLayerWithMappedLoaders(String name, Set<Path> paths, Set<String> roots, Function<String, ClassLoader> mappedLoaders) {
		return createLayer(LoaderType.MappedLoaders, name, paths, roots, null, mappedLoaders);
	}

	@Override
	public void modified(WovenClass wovenClass) {
		if (wovenClass.getState() == WovenClass.TRANSFORMED) {
			boolean createNewLayer;
			// need to make sure the class loader is associated with a layer before allowing a class define
			layersRead.lock();
			try {
				// check if there is an existing module for this wiring
				createNewLayer = wiringToModule.get(wovenClass.getBundleWiring()) == null;
			} finally {
				layersRead.unlock();
			}
			if (createNewLayer) {
				createNewWiringLayers();
			}
		}
	}

	@Override
	public void bundleChanged(BundleEvent event) {
		switch (event.getType()) {
		case BundleEvent.UNINSTALLED:
		case BundleEvent.UNRESOLVED:
		case BundleEvent.UPDATED:
			// any of the above events can cause a layer with the
			// bundle to become invalidated.
			createNewWiringLayers();
			break;
		default:
			break;
		}
	}

	@Override
	public void frameworkEvent(FrameworkEvent event) {
		if (event.getType() == FrameworkEvent.PACKAGES_REFRESHED) {
			createNewWiringLayers();
		}
	}

	@Override
	public void weave(WovenClass wovenClass) {
		// do nothing; just need to make sure there is a hook so the WovenClassListener will get called
	}
}
