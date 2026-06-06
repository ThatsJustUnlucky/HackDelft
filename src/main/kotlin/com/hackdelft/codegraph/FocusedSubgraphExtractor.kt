package com.hackdelft.codegraph

// Layer 2: extract a small, readable subgraph around a selected node.
// Answers "what calls this / what does this call / what contains it" without inventing anything —
// it only filters the deterministic full graph.
object FocusedSubgraphExtractor {

  private const val MAX_NODES = 24

  fun extract(full: GraphData, selectedId: String): GraphData {
    if (full.nodes.none { it.id == selectedId }) return full

    val byId = full.nodes.associateBy { it.id }
    val adj = HashMap<String, MutableSet<String>>()
    full.edges.forEach { e ->
      adj.getOrPut(e.source) { mutableSetOf() }.add(e.target)
      adj.getOrPut(e.target) { mutableSetOf() }.add(e.source)
    }

    val keep = LinkedHashSet<String>()
    keep.add(selectedId)
    val firstHop = adj[selectedId]?.toList() ?: emptyList()
    firstHop.forEach { keep.add(it) }
    // Second hop (callers-of-callers / callees-of-callees) until we hit the cap.
    for (n in firstHop) {
      if (keep.size >= MAX_NODES) break
      adj[n]?.forEach { if (keep.size < MAX_NODES) keep.add(it) }
    }
    // Always pull in the containing class of any method we kept, so the subgraph stays legible.
    full.edges.filter { it.type == "contains" && keep.contains(it.target) }
      .forEach { keep.add(it.source) }

    val nodes = keep.mapNotNull { byId[it] }
    val edges = full.edges.filter { keep.contains(it.source) && keep.contains(it.target) }
    return GraphData(nodes, edges)
  }
}
