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
class AIExplainService(private val projectBasePath: String? = null) {

  companion object {
    // Configurable step-type taxonomy (kept as plain strings, not a hard enum).
    val ALLOWED_TYPES = listOf(
      "input", "validation", "processing", "persistence",
      "external-call", "ui", "error-handling", "test", "orchestration", "unknown"
    )
    private const val EXPLANATION_MODEL = "gpt-5.5"
    private const val EXPLANATION_REASONING_EFFORT = "high"
    private const val TYPE_MODEL = "gpt-5.4-nano"
    private const val ENDPOINT = "https://api.openai.com/v1/chat/completions"
  }

  private val apiKey: String? = loadApiKey()
  private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()

  fun explain(selectedId: String, focused: GraphData, snippets: Map<String, String>): AIExplanationResponse {
    val key = apiKey ?: return mock(selectedId, focused)
    return try {
      val explanation = parse(callExplanationModel(key, buildPrompt(selectedId, focused, snippets)))
      val typed = classifyStepTypes(key, explanation, focused, selectedId)
      validate(typed, focused, selectedId)
    } catch (e: Exception) {
      mock(selectedId, focused, "OpenAI request failed: ${e.message ?: e.javaClass.simpleName}")
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
Set every step type to "unknown". A separate lightweight classifier will assign step types.

For each step, include:
- explanation: one short sentence for the step list
- detail: a fuller explanation shown when the user clicks the step

Respond with a JSON object of this shape:
{"summary": string, "steps": [{"id": string, "title": string, "type": string, "explanation": string, "detail": string, "focusNodes": [existing node ids only], "focusEdges": []}], "riskNotes": [string], "suggestedNextNodes": [existing node ids]}

Selected node: $selStr

Focused graph (JSON): ${graphToJson(focused)}

Relevant snippets:
$snipStr
""".trim()
  }

  private fun graphToJson(d: GraphData): String {
    val nodes = d.nodes.joinToString(",") { """{"id":"${it.id.j()}","label":"${it.label.j()}","type":"${it.type}","folder":"${it.folder.j()}"}""" }
    val edges = d.edges.joinToString(",") { edge ->
      val calls = edge.methodCalls.take(20).joinToString(",") { call ->
        """{"callerMethod":"${call.callerMethod.j()}","targetMethod":"${call.targetMethod.j()}","callerLine":${call.callerLine},"targetLine":${call.targetLine}}"""
      }
      """{"source":"${edge.source.j()}","target":"${edge.target.j()}","type":"${edge.type}","methodCalls":[$calls]}"""
    }
    return """{"nodes":[$nodes],"edges":[$edges]}"""
  }

  // --- OpenAI call ---

  private fun callExplanationModel(key: String, prompt: String): String =
    callOpenAI(
      key = key,
      model = EXPLANATION_MODEL,
      reasoningEffort = EXPLANATION_REASONING_EFFORT,
      timeout = Duration.ofSeconds(60),
      systemMessage = "You are a precise code explanation assistant that returns only valid JSON.",
      prompt = prompt
    )

  private fun callTypeModel(key: String, prompt: String): String =
    callOpenAI(
      key = key,
      model = TYPE_MODEL,
      reasoningEffort = null,
      timeout = Duration.ofSeconds(20),
      systemMessage = "You classify explanation steps into an allowed taxonomy and return only valid JSON.",
      prompt = prompt
    )

  private fun callOpenAI(
    key: String,
    model: String,
    reasoningEffort: String?,
    timeout: Duration,
    systemMessage: String,
    prompt: String
  ): String {
    val reasoningField = reasoningEffort?.let { ""","reasoning_effort":"${it.j()}"""" } ?: ""
    val body = """{"model":"${model.j()}"$reasoningField,"response_format":{"type":"json_object"},"messages":[{"role":"system","content":"${systemMessage.j()}"},{"role":"user","content":"${prompt.j()}"}]}"""
    val req = HttpRequest.newBuilder()
      .uri(URI.create(ENDPOINT))
      .header("Authorization", "Bearer $key")
      .header("Content-Type", "application/json")
      .timeout(timeout)
      .POST(HttpRequest.BodyPublishers.ofString(body, Charsets.UTF_8))
      .build()
    val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
    if (resp.statusCode() !in 200..299) throw RuntimeException("OpenAI ${resp.statusCode()}: ${openAIError(resp.body())}")
    return JsonParser.parseString(resp.body()).asJsonObject
      .getAsJsonArray("choices").get(0).asJsonObject
      .getAsJsonObject("message").get("content").asString
  }

  private fun classifyStepTypes(
    key: String,
    response: AIExplanationResponse,
    focused: GraphData,
    selectedId: String
  ): AIExplanationResponse {
    if (response.steps.isEmpty()) return response
    val choices = try {
      parseTypeChoices(callTypeModel(key, buildTypePrompt(response, focused, selectedId)))
    } catch (_: Exception) {
      emptyMap()
    }
    if (choices.isEmpty()) return response
    return response.copy(
      steps = response.steps.map { step ->
        step.copy(type = choices[step.id]?.takeIf { ALLOWED_TYPES.contains(it) } ?: "unknown")
      }
    )
  }

  private fun buildTypePrompt(response: AIExplanationResponse, focused: GraphData, selectedId: String): String {
    val selected = focused.nodes.find { it.id == selectedId }
    val nodeLabels = focused.nodes.associate { it.id to "${it.type} ${it.label}" }
    val steps = response.steps.joinToString(",") { step ->
      val focus = step.focusNodes.joinToString(",") { id -> """"${id.j()}":"${(nodeLabels[id] ?: id).j()}"""" }
      """{"id":"${step.id.j()}","title":"${step.title.j()}","explanation":"${step.explanation.j()}","detail":"${step.detail.j()}","focusNodes":{$focus}}"""
    }
    return """
Assign exactly one type to each explanation step.
Allowed step types: ${ALLOWED_TYPES.joinToString(", ")}
If no type fits, use "unknown".

Selected node: ${selected?.let { "${it.type} \"${it.label}\" (id=${it.id})" } ?: selectedId}

Steps: [$steps]

Return JSON only:
{"steps":[{"id":"existing step id","type":"one allowed step type"}]}
""".trim()
  }

  private fun parseTypeChoices(json: String): Map<String, String> {
    val root = JsonParser.parseString(json).asJsonObject
    val arr = root.getAsJsonArray("steps") ?: root.getAsJsonArray("types") ?: return emptyMap()
    return arr.mapNotNull { el ->
      val o = el.asJsonObject
      val id = o.str("id")
      val type = o.str("type")
      if (id == null || type == null) null else id to type
    }.toMap()
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
        detail = o.str("detail") ?: o.str("explanation") ?: "",
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
        detail = s.detail,
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

  private fun mock(
    selectedId: String,
    focused: GraphData,
    riskNote: String = "Set OPENAI_API_KEY for AI-generated explanations."
  ): AIExplanationResponse {
    val byId = focused.nodes.associateBy { it.id }
    val sel = byId[selectedId]
    val focusId = if (byId.containsKey(selectedId)) selectedId else (focused.nodes.firstOrNull()?.id ?: selectedId)
    val selLabel = sel?.label ?: selectedId
    val neighbors = focused.edges
      .filter { it.source == focusId || it.target == focusId }
      .map { if (it.source == focusId) it.target else it.source }
      .distinct()

    val steps = mutableListOf(
      AIExplanationStep("step-1", "Entry point", "orchestration",
        "$selLabel is the focus of this view.",
        "CodeMap is showing an offline explanation because the AI call is unavailable. This selected method or file is the starting point for the focused walkthrough.",
        listOf(focusId))
    )
    val cycle = listOf("processing", "external-call", "persistence", "validation", "ui")
    neighbors.take(4).forEachIndexed { i, nId ->
      val n = byId[nId] ?: return@forEachIndexed
      steps += AIExplanationStep(
        "step-${i + 2}", n.label, cycle[i % cycle.size],
        "$selLabel is connected to ${n.label}.",
        "The deterministic graph found a connection between $selLabel and ${n.label}. Use the graph highlight to inspect the related file or method.",
        listOf(focusId, nId)
      )
    }
    return AIExplanationResponse(
      summary = "Offline overview of $selLabel and ${neighbors.size} related nodes.",
      steps = steps,
      riskNotes = listOf(riskNote),
      suggestedNextNodes = neighbors.take(3)
    )
  }

  private fun loadApiKey(): String? =
    System.getenv("OPENAI_API_KEY")?.takeIf { it.isNotBlank() }
      ?: System.getProperty("openai.api.key")?.takeIf { it.isNotBlank() }
      ?: readDotEnvApiKey(projectBasePath?.let { Path.of(it) })
      ?: readDotEnvApiKey(Path.of(System.getProperty("user.dir")))

  private fun readDotEnvApiKey(startPath: Path?): String? {
    var dir: Path? = startPath?.toAbsolutePath()
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

  private fun openAIError(body: String): String =
    try {
      JsonParser.parseString(body).asJsonObject
        .getAsJsonObject("error")
        ?.str("message")
        ?: body.take(300)
    } catch (_: Exception) {
      body.take(300)
    }

  // JSON string-escape for hand-built request bodies / prompts.
  private fun String.j() =
    replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "").replace("\t", " ")

  private fun JsonObject.str(k: String): String? = get(k)?.takeIf { !it.isJsonNull }?.asString
  private fun JsonObject.arr(k: String): List<String> =
    getAsJsonArray(k)?.mapNotNull { if (it.isJsonNull) null else it.asString } ?: emptyList()
}
