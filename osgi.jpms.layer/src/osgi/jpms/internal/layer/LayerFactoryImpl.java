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

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.lang.module.Configuration;
import java.lang.module.ModuleFinder;
import java.lang.module.ResolutionException;
import java.lang.reflect.Layer;
import java.lang.reflect.Layer.Controller;
import java.lang.reflect.LayerInstantiationException;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock.ReadLock;
import java.util.concurrent.locks.ReentrantReadWriteLock.WriteLock;
import java.util.function.Consumer;
import java.util.function.Function;

import org.osgi.framework.Bundle;
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
import org.osgi.framework.namespace.HostNamespace;
import org.osgi.framework.namespace.PackageNamespace;
import org.osgi.framework.wiring.BundleCapability;
import org.osgi.framework.wiring.BundleRevision;
import org.osgi.framework.wiring.BundleWire;
import org.osgi.framework.wiring.BundleWiring;
import org.osgi.framework.wiring.FrameworkWiring;
import org.osgi.resource.Requirement;
import org.osgi.resource.Resource;

import osgi.jpms.layer.LayerFactory;

public class LayerFactoryImpl implements LayerFactory, WovenClassListener, WeavingHook, SynchronousBundleListener, FrameworkListener {

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

	public static final String BOOT_JPMS_MODULE = "equinox.boot.jpms.module";
	private final static Module systemModule = LayerFactoryImpl.class.getModule().getLayer().findModule(Constants.SYSTEM_BUNDLE_SYMBOLICNAME).get();
	private final static String CACHE_FILE = "osgi.jpms.layer/privates.cache";
	private final Activator activator;
	private final BundleContext context;
	private final FrameworkWiring fwkWiring;
	private final WriteLock layersWrite;
	private final ReadLock layersRead;
	private final AtomicLong nextLayerId = new AtomicLong(0);
	private final ResolutionGraph<BundleWiring, BundlePackage> graph = new ResolutionGraph<>();
	private final BundleWiringPrivates privatesCache;
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
	private final HashMap<Module, Controller> controllers = new HashMap<>(); 

	public LayerFactoryImpl(Activator activator, BundleContext context) {
		this.activator = activator;
		this.context = context;
		privatesCache = loadPrivatesCache(context, activator);
		Bundle systemBundle = context.getBundle(Constants.SYSTEM_BUNDLE_LOCATION);
		fwkWiring = systemBundle.adapt(FrameworkWiring.class);
		ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
		layersWrite = lock.writeLock();
		layersRead = lock.readLock();

		BundleWiring systemWiring = systemBundle.adapt(BundleWiring.class);
		addToResolutionGraph(Collections.singleton(systemWiring));
		wiringToModule.put(systemWiring, systemModule);
	}

	private static BundleWiringPrivates loadPrivatesCache(BundleContext context, Activator activator) {
		File cacheFile = context.getDataFile(CACHE_FILE);
		if (cacheFile.exists()) {
		ObjectInputStream ois = null;
		try {
			ois = new ObjectInputStream(new FileInputStream(cacheFile));
			return (BundleWiringPrivates) ois.readObject();
		} catch (IOException | ClassNotFoundException e) {
			activator.logError("Failed to load privates cache.", e);
		} finally {
			if (ois != null) {
				try {
					ois.close();
				} catch (IOException e) {
					// ignore
				}
			}
		}
		}
		return new BundleWiringPrivates();
	}

	void savePrivatesCache(BundleContext context) {
		// first remove any stale wirings
		Set<BundleWiring> inUseWirings = getInUseBundleWirings();
		Set<BundleWiringLastModified> lastModified = new HashSet<>();
		for (BundleWiring wiring : inUseWirings) {
			lastModified.add(new BundleWiringLastModified(wiring));
		}
		privatesCache.retainAll(lastModified);
		File cacheFile = context.getDataFile(CACHE_FILE);
		cacheFile.getParentFile().mkdirs();
		ObjectOutputStream oos = null;
		try {
			oos = new ObjectOutputStream(new FileOutputStream(cacheFile));
			oos.writeObject(privatesCache);
		} catch (IOException e) {
			activator.logError("Failed to save privates cache.", e);
		} finally {
			if (oos != null) {
				try {
					oos.close();
				} catch (IOException e) {
					// ignore
				}
			}
		}
	}

	private Set<BundleWiring> getInUseBundleWirings() {
		Set<BundleWiring> wirings = new HashSet<>();
		Collection<BundleCapability> bundles = fwkWiring.findProviders(ALL_BUNDLES_REQUIREMENT);
		for (BundleCapability bundleCap : bundles) {
			// Only pay attention to non JPMS boot modules.
			// NOTE this means we will not create a real JPMS Module or Layer for this bundle
			if (bundleCap.getAttributes().get(BOOT_JPMS_MODULE) == null) {
				BundleRevision revision = bundleCap.getRevision();
				BundleWiring wiring = revision.getWiring();
				if (wiring != null && wiring.isInUse()) {
					wirings.add(wiring);
				}
			}
		}
		return wirings;
	}

	private void createNewWiringLayers() {
		long start = System.nanoTime();
		layersWrite.lock();
		try {
			long cleanUpStart = System.nanoTime();
			// first clean up layers that are not in use anymore
			for (Iterator<Entry<BundleWiring, Module>> wirings = wiringToModule.entrySet().iterator(); wirings.hasNext();) {
				Entry<BundleWiring, Module> wiringModule = wirings.next();
				if (!wiringModule.getKey().isInUse()) {
					// invalidate any named layers that used it
					Collection<NamedLayerImpl> namedLayers = moduleToNamedLayers.remove(wiringModule.getValue());
					if (namedLayers != null) {
						for (NamedLayerImpl namedLayer : namedLayers) {
							namedLayer.invalidate();
							moduleToNamedLayers.forEach((k, v) -> v.remove(namedLayer));
						}
					}
					clearController(wiringModule.getValue());
					// remove the wiring no long in use
					wirings.remove();
				}
			}
			System.out.println("Time to clean up layers: " + TimeUnit.MILLISECONDS.convert((System.nanoTime() - cleanUpStart), TimeUnit.NANOSECONDS));

			long currentWiringsStart = System.nanoTime();
			Set<BundleWiring> currentWirings = getInUseBundleWirings();
			System.out.println("Time to get currentWirings: " + TimeUnit.MILLISECONDS.convert((System.nanoTime() - currentWiringsStart), TimeUnit.NANOSECONDS));

			addToResolutionGraph(currentWirings);

			long moduleCreateStart = System.nanoTime();
			// create modules for each node in the graph
			graph.forEach((n) -> createModule(n));
			System.out.println("Time to create modules: " + TimeUnit.MILLISECONDS.convert((System.nanoTime() - moduleCreateStart), TimeUnit.NANOSECONDS));

			addReadsNest(wiringToModule);
		} finally {
			layersWrite.unlock();
			System.out.println("Total Time to create bundle layers: " + TimeUnit.MILLISECONDS.convert((System.nanoTime() - start), TimeUnit.NANOSECONDS));
		}
	}

	private Module createModule(ResolutionGraph<BundleWiring, BundlePackage>.Node n) {
		Module m = wiringToModule.get(n.getValue());
		if (m == null) {
			NodeFinder finder = new NodeFinder(activator, n, canBuildModuleHierarchy(n), true);
			Configuration config;
			List<Layer> layers;
			try {
				if (canBuildModuleHierarchy(n)) {
					Set<Module> dependsOn = new HashSet<>();
					for (ResolutionGraph<BundleWiring, BundlePackage>.Node d : n.dependsOn()) {
						dependsOn.add(createModule(d));
					}
					List<Configuration> configs = new ArrayList<>(dependsOn.size());
					layers = new ArrayList<>(dependsOn.size());
					for (Module d : dependsOn) {
						Layer l = d.getLayer();
						if (l != null) {
							// unnamed modules have no layers.
							// note that a null layer should result in a resolution error below
							layers.add(l);
							configs.add(l.configuration());
						}
					}

					configs.add(Layer.boot().configuration());
					layers.add(Layer.boot());

					config = Configuration.resolve(finder, configs, ModuleFinder.of(), Collections.singleton(finder.name));
				} else {
					String cause = n.hasSplitSources() ? " split packages" : "";
					cause += n.hasCycleSources() ? ((cause.isEmpty() ? "" : " and") + " cycles") : "";
					activator.logError("Could not attempt layer hierarchy for '" + finder.name + "' because of" + cause + ".", null);
					// try without module Hierarchy
					config = Layer.boot().configuration().resolve(finder, ModuleFinder.of(), Collections.singleton(finder.name));
					layers = Collections.singletonList(Layer.boot());
				}
			} catch (ResolutionException e) {
				activator.logError("Resolution error creating layer for: " + finder.name, e);
				// well something blew up; try without module hierarchy and boot modules
				finder = new NodeFinder(activator, n, false, false);
				config = Layer.boot().configuration().resolve(finder, ModuleFinder.of(), Collections.singleton(finder.name));
				layers = Collections.singletonList(Layer.boot());
			}

			final String finderName = finder.name;
			Controller controller = null;
			try {
				controller = Layer.defineModules(
						config,
						layers,
						// Map the module names to the wiring class loaders
						// NOTE we should only have one module in this layer
						(name) -> {
							return Optional.ofNullable(
									finderName.equals(name) ? n.getValue() : null).map(
											(w) -> {return w.getClassLoader();}).get();
						}
				);
				Layer layer = controller.layer();
				m = layer.modules().iterator().next();
			} catch (LayerInstantiationException e) {
				// The most likely cause is because we have loaded classes from the 
				// class loader before defining the module.
				// This is possible if the jpms support fragment is installed after
				// bundle code has been run, for example a provisioning agent.
				// We fall back to using the unnamed module for the bundle class loader
				m = n.getValue().getClassLoader().getUnnamedModule();
				activator.logError("Falling back to unnamed module for: " + n.getValue().getRevision().getSymbolicName(), e);
			}
			saveController(m, controller);
			wiringToModule.put(n.getValue(), m);
		}
		return m;
	}

	private void saveController(Module module, Controller controller) {
		controllers.put(module, controller);
	}

	private void clearController(Module module) {
		controllers.remove(module);
	}

	private void addReads(Module wantsRead, Collection<Module> toTargets) {
		Controller controller = controllers.get(wantsRead);
		if (controller != null) {
			for (Module toTarget : toTargets) {
				controller.addReads(wantsRead, toTarget);
			}
		}
	}

	private void addReadsNest(Map<BundleWiring, Module> wiringToModule) {
		long addReadsNestStart = System.nanoTime();
		Set<Module> bootModules = Layer.boot().modules();
		Collection<Module> allBundleModules = wiringToModule.values();
		// Not checking for existing edges for simplicity.
		for (Module module : allBundleModules) {
			if (!systemModule.equals(module)) {
				// First add reads to all boot modules.
				addReads(module, bootModules);
				// Now ensure bidirectional read of all bundle modules.
				addReads(module, allBundleModules);
				// Add read to the system.bundle module.
				addReads(module, Collections.singleton(systemModule));
			}
		}
		System.out.println("Time to addReadsNest: " + TimeUnit.MILLISECONDS.convert((System.nanoTime() - addReadsNestStart), TimeUnit.NANOSECONDS));
	}

	static boolean canBuildModuleHierarchy(ResolutionGraph<BundleWiring, BundlePackage>.Node n) {
		return !(n.hasCycleSources() || n.hasSplitSources());
	}

	private void addToResolutionGraph(Set<BundleWiring> currentWirings) {
		long startAddToGraph = System.nanoTime();
		currentWirings.forEach((w) -> addToGraph(w));
		System.out.println("Time addToGraph: " + TimeUnit.MILLISECONDS.convert((System.nanoTime() - startAddToGraph), TimeUnit.NANOSECONDS));

		long startAddWires = System.nanoTime();
		for (Iterator<ResolutionGraph<BundleWiring, BundlePackage>.Node> nodes = graph.iterator(); nodes.hasNext();) {
			ResolutionGraph<BundleWiring, BundlePackage>.Node n = nodes.next();
			if (!currentWirings.contains(n.getValue()) && n.getValue().getBundle().getBundleId() != 0) {
				nodes.remove();
			} else {
				addWires(n);
			}
		}
		System.out.println("Time addWires: " + TimeUnit.MILLISECONDS.convert((System.nanoTime() - startAddWires), TimeUnit.NANOSECONDS));

		long startPopulateSources = System.nanoTime();
		graph.populateSources();
		System.out.println("Time populateSources: " + TimeUnit.MILLISECONDS.convert((System.nanoTime() - startPopulateSources), TimeUnit.NANOSECONDS));
	}

	private void addWires(ResolutionGraph<BundleWiring, BundlePackage>.Node tail) {
		if (tail.isPopulated()) {
			return;
		}
		List<BundleWire> pkgWires = tail.getValue().getRequiredWires(PackageNamespace.PACKAGE_NAMESPACE);
		for (BundleWire pkgWire : pkgWires) {
			ResolutionGraph<BundleWiring, BundlePackage>.Node head = graph.getNode(pkgWire.getProviderWiring());
			// head will be null for boot modules because we do not map them into real JPMS modules
			if (head == null) {
				if (pkgWire.getCapability().getAttributes().get(LayerFactoryImpl.BOOT_JPMS_MODULE) != null) {
					// wires to boot modules are ignored
					continue;
				}
			}
			BundlePackage importPackage = BundlePackage.createSimplePackage((String) pkgWire.getCapability().getAttributes().get(PackageNamespace.PACKAGE_NAMESPACE)); 
			graph.addWire(tail, importPackage , head, false);
		}
		List<BundleWire> bundleWires = tail.getValue().getRequiredWires(BundleNamespace.BUNDLE_NAMESPACE);
		for (BundleWire bundleWire : bundleWires) {
			ResolutionGraph<BundleWiring, BundlePackage>.Node head = graph.getNode(bundleWire.getProviderWiring());
			if (head == null) {
				// head will be null for boot modules because we do not map them into real JPMS modules
				if (bundleWire.getCapability().getAttributes().get(LayerFactoryImpl.BOOT_JPMS_MODULE) != null) {
					// wires to boot modules are ignored
					continue;
				}
			}
			graph.addWire(tail, null, head, BundleNamespace.VISIBILITY_REEXPORT.equals(bundleWire.getRequirement().getDirectives().get(BundleNamespace.REQUIREMENT_VISIBILITY_DIRECTIVE)));
		}
	}

	private void addToGraph(BundleWiring w) {
		if (graph.getNode(w) == null) {
			Set<BundlePackage> exports = getExports(w);
			Set<BundlePackage> substitutes = getSubstitutes(w, exports);
			Set<BundlePackage> privates = privatesCache.getPrivates(w, exports);
			graph.addNode(w, exports, substitutes, privates);
		}
	}

	private static Set<BundlePackage> getSubstitutes(BundleWiring w, Set<BundlePackage> exports) {
		Set<BundlePackage> results = new HashSet<>();
		for (BundleCapability export : w.getRevision().getDeclaredCapabilities(PackageNamespace.PACKAGE_NAMESPACE)) {
			results.add(BundlePackage.createSimplePackage((String) export.getAttributes().get(PackageNamespace.PACKAGE_NAMESPACE)));
		}
		for (BundleWire hostWire : w.getProvidedWires(HostNamespace.HOST_NAMESPACE)) {
			for (BundleCapability export : hostWire.getRequirer().getDeclaredCapabilities(PackageNamespace.PACKAGE_NAMESPACE)) {
				results.add(BundlePackage.createSimplePackage((String) export.getAttributes().get(PackageNamespace.PACKAGE_NAMESPACE)));
			}
		}
		results.removeAll(exports);
		return results;
	}

	private static Set<BundlePackage> getExports(BundleWiring wiring) {
		Set<BundlePackage> results = new HashSet<>();
		for (BundleCapability export : wiring.getCapabilities(PackageNamespace.PACKAGE_NAMESPACE)) {
			results.add(BundlePackage.createExportPackage(export));
		}
		return results;
	}

	private NamedLayer createLayer(LoaderType type, String name, Set<Path> paths, Set<String> roots, ClassLoader parent, Function<String, ClassLoader> mappedLoaders) {
		ModuleFinder finder = ModuleFinder.of(paths.toArray(new Path[0]));
		Set<String> required = new HashSet<>();
		finder.findAll().forEach(
				(m) -> m.descriptor().requires().forEach(
						(r) -> required.add(r.name())));
		layersWrite.lock();
		try {
			createNewWiringLayers();
			// TODO not an optimized lookup here for the requires
			List<Module> dependsOn = new ArrayList<>();
			for (Module m : wiringToModule.values()) {
				if (required.contains(m.getName())) {
					dependsOn.add(m);
					break;
				}
			}
			List<Configuration> configs = new ArrayList<>(dependsOn.size() + 1);
			List<Layer> layers = new ArrayList<>(dependsOn.size() + 1);
			for (Module d : dependsOn) {
				Layer l = d.getLayer();
				layers.add(l);
				configs.add(l.configuration());
			}
			// always add the system layer/configuration which give access to boot
			layers.add(systemModule.getLayer());
			configs.add(systemModule.getLayer().configuration());

			Configuration config = Configuration.resolveAndBind(finder, configs, ModuleFinder.of(), roots);
			Layer layer;
			switch (type) {
				case OneLoader:
					layer = Layer.defineModulesWithOneLoader(config, layers, parent).layer();
					break;
				case ManyLoaders :
					layer = Layer.defineModulesWithManyLoaders(config, layers, parent).layer();
					break;
				case MappedLoaders :
					layer = Layer.defineModules(config, layers, mappedLoaders).layer();
					break;
				default:
					throw new IllegalArgumentException(type.toString());
			}
			NamedLayerImpl result = new NamedLayerImpl(layer, name);
			for (Module m : dependsOn) {
				moduleToNamedLayers.computeIfAbsent(m, (k) -> new ArrayList<>()).add(result);
			}
			return result;
		} finally {
			layersWrite.unlock();
		}
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
		case BundleEvent.UNRESOLVED:
			// only create new wiring to flush out old stuff
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

	@Override
	public Map<Bundle, Module> getModules() {
		Map<Bundle, Module> modules = new HashMap<>();
		boolean createNewWiringLayers = false;
		layersRead.lock();
		try {
			for (Bundle b : context.getBundles()) {
				BundleWiring wiring = b.adapt(BundleWiring.class);
				Module m = wiringToModule.get(wiring);
				if (m == null ) {
					if ((b.getState() & (Bundle.INSTALLED | Bundle.UNINSTALLED)) == 0) {
						createNewWiringLayers = true;
						break;
					}
				} else {
					modules.put(wiring.getBundle(), m);
				}
			}
		} finally {
			layersRead.unlock();
		}

		if (createNewWiringLayers) {
			createNewWiringLayers();
			modules.clear();
			layersRead.lock();
			try {
				for (Bundle b : context.getBundles()) {
					BundleWiring wiring = b.adapt(BundleWiring.class);
					Module m = wiringToModule.get(wiring);
					if (m != null) {
						modules.put(wiring.getBundle(), m);
					}
				}
			} finally {
				layersRead.unlock();
			}
		}
		
		return modules;
	}
}
