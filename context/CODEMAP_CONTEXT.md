# CodeMap Product Context

This document is context only. It describes the product vision, MVP scope, architecture, and data contracts for CodeMap.

It does **not** replace `CLAUDE.md`, `AGENTS.md`, or any other agent behavior instructions. If there is a conflict, the repo-level agent instructions take priority.

Agents should use this file to understand what we are building before planning or coding.

---

## Project Summary

CodeMap is an IntelliJ IDE plugin for a 24-hour hackathon.

The goal is to help developers understand large and unfamiliar codebases by turning the repository into an interactive visual map. The plugin should make it easier to onboard into a project, understand how files/functions interact, and make changes with more confidence.

CodeMap combines:

1. A deterministic repository graph generated from the actual codebase.
2. A cached Obsidian-style visual map of the repo.
3. AI-generated explanations over selected parts of that graph.
4. Predefined frontend animations that help users understand each step of a code flow.

The core product idea is:

> A whole-repo visual map with AI-powered focus mode.

---

## Current Implementation Status

Kotlin IntelliJ plugin (`src/main/kotlin/com/hackdelft/codegraph/`) with a Cytoscape.js frontend embedded in a JCEF tool window. Build: Gradle 8.6, JDK 17 toolchain, IntelliJ 2023.2.5, `org.jetbrains.intellij` 1.17.4. Run with `./gradlew runIde` (the Gradle wrapper is committed).

### Implemented

- **Repo graph (Flow 1):** `GraphDataExtractor` uses PSI (`AllClassesSearch`) to build a deterministic graph of classes, interfaces, abstract classes, and methods, with `contains` / `calls` / `inherits` edges. Rendered by `CodeGraphPanel` (Cytoscape): search, structure/calls filters, per-class colors, click-to-navigate, right-click "Open in Graph View".
- **Local cache (Layer 1):** `GraphCache` reads/writes `.codemap/cache.json` (gitignored). `refreshGraph()` loads the cache or extracts; the "Refresh" button forces re-extract.
- **Focused subgraph (Layer 2):** `FocusedSubgraphExtractor.extract(full, selectedId)` filters the full graph to ~24 nodes around a selection (1–2 hops + containing classes). Deterministic; no AI.
- **AI explanation (Layer 3):** `AIExplainService` first builds a high-compute explanation prompt from the focused subgraph + code snippets, calls OpenAI with `gpt-5.5` (`reasoning_effort: high`, JSON mode), and asks the explanation pass to leave step types as `unknown`. It then runs a lightweight type-classification pass with `gpt-5.4-nano` to assign one allowed step type per step. The backend parses the result and validates every `focusNodes` id against the real subgraph (unknown/unsupported types -> `unknown`). The API key is loaded from `OPENAI_API_KEY`, `-Dopenai.api.key`, or the project `.env`; the service falls back to a deterministic offline mock when no key is available or the explanation request fails. Contracts in `ExplanationModels.kt` (`AIExplanationStep`, `AIExplanationResponse`).
- **Step panel + animations (Layer 4):** In `CodeGraphPanel`'s embedded HTML — a side panel lists typed steps; `playStep` / `showAllSteps` glow/dim/animate the relevant nodes and edges, colored by step type (`TYPE_COLORS`). Frontend owns all animation; AI returns only a type string + node ids.
- **Actions:** "Explain with CodeMap" (editor right-click → `ExplainWithCodeMapAction`) and "✨ Explain" (graph toolbar). Both route to `CodeGraphPanel.explainNode(nodeId)`.

### Key conventions

- Node ids: `class:<qualifiedName>`, `method:<qualifiedName>.<name>`. Edge types: `contains`, `calls`, `inherits`.
- JS↔Kotlin bridge: one `JBCefJSQuery`; frontend posts `{t:'nav'|'explain'|'refresh', id}`, routed by `handleMessage`.
- Step-type taxonomy is single-sourced in `AIExplainService.ALLOWED_TYPES`; mirror any new type into `TYPE_COLORS` in the HTML so it gets a color (else grey fallback).
- AI prompts live only in `AIExplainService` (`buildPrompt` = explanation prompt; `buildTypePrompt` = step-type classifier prompt; system messages in `callExplanationModel` / `callTypeModel`). Model/endpoint constants live in that file. Current GPT-5.x chat-completion calls should not set non-default `temperature`.

### Not yet done

- Richer node kinds (folders/files/external deps per the data contracts) — extractor is class/method only.
- AI `focusEdges` are parsed but unused; step animations derive edges from `focusNodes`.
- Cold-start race: editor "Explain" on a never-opened tool window can fire before the web page finishes loading.

### Planned `clean_up` direction

This branch should reduce graph size and UI lag by changing the main repository map from method-level nodes to file-level nodes.

- **File nodes instead of method nodes:** Each graph node should represent a source file, not an individual function/method. This keeps large projects readable and avoids creating thousands of nodes.
- **Aggregated file-to-file edges:** If methods in file A call methods in file B, create a single edge from file A to file B. Do not create more than one edge for the same ordered pair of files.
- **Method list on each connection:** Each file-to-file edge should store a list of method-level calls that caused that connection. For example, an edge can contain `callerMethod`, `targetMethod`, and source location metadata for each call.
- **Directed graph:** If calls only go from file A to file B, show the edge as directed A -> B. If calls exist in both directions, support either two directed edges or a clear bidirectional representation, but still avoid duplicate edges for the same direction.
- **Folder-aware layout:** Files in the same folder/package should be visually grouped or placed near each other when possible. This can be done through layout hints, group labels, parent clusters, or another lightweight visual treatment.
- **Known Java caveat:** Object creation and constructor-heavy patterns may connect one file to many others in Java codebases, especially when dependency setup happens in a central class. Do not solve this immediately, but keep the edge metadata rich enough that this can be filtered or weighted later.
- **AI remains method-based:** The AI explanation flow should still operate on methods and focused method-level context, even though the main graph is file-level.
- **Method selection entry points:** Users should be able to trigger method explanations in two ways:
  - Right-click a method in the code editor and choose "Explain with CodeMap".
  - Open a file-to-file edge's method-call list and choose one of the methods shown there.
- **Navigate to method code:** Double-clicking a method from an edge's method-call list should navigate to that method in the editor.
- **Generated step details:** The AI response should include both the step summary shown in the list and a more thorough explanation for each step. Generate the thorough per-step response during the first AI call; do not make another AI request when the user clicks a step.
- **Step click behavior:** Clicking a step should show the already-generated thorough explanation for that step and play only that step's predefined type animation. Step clicks should not regenerate AI content.

---

## Core Principle

The graph is deterministic.

AI must **not** generate files, functions, edges, dependencies, or graph structure.

The backend/plugin is responsible for extracting real structure from the repository using static analysis, file structure, imports, calls, usages, and project metadata.

AI is only used to:

- explain a selected file/function/subgraph
- classify steps using the currently configured step types
- generate a developer-friendly walkthrough
- suggest which existing nodes are relevant in each explanation step

All AI output must be validated against known graph node IDs and edge IDs before being shown in the UI.

---

## Main User Flows

### Flow 1: Repository map

1. User opens the CodeMap tool window.
2. CodeMap shows a general Obsidian-style repository map.
3. The initial graph should be high-level enough to stay readable.
4. Nodes can represent folders, files, classes, functions, tests, external dependencies, or other useful code entities.
5. User can click nodes to inspect or focus them.
6. Clicking a node should eventually allow opening the related source location in IntelliJ.

### Flow 2: Explain from graph

1. User clicks or right-clicks a node in the graph.
2. User chooses something like “Explain with CodeMap”.
3. CodeMap extracts a focused subgraph around the selected node.
4. The AI receives the selected node, focused graph, and relevant code snippets.
5. AI returns a structured step-by-step explanation.
6. The frontend displays the steps in a side panel.
7. User can click individual steps to highlight/animate only that step.
8. User can also choose “Show all steps” to see the full flow at once.

For the `clean_up` target graph, the graph flow should become method selection from a file-level map:

1. User clicks a file node or file-to-file edge.
2. If an edge is selected, CodeMap shows the aggregated method-call list for that file-to-file connection.
3. User chooses a method from that list, or double-clicks the method to navigate to its code.
4. CodeMap extracts focused method-level explanation context around the selected method.
5. AI returns a structured step-by-step explanation, including both short list text and longer per-step detail text.
6. Clicking a step shows the already-generated detail text and plays only that step's predefined type animation.

### Flow 3: Explain from editor

1. User right-clicks a function/file/class in the IntelliJ editor.
2. User selects “Visualize with CodeMap” or “Explain with CodeMap”.
3. CodeMap opens the tool window.
4. The graph focuses on the selected code element.
5. AI generates a step-by-step explanation for the focused subgraph.
6. The user can interact with the graph and explanation steps.
7. For method explanations, right-clicking the method in code should remain the fastest entry point.

---

## Product Layers

### Layer 1: General repo map

Generate an Obsidian-style graph of the repository.

This should be cached locally so that big repositories do not need to regenerate the entire graph repeatedly.

Possible cache location:

```text
.codemap/cache.json
```

The cache should include:

- graph nodes
- graph edges
- metadata
- timestamps and/or file hashes if feasible
- selected project information if needed

For MVP, simple cache invalidation is acceptable. For example, regenerate when the user clicks a refresh button or when the cache does not exist.

### Layer 2: Focused explanation mode

When the user selects a file/function/class, CodeMap should extract a smaller focused subgraph around it.

The focused subgraph should answer questions like:

- What does this code interact with?
- What calls this?
- What does it call?
- What data or control flow passes through this area?
- What related tests or external dependencies exist?
- What should the developer read next?

AI should generate explanation steps over this focused graph.

### Layer 3: Typed steps

The explanation should consist of ordered steps.

Each step should have a `type`, but the exact taxonomy of step types is **not finalized yet**.

Do not hardcode a permanent enum too early. Treat step types as configurable strings.

The architecture should support:

- adding new step types
- removing step types
- renaming step types
- mapping each configured type to a predefined animation
- falling back to a default animation for unknown types

Possible future step type examples, not final:

- input/data collection
- validation/checking
- transformation/processing
- persistence/storage
- external call
- UI or response update
- error handling
- test coverage
- orchestration/control flow
- unknown

### Layer 4: Animations

The frontend owns all animations.

AI does **not** generate animations or frontend code.

AI only returns a step type and the node/edge IDs that should be focused for that step.

The frontend maps step types to predefined animations.

Example mapping concept:

```ts
const animationByType: Record<string, AnimationName> = {
  "input": "pulse-source",
  "processing": "flow-highlight",
  "storage": "save-drop",
  "external-call": "outward-ping",
  "error-handling": "warning-flash",
  "unknown": "basic-focus"
};
```

For MVP, the animations can be simple:

- focused node glow
- dim unrelated nodes
- animated focused edges
- pulse selected node
- warning highlight
- camera pan/zoom to selected nodes
- “show all steps” highlighting multiple groups

### Layer 5: Optional error visualization

If time allows, CodeMap may show errors or warnings on the graph.

Examples:

- failing test node
- compile error location
- stack trace location
- risky file/function
- missing or weak test coverage

This is optional and should not block the main MVP.

---

## Backend Responsibilities

The backend is an IntelliJ plugin, preferably written in Kotlin.

Backend responsibilities:

- Register IntelliJ actions:
  - “Open CodeMap”
  - “Visualize with CodeMap”
  - “Explain with CodeMap”
- Detect the selected file/function/class from the editor when possible.
- Build the deterministic repo graph.
- Cache and load the repo graph from local project files.
- Extract a focused subgraph around the selected node.
- Collect short relevant code snippets for AI context.
- Call the OpenAI API using the provided hackathon key from `OPENAI_API_KEY`, `-Dopenai.api.key`, or the project `.env`. Do not commit real API keys.
- Ask AI for JSON only.
- Validate AI response against known node IDs and edge IDs.
- Send graph data and AI explanation data to the frontend.
- Receive frontend node-click events.
- Open the corresponding file/function in IntelliJ.

---

## Frontend Responsibilities

The frontend is a React app embedded inside the IntelliJ plugin using a JCEF tool window.

Frontend responsibilities:

- Render the general repo graph.
- Render focused graph mode.
- Show a side panel with AI-generated steps.
- Allow selecting individual steps.
- Allow “Show all steps”.
- Animate nodes and edges using predefined animations.
- Map step types to animations through configuration.
- Provide a fallback animation for unknown step types.
- Handle graph node clicks.
- Send node-click events back to the IntelliJ backend.
- Be polished enough for a hackathon demo.

Recommended graph library:

- React Flow, for quick custom graph nodes, animated edges, clickable nodes, and viewport controls.

Alternative:

- Cytoscape.js, if the team prefers graph-specific layouts.

---

## Data Contracts

These schemas are the contract between backend, frontend, cache, and AI.

Keep them stable and simple.

### GraphNode

```json
{
  "id": "stable unique id",
  "label": "human readable name",
  "kind": "repo|folder|file|class|function|test|external|database|ui|unknown",
  "filePath": "optional path",
  "line": "optional line number",
  "metadata": {}
}
```

### GraphEdge

```json
{
  "id": "source->target:relation",
  "source": "node id",
  "target": "node id",
  "relation": "contains|imports|calls|calledBy|tests|reads|writes|updates|external|unknown"
}
```

For the `clean_up` file-level graph target, each edge should represent one aggregated directed file-to-file connection and include the method calls that caused it:

```json
{
  "id": "fileA->fileB:calls",
  "source": "file node id",
  "target": "file node id",
  "relation": "calls",
  "methodCalls": [
    {
      "callerMethod": "qualified caller method",
      "targetMethod": "qualified target method",
      "callerLine": "optional line number",
      "targetLine": "optional line number"
    }
  ]
}
```

### CodeGraph

```json
{
  "nodes": [],
  "edges": [],
  "selectedNodeId": "optional selected node id",
  "metadata": {}
}
```

### AIExplanationStep

```json
{
  "id": "step-1",
  "title": "Short title",
  "type": "configured step type string",
  "explanation": "Short developer-friendly explanation",
  "detail": "Longer explanation shown when the user opens this step",
  "focusNodes": ["existing node ids only"],
  "focusEdges": ["existing edge ids only"]
}
```

### AIExplanationResponse

```json
{
  "summary": "Short overview of the selected code",
  "steps": [],
  "riskNotes": ["optional notes about risks, missing tests, or uncertainty"],
  "suggestedNextNodes": ["optional existing node ids"]
}
```

---

## AI Rules

The current implementation uses two AI passes:

1. Explanation pass: `gpt-5.5` with `reasoning_effort: high` receives the selected node, focused graph, and relevant snippets; it returns JSON explanation steps with `type` set to `unknown`.
2. Type-selection pass: `gpt-5.4-nano` receives the generated steps plus allowed/configured step types; it returns JSON mapping each existing step id to exactly one allowed type.

AI should receive:

- selected node
- focused graph
- relevant snippets
- allowed/configured step types
- task instructions

AI must return JSON only.

AI must not:

- invent files
- invent functions
- invent edges
- invent dependencies
- output code unless explicitly requested
- output animation code
- refer to node IDs or edge IDs that do not exist

The backend must validate:

- every `focusNodes` entry exists in the graph
- every `focusEdges` entry exists in the graph
- every step has a safe fallback type if the type is unknown
- malformed responses do not crash the plugin

---

## Suggested AI Prompt Shape

Use this as the general shape, not necessarily the final exact prompt. The current implementation splits explanation generation and step-type selection into separate prompts.

```text
You are helping a developer understand a selected part of a codebase.

The graph was extracted deterministically from the IDE.
Only use the provided nodes, edges, and snippets.
Do not invent files, functions, or dependencies.

Return JSON only.

Create a short step-by-step explanation of how the selected node interacts with the rest of the focused graph.

Set every step type to "unknown". A separate lightweight classifier will assign step types.

Each step must include:
- id
- title
- type
- explanation
- detail
- focusNodes
- focusEdges

Selected node:
{{selectedNode}}

Focused graph:
{{focusedGraph}}

Relevant snippets:
{{snippets}}
```

The classifier prompt should receive the generated steps and the allowed step-type list, then return JSON like:

```json
{"steps":[{"id":"existing step id","type":"one allowed step type"}]}
```

---

## MVP Scope

Prioritize a working, polished demo over perfect static analysis.

MVP should include:

1. IntelliJ tool window.
2. Basic general repo map.
3. Local graph cache.
4. Right-click action from editor.
5. Focused graph around selected file/function.
6. AI-generated JSON explanation steps.
7. Step panel in frontend.
8. Predefined animations based on step type.
9. Click node to open file if feasible.

Avoid spending too much time on:

- perfect multi-language support
- full semantic understanding of all code
- complete call graph accuracy
- complex cache invalidation
- perfect graph layout
- advanced risk scoring
- generating new animations dynamically
- natural language repo chat

---

## Implementation Priorities

Suggested order:

1. Define shared TypeScript/Kotlin-compatible JSON schemas.
2. Build mock frontend with hardcoded graph and hardcoded explanation steps.
3. Add animations and step panel in frontend.
4. Create IntelliJ tool window with embedded frontend.
5. Send mock graph data from Kotlin to frontend.
6. Add basic repo graph generation.
7. Add cache read/write.
8. Add editor right-click action and selected element detection.
9. Add focused subgraph extraction.
10. Add OpenAI API call.
11. Validate AI response.
12. Polish demo flow.

---

## Development Environment

The project can be developed from VS Code as a Kotlin/Gradle workspace.

Current VS Code support lives in `.vscode/`:

- `extensions.json` recommends Kotlin, Java, and Gradle extensions.
- `settings.json` enables Gradle wrapper import and Kotlin language server support.
- `tasks.json` provides `Gradle: build`, `Gradle: run IDE`, and `Gradle: run IDE debug`.
- `launch.json` provides `Attach to Gradle runIde` for attaching to `runIde --debug-jvm` on port `5005`.

Main local commands:

```powershell
.\gradlew.bat compileKotlin
.\gradlew.bat runIde
.\gradlew.bat runIde --debug-jvm
```

`compileKotlin` has been verified after adding the VS Code workspace files. A full `build` may fail if the IntelliJ sandbox plugin jar is locked by a running sandbox IDE process.

---

## Demo Goal

The demo should show:

1. A developer opens an unfamiliar repo.
2. CodeMap displays a visual repo map.
3. The developer right-clicks a function and selects “Explain with CodeMap”.
4. The graph focuses on related files/functions.
5. AI produces a step-by-step explanation.
6. Each step highlights and animates the relevant nodes/edges.
7. The developer can click a node to jump to the source file.
8. The final message is that CodeMap helps developers understand unfamiliar code and make safer changes.

---

## Naming

Current product name: CodeMap.

Possible UI labels:

- Open CodeMap
- Visualize with CodeMap
- Explain with CodeMap
- Focus Mode
- Repository Map
- Step View
- Show All Steps
- Open in Editor

---

## Notes for Agents

When working on this project:

- First propose a short plan before coding.
- Keep changes small and demo-oriented.
- Prefer stable interfaces between backend/frontend/AI.
- Do not over-engineer the static analysis.
- Do not hardcode final step types yet.
- Make step types configurable strings.
- Use sensible fallbacks when AI output is incomplete.
- Keep the experience smooth and visually understandable.
