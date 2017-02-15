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

import java.io.Serializable;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.osgi.framework.wiring.BundleWiring;

public class BundleWiringPrivates implements Serializable {
	private static final long serialVersionUID = 1L;

	private final Map<BundleWiringLastModified, Set<BundlePackage>> wiringToPrivates = new HashMap<>();

	public BundleWiringPrivates() {
	}

	public Set<BundlePackage> getPrivates(BundleWiring wiring, Set<BundlePackage> exports) {
		return wiringToPrivates.computeIfAbsent(new BundleWiringLastModified(wiring), (lm) -> findPrivates(wiring, exports));
	}

	private Set<BundlePackage> findPrivates(BundleWiring wiring, Set<BundlePackage> exports) {
		if (wiring.getBundle().getBundleId() == 0) {
			return Collections.emptySet();
		}
		// TODO JPMS-ISSUE-002: (Low Priority) Need to scan for private packages.
		// Can the Layer API be enhanced to map a classloader to a default module to use? 

		Set<BundlePackage> results = new HashSet<>();

		// Look for private packages.  Each private package needs to be known
		// to the JPMS otherwise the classes in them will be associated with the
		// unknown module.
		// Discover packages the hard way
		// TODO could look the Private-Package header bnd produces
		Collection<String> classes = wiring.listResources("/", "*.class", BundleWiring.LISTRESOURCES_LOCAL | BundleWiring.LISTRESOURCES_RECURSE);
		for (String path : classes) {
			int beginIndex = 0;
			if (path.startsWith("/")) {
				beginIndex = 1;
			}
			int endIndex = path.lastIndexOf('/');
			if (endIndex >= 0) {
				path = path.substring(beginIndex, endIndex);
				results.add(BundlePackage.createSimplePackage(path.replace('/', '.')));
			}
		}
		results.removeAll(exports);
		return results;
	}

	public void retainAll(Set<BundleWiringLastModified> lastModified) {
		wiringToPrivates.keySet().retainAll(lastModified);
	}
}
