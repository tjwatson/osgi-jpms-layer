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
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.osgi.framework.namespace.HostNamespace;
import org.osgi.framework.wiring.BundleWire;
import org.osgi.framework.wiring.BundleWiring;

public class BundleWiringLastModified implements Serializable {
	private static final long serialVersionUID = 1L;
	private static AtomicLong nextNotCurrentID = new AtomicLong(-1);

	private final Map<Long, Long> lastModifieds = new HashMap<>();

	public BundleWiringLastModified(BundleWiring hostWiring) {
		// get the host last modified
		if (hostWiring.isCurrent()) {
			// use the current bundle id and last modified
			lastModifieds.put(hostWiring.getBundle().getBundleId(), hostWiring.getBundle().getLastModified());
		} else {
			// use a unique negative id to indicate not current
			lastModifieds.put(nextNotCurrentID.getAndDecrement(), hostWiring.getBundle().getLastModified());
		}
		for (BundleWire hostWire : hostWiring.getProvidedWires(HostNamespace.HOST_NAMESPACE)) {
			// Always use the fragment id and last modified.
			// It makes no difference if it is current or not because the host wiring indicates that. 
			lastModifieds.put(hostWire.getRequirer().getBundle().getBundleId(), hostWire.getRequirer().getBundle().getLastModified());
		}
	}

	@Override
	public boolean equals(Object other) {
		if (other instanceof BundleWiringLastModified) {
			return lastModifieds.equals(((BundleWiringLastModified) other).lastModifieds);
		}
		return false;
	}

	@Override
	public int hashCode() {
		return lastModifieds.hashCode();
	}
}
