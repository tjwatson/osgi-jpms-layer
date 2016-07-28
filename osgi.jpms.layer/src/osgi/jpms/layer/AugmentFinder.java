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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleDescriptor.Builder;
import java.lang.module.ModuleDescriptor.Exports;
import java.lang.module.ModuleDescriptor.Provides;
import java.lang.module.ModuleDescriptor.Requires;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReader;
import java.lang.module.ModuleReference;
import java.net.URI;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
/**
 * Augments modules from a finder with additional resources
 */
public class AugmentFinder implements ModuleFinder {
	/**
	 * Augments a reader with additional resources
	 */
	class AugmentReader implements ModuleReader {
		private final ModuleReader reader;
		public AugmentReader(ModuleReference ref) {
			try {
				this.reader = ref.open();
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		}
		@Override
		public void close() throws IOException {
			reader.close();
		}
		@Override
		public Optional<URI> find(String name) throws IOException {
			// look in the original reader first; then check additions
			return reader.find(name).or(() -> Optional.of(additions.get(name)));
		}

	}

	private final Map<String, ModuleReference> allReferences = new HashMap<>();
	private final Map<String, URI> additions;

	/**
	 * Creates an augment finder that modifies readers of the given finder
	 * with the specified additions
	 * @param finder the finder to augment
	 * @param additions the additions to use for augmenting
	 */
	public AugmentFinder(ModuleFinder finder, Map<String, URI> additions) {
		this.additions = additions;
		Set<ModuleReference> all = finder.findAll();
		// make a copy of each module reference so we can augment its description
		for (ModuleReference ref : all) {
			Builder copyBuilder = toBuilder(ref.descriptor());
			for (String name : additions.keySet()) {
				String dir = name.substring(0, name.lastIndexOf('/'));
				String pn = dir.replace('/', '.');
				try {
					// TODO This is a hack to export, but it makes it easier
					// so that this bundle can access the class reflectively
					copyBuilder.exports(pn);
				} catch (IllegalStateException e) {
					// ignore duplicates
				}
			}
			ModuleReference augmentRef = new ModuleReference(copyBuilder.build(), ref.location().get(), () -> new AugmentReader(ref));
			allReferences.put(ref.descriptor().name(), augmentRef);
		}
	}

	/**
	 * Copies the given descriptor into a new builder so we can export more packages.
	 * Note this method is sensitive to changes in descriptor information.
	 * If descriptors start providing more information then this method
	 * implementation will have to be adjusted
	 * @param descriptor the descriptor to augment
	 * @return the new builder
	 */
	private Builder toBuilder(ModuleDescriptor descriptor) {
		Builder builder = new Builder(descriptor.name());

		builder.conceals(descriptor.conceals());

		for(Exports e : descriptor.exports()) {
			builder.exports(e);
		}

		descriptor.mainClass().ifPresent((mc) -> builder.mainClass(mc));
		descriptor.osArch().ifPresent((arch) -> builder.osArch(arch));
		descriptor.osName().ifPresent((name) -> builder.osName(name));
		descriptor.osVersion().ifPresent((version) -> builder.osVersion(version));

		for(Provides p : descriptor.provides().values()) {
			builder.provides(p);
		}

		for(Requires r : descriptor.requires()) {
			builder.requires(r);
		}

		descriptor.version().ifPresent((version) -> builder.version(version));

		for (String u : descriptor.uses()) {
			builder.uses(u);
		}
		return builder;
	}

	@Override
	public Optional<ModuleReference> find(String name) {
		return Optional.ofNullable(allReferences.get(name));
	}

	@Override
	public Set<ModuleReference> findAll() {
		return new HashSet<>(allReferences.values());
	}

}
