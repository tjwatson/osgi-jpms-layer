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

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import org.osgi.framework.wiring.BundleWiring;

public class ResolutionGraph implements Iterable<ResolutionGraph.Node>{
	public class Node {
		private final BundleWiring v;
		private final Set<BundlePackage> provides;
		private final Set<BundlePackage> substitutes;
		private final Set<BundlePackage> privates;
		private final Map<BundlePackage, Set<Node>> sources;
		private final Set<Node> dependsOn;
		private final Set<Node> transitives;
		private final Set<Wire> requiredWires;
		private final Set<Node> serviceDeps;
		private boolean sourcesPopulated = false;
		private boolean checkedCycles = false;
		private boolean hasSplitSources = false;
		private boolean hasCycleSources = false;

		Node(BundleWiring wiring, Set<BundlePackage> provides, Set<BundlePackage> substitutes, Set<BundlePackage> privates) {
			this.v = wiring;
			this.provides = Collections.unmodifiableSet(provides);
			this.substitutes = Collections.unmodifiableSet(substitutes);
			this.privates = new HashSet<>(privates);
			this.sources = new HashMap<>();
			this.requiredWires = new HashSet<>();
			this.dependsOn = new HashSet<>();
			this.transitives = new HashSet<>();
			this.serviceDeps = new HashSet<>();
		}
		public BundleWiring getValue() {
			return v;
		}

		public Set<BundlePackage> getPrivates() {
			return privates;
		}

		public Set<BundlePackage> getProvides() {
			return provides;
		}

		public boolean hasSplitSources() {
			return hasSplitSources;
		}

		public boolean hasCycleSources() {
			return hasCycleSources;
		}

		public Set<Node> dependsOn() {
			return dependsOn;
		}

		public boolean isTransitive(Node node) {
			return transitives.contains(node);
		}

		@Override
		public boolean equals(Object o) {
			if (!(o instanceof ResolutionGraph.Node)) {
				return false;
			}
			return v.equals(((ResolutionGraph.Node) o).v);
		}
		@Override
		public int hashCode() {
			return v.hashCode();
		}

		public String toString() {
			return v.toString();
		}

		private void populateSources() {
			if (sourcesPopulated) {
				return;
			}
			sourcesPopulated = true;

			// populate export sources upfront with this node
			for (BundlePackage p : provides) {
				addToSources(p, this);
			}

			Set<BundlePackage> singles = new HashSet<>();
			Set<Node> visited = new HashSet<>();
			for (Wire w : requiredWires) {
				if (w.getSingle() != null) {
					// record single source import-package to remove the privates next
					singles.add(w.getSingle());
					// treat each wire as a require wire because that is all JPMS understands
					w.getHead().addMultiSource(this, visited);
				}
			}

			// populate privates after removing ones that got overridden by singles
			privates.removeAll(singles);
			for (BundlePackage p : privates) {
				addToSources(p, this);
			}

			for (Wire wire : requiredWires) {
				if (wire.getSingle() == null) {
					wire.getHead().addMultiSource(this, visited);
					if (wire.isTransitive()) {
						transitives.add(wire.getHead());
					}
				}
			}

			// populate service dependencies
			for (Node provider : serviceDeps) {
				dependsOn.add(provider);
			}
		}

		private void checkCycles() {
			if (checkedCycles) {
				return;
			}
			checkedCycles = true;
			if (detectCycle(new HashSet<>(), this, this.dependsOn)) {
				hasCycleSources = true;
			}
		}

		boolean detectCycle(Set<Node> visited, Node start, Set<Node> dependencies) {
			if (dependencies.contains(start)) {
				return true;
			} else {
				for (Node dependency : dependencies) {
					if (visited.add(dependency)) {
						if (detectCycle(visited, start, dependency.dependsOn)) {
							return true;
						}
					}
				}
			}
			return false;
		}

		private void addToSources(BundlePackage p, Node node) {
			Set<Node> sourceNodes = sources.computeIfAbsent(p, (k) -> new HashSet<>());
			sourceNodes.add(node);
			if (sourceNodes.size() > 1) {
				hasSplitSources = true;
			}
			if (!this.equals(node)) {
				dependsOn.add(node);
			}
		}

		public boolean isPopulated() {
			return sourcesPopulated;
		}

		private void addSingleSource(Node tail, BundlePackage p, Set<Node> visited) {
			if (!visited.add(this)) {
				return;
			}
			if (provides.contains(p)) {
				tail.addToSources(p, this);
				// look at all non-single wires; if this node provides p
				for (Wire w : requiredWires) {
					if (w.getSingle() == null) {
						w.getHead().addSingleSource(tail, p, visited);
					}
				}
			}
		}

		private void addMultiSource(Node tail, Set<Node> visited) {
			if (!visited.add(this)) {
				return;
			}
			for (BundlePackage p : provides) {
				addSingleSource(tail, p, new HashSet<>());
			}
			for (Wire w : requiredWires) {
				if (w.getSingle() != null) {
					if (substitutes.contains(w.getSingle())) {
						w.getHead().addMultiSource(tail, visited);
					}
				} else if (w.isTransitive()) {
					w.getHead().addMultiSource(tail, visited);
				}
			}
		}
	}

	public class Wire {
		private final Node tail;
		private final BundlePackage single;
		private final Node head;
		private final boolean transitive;

		Wire (Node tail, BundlePackage single, Node head, boolean transitive) {
			this.tail = tail;
			this.single = single;
			this.head = head;
			this.transitive = transitive;
		}

		public Node getTail() {
			return tail;
		}

		public Node getHead() {
			return head;
		}

		public BundlePackage getSingle() {
			return single;
		}

		public boolean isTransitive() {
			return transitive;
		}
	}

	private final Map<BundleWiring, Node> nodes = new HashMap<>();

	public Node addNode(BundleWiring wiring, Set<BundlePackage> provides, Set<BundlePackage> substitutes, Set<BundlePackage> privates) {
		return nodes.computeIfAbsent(wiring, (w) -> new Node(w, provides, substitutes, privates));
	}

	public Wire addWire(Node tail, BundlePackage single, Node head, boolean transitive) {
		if (tail == null) {
			throw new NullPointerException("No tail.");
		}
		if (head == null) {
			throw new NullPointerException("No head.");
		}
		if (tail.isPopulated()) {
			throw new IllegalStateException("Tail node is already populated.");
		}
		Wire wire = new Wire(tail, single, head, transitive);
		tail.requiredWires.add(wire);
		return wire;
	}

	public void addServiceDepenency(Node tail, Node head) {
		tail.serviceDeps.add(head);
	}

	public void populateSources() {
		nodes.forEach((v, n) -> n.populateSources());
		nodes.forEach((v, n) -> n.checkCycles());
	}

	public Node getNode(BundleWiring v) {
		return nodes.get(v);
	}
	
	@Override
	public Iterator<Node> iterator() {
		return nodes.values().iterator();
	}
}
