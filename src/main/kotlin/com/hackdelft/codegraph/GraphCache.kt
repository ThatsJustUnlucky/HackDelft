package com.hackdelft.codegraph

import com.google.gson.JsonParser
import com.intellij.openapi.project.Project
import java.io.File

// Layer 1 cache: persist the deterministic repo graph to .codemap/cache.json so large
// repos don't re-extract on every open. Simple invalidation: refresh button forces re-extract.
class GraphCache(project: Project) {

  private val file: File? = project.basePath?.let { File(it, ".codemap/cache.json") }

  fun load(): GraphData? {
    val f = file ?: return null
    if (!f.exists()) return null
    return try {
      deserialize(f.readText())
    } catch (_: Exception) {
      null
    }
  }

  fun save(data: GraphData) {
    val f = file ?: return
    try {
      f.parentFile?.mkdirs()
      f.writeText(serialize(data))
    } catch (_: Exception) {
    }
  }

  private fun serialize(d: GraphData): String {
    val nodes = d.nodes.joinToString(",") { n ->
      """{"id":"${n.id.e()}","label":"${n.label.e()}","type":"${n.type}","filePath":"${n.filePath.e()}","color":"${n.color}","folder":"${n.folder.e()}","isTest":${n.isTest}}"""
    }
    val edges = d.edges.filter { it.type != "calls" || it.methodCalls.isNotEmpty() }.joinToString(",") { e ->
      val calls = e.methodCalls.joinToString(",") { c ->
        """{"callerMethod":"${c.callerMethod.e()}","targetMethod":"${c.targetMethod.e()}","callerFile":"${c.callerFile.e()}","targetFile":"${c.targetFile.e()}","callerLine":${c.callerLine},"targetLine":${c.targetLine}}"""
      }
      """{"source":"${e.source.e()}","target":"${e.target.e()}","type":"${e.type}","color":"${e.color}","methodCalls":[$calls]}"""
    }
    return """{"nodes":[$nodes],"edges":[$edges]}"""
  }

  private fun deserialize(s: String): GraphData {
    val root = JsonParser.parseString(s).asJsonObject
    val nodes = root.getAsJsonArray("nodes").map {
      val o = it.asJsonObject
      GraphNode(
        o.get("id").asString, o.get("label").asString, o.get("type").asString,
        o.get("filePath").asString,
        o.get("color")?.takeIf { c -> !c.isJsonNull }?.asString ?: "",
        o.get("folder")?.takeIf { f -> !f.isJsonNull }?.asString ?: "",
        o.get("isTest")?.takeIf { t -> !t.isJsonNull }?.asBoolean ?: false
      )
    }
    val edges = root.getAsJsonArray("edges").map {
      val o = it.asJsonObject
      val calls = o.getAsJsonArray("methodCalls")?.map { callEl ->
        val c = callEl.asJsonObject
        MethodCall(
          callerMethod = c.get("callerMethod")?.takeIf { v -> !v.isJsonNull }?.asString ?: "",
          targetMethod = c.get("targetMethod")?.takeIf { v -> !v.isJsonNull }?.asString ?: "",
          callerFile = c.get("callerFile")?.takeIf { v -> !v.isJsonNull }?.asString ?: "",
          targetFile = c.get("targetFile")?.takeIf { v -> !v.isJsonNull }?.asString ?: "",
          callerLine = c.get("callerLine")?.takeIf { v -> !v.isJsonNull }?.asInt ?: -1,
          targetLine = c.get("targetLine")?.takeIf { v -> !v.isJsonNull }?.asInt ?: -1
        )
      } ?: emptyList()
      GraphEdge(
        o.get("source").asString, o.get("target").asString, o.get("type").asString,
        o.get("color")?.takeIf { c -> !c.isJsonNull }?.asString ?: "",
        calls
      )
    }.filter { it.type != "calls" || it.methodCalls.isNotEmpty() }
    return GraphData(nodes, edges)
  }

  private fun String.e() = replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "")
}
