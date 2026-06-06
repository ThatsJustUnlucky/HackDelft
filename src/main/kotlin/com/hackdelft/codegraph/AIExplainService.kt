package com.hackdelft.codegraph

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

// Layer 3: turn a focused subgraph into typed, ordered explanation steps.
// Reads the key from OPENAI_API_KEY, -Dopenai.api.key, or a local .env file. With no key
// (or any failure) it falls back to a deterministic offline mock so the demo always works.
// Every response is validated against the real graph node ids before it leaves this class.
class AIExplainService {

  companion object {
    // Configurable step-type taxonomy (kept as plain strings, not a hard enum).
    val ALLOWED_TYPES = listOf(
      "input", "validation", "processing", "persistence",
      "external-call", "ui", "error-handling", "test", "orchestration", "unknown"
    )
    private const val MODEL = "gpt-5.5"
    private const val REASONING_EFFORT = "medium"
    private const val ENDPOINT = "https://api.openai.com/v1/chat/completions"
  }

  private val apiKey: String? = loadApiKey()
  private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()

  fun explain(selectedId: String, focused: GraphData, snippets: Map<String, String>): AIExplanationResponse {
    val key = apiKey ?: return mock(selectedId, focused)
    return try {
      val content = callOpenAI(key, buildPrompt(selectedId, focused, snippets))
      validate(parse(content), focused, selectedId)
    } catch (_: Exception) {
      mock(selectedId, focused)
    }
  }

  // --- prompt ---

  private fun buildPrompt(selectedId: String, focused: GraphData, snippets: Map<String, String>): String {
    val sel = focused.nodes.find { it.id == selectedId }
    val selStr = sel?.let { "${it.type} \"${it.label}\" (id=${it.id})" } ?: selectedId
    val snipStr = snippets.entries.joinToString("\n\n") { "// ${it.key}\n${it.value}" }
    return """
You are helping a developer understand a selected part of a codebase.
The graph was extracted deterministically from the IDE.
Only use the provided nodes, edges, and snippets. Do not invent files, functions, or dependencies.
Return JSON only.

Create a short (3-6) step-by-step explanation of how the selected node interacts with the rest of the focused graph.

Allowed step types: ${ALLOWED_TYPES.joinToString(", ")}
If no type fits, use "unknown".

Respond with a JSON object of this shape:
{"summary": string, "steps": [{"id": string, "title": string, "type": string, "explanation": string, "focusNodes": [existing node ids only], "focusEdges": []}], "riskNotes": [string], "suggestedNextNodes": [existing node ids]}

Selected node: $selStr

Focused graph (JSON): ${graphToJson(focused)}

Relevant snippets:
$snipStr
""".trim()
  }

  private fun graphToJson(d: GraphData): String {
    val nodes = d.nodes.joinToString(",") { """{"id":"${it.id.j()}","label":"${it.label.j()}","type":"${it.type}"}""" }
    val edges = d.edges.joinToString(",") { """{"source":"${it.source.j()}","target":"${it.target.j()}","type":"${it.type}"}""" }
    return """{"nodes":[$nodes],"edges":[$edges]}"""
  }

  // --- OpenAI call ---

  private fun callOpenAI(key: String, prompt: String): String {
    val body = """{"model":"$MODEL","reasoning_effort":"$REASONING_EFFORT","temperature":0.2,"response_format":{"type":"json_object"},"messages":[{"role":"system","content":"You are a precise code explanation assistant that returns only valid JSON."},{"role":"user","content":"${prompt.j()}"}]}"""
    val req = HttpRequest.newBuilder()
      .uri(URI.create(ENDPOINT))
      .header("Authorization", "Bearer $key")
      .header("Content-Type", "application/json")
      .timeout(Duration.ofSeconds(45))
      .POST(HttpRequest.BodyPublishers.ofString(body, Charsets.UTF_8))
      .build()
    val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
    if (resp.statusCode() !in 200..299) throw RuntimeException("OpenAI ${resp.statusCode()}")
    return JsonParser.parseString(resp.body()).asJsonObject
      .getAsJsonArray("choices").get(0).asJsonObject
      .getAsJsonObject("message").get("content").asString
  }

  // --- parsing + validation ---

  private fun parse(json: String): AIExplanationResponse {
    val root = JsonParser.parseString(json).asJsonObject
    val steps = root.getAsJsonArray("steps")?.mapIndexed { i, el ->
      val o = el.asJsonObject
      AIExplanationStep(
        id = o.str("id") ?: "step-${i + 1}",
        title = o.str("title") ?: "Step ${i + 1}",
        type = o.str("type") ?: "unknown",
        explanation = o.str("explanation") ?: "",
        focusNodes = o.arr("focusNodes")
      )
    } ?: emptyList()
    return AIExplanationResponse(
      summary = root.str("summary") ?: "",
      steps = steps,
      riskNotes = root.arr("riskNotes"),
      suggestedNextNodes = root.arr("suggestedNextNodes")
    )
  }

  private fun validate(r: AIExplanationResponse, focused: GraphData, selectedId: String): AIExplanationResponse {
    val ids = focused.nodes.map { it.id }.toSet()
    val fallbackNode = if (ids.contains(selectedId)) selectedId else (focused.nodes.firstOrNull()?.id ?: selectedId)
    val steps = r.steps.map { s ->
      AIExplanationStep(
        id = s.id,
        title = s.title,
        type = if (ALLOWED_TYPES.contains(s.type)) s.type else "unknown",
        explanation = s.explanation,
        focusNodes = s.focusNodes.filter { ids.contains(it) }.ifEmpty { listOf(fallbackNode) }
      )
    }
    return AIExplanationResponse(
      summary = r.summary,
      steps = steps,
      riskNotes = r.riskNotes,
      suggestedNextNodes = r.suggestedNextNodes.filter { ids.contains(it) }
    )
  }

  // --- offline fallback ---

  private fun mock(selectedId: String, focused: GraphData): AIExplanationResponse {
    val byId = focused.nodes.associateBy { it.id }
    val sel = byId[selectedId]
    val selLabel = sel?.label ?: selectedId
    val neighbors = focused.edges
      .filter { it.source == selectedId || it.target == selectedId }
      .map { if (it.source == selectedId) it.target else it.source }
      .distinct()

    val steps = mutableListOf(
      AIExplanationStep("step-1", "Entry point", "orchestration",
        "$selLabel is the focus of this view.", listOf(selectedId))
    )
    val cycle = listOf("processing", "external-call", "persistence", "validation", "ui")
    neighbors.take(4).forEachIndexed { i, nId ->
      val n = byId[nId] ?: return@forEachIndexed
      steps += AIExplanationStep(
        "step-${i + 2}", n.label, cycle[i % cycle.size],
        "$selLabel is connected to ${n.label}.", listOf(selectedId, nId)
      )
    }
    return AIExplanationResponse(
      summary = "Offline overview of $selLabel and ${neighbors.size} related nodes.",
      steps = steps,
      riskNotes = listOf("Set OPENAI_API_KEY for AI-generated explanations."),
      suggestedNextNodes = neighbors.take(3)
    )
  }

  private fun loadApiKey(): String? =
    System.getenv("OPENAI_API_KEY")?.takeIf { it.isNotBlank() }
      ?: System.getProperty("openai.api.key")?.takeIf { it.isNotBlank() }
      ?: readDotEnvApiKey()

  private fun readDotEnvApiKey(): String? {
    var dir: Path? = Path.of(System.getProperty("user.dir")).toAbsolutePath()
    repeat(8) {
      val envFile = dir?.resolve(".env")
      if (envFile != null && Files.isRegularFile(envFile)) {
        Files.readAllLines(envFile).forEach { line ->
          val trimmed = line.trim()
          if (trimmed.startsWith("OPENAI_API_KEY=")) {
            return trimmed.substringAfter("=").trim().trim('"', '\'').takeIf { it.isNotBlank() }
          }
        }
      }
      dir = dir?.parent
    }
    return null
  }

  // JSON string-escape for hand-built request bodies / prompts.
  private fun String.j() =
    replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "").replace("\t", " ")

  private fun JsonObject.str(k: String): String? = get(k)?.takeIf { !it.isJsonNull }?.asString
  private fun JsonObject.arr(k: String): List<String> =
    getAsJsonArray(k)?.mapNotNull { if (it.isJsonNull) null else it.asString } ?: emptyList()
}
