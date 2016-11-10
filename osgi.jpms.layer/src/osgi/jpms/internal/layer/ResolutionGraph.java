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

public class ResolutionGraph<V, P> implements Iterable<ResolutionGraph<V, P>.Node>{
	public class Node {
		private final V v;
		private final Set<P> provides;
		private final Set<P> substitutes;
		private final Set<P> privates;
		private final Map<P, Set<Node>> sources;
		private final Set<Node> dependsOn;
		private final Set<Wire> requiredWires;
		private boolean sourcesPopulated = false;
		private boolean checkedCycles = false;
		private boolean hasSplitSources = false;
		private boolean hasCycleSources = false;

		Node(V v, Set<P> provides, Set<P> substitutes, Set<P> privates) {
			this.v = v;
			this.provides = Collections.unmodifiableSet(provides);
			this.substitutes = Collections.unmodifiableSet(substitutes);
			this.privates = new HashSet<>(privates);
			this.sources = new HashMap<>();
			this.requiredWires = new HashSet<>();
			this.dependsOn = new HashSet<>();
		}
		public V getValue() {
			return v;
		}

		public Set<P> getPrivates() {
			return privates;
		}

		public Set<P> getProvides() {
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

		@SuppressWarnings("rawtypes")
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
			for (P p : provides) {
				addToSources(p, this);
			}

			Set<P> singles = new HashSet<>();
			for (Wire w : requiredWires) {
				if (w.single != null) {
					singles.add(w.single);
					w.head.addSingleSource(this, w.single, new HashSet<>());
				}
			}

			// populate privates after removing ones that got overridden by singles
			privates.removeAll(singles);
			for (P p : privates) {
				addToSources(p, this);
			}

			for (Wire wire : requiredWires) {
				if (wire.single == null) {
					wire.head.addMultiSource(this, singles);
				}
			}
		}

		private void checkCycles() {
			if (checkedCycles) {
				return;
			}
			checkedCycles = true;
			dependsOn.forEach((n) ->{
				if (n.dependsOn.contains(this)) {
					hasCycleSources = true;
				}
			});
		}

		private void addToSources(P p, Node node) {
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

		private void addSingleSource(Node tail, P p, Set<Node> visited) {
			if (!visited.add(this)) {
				return;
			}
			if (provides.contains(p)) {
				tail.addToSources(p, this);
				// look at all non-single wires; if this node provides p
				for (Wire w : requiredWires) {
					if (w.single == null) {
						w.head.addSingleSource(tail, p, visited);
					}
				}
			}
		}

		private void addMultiSource(Node tail, Set<P> singles) {
			for (P p : provides) {
				if (!singles.contains(p)) {
					addSingleSource(tail, p, new HashSet<>());
				}
			}
			for (Wire w : requiredWires) {
				if (w.single != null) {
					if (substitutes.contains(w.single)) {
						w.head.addSingleSource(tail, w.single, new HashSet<>());
					}
				} else if (w.isTransitive()) {
					w.head.addMultiSource(tail, singles);
				}
			}
		}
	}

	public class Wire {
		private final Node tail;
		private final P single;
		private final Node head;
		private final boolean transitive;

		Wire (Node tail, P single, Node head, boolean transitive) {
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

		public P getSingle() {
			return single;
		}

		boolean isTransitive() {
			return transitive;
		}
	}

	private final Map<V, Node> nodes = new HashMap<>();

	public Node addNode(V v, Set<P> provides, Set<P> substitutes, Set<P> privates) {
		return nodes.computeIfAbsent(v, (nv) -> new Node(nv, provides, substitutes, privates));
	}

	public Wire addWire(Node tail, P single, Node head, boolean transitive) {
		if (tail.isPopulated()) {
			throw new IllegalStateException("Tail node is already populated.");
		}
		Wire wire = new Wire(tail, single, head, transitive);
		tail.requiredWires.add(wire);
		return wire;
	}

	public void populateSources() {
		nodes.forEach((v, n) -> n.populateSources());
		nodes.forEach((v, n) -> n.checkCycles());
	}

	public Node getNode(V v) {
		return nodes.get(v);
	}
	
	@Override
	public Iterator<Node> iterator() {
		return nodes.values().iterator();
	}
}
