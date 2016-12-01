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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleDescriptor.Exports;
import java.lang.module.ModuleDescriptor.Provides;
import java.lang.module.ModuleDescriptor.Requires;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReference;
import java.lang.reflect.Layer;
import java.util.Collections;
import java.util.Dictionary;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import javax.lang.model.element.ModuleElement.UsesDirective;

import org.eclipse.osgi.container.Module;
import org.eclipse.osgi.container.ModuleContainerAdaptor.ModuleEvent;
import org.eclipse.osgi.container.ModuleRevisionBuilder;
import org.eclipse.osgi.internal.hookregistry.ActivatorHookFactory;
import org.eclipse.osgi.internal.hookregistry.HookConfigurator;
import org.eclipse.osgi.internal.hookregistry.HookRegistry;
import org.eclipse.osgi.internal.hookregistry.StorageHookFactory;
import org.eclipse.osgi.internal.hookregistry.StorageHookFactory.StorageHook;
import org.eclipse.osgi.storage.BundleInfo.Generation;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleException;
import org.osgi.framework.Constants;
import org.osgi.framework.Version;
import org.osgi.framework.namespace.BundleNamespace;
import org.osgi.framework.namespace.IdentityNamespace;
import org.osgi.framework.namespace.PackageNamespace;
import org.osgi.framework.wiring.FrameworkWiring;
import org.osgi.resource.Namespace;

import osgi.jpms.internal.layer.EquinoxJPMSSupport.EquinoxJPMSStorageHook;

public class EquinoxJPMSSupport extends StorageHookFactory<Object, Object, EquinoxJPMSStorageHook> implements HookConfigurator, ActivatorHookFactory, BundleActivator {

	// A storage hook is needed only to allow us to adaptModuleRevisionBuilder
	public class EquinoxJPMSStorageHook extends StorageHook<Object, Object> {
		public EquinoxJPMSStorageHook(Generation generation) {
			super(generation, EquinoxJPMSSupport.class);
		}

		@Override
		public void initialize(Dictionary<String, String> manifest) throws BundleException {
			// nothing
		}

		@Override
		public void load(Object loadContext, DataInputStream is) throws IOException {
			// nothing
		}

		@Override
		public void save(Object saveContext, DataOutputStream os) throws IOException {
			// nothing
		}

		@Override
		public ModuleRevisionBuilder adaptModuleRevisionBuilder(ModuleEvent operation, Module origin,
				ModuleRevisionBuilder builder) {
			if (builder.getSymbolicName() == null) {
				// only do this if this doesn't have a bsn
				File f = getGeneration().getContent();
				try {
					ModuleFinder finder = ModuleFinder.of(f.toPath());
					Set<ModuleReference> found = finder.findAll();
					if (found.size() == 1) {
						// we only pay attention if we find exactly 1 module
						return createBuilder(found.iterator().next());
					}
				} catch (Exception e) {
					// TODO should log
					e.printStackTrace();
				}
			}
			// fall back to default
			return super.adaptModuleRevisionBuilder(operation, origin, builder);
		}

		private ModuleRevisionBuilder createBuilder(ModuleReference ref) {
			ModuleDescriptor desc = ref.descriptor();
			ModuleRevisionBuilder builder = new ModuleRevisionBuilder();
			builder.setSymbolicName(desc.name());

			Version version = desc.version().map((v) -> {
				try {
					return Version.valueOf(v.toString());
				} catch (IllegalArgumentException e) {
					return Version.emptyVersion;
				}
			}).orElse(Version.emptyVersion);
			builder.setVersion(version);

			// add bundle and identity capabilities, do not create host capability for JPMS
			builder.addCapability(
					BundleNamespace.BUNDLE_NAMESPACE,
					Map.of(),
					Map.of(
							BundleNamespace.BUNDLE_NAMESPACE, desc.name(),
							BundleNamespace.CAPABILITY_BUNDLE_VERSION_ATTRIBUTE, version));
			builder.addCapability(
					IdentityNamespace.IDENTITY_NAMESPACE,
					Map.of(),
					Map.of(
							IdentityNamespace.IDENTITY_NAMESPACE, desc.name(),
							IdentityNamespace.CAPABILITY_VERSION_ATTRIBUTE, version));

			for(Exports exports : desc.exports()) {
				// TODO map targets to x-friends directive.
				builder.addCapability(
						PackageNamespace.PACKAGE_NAMESPACE,
						Map.of(),
						Map.of(PackageNamespace.PACKAGE_NAMESPACE, exports.source()));
			}

			for(Provides provides : desc.provides()) {
				builder.addCapability(
						JpmsServiceNamespace.JPMS_SERVICE_NAMESPACE,
						Map.of(),
						Map.of(
								JpmsServiceNamespace.JPMS_SERVICE_NAMESPACE, provides.service(),
								JpmsServiceNamespace.CAPABILITY_PROVIDES_WITH, provides.providers().get(0)));
			}

			for (Requires requires : desc.requires()) {
				Map<String, String> directives = new HashMap<>();

				// determine the resolution value based on the STATIC modifier
				String resolution = requires.modifiers().contains(Requires.Modifier.STATIC) ? Namespace.RESOLUTION_OPTIONAL : Namespace.RESOLUTION_MANDATORY;
				directives.put(Namespace.REQUIREMENT_RESOLUTION_DIRECTIVE, resolution);
				// determine the visibility value based on the TRANSITIVE modifier
				String visibility = requires.modifiers().contains(Requires.Modifier.TRANSITIVE) ? BundleNamespace.VISIBILITY_REEXPORT : BundleNamespace.VISIBILITY_PRIVATE;
				directives.put(BundleNamespace.REQUIREMENT_VISIBILITY_DIRECTIVE, visibility);
				// create a bundle filter based on the requires name 
				directives.put(Namespace.REQUIREMENT_FILTER_DIRECTIVE, "(" + BundleNamespace.BUNDLE_NAMESPACE + "=" + requires.name() + ")");

				builder.addRequirement(BundleNamespace.BUNDLE_NAMESPACE, directives, Collections.emptyMap());
			}

			for(String uses : desc.uses()) {
				builder.addRequirement(JpmsServiceNamespace.JPMS_SERVICE_NAMESPACE,
						Map.of(Namespace.REQUIREMENT_RESOLUTION_DIRECTIVE, Namespace.RESOLUTION_OPTIONAL),
						Map.of(JpmsServiceNamespace.JPMS_SERVICE_NAMESPACE, uses));
			}
			return builder;
		}

	}

	private static final String bootModuleLocationPrefix = "jpmsBootModule:";

	@Override
	public void addHooks(HookRegistry hookRegistry)  {
		hookRegistry.addStorageHookFactory(this);
		hookRegistry.addActivatorHookFactory(this);
	}

	@Override
	public int getStorageVersion() {
		return 0;
	}

	@Override
	protected EquinoxJPMSStorageHook createStorageHook(Generation generation) {
		return new EquinoxJPMSStorageHook(generation);
	}

	@Override
	public BundleActivator createActivator() {
		return this;
	}

	@Override
	public void start(BundleContext context) throws Exception {
		// keep track of the boot modules that should be installed
		Set<String> bootModuleLocations = new HashSet<>();
		// make sure all boot modules are installed
		for (java.lang.reflect.Module module : Layer.boot().modules()) {
			bootModuleLocations.add(installBootModule(module, context));
		}
		Set<Bundle> refresh = new HashSet<>();
		for (Bundle b : context.getBundles()) {
			String bLoc = b.getLocation();
			if (bLoc.startsWith(bootModuleLocationPrefix) && !bootModuleLocations.contains(b.getLocation())) {
				// something changed in VM configuration since last start;
				// must uninstall this boot module
				b.uninstall();
				refresh.add(b);
			}
		}
		if (!refresh.isEmpty()) {
			CountDownLatch latch = new CountDownLatch(1);
			context.getBundle(Constants.SYSTEM_BUNDLE_LOCATION).adapt(FrameworkWiring.class).refreshBundles(refresh, (e) -> latch.countDown());
			latch.await(10, TimeUnit.SECONDS);
		}
	}

	private String installBootModule(java.lang.reflect.Module module, BundleContext context) throws IOException, BundleException {
		String bootLocation = bootModuleLocationPrefix + module.getName();
		if (context.getBundle(bootLocation) == null) {
			Manifest m = new Manifest();
			Attributes mainAttrs = m.getMainAttributes();
			mainAttrs.put(Attributes.Name.MANIFEST_VERSION, "1.0");
			mainAttrs.putValue(Constants.BUNDLE_MANIFESTVERSION, "2");
			mainAttrs.putValue(Constants.BUNDLE_SYMBOLICNAME, module.getName() + "; " + LayerFactoryImpl.BOOT_JPMS_MODULE + "=true");
			mainAttrs.putValue(Constants.BUNDLE_VERSION, module.getDescriptor().version().map((v) -> {
				String s = v.toString();
				int indexDash = s.indexOf('-');
				if (indexDash >= 0) {
					s = s.substring(0, indexDash);
				}
				return s;
			}).orElse("0.0.0"));
			StringBuilder exportPackages = new StringBuilder();
			for (Exports exports : module.getDescriptor().exports()) {
				if (exports.targets().isEmpty() && !exports.source().startsWith("java.")) {
					if (exportPackages.length() > 0) {
						exportPackages.append(", ");
					}
					exportPackages.append(exports.source());
					exportPackages.append("; mandatory:=" + LayerFactoryImpl.BOOT_JPMS_MODULE + "; " + LayerFactoryImpl.BOOT_JPMS_MODULE + "=true");
				}
			}
			if (exportPackages.length() > 0) {
				mainAttrs.putValue(Constants.EXPORT_PACKAGE, exportPackages.toString());
			}
			mainAttrs.putValue(Constants.REQUIRE_BUNDLE, Constants.SYSTEM_BUNDLE_SYMBOLICNAME);

			ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			JarOutputStream jarOut = new JarOutputStream(bytes, m);
			jarOut.close();
			context.installBundle(bootLocation, new ByteArrayInputStream(bytes.toByteArray()));
		}
		return bootLocation;
	}

	@Override
	public void stop(BundleContext context) throws Exception {
		// nothing
	}

}
