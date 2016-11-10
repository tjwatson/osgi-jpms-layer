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

import java.lang.module.ModuleDescriptor.Builder;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import org.osgi.framework.namespace.PackageNamespace;
import org.osgi.framework.wiring.BundleCapability;

public class BundlePackage {
	private final String name;
	private final Set<String> friends;

	static BundlePackage createExportPackage(BundleCapability packageCap) {
		String name = (String) packageCap.getAttributes().get(PackageNamespace.PACKAGE_NAMESPACE);
		if (name == null) {
			return null;
		}
		Set<String> friendsSet = Collections.emptySet();
		String friendsDir = packageCap.getDirectives().get("x-friends");
		if (friendsDir != null) {
			String[] friends = friendsDir == null ? new String[0] : friendsDir.split(",");
			for (int i = 0; i < friends.length; i++) {
				friends[i] = friends[i].trim();
			}
			// did not use Set.of(E...) because of bad meta-data that can have duplicate friends
			friendsSet = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(friends)));
		}
		return new BundlePackage(name, friendsSet);
	}

	static BundlePackage createSimplePackage(String name) {
		return new BundlePackage(name, Collections.emptySet());
	}

	private BundlePackage(String name, Set<String> friends) {
		this.name = name;
		this.friends = friends;
	}

	@Override
	public boolean equals(Object o) {
		if (!(o instanceof BundlePackage)) {
			return false;
		}
		return name.equals(((BundlePackage) o).name);
	}

	@Override
	public int hashCode() {
		return name.hashCode();
	}

	void addExport(Builder builder) {
		try {
			if (friends.isEmpty()) {
				builder.exports(name);
			} else {
				builder.exports(name, friends);
			}
		} catch(IllegalArgumentException e) {
			e.printStackTrace();
		} catch (IllegalStateException e) {
			e.printStackTrace();
		}
	}

	void addPrivate(Builder builder) {
		builder.contains(name);
	}

	public String toString() {
		return name + friends;
	}
}
