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
import java.lang.module.ModuleDescriptor.Requires.Modifier;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReader;
import java.lang.module.ModuleReference;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import org.osgi.framework.Constants;
import org.osgi.framework.wiring.BundleRevision;
import org.osgi.framework.wiring.BundleWiring;

/**
 * A module finder that represents the wirings of bundles as modules
 *
 */
public class NodeFinder implements ModuleFinder {
	final String name;
	final ModuleReference moduleRef;

	/**
	 * Creates a module finder for a single bundle module reference.
	 * @param wirings a mapping of module names to bundle wirings.  The bundle
	 * wiring will be used to back a module with a name of the key value.
	 */
	public NodeFinder(Activator activator, ResolutionGraph<BundleWiring, BundlePackage>.Node node, boolean includeRequires) {
		String bsn = node.getValue().getRevision().getSymbolicName();
		name = bsn == null ? "" : bsn;
		moduleRef = createModuleReference(activator, name, node, includeRequires);
	}

	private static ModuleReference createModuleReference(Activator activator, String name, final ResolutionGraph<BundleWiring, BundlePackage>.Node node, boolean includeRequires) {
		// name -> bundle bsn
		Builder builder = ModuleDescriptor.openModule(name);
		// version -> bundle version
		builder.version(node.getValue().getBundle().getVersion().toString());

		// exports -> wirings package capabilities
		node.getProvides().forEach((p) -> {
			try {
				p.addExport(builder);
			} catch(IllegalArgumentException e) {
				activator.logError("Bad package in: " + name, e);
			} catch (IllegalStateException e) {
				activator.logError("Bad package in: " + name, e);
			}
		});
		// privates -> all packages contained in bundle class path
		node.getPrivates().forEach((p) -> p.addPrivate(builder));

		if (includeRequires && LayerFactoryImpl.canBuildModuleHierarchy(node)) {
			for (ResolutionGraph<BundleWiring, BundlePackage>.Node dependency : node.dependsOn()) {
				BundleRevision r = dependency.getValue().getRevision();
				String bsn;
				if (r.getBundle().getBundleId() == 0) {
					bsn = Constants.SYSTEM_BUNDLE_SYMBOLICNAME;
				} else {
					bsn = r.getSymbolicName();
				}
				if (node.isTransitive(dependency)) {
					builder.requires(EnumSet.of(Modifier.TRANSITIVE), bsn);
				} else {
					builder.requires(bsn);
				}
			}
		}

		// TODO hack to enable addReads
		builder.contains("osgi.jpms.internal.layer.addreads");
		ModuleDescriptor desc = builder.build();
		return new ModuleReference(desc, null, () -> {return getReader(node.getValue());});
	}

	private static ModuleReader getReader(BundleWiring wiring) {
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
				Collection<String> resources = wiring.listResources(path, filePattern, BundleWiring.LISTRESOURCES_LOCAL);
				URI uri;
				try {
					uri = resources.isEmpty() ? null : wiring.getClassLoader().getResource(name).toURI();
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
		return Optional.ofNullable(this.name.equals(name) ? moduleRef : null);
	}

	@Override
	public Set<ModuleReference> findAll() {
		return Collections.singleton(moduleRef);
	}

}
