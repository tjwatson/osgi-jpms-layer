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

import java.io.IOException;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleDescriptor.Builder;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReader;
import java.lang.module.ModuleReference;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import org.eclipse.osgi.util.ManifestElement;
import org.osgi.framework.BundleException;
import org.osgi.framework.namespace.PackageNamespace;
import org.osgi.framework.wiring.BundleCapability;
import org.osgi.framework.wiring.BundleWiring;

/**
 * A module finder that represents the wirings of bundles as modules
 *
 */
public class BundleLayerFinder implements ModuleFinder {
	private final Map<String, ModuleReference> moduleReferences;

	/**
	 * Creates a module finder for finding module references that represent
	 * resolved bundles
	 * @param wirings a mapping of module names to bundle wirings.  The bundle
	 * wiring will be used to back a module with a name of the key value.
	 */
	public BundleLayerFinder(Map<String, BundleWiring> wirings) {
		moduleReferences = new HashMap<>();
		for (Map.Entry<String, BundleWiring> wiringEntry : wirings.entrySet()) {
			moduleReferences.put(wiringEntry.getKey(), createModuleReference(wiringEntry));
		}
	}
	private ModuleReference createModuleReference(Entry<String, BundleWiring> wiringEntry) {
		// Simplistic Builder that only fills in the module:
		// name -> bundle bsn
		// version -> bundle version
		// exports -> wirings package capabilities
		Builder builder = ModuleDescriptor.module(wiringEntry.getKey());
		builder.version(wiringEntry.getValue().getBundle().getVersion().toString());
		for (BundleCapability packageCap : wiringEntry.getValue().getCapabilities(PackageNamespace.PACKAGE_NAMESPACE)) {
			// we only care about non-internal and non-friends-only packages
			if (packageCap.getDirectives().get("x-internal") == null && packageCap.getDirectives().get("x-friends") == null) {
				String packageName = (String) packageCap.getAttributes().get(PackageNamespace.PACKAGE_NAMESPACE);
				try {
					builder.exports(packageName);
					builder.opens(packageName);
				} catch (IllegalStateException e) {
					// ignore duplicates
				} catch (IllegalArgumentException e) {
					System.err.println("XXX bad package name: " + packageName);
				}
			}
		}
		// Look for private packages.  Each private package needs to be known
		// to the JPMS otherwise the classes in them will be associated with the
		// unknown module.  For now use the Private-Package header, should also do some
		// scanning of the bundle when they don't have this header.
		// TODO JPMS-ISSUE-002: (Low Priority) Need to scan for private packages.
		// Can the Layer API be enhanced to map a classloader to a default module to use? 
		String privatePackage = wiringEntry.getValue().getBundle().getHeaders("").get("Private-Package");
		try {
			ManifestElement[] packageElements = ManifestElement.parseHeader("Private-Package", privatePackage);
			if (packageElements != null) {
				for (ManifestElement packageElement : packageElements) {
					for (String packageName : packageElement.getValueComponents()) {
						try {
							builder.opens(packageName);
						} catch (IllegalStateException e) {
							// ignore duplicates
						} catch (IllegalArgumentException e) {
							System.err.println("XXX bad package name: " + packageName);
						}
					}
				}
			} else {
				// need to discover packages
				Collection<String> classes = wiringEntry.getValue().listResources("/", "*.class", BundleWiring.LISTRESOURCES_LOCAL | BundleWiring.LISTRESOURCES_RECURSE);
				Set<String> packages = new HashSet<>();
				for (String path : classes) {
					int beginIndex = 0;
					if (path.startsWith("/")) {
						beginIndex = 1;
					}
					int endIndex = path.lastIndexOf('/');
					path = path.substring(beginIndex, endIndex);
					packages.add(path.replace('/', '.'));
				}
				for (String pkg : packages) {
					try {
						builder.exports(pkg);
						builder.opens(pkg);
					} catch (IllegalStateException e) {
						// ignore duplicates
					} catch (IllegalArgumentException e) {
						System.err.println("XXX bad package name: " + pkg);
					}
				}
			}
			// TODO hack to enable addReads
			builder.opens("osgi.jpms.internal.layer.addreads");
		} catch (BundleException e1) {
			// ignore and move on
		}
		return new ModuleReference(builder.build(), null, () -> {return getReader(wiringEntry.getValue());});
	}

	private ModuleReader getReader(final BundleWiring bundleWiring) {
		// Pretty sure this never used, but it is possible the
		// jpms layer above would want to get resources out of modules
		// in the bundle layer.  This code would provide that access.  
		return new ModuleReader() {
			@Override
			public Optional<URI> find(String name) throws IOException {
				int lastSlash = name.lastIndexOf('/');
				String path;
				String filePattern;
				if (lastSlash > 0) {
					path = name.substring(0, lastSlash);
					filePattern = name.substring(lastSlash + 1);
				} else {
					path = "";
					filePattern = name;
				}
				Collection<String> resources = bundleWiring.listResources(path, filePattern, BundleWiring.LISTRESOURCES_LOCAL);
				URI uri;
				try {
					uri = resources.isEmpty() ? null : bundleWiring.getClassLoader().getResource(name).toURI();
				} catch (URISyntaxException e) {
					uri = null;
				}
				return Optional.ofNullable(uri);
			}
			
			@Override
			public void close() throws IOException {
			}

			@Override
			public Stream<String> list() throws IOException {
				return Stream.empty();
			}
		};
	}

	@Override
	public Optional<ModuleReference> find(String name) {
		return Optional.ofNullable(moduleReferences.get(name));
	}

	@Override
	public Set<ModuleReference> findAll() {
		return new HashSet<>(moduleReferences.values());
	}

}
