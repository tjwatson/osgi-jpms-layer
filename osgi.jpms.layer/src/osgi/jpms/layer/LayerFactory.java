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
package osgi.jpms.layer;

import java.lang.module.Configuration;
import java.lang.module.ModuleFinder;
import java.lang.reflect.Layer;
import java.lang.reflect.Method;
import java.lang.reflect.Module;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleReference;
import org.osgi.framework.Constants;
import org.osgi.framework.namespace.BundleNamespace;
import org.osgi.framework.wiring.BundleCapability;
import org.osgi.framework.wiring.BundleWiring;
import org.osgi.framework.wiring.FrameworkWiring;
import org.osgi.resource.Namespace;
import org.osgi.resource.Requirement;
import org.osgi.resource.Resource;

import osgi.jpms.layer.reads.ReadsModifier;

public class LayerFactory {

	// Requirement to find all "host" bundle identities
	static class BundleRequirement implements Requirement {
		private final String filter;
		BundleRequirement(String name) {
			filter = "(" + BundleNamespace.BUNDLE_NAMESPACE + "=" + name + ")";
		}
		@Override
		public Resource getResource() {
			return null;
		}
		
		@Override
		public String getNamespace() {
			return BundleNamespace.BUNDLE_NAMESPACE;
		}
		
		@Override
		public Map<String, String> getDirectives() {
			return Collections.singletonMap(Namespace.REQUIREMENT_FILTER_DIRECTIVE, filter);
		}
		
		@Override
		public Map<String, Object> getAttributes() {
			return Collections.emptyMap();				
		}
	}

	public static class BundleModuleClassLoader extends ClassLoader {
		public BundleModuleClassLoader(ClassLoader parent) {
			super(parent);
		}
	}

	/**
	 * Creates a bundle Layer using the specified bundle names
	 * @param context the bundle context used to find the named bundles
	 * @param names the names of bundles to include in the bundle layer
	 * @return
	 */
	public static Layer createBundleLayer(BundleContext context, Collection<String> names) {
		FrameworkWiring fwkWiring = context.getBundle(Constants.SYSTEM_BUNDLE_LOCATION).adapt(FrameworkWiring.class);
		// get the current class loaders for the bundles with the specified names
		// TODO should perform a transitive closure of the dependencies of the named bundles
		// TODO and include all required bundles in the layer
		Map<String, BundleWiring> wirings = new HashMap<>();
		for (String name : names) {
			Collection<BundleCapability> bundles = fwkWiring.findProviders(new BundleRequirement(name));
			if (!bundles.isEmpty()) {  // just assuming one bundle per bsn for now
				BundleWiring wiring = bundles.iterator().next().getRevision().getWiring();
				if (wiring != null) {
					wirings.put(name, wiring);
				}
			}
		}
		Configuration config = Layer.boot().configuration().resolveRequires(new BundleLayerFinder(wirings), ModuleFinder.of(), names);
		// A concurrent map is used to lazily create delegating module class loaders
		ConcurrentMap<String, ClassLoader> moduleLoaders = new ConcurrentHashMap<>();
		Layer bundleLayer = Layer.boot().defineModules(config, (name) -> {return getClassLoader(name, wirings.get(name), moduleLoaders);});
		return bundleLayer;
	}

	private static ClassLoader getClassLoader(String name, BundleWiring bundleWiring, ConcurrentMap<String, ClassLoader> moduleLoaders) {
		// We cannot directly use the wiring class loader here because 
		// if any classes are already defined by this class loader in
		// packages the bundle exports then the jpms complains.
		// We create a new class loader that never defines any classes and only
		// delegates to its parent bundle class loader
		return moduleLoaders.computeIfAbsent(name, n -> new BundleModuleClassLoader(bundleWiring.getClassLoader()));
	}

	public static Layer createJpmsLayer(Layer bundleLayer, Path modulesPath, Set<String> roots) {
		// We need to inject the ReadsModifier into each module in the jpms layer
		// so we can force jpms modules read access to our bundle modules
		URL readsModifierURL = LayerFactory.class.getResource("reads/ReadsModifier.class");
		URI readsModifierURI;
		try {
			readsModifierURI = readsModifierURL.toURI();
		} catch (URISyntaxException e) {
			throw new RuntimeException(e);
		}
		Map<String, URI> additions = Collections.singletonMap("osgi/jpms/layer/reads/ReadsModifier.class", readsModifierURI);

		ModuleFinder finder = ModuleFinder.of(modulesPath);
		// the augment finder augments each module content with the additions
		ModuleFinder augmentFinder = new AugmentFinder(finder, additions);
		Configuration cf = bundleLayer.configuration().resolveRequires(augmentFinder, ModuleFinder.of(), roots);
		// need to use many loaders to allow each module to provide the ReadsModifier class.
		Layer jpmsLayer = bundleLayer.defineModulesWithManyLoaders(cf, null);

		Set<Module> bundleModules = bundleLayer.modules();
		Set<Module> jpmsModules = jpmsLayer.modules();
		for (Module jpmsModule : jpmsModules) {
			for (Module bundleModule : bundleModules) {
				addReads(jpmsModule, bundleModule);
			}
		}
		return jpmsLayer;
	}

	private static void addReads(Module jpmsModule, Module bundleModule) {
		try {
			Module unnamedModule;
			ClassLoader delegateParent = bundleModule.getClassLoader().getParent();
			if (delegateParent instanceof BundleReference) {
				// This is for a normal bundle, we know all classes are associated
				// with the unnamed module of the delegate parent class loader
				unnamedModule = delegateParent.getUnnamedModule();
			} else {
				// here we assume the bundle module is the system bundle
				// just return the module associated with the impls class
				// if the framework is packaged as a jmps module then this may really
				// be named
				unnamedModule = Bundle.class.getModule();
			}

			ClassLoader m1Loader = jpmsModule.getClassLoader();
			Class<?> readsModifier = m1Loader.loadClass(ReadsModifier.class.getName());
			Method addReads = readsModifier.getDeclaredMethod("addReads", Module.class, Module.class);
			addReads.invoke(null, jpmsModule, unnamedModule);
		} catch (RuntimeException e) {
			throw e;
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}
}
