package com.hackdelft.codegraph

// Layer 2: extract a small, readable file-level subgraph around a selected file or method.
// The main map is file-level, but method explanations still use method ids to choose which
// aggregated file-call edges are relevant.
object FocusedSubgraphExtractor {

  private const val MAX_NODES = 24

  fun extract(full: GraphData, selectedId: String, selectedFilePath: String? = null): GraphData {
    return if (selectedId.startsWith("method:")) {
      extractForMethod(full, selectedId, selectedFilePath)
    } else {
      extractForNode(full, selectedId)
    }
  }

  private fun extractForNode(full: GraphData, selectedId: String): GraphData {
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
    for (n in firstHop) {
      if (keep.size >= MAX_NODES) break
      adj[n]?.forEach { if (keep.size < MAX_NODES) keep.add(it) }
    }

    val nodes = keep.mapNotNull { byId[it] }
    val edges = full.edges.filter { keep.contains(it.source) && keep.contains(it.target) }
    return GraphData(nodes, edges)
  }

  private fun extractForMethod(full: GraphData, selectedMethodId: String, selectedFilePath: String?): GraphData {
    val selectedFileId = selectedFilePath?.replace('\\', '/')?.let { "file:$it" }
    val matchingEdges = full.edges.filter { edge ->
      edge.methodCalls.any { it.callerMethod == selectedMethodId || it.targetMethod == selectedMethodId }
    }

    val keep = LinkedHashSet<String>()
    selectedFileId?.let { keep.add(it) }
    matchingEdges.forEach {
      keep.add(it.source)
      keep.add(it.target)
    }

    if (keep.isEmpty()) {
      selectedFileId?.let { keep.add(it) }
    }

    val seed = keep.toList()
    full.edges.forEach { edge ->
      if (keep.size >= MAX_NODES) return@forEach
      if (seed.contains(edge.source) || seed.contains(edge.target)) {
        keep.add(edge.source)
        keep.add(edge.target)
      }
    }

    val byId = full.nodes.associateBy { it.id }
    val nodes = keep.mapNotNull { byId[it] }
    val edges = full.edges.filter { keep.contains(it.source) && keep.contains(it.target) }
    return if (nodes.isNotEmpty()) GraphData(nodes, edges) else full
  }
}
