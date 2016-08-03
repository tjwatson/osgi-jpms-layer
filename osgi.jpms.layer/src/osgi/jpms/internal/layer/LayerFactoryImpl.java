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
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock.ReadLock;
import java.util.concurrent.locks.ReentrantReadWriteLock.WriteLock;
import java.util.function.Function;

import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleEvent;
import org.osgi.framework.Constants;
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

public class LayerFactoryImpl implements LayerFactory, WovenClassListener, WeavingHook, SynchronousBundleListener {

	/**
	 * A bundle layer creates a JPMS layer which is used to represent resolve OSGi bundles as modules.
	 * As bundles are resolved a new bundle layer is created which uses the previous bundle layer as
	 * its parent.  Bundle layers provide a single linear parent hierarchy.  A bundle layer can only
	 * have one parent and will only be a parent to a single bundle layer, although it can be a parent
	 * to multiple named layers 
	 * <p>
	 * This single linear parent hierarchy is necessary to allow bundle modules contained in parent
	 * layers to be discarded and re-resolved in a later bundle layer.  The old (discarded) bundle
	 * modules still exist, but will be overridden by later bundle layers
	 */
	// TODO JPMS-ISSUE-006 (Medium Priority) static set of resolved modules causes class loader pinning if a module is discarded
	// Can JPMS be enhanced to discard sub graphs of modules in a Layer?
	class BundleLayer {
		final Layer layer;
		final AtomicBoolean isValid = new AtomicBoolean(true);
		final BundleLayer parent;
		final Collection<NamedLayer> children = new HashSet<>();
		final Map<Bundle, BundleWiring> wirings = new HashMap<>();

		BundleLayer(BundleLayer parent, Map<String, BundleWiring> wiringMap) {
			this.parent = parent;
			for (BundleWiring wiring : wiringMap.values()) {
				wirings.put(wiring.getBundle(), wiring);
			}
			// Create a finder based of the wiring map
			BundleLayerFinder finder = new BundleLayerFinder(wiringMap);
			// If the parent is null then use the layer the framework is in as the parent
			Layer parentLayer = parent == null ? Bundle.class.getModule().getLayer() : parent.layer;
			// resolve all names in the wiring map
			Configuration config = parentLayer.configuration().resolveRequires(finder, ModuleFinder.of(), wiringMap.keySet());
			// Map the module names to the wiring class loaders
			layer = parentLayer.defineModules(config, (name) -> {
				return Optional.ofNullable(wiringMap.get(name)).map((wiring) -> {return wiring.getClassLoader();}).get();
			});
		}

		NamedLayer addChild(String name, Layer layer) {
			NamedLayer namedLayer = new NamedLayerImpl(this, layer, name);
			children.add(namedLayer);
			return namedLayer;
		}

		/**
		 * Finds a bundle layer the wiring is contained in
		 * @param wiring the wiring to find a bundle layer for
		 * @return the bundle layer for the wiring or {@code null} if
		 * there is no bundle layer found.
		 */
		BundleLayer findWiring(BundleWiring wiring) {
			BundleLayer currentLayer = this;
			while (currentLayer != null) {
				if (wiring.equals(currentLayer.wirings.get(wiring.getBundle()))) {
					return currentLayer;
				}
				currentLayer = currentLayer.parent;
			}
			return null;
		}

		/**
		 * Finds the bundle layer a bundle is contained in
		 * @param b the bundle to find a bundle layer for
		 * @return the bundle layer for the bundle or {@code null} if
		 * there is no bundle layer found.
		 */
		BundleLayer findBundle(Bundle b) {
			BundleLayer currentLayer = this;
			while (currentLayer != null) {
				if (wirings.containsKey(b)) {
					return currentLayer;
				}
				currentLayer = currentLayer.parent;
			}
			return null;
		}
	}

	class NamedLayerImpl implements NamedLayer {
		final BundleLayer parentbundleLayer;
		final Layer layer;
		final String name;
		final long id = nextLayerId.getAndIncrement();
		NamedLayerImpl(BundleLayer parentBundleLayer, Layer layer, String name) {
			this.parentbundleLayer = parentBundleLayer;
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
			return parentbundleLayer.isValid.get();
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

	private final FrameworkWiring fwkWiring;
	private final WriteLock layersWrite;
	private final ReadLock layersRead;
	private final AtomicLong nextLayerId = new AtomicLong(0);
	private BundleLayer current = null;

	public LayerFactoryImpl(BundleContext context) {
		fwkWiring = context.getBundle(Constants.SYSTEM_BUNDLE_LOCATION).adapt(FrameworkWiring.class);
		ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
		layersWrite = lock.writeLock();
		layersRead = lock.readLock();
		//createNewBundleLayer();
	}

	private void createNewBundleLayer() {
		Map<String, BundleWiring> wirings = new HashMap<>();
		Collection<BundleCapability> bundles = fwkWiring.findProviders(ALL_BUNDLES_REQUIREMENT);
		for (BundleCapability bundleCap : bundles) {
			BundleRevision revision = bundleCap.getRevision();
			BundleWiring wiring = revision.getWiring();
			// Ignore system bundle, we assume the launcher created a layer for the system.bundle module
			if (wiring != null && wiring.isCurrent() && revision.getBundle().getBundleId() != 0) {
				// assuming one bundle per bsn for now; first wins, it should have the highest version
				// If multiple are allowed it is unclear which one would be used by JPMS when resolving child layers
				// TODO JPMS-ISSUE-007 (Low Priority) can have multiple modules with the same name in the same layer but no way for modules to require a specific version
				if (!wirings.containsKey(revision.getSymbolicName())) {
					wirings.put(revision.getSymbolicName(), wiring);
				}
			}
		}
		layersWrite.lock();
		try {
			if (current != null) {
				Iterator<Entry<String, BundleWiring>> iWirings = wirings.entrySet().iterator();
				while (iWirings.hasNext()) {
					BundleWiring wiring = iWirings.next().getValue();
					// Check if parent already has this wiring
					if (current.findWiring(wiring) != null) {
						iWirings.remove();
					}
				}
			}
			if (current == null || !wirings.isEmpty()) {
				current = new BundleLayer(current, wirings);
			}
		} finally {
			layersWrite.unlock();
		}
	}

	private NamedLayer createLayer(LoaderType type, String name, Set<Path> paths, Set<String> roots, ClassLoader parent, Function<String, ClassLoader> mappedLoaders) {
		ModuleFinder finder = ModuleFinder.of(paths.toArray(new Path[0]));
		layersWrite.lock();
		try {
			if (current == null) {
				createNewBundleLayer();
			}
			Configuration config = current.layer.configuration().resolveRequires(finder, ModuleFinder.of(), roots);
			Layer jpmsLayer;
			switch (type) {
				case OneLoader:
					jpmsLayer = current.layer.defineModulesWithOneLoader(config, parent);
					break;
				case ManyLoaders :
					jpmsLayer = current.layer.defineModulesWithManyLoaders(config, parent);
					break;
				case MappedLoaders :
					jpmsLayer = current.layer.defineModules(config, mappedLoaders);
					break;
				default:
					throw new IllegalArgumentException(type.toString());
			}
			return current.addChild(name, jpmsLayer);

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
				createNewLayer = current == null || current.findWiring(wovenClass.getBundleWiring()) == null;
			} finally {
				layersRead.unlock();
			}
			if (createNewLayer) {
				createNewBundleLayer();
			}
		}
	}

	@Override
	public void bundleChanged(BundleEvent event) {
		switch (event.getType()) {
		case BundleEvent.UNINSTALLED:
		case BundleEvent.UNRESOLVED:
		case BundleEvent.UPDATED:
			// any of the above events cause the layer with the
			// bundle to become invalidated.
			invalidateLayer(event.getBundle());
			break;
		default:
			break;
		}
	}

	private void invalidateLayer(Bundle bundle) {
		layersRead.lock();
		try {
			if (current != null) {
				BundleLayer containingLayer = current.findBundle(bundle);
				if (!containingLayer.wirings.get(bundle).isCurrent()) {
					containingLayer.isValid.set(false);
				}
			}
		} finally {
			layersRead.unlock();
		}
	}

	@Override
	public void weave(WovenClass wovenClass) {
		// do nothing; just need to make sure there is a hook so the WovenClassListener will get called
	}

}
