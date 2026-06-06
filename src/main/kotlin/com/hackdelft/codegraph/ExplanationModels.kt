package com.hackdelft.codegraph

// AI explanation contract (see context/CODEMAP_CONTEXT.md → Data Contracts).
// AI never invents structure: focusNodes/focusEdges are validated against the real graph.

data class AIExplanationStep(
  val id: String,
  val title: String,
  val type: String,
  val explanation: String,
  val focusNodes: List<String>,
  val focusEdges: List<String> = emptyList()
)

data class AIExplanationResponse(
  val summary: String,
  val steps: List<AIExplanationStep>,
  val riskNotes: List<String> = emptyList(),
  val suggestedNextNodes: List<String> = emptyList()
)
