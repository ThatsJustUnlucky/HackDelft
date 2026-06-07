package com.hackdelft.codegraph

import com.google.gson.JsonParser
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.pom.Navigatable
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefJSQuery
import java.awt.BorderLayout
import java.util.concurrent.Executors
import javax.swing.JPanel

class CodeGraphPanel(private val project: Project) {

  val component: JPanel = JPanel(BorderLayout())
  private val browser = JBCefBrowser()
  private val extractor = GraphDataExtractor(project)
  private val cache = GraphCache(project)
  private val aiService = AIExplainService(project.basePath)
  private val jsQuery = JBCefJSQuery.create(browser)
  private val bg = Executors.newSingleThreadExecutor()

  @Volatile
  private var currentGraph: GraphData? = null
  @Volatile
  private var currentExplanation: ExplainContext? = null

  private data class ExplainContext(
    val selectedId: String,
    val repoGraph: GraphData,
    val focused: GraphData,
    val snippets: Map<String, String>,
    val response: AIExplanationResponse,
    val history: MutableList<Pair<String, String>> = mutableListOf()
  )

  companion object {
    private val instances = mutableMapOf<Project, CodeGraphPanel>()
    fun getInstance(project: Project) = instances[project]
  }

  init {
    instances[project] = this

    jsQuery.addHandler { msg ->
      handleMessage(msg)
      null
    }

    browser.jbCefClient.addLoadHandler(object : org.cef.handler.CefLoadHandlerAdapter() {
      override fun onLoadEnd(b: org.cef.browser.CefBrowser?, frame: org.cef.browser.CefFrame?, code: Int) {
        if (frame?.isMain == true) {
          val bridge = """
            window.codemapSend=function(m){${jsQuery.inject("m")}};
            window.navigateToNode=function(id){window.codemapSend(JSON.stringify({t:'nav',id:id}));};
            window.explainNode=function(id){window.codemapSend(JSON.stringify({t:'explain',id:id}));};
            window.codemapAsk=function(q,effort){window.codemapSend(JSON.stringify({t:'chat',q:q,effort:effort}));};
            window.codemapRefresh=function(){window.codemapSend(JSON.stringify({t:'refresh'}));};
          """.trimIndent()
          browser.cefBrowser.executeJavaScript(bridge, "", 0)
          refreshGraph()
        }
      }
    }, browser.cefBrowser)

    browser.loadHTML(html())
    component.add(browser.component, BorderLayout.CENTER)
  }

  /** Open in Graph View: reveal a single method's connections (both directions) in the graph. */
  fun openMethodInGraph(methodId: String) {
    browser.cefBrowser.executeJavaScript("openMethodConnections(\"${methodId.e()}\");", "", 0)
  }

  fun refreshGraph(force: Boolean = false) {
    bg.submit {
      try {
        val data = if (force) extractAndCache() else (cache.load() ?: extractAndCache())
        currentGraph = data
        browser.cefBrowser.executeJavaScript("updateGraph(${toJson(data)});", "", 0)
      } catch (_: Exception) {}
    }
  }

  /** Flow 2/3: build a focused subgraph around [nodeId], ask the AI for typed steps, push to the UI. */
  fun explainNode(nodeId: String) {
    // Show the loading panel right away so the editor-triggered flow gives instant feedback during the long AI call.
    currentExplanation = null
    browser.cefBrowser.executeJavaScript("openPanelLoading();", "", 0)
    bg.submit {
      try {
        val full = currentGraph ?: extractAndCache().also { currentGraph = it }
        val filePath = methodFilePath(nodeId)
        val focused = FocusedSubgraphExtractor.extract(full, nodeId, filePath)
        val snippets = collectSnippets(focused, nodeId)
        val anchorId = if (nodeId.startsWith("file:")) nodeId else filePath?.let { "file:$it" }
        val response = aiService.explain(nodeId, focused, snippets, anchorId)
        currentExplanation = ExplainContext(nodeId, full, focused, snippets, response)
        // Add the used-method overlay (visible only during the step view), then show the steps.
        // showExplanation auto-plays step 0 (anchor-only highlight + camera fit).
        // Don't call focusNode here: it re-lights the node's neighbors and overrides the step highlight.
        browser.cefBrowser.executeJavaScript("setStepMethods(${methodSubgraphJson(focused, nodeId, anchorId)});", "", 0)
        browser.cefBrowser.executeJavaScript("showExplanation(${explanationToJson(response)});", "", 0)
      } catch (_: Exception) {}
    }
  }

  private fun extractAndCache(): GraphData = extractor.extract().also { cache.save(it) }

  private fun handleMessage(msg: String) {
    val obj = try { JsonParser.parseString(msg).asJsonObject } catch (_: Exception) { return }
    when (obj.get("t")?.asString) {
      "nav" -> obj.get("id")?.asString?.let { navigateTo(it) }
      "explain" -> obj.get("id")?.asString?.let { explainNode(it) }
      "chat" -> obj.get("q")?.asString?.let { answerChat(it, obj.get("effort")?.asString ?: "medium") }
      "refresh" -> refreshGraph(force = true)
    }
  }

  private fun answerChat(question: String, reasoningEffort: String) {
    val q = question.trim()
    if (q.isBlank()) return
    bg.submit {
      val ctx = currentExplanation
      if (ctx == null) {
        browser.cefBrowser.executeJavaScript("appendChatAnswer(\"Open an explanation before asking a follow-up.\", true);", "", 0)
        return@submit
      }
      val answer = aiService.answerFollowUp(
        selectedId = ctx.selectedId,
        repoGraph = ctx.repoGraph,
        focused = ctx.focused,
        snippets = ctx.snippets,
        explanation = ctx.response,
        question = q,
        reasoningEffort = reasoningEffort,
        history = ctx.history.toList()
      )
      if (currentExplanation !== ctx) return@submit
      ctx.history += q to answer
      browser.cefBrowser.executeJavaScript("appendChatAnswer(\"${answer.e()}\", false);", "", 0)
    }
  }

  private fun collectSnippets(focused: GraphData, selectedId: String): Map<String, String> =
    ReadAction.compute<Map<String, String>, Exception> {
      val out = LinkedHashMap<String, String>()
      putSnippet(out, selectedId)
      focused.edges.asSequence()
        .flatMap { it.methodCalls.asSequence() }
        .flatMap { sequenceOf(it.callerMethod, it.targetMethod) }
        .distinct()
        .take(10)
        .forEach { putSnippet(out, it) }
      for (n in focused.nodes.take(4)) {
        if (out.size >= 12) break
        putSnippet(out, n.id)
      }
      out
    }

  private fun putSnippet(out: MutableMap<String, String>, id: String) {
    if (out.containsKey(id)) return
    val text = resolvePsi(id)?.text ?: return
    out[id] = text.take(800)
  }

  private fun resolvePsi(nodeId: String): PsiElement? {
    val facade = JavaPsiFacade.getInstance(project)
    val scope = GlobalSearchScope.projectScope(project)
    return when {
      nodeId.startsWith("class:") -> facade.findClass(nodeId.removePrefix("class:"), scope)
      nodeId.startsWith("method:") -> {
        val rest = nodeId.removePrefix("method:")
        val dot = rest.lastIndexOf('.')
        if (dot < 0) null
        else facade.findClass(rest.substring(0, dot), scope)
          ?.findMethodsByName(rest.substring(dot + 1), false)?.firstOrNull()
      }
      nodeId.startsWith("file:") -> {
        val vf = LocalFileSystem.getInstance().findFileByPath(nodeId.removePrefix("file:")) ?: return null
        PsiManager.getInstance(project).findFile(vf)
      }
      else -> null
    }
  }

  private fun explanationToJson(r: AIExplanationResponse): String {
    val steps = r.steps.joinToString(",") { s ->
      val fn = s.focusNodes.joinToString(",") { "\"${it.e()}\"" }
      """{"id":"${s.id.e()}","title":"${s.title.e()}","type":"${s.type.e()}","explanation":"${s.explanation.e()}","detail":"${s.detail.e()}","focusNodes":[$fn],"focusMethod":"${s.focusMethod.e()}"}"""
    }
    val risks = r.riskNotes.joinToString(",") { "\"${it.e()}\"" }
    return """{"summary":"${r.summary.e()}","steps":[$steps],"riskNotes":[$risks]}"""
  }

  /** Used-method overlay for the step view: the selected method + the (cross-file) methods it calls. */
  private fun methodSubgraphJson(focused: GraphData, selectedId: String, anchorId: String?): String {
    val fileColor = focused.nodes.associate { it.id to it.color }
    val methods = LinkedHashMap<String, Triple<String, String, String>>() // id -> (label, fileNodeId, color)
    val edges = LinkedHashSet<Pair<String, String>>()
    if (anchorId != null && fileColor.containsKey(anchorId)) {
      methods[selectedId] = Triple(methodLabel(selectedId), anchorId, fileColor[anchorId] ?: "")
    }
    for (e in focused.edges) {
      for (c in e.methodCalls) {
        if (c.callerMethod != selectedId) continue
        methods.getOrPut(c.callerMethod) { Triple(methodLabel(c.callerMethod), e.source, fileColor[e.source] ?: "") }
        methods.getOrPut(c.targetMethod) { Triple(methodLabel(c.targetMethod), e.target, fileColor[e.target] ?: "") }
        edges += c.callerMethod to c.targetMethod
      }
    }
    val methodsJson = methods.entries.joinToString(",") { (id, t) ->
      """{"id":"${id.e()}","label":"${t.first.e()}","file":"${t.second.e()}","color":"${t.third}"}"""
    }
    val edgesJson = edges.joinToString(",") { (s, t) -> """{"source":"${s.e()}","target":"${t.e()}"}""" }
    return """{"methods":[$methodsJson],"edges":[$edgesJson]}"""
  }

  private fun methodLabel(methodId: String): String =
    methodId.removePrefix("method:").substringAfterLast('.')

  private fun navigateTo(nodeId: String) {
    bg.submit {
      when {
        nodeId.startsWith("file:") -> {
          val vf = LocalFileSystem.getInstance().findFileByPath(nodeId.removePrefix("file:")) ?: return@submit
          ApplicationManager.getApplication().invokeLater {
            FileEditorManager.getInstance(project).openFile(vf, true)
          }
        }
        else -> {
          val nav = ReadAction.compute<Navigatable?, Exception> { resolve(nodeId) } ?: return@submit
          ApplicationManager.getApplication().invokeLater { nav.navigate(true) }
        }
      }
    }
  }

  private fun resolve(nodeId: String): Navigatable? {
    val facade = JavaPsiFacade.getInstance(project)
    val scope = GlobalSearchScope.projectScope(project)
    return when {
      nodeId.startsWith("class:") ->
        facade.findClass(nodeId.removePrefix("class:"), scope) as? Navigatable

      nodeId.startsWith("method:") -> {
        val rest = nodeId.removePrefix("method:")
        val dot = rest.lastIndexOf('.')
        if (dot < 0) return null
        val cls = facade.findClass(rest.substring(0, dot), scope) ?: return null
        cls.findMethodsByName(rest.substring(dot + 1), false).firstOrNull() as? Navigatable
      }

      nodeId.startsWith("file:") -> {
        val vf = LocalFileSystem.getInstance().findFileByPath(nodeId.removePrefix("file:")) ?: return null
        PsiManager.getInstance(project).findFile(vf) as? Navigatable
      }

      else -> null
    }
  }

  private fun toJson(data: GraphData): String {
    val nodes = data.nodes.joinToString(",") { n ->
      """{"data":{"id":"${n.id.e()}","label":"${n.label.e()}","type":"${n.type}","fp":"${n.filePath.e()}","folder":"${n.folder.e()}","color":"${n.color}","isTest":${n.isTest}}}"""
    }
    val edges = data.edges.filter { it.type != "calls" || it.methodCalls.isNotEmpty() }.mapIndexed { i, e ->
      val calls = e.methodCalls.joinToString(",") { c ->
        """{"callerMethod":"${c.callerMethod.e()}","targetMethod":"${c.targetMethod.e()}","callerFile":"${c.callerFile.e()}","targetFile":"${c.targetFile.e()}","callerLine":${c.callerLine},"targetLine":${c.targetLine}}"""
      }
      """{"data":{"id":"e$i","source":"${e.source.e()}","target":"${e.target.e()}","type":"${e.type}","color":"${e.color}","methodCalls":[$calls]}}"""
    }.joinToString(",")
    return """{"nodes":[$nodes],"edges":[$edges]}"""
  }

  private fun methodFilePath(nodeId: String): String? =
    if (nodeId.startsWith("method:")) {
      ReadAction.compute<String?, Exception> { resolvePsi(nodeId)?.containingFile?.virtualFile?.path?.replace('\\', '/') }
    } else null

  private fun String.e() = replace("\\", "\\\\").replace("\"", "\\\"")
    .replace("\n", "\\n").replace("\r", "")

  private fun html() = """<!DOCTYPE html>
<html><head><meta charset="UTF-8">
<script src="https://cdnjs.cloudflare.com/ajax/libs/d3/7.9.0/d3.min.js"></script>
<style>
*{box-sizing:border-box;margin:0;padding:0}
html,body{width:100%;height:100%;overflow:hidden;background:#1e1f22;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif}
body{display:flex;flex-direction:column}
#topbar{flex-shrink:0;height:52px;background:#2b2d30;border-bottom:1px solid #393b40;display:flex;align-items:center;gap:8px;padding:0 12px}
#graph-wrap{flex:1;min-height:0;position:relative}
#graph{display:block;width:100%;height:100%;background:#1e1f22}
#statusbar{flex-shrink:0;height:32px;background:#2b2d30;border-top:1px solid #393b40;display:flex;align-items:center;padding:0 12px;gap:18px}
.top-search{flex:1;max-width:240px;background:#1e1f22;border:1px solid #393b40;border-radius:8px;padding:7px 12px;color:#dfe1e5;font-size:13px;outline:none}
.top-search:focus{border-color:#548af7}
.top-search::placeholder{color:#6f737a}
.btn{background:#1e1f22;color:#dfe1e5;border:1px solid #393b40;padding:7px 13px;cursor:pointer;border-radius:8px;font-size:12px;font-weight:500;transition:all .15s;white-space:nowrap}
.btn:hover{background:#43454a;border-color:#548af7;color:#548af7}
.btn.active{background:#3a2424;border-color:#db5c5c;color:#db5c5c}
.btn:disabled{opacity:.45;cursor:default}
.btn:disabled:hover{background:#1e1f22;border-color:#393b40;color:#dfe1e5}
.sep{width:1px;height:26px;background:#393b40;margin:0 2px}
.menu-wrap{position:relative;flex-shrink:0}
.menu{position:absolute;top:36px;left:0;z-index:30;width:150px;background:#2b2d30;border:1px solid #393b40;border-radius:8px;padding:6px;box-shadow:0 10px 24px rgba(0,0,0,.45)}
.menu.hidden{display:none}
.menu .btn{display:block;width:100%;text-align:left;margin-bottom:5px}
.menu .btn:last-child{margin-bottom:0}
#stats{color:#6f737a;font-size:11px;white-space:nowrap}
#selected-info{color:#548af7;font-size:11px;flex:1;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.legend{display:flex;align-items:center;gap:5px;color:#6f737a;font-size:11px;white-space:nowrap}
.dot{width:10px;height:10px;border-radius:50%;flex-shrink:0}
.ring{width:11px;height:11px;border-radius:50%;border:2px solid #5fad65;flex-shrink:0}
.dash{width:16px;height:0;border-top:2px solid #6f737a;flex-shrink:0}
.dash.p{border-top:2px solid #b179c9}
.dash.g{border-top:2px dashed #5fad65}
.nlabel{fill:#dfe1e5;font-size:11px;paint-order:stroke;stroke:#1e1f22;stroke-width:3px;stroke-linejoin:round;pointer-events:none;font-weight:500}
.omlabel{fill:#dfe1e5;font-size:9px;paint-order:stroke;stroke:#2b2d30;stroke-width:3px;stroke-linejoin:round;pointer-events:none}
g.node,path.vis{transition:opacity .16s ease}
#fxpanel{position:absolute;top:10px;left:10px;z-index:20;background:rgba(43,45,48,0.97);border:1px solid #393b40;border-radius:10px;padding:12px 14px;width:208px;box-shadow:0 10px 26px rgba(0,0,0,.5)}
#fxpanel.hidden{display:none}
.fx-h{color:#b179c9;font-size:11px;font-weight:600;margin-bottom:11px;letter-spacing:.3px}
.fx-row{margin-bottom:11px}
.fx-row:last-child{margin-bottom:0}
.fx-lab{display:flex;justify-content:space-between;color:#bcbec4;font-size:11px;margin-bottom:4px}
.fx-val{color:#548af7;font-variant-numeric:tabular-nums}
.fx-row input[type=range]{width:100%;accent-color:#548af7;cursor:pointer}
#steppanel{position:fixed;top:52px;right:0;bottom:32px;width:340px;background:#2b2d30F2;backdrop-filter:blur(4px);border-left:1px solid #393b40;display:flex;flex-direction:column;z-index:10;box-shadow:-6px 0 22px rgba(0,0,0,.4)}
#steppanel.hidden{display:none}
#sp-head{flex-shrink:0;display:flex;align-items:center;justify-content:space-between;padding:12px 14px;border-bottom:1px solid #393b40}
#sp-title{color:#b179c9;font-weight:600;font-size:13px}
.sp-x{background:none;border:none;color:#6f737a;cursor:pointer;font-size:14px}
.sp-x:hover{color:#db5c5c}
#sp-summary{flex-shrink:0;padding:12px 14px;color:#bcbec4;font-size:12px;line-height:1.5;border-bottom:1px solid #393b40}
#sp-detail{flex-shrink:0;padding:12px 14px;color:#dfe1e5;font-size:12px;line-height:1.5;border-bottom:1px solid #393b40;max-height:150px;overflow:auto}
#sp-steps{flex:1;overflow:auto;padding:8px}
.sp-step{background:#1e1f22;border:1px solid #393b40;border-radius:8px;padding:10px;margin-bottom:8px;cursor:pointer;transition:all .15s}
.sp-step:hover{border-color:#548af7}
.sp-step.active{border-color:#548af7;background:#2e436e}
.sp-step-h{display:flex;align-items:center;gap:8px;margin-bottom:5px}
.sp-badge{font-size:9px;font-weight:700;color:#2b2d30;padding:2px 7px;border-radius:10px;text-transform:uppercase;letter-spacing:.4px;white-space:nowrap}
.sp-step-t{color:#dfe1e5;font-size:12px;font-weight:600;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.sp-step-e{color:#6f737a;font-size:11px;line-height:1.5}
#sp-risks{flex-shrink:0;padding:0 14px;color:#d6ae58;font-size:11px;line-height:1.5;max-height:90px;overflow:auto}
#sp-risks:empty{display:none}
#sp-actions{flex-shrink:0;padding:10px 14px;border-top:1px solid #393b40}
#sp-actions .btn{width:100%}
#sp-chat{flex-shrink:0;border-top:1px solid #393b40;padding:10px 14px;background:#25272a}
#sp-chat-log{max-height:112px;overflow:auto;display:flex;flex-direction:column;gap:6px;margin-bottom:8px;transition:max-height .16s ease}
.chat-msg{font-size:11px;line-height:1.45;border:1px solid #393b40;border-radius:8px;padding:7px 8px;white-space:pre-wrap}
.chat-msg.user{align-self:flex-end;max-width:88%;background:#24314f;color:#dfe1e5;border-color:#548af7}
.chat-msg.ai{align-self:flex-start;max-width:94%;background:#1e1f22;color:#bcbec4}
.chat-msg.error{align-self:flex-start;max-width:94%;background:#3a2424;color:#db5c5c;border-color:#db5c5c}
#sp-chat-form{display:flex;gap:6px}
#sp-chat-q{flex:1;min-width:0;background:#1e1f22;border:1px solid #393b40;border-radius:8px;padding:7px 9px;color:#dfe1e5;font-size:12px;outline:none}
#sp-chat-q:focus{border-color:#548af7}
#sp-chat-q:disabled{opacity:.55}
#sp-chat-send{padding-left:10px;padding-right:10px}
#sp-chat-effort{display:flex;align-items:center;justify-content:flex-end;gap:7px;margin-bottom:8px;color:#6f737a;font-size:11px}
#sp-chat.has-response #sp-chat-log{max-height:164px}
#sp-chat-effort select{width:92px;background:#1e1f22;border:1px solid #393b40;border-radius:6px;color:#bcbec4;padding:3px 5px;font-size:11px;outline:none;cursor:pointer}
#sp-chat-effort select:focus{border-color:#548af7}
#sp-chat-effort select:disabled{opacity:.55;cursor:default}
#methodpanel{position:fixed;top:52px;right:0;bottom:32px;width:380px;background:#2b2d30F2;backdrop-filter:blur(4px);border-left:1px solid #393b40;display:flex;flex-direction:column;z-index:11;box-shadow:-6px 0 22px rgba(0,0,0,.4)}
#methodpanel.hidden{display:none}
#mp-head{flex-shrink:0;display:flex;align-items:center;justify-content:space-between;padding:12px 14px;border-bottom:1px solid #393b40}
#mp-title{color:#548af7;font-weight:600;font-size:13px}
#mp-summary{flex-shrink:0;padding:10px 14px;color:#bcbec4;font-size:12px;line-height:1.4;border-bottom:1px solid #393b40}
#mp-search-wrap{flex-shrink:0;padding:8px 14px;border-bottom:1px solid #393b40}
#mp-search{width:100%;background:#1e1f22;border:1px solid #393b40;border-radius:8px;padding:7px 10px;color:#dfe1e5;font-size:12px;outline:none}
#mp-search:focus{border-color:#548af7}
#mp-search::placeholder{color:#6f737a}
#mp-methods{flex:1;overflow:auto;padding:8px}
.mp-call{background:#1e1f22;border:1px solid #393b40;border-radius:8px;padding:9px;margin-bottom:8px}
.mp-line{display:flex;align-items:center;gap:6px;color:#6f737a;font-size:11px;margin-bottom:8px}
.mp-method{color:#dfe1e5;font-size:11px;font-weight:600;cursor:pointer;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;max-width:145px}
.mp-method:hover{color:#548af7}
.mp-actions{display:flex;gap:6px}
.mp-actions .btn{flex:1;padding:5px 8px;font-size:11px}
.mp-group{margin-bottom:12px}
.mp-group-h{color:#548af7;font-size:12px;font-weight:600;margin-bottom:6px;display:flex;align-items:center;gap:6px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.mp-count{color:#6f737a;font-size:10px;font-weight:500;background:#1e1f22;border:1px solid #393b40;border-radius:8px;padding:1px 6px;flex-shrink:0}
.mp-chips{display:flex;flex-wrap:wrap;gap:5px}
.mp-chip{display:inline-flex;align-items:center;gap:4px;background:#1e1f22;border:1px solid #393b40;border-radius:7px;padding:4px 7px;font-size:10px;max-width:100%}
.mp-chip.defined{border-color:#5fad65;background:#1e2d22}
.mp-chip .mp-cm{color:#dfe1e5;cursor:pointer;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;max-width:120px}
.mp-chip .mp-cm:hover{color:#548af7}
.mp-chip .mp-arrow{color:#6f737a;flex-shrink:0}
</style></head><body>

<div id="topbar">
  <input id="search" class="top-search" type="text" placeholder="Search files..." oninput="searchNodes(this.value)">
  <input id="method-search" class="top-search" type="text" placeholder="Search methods..." oninput="searchMethods(this.value)">
  <div class="sep"></div>
  <button class="btn" onclick="fitAll()">Fit</button>
  <button class="btn" onclick="relayout()">Re-layout</button>
  <div class="menu-wrap">
    <button class="btn" onclick="toggleViewMenu(event)">Controls ▾</button>
    <div id="viewmenu" class="menu hidden">
      <button class="btn" onclick="toggleFx();closeViewMenu()">Forces</button>
      <button class="btn" id="btn-calls" onclick="toggleEdgeType('calls','btn-calls')">Calls</button>
      <button class="btn" id="btn-inherits" onclick="toggleEdgeType('inherits','btn-inherits')">Inherits</button>
      <button class="btn" id="btn-tests" onclick="toggleEdgeType('tests','btn-tests')">Tests</button>
    </div>
  </div>
  <div class="sep"></div>
  <button class="btn active" id="btn-hidetests" onclick="toggleTestFiles('btn-hidetests')" title="Make all test files invisible">Hide Tests</button>
  <div class="sep"></div>
  <button class="btn" onclick="resetView()">Reset</button>
  <button class="btn" onclick="window.codemapRefresh&&window.codemapRefresh()">Refresh</button>
</div>

<div id="graph-wrap"><svg id="graph"></svg>
  <div id="fxpanel" class="hidden">
    <div class="fx-h">Graph forces</div>
    <div class="fx-row"><div class="fx-lab"><span>Spread</span><span class="fx-val" id="fx-repel-v">1.2</span></div><input type="range" min="0.3" max="2.6" step="0.1" value="1.2" oninput="setFx('repel',this.value,'fx-repel-v')"></div>
    <div class="fx-row"><div class="fx-lab"><span>Link length</span><span class="fx-val" id="fx-link-v">1.3</span></div><input type="range" min="0.5" max="2.6" step="0.1" value="1.3" oninput="setFx('linkDist',this.value,'fx-link-v')"></div>
    <div class="fx-row"><div class="fx-lab"><span>Grouping</span><span class="fx-val" id="fx-cluster-v">0.06</span></div><input type="range" min="0" max="0.22" step="0.01" value="0.06" oninput="setFx('cluster',this.value,'fx-cluster-v')"></div>
  </div>
</div>

<div id="steppanel" class="hidden">
  <div id="sp-head">
    <span id="sp-title">✨ CodeMap Focus</span>
    <button class="sp-x" onclick="closePanel()">✕</button>
  </div>
  <div id="sp-summary"></div>
  <div id="sp-detail"></div>
  <div id="sp-steps"></div>
  <div id="sp-risks"></div>
  <div id="sp-actions"><button class="btn" onclick="showAllSteps()">Show All Steps</button></div>
  <div id="sp-chat">
    <div id="sp-chat-log"></div>
    <div id="sp-chat-effort">
      <span>Reasoning</span>
      <select id="sp-chat-effort-select" onchange="setChatEffort(this.value)">
        <option value="low">Low</option>
        <option value="medium" selected>Medium</option>
        <option value="high">High</option>
        <option value="xhigh">Extra High</option>
      </select>
    </div>
    <div id="sp-chat-form">
      <input id="sp-chat-q" type="text" placeholder="Ask a follow-up..." onkeydown="chatKey(event)">
      <button class="btn" id="sp-chat-send" onclick="sendChat()">Send</button>
    </div>
  </div>
</div>

<div id="methodpanel" class="hidden">
  <div id="mp-head">
    <span id="mp-title">File Calls</span>
    <button class="sp-x" onclick="closeMethodPanel()">✕</button>
  </div>
  <div id="mp-summary"></div>
  <div id="mp-search-wrap"><input id="mp-search" type="text" placeholder="Search methods..." oninput="filterMethodPanel(this.value)"></div>
  <div id="mp-methods"></div>
</div>

<div id="statusbar">
  <div class="legend"><div class="dot" style="background:#548af7"></div>File</div>
  <div class="legend"><div class="ring"></div>Test</div>
  <div class="legend"><div class="dash"></div>Calls</div>
  <div class="legend"><div class="dash p"></div>Inherits</div>
  <div class="legend"><div class="dash g"></div>Tests</div>
  <div class="sep"></div>
  <div id="stats">Loading...</div>
  <div id="selected-info"></div>
</div>

<script>
// ---- state ----
var graphEl, svg, viewport, gLinks, gNodes, gOverlay, sim, zoom;
var curK = 1;
var rawNodes = [], rawLinks = [], nodeById = {}, adj = {}, folderAngle = {};
var nodeSel = null, linkSel = null;
var selectedId = null, hoverLock = false, stepMode = false;
var currentSteps = [], currentMethodCalls = [];
var chatPending = false;
var chatEffort = 'medium';
var overlayMethods = [], overlayCalls = [];
var searchCameraBefore = null;
var hiddenTypes = {calls:false, inherits:false, tests:false};
var hideTests = true;
var fx = {repel:1.2, linkDist:1.3, cluster:0.06};

var EDGE_STYLE = {
  calls:    {stroke:'#43454a', dash:'',     base:1.0},
  inherits: {stroke:'#b179c9', dash:'',     base:2.0},
  tests:    {stroke:'#5fad65', dash:'5,4',  base:1.8}
};
var ARROW = {calls:'#6f737a', inherits:'#b179c9', tests:'#5fad65', mc:'#d6ae58'};
var TYPE_COLORS = {input:'#7dcfff',validation:'#d6ae58',processing:'#548af7',persistence:'#5fad65','external-call':'#b179c9',ui:'#2ac3de','error-handling':'#db5c5c',test:'#5fad65',orchestration:'#b179c9',unknown:'#dfe1e5'};
var TYPE_SHORT = {input:'input',validation:'valid',processing:'proc',persistence:'store','external-call':'ext',ui:'ui','error-handling':'error',test:'test',orchestration:'flow',unknown:'•'};
function colorByType(t){return TYPE_COLORS[t]||'#dfe1e5';}
function shortType(t){return TYPE_SHORT[t]||t;}
function escapeHtml(s){return (s||'').replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;');}
function escapeAttr(s){return escapeHtml(s).replace(/'/g,'&#39;').replace(/"/g,'&quot;');}
function methodLabel(id){var s=(id||'').replace(/^method:/,'');return s.substring(s.lastIndexOf('.')+1);}
function navigateMethod(id){if(window.navigateToNode)window.navigateToNode(id);}
function explainMethod(id){openPanelLoading();if(window.explainNode)window.explainNode(id);}
function label(id){return (nodeById[id]&&nodeById[id].label)||id;}
function radius(d){return d.r||8;}

// ---- init ----
function W(){return graphEl.clientWidth||800;}
function H(){return graphEl.clientHeight||600;}
function sizeSvg(){svg.attr('width',W()).attr('height',H());}

function init(){
  graphEl = document.getElementById('graph-wrap');
  svg = d3.select('#graph');
  buildDefs();
  viewport = svg.append('g').attr('class','viewport');
  gLinks = viewport.append('g').attr('class','links');
  gNodes = viewport.append('g').attr('class','nodes');
  gOverlay = viewport.append('g').attr('class','overlay');
  zoom = d3.zoom().scaleExtent([0.12,4])
    .filter(function(event){if(event.type==='wheel')return true;if(event.button)return false;return !(event.target.closest&&event.target.closest('.node,.link'));})
    .on('zoom',function(e){viewport.attr('transform',e.transform);curK=e.transform.k;updateLabels();});
  svg.call(zoom).on('dblclick.zoom',null);
  svg.on('click',backgroundClick);
  sizeSvg();
  new ResizeObserver(function(){sizeSvg();if(sim)sim.alpha(0.3).restart();}).observe(graphEl);
}

function buildDefs(){
  var defs = svg.append('defs');
  ['calls','inherits','tests','mc'].forEach(function(t){
    defs.append('marker').attr('id','ary-'+t).attr('viewBox','0 -5 10 10')
      .attr('refX',9).attr('refY',0).attr('markerWidth',9).attr('markerHeight',9)
      .attr('markerUnits','userSpaceOnUse').attr('orient','auto')
      .append('path').attr('d','M0,-4L9,0L0,4').attr('fill',ARROW[t]);
  });
  var f = defs.append('filter').attr('id','glow').attr('x','-70%').attr('y','-70%').attr('width','240%').attr('height','240%');
  f.append('feGaussianBlur').attr('stdDeviation',4.5).attr('result','b');
  var m = f.append('feMerge'); m.append('feMergeNode').attr('in','b'); m.append('feMergeNode').attr('in','SourceGraphic');
}

// ---- data load ----
function updateGraph(d){
  var incoming = (d.nodes||[]).map(function(n){return n.data;});
  var ids = {}; incoming.forEach(function(n){ids[n.id]=1;});
  var inLinks = (d.edges||[]).map(function(e){return e.data;}).filter(function(e){return ids[e.source]&&ids[e.target];});

  var prev = nodeById;
  rawNodes = incoming.map(function(n){
    var o = prev[n.id]||{};
    return {id:n.id,label:n.label,type:n.type,folder:n.folder||'.',color:n.color||'#548af7',isTest:!!n.isTest,
            x:o.x,y:o.y,vx:o.vx,vy:o.vy};
  });
  nodeById = {}; rawNodes.forEach(function(n){nodeById[n.id]=n;});

  adj = {}; rawNodes.forEach(function(n){adj[n.id]=new Set();});
  rawLinks = inLinks.map(function(e,i){return {id:'e'+i,source:e.source,target:e.target,si:e.source,ti:e.target,type:e.type,methodCalls:e.methodCalls||[]};});
  rawLinks.forEach(function(l){adj[l.source].add(l.target);adj[l.target].add(l.source);});
  rawNodes.forEach(function(n){n.deg=adj[n.id].size;n.r=Math.max(6,Math.min(30,5+3.4*Math.sqrt(n.deg)));});

  var folders = []; var seen={};
  rawNodes.forEach(function(n){if(!seen[n.folder]){seen[n.folder]=1;folders.push(n.folder);}});
  folderAngle = {}; folders.forEach(function(f,i){folderAngle[f]=(2*Math.PI*i)/Math.max(folders.length,1);});

  buildSim();
  draw();
  var mc = rawLinks.reduce(function(s,l){return s+((l.methodCalls||[]).length);},0);
  document.getElementById('stats').textContent = rawNodes.length+' files · '+rawLinks.length+' links · '+mc+' calls';
  setTimeout(fitAll, 380);
}

function folderX(d){return W()/2 + Math.min(W(),H())*0.30*Math.cos(folderAngle[d.folder]||0);}
function folderY(d){return H()/2 + Math.min(W(),H())*0.30*Math.sin(folderAngle[d.folder]||0);}

function buildSim(){
  if(sim) sim.stop();
  sim = d3.forceSimulation(rawNodes)
    .force('charge', d3.forceManyBody().strength(function(d){return (-130-11*d.deg)*fx.repel;}).distanceMax(520))
    .force('link', d3.forceLink(rawLinks).id(function(d){return d.id;}).distance(function(l){return (64+radius(l.source)+radius(l.target))*fx.linkDist;}).strength(0.16))
    .force('collide', d3.forceCollide(function(d){return radius(d)+8;}).iterations(2))
    .force('x', d3.forceX(folderX).strength(fx.cluster))
    .force('y', d3.forceY(folderY).strength(fx.cluster))
    .velocityDecay(0.30).alpha(0.9).alphaDecay(0.024)
    .on('tick', ticked);
}

function linkWidth(l){
  if(l.type==='calls') return 1+0.55*Math.sqrt((l.methodCalls||[]).length);
  return EDGE_STYLE[l.type].base;
}
function edgeOpacity(l){return l.type==='calls'?0.5:0.8;}

function draw(){
  // links: each is a <g> with a wide invisible hit path + a visible path
  var lsel = gLinks.selectAll('g.link').data(rawLinks,function(d){return d.id;});
  lsel.exit().remove();
  var lent = lsel.enter().append('g').attr('class','link');
  lent.append('path').attr('class','hit').attr('fill','none').attr('stroke','transparent').attr('stroke-width',14)
    .style('pointer-events','stroke').style('cursor','pointer')
    .on('click',function(e,d){e.stopPropagation();edgeClick(d);});
  lent.append('path').attr('class','vis').attr('fill','none').attr('stroke-linecap','round');
  linkSel = lent.merge(lsel);
  linkSel.select('path.vis')
    .attr('stroke',function(d){return EDGE_STYLE[d.type].stroke;})
    .attr('stroke-width',linkWidth)
    .attr('stroke-dasharray',function(d){return EDGE_STYLE[d.type].dash;})
    .attr('marker-end',function(d){return 'url(#ary-'+d.type+')';})
    .style('opacity',function(d){return hiddenTypes[d.type]?0:edgeOpacity(d);});

  // nodes
  var nsel = gNodes.selectAll('g.node').data(rawNodes,function(d){return d.id;});
  nsel.exit().remove();
  var nent = nsel.enter().append('g').attr('class','node').style('cursor','pointer')
    .call(makeDrag())
    .on('click',function(e,d){e.stopPropagation();nodeClick(d);})
    .on('mouseover',function(e,d){if(!stepMode&&!hoverLock)hoverNode(d);})
    .on('mouseout',function(){if(!stepMode&&!hoverLock)restoreView();});
  nent.append('circle');
  nent.append('text').attr('text-anchor','middle').attr('class','nlabel');
  nodeSel = nent.merge(nsel);
  nodeSel.select('circle')
    .attr('r',radius)
    .attr('fill',function(d){return d.color;})
    .attr('stroke',function(d){return d.isTest?'#5fad65':'rgba(0,0,0,0.38)';})
    .attr('stroke-width',function(d){return d.isTest?2:1.5;})
    .attr('stroke-dasharray',function(d){return d.isTest?'3,2':'';});
  nodeSel.select('text').text(function(d){return d.label;}).attr('dy',function(d){return radius(d)+12;});
  updateLabels();
  applyTestVisibility();
}

function ticked(){
  if(linkSel){linkSel.select('path.vis').attr('d',linkPath);linkSel.select('path.hit').attr('d',linkPath);}
  if(nodeSel){nodeSel.attr('transform',function(d){return 'translate('+d.x+','+d.y+')';});}
  positionOverlay();
}

function linkPath(d){
  var s=d.source,t=d.target;
  var dx=t.x-s.x, dy=t.y-s.y, L=Math.sqrt(dx*dx+dy*dy)||1;
  var ux=dx/L, uy=dy/L, sr=radius(s), tr=radius(t)+8;
  var x1=s.x+ux*sr, y1=s.y+uy*sr, x2=t.x-ux*tr, y2=t.y-uy*tr;
  var k=((d.si<d.ti)?1:-1)*0.12;
  var mx=(x1+x2)/2-(y2-y1)*k, my=(y1+y2)/2+(x2-x1)*k;
  return 'M'+x1+','+y1+'Q'+mx+','+my+' '+x2+','+y2;
}

function makeDrag(){
  return d3.drag().container(function(){return gNodes.node();})
    .on('start',function(e,d){if(!e.active)sim.alphaTarget(0.2).restart();d.fx=e.x;d.fy=e.y;})
    .on('drag',function(e,d){d.fx=e.x;d.fy=e.y;})
    .on('end',function(e,d){if(!e.active)sim.alphaTarget(0);d.fx=null;d.fy=null;});
}

// ---- labels by zoom + importance ----
function updateLabels(){
  if(!nodeSel||stepMode) return;
  nodeSel.select('text').style('display',function(d){
    if(curK>=1.15) return 'block';
    if(curK>=0.6) return d.deg>=3?'block':'none';
    return d.deg>=6?'block':'none';
  }).style('opacity',1);
}

// ---- hover / selection ----
function hoverNode(d){
  var keep={}; keep[d.id]=1; adj[d.id].forEach(function(id){keep[id]=1;});
  nodeSel.style('opacity',function(n){return keep[n.id]?1:0.12;});
  linkSel.select('path.vis').style('opacity',function(l){return (l.source.id===d.id||l.target.id===d.id)?0.95:0.05;});
}

function restoreView(){
  if(!nodeSel) return;
  nodeSel.style('opacity',1);
  linkSel.select('path.vis').style('opacity',function(l){return hiddenTypes[l.type]?0:edgeOpacity(l);});
  updateLabels();
}

function resetCircleStrokes(){
  if(!nodeSel) return;
  nodeSel.select('circle')
    .attr('stroke',function(d){return d.isTest?'#5fad65':'rgba(0,0,0,0.38)';})
    .attr('stroke-width',function(d){return d.isTest?2:1.5;})
    .attr('filter',null);
}

function nodeClick(d){
  selectedId=d.id; hoverLock=true; stepMode=false;
  clearStepMethods();
  hoverNode(d);
  resetCircleStrokes();
  nodeSel.filter(function(n){return n.id===d.id;}).select('circle').attr('stroke','#fff').attr('stroke-width',3);
  document.getElementById('selected-info').textContent='▶ '+d.label+(d.isTest?' (test)':'');
  openNodePanel(d);
  if(window.navigateToNode) window.navigateToNode(d.id);
}

function edgeClick(d){
  selectedId=null; hoverLock=true; stepMode=false;
  clearStepMethods();
  var keep={}; keep[d.source.id]=1; keep[d.target.id]=1;
  nodeSel.style('opacity',function(n){return keep[n.id]?1:0.10;});
  nodeSel.select('text').style('display',function(n){return keep[n.id]?'block':'none';}).style('opacity',1);
  linkSel.select('path.vis').style('opacity',function(l){return l.id===d.id?1:0.04;});
  if(d.type==='calls'&&d.methodCalls&&d.methodCalls.length){openMethodPanel(d);}
  else{
    closeMethodPanel();
    var rel = d.type==='inherits'?' ▸ extends/implements ▸ ':' ▸ tests ▸ ';
    document.getElementById('selected-info').textContent=label(d.source.id)+rel+label(d.target.id);
  }
}

function backgroundClick(){
  selectedId=null; hoverLock=false;
  if(stepMode){ closePanel(true); document.getElementById('selected-info').textContent=''; return; }
  closeViewMenu();
  clearTopSearches(true);
  closeMethodPanel();
  resetCircleStrokes(); restoreView();
  document.getElementById('selected-info').textContent='';
}

// ---- camera ----
function fitAll(){fitTo(hideTests?rawNodes.filter(function(n){return !n.isTest;}):rawNodes,60);}
function fitTo(nodes,pad,maxScale){
  if(!nodes||!nodes.length) return;
  var minX=1e9,minY=1e9,maxX=-1e9,maxY=-1e9,any=false;
  nodes.forEach(function(n){if(n.x==null)return;any=true;var r=radius(n);minX=Math.min(minX,n.x-r);minY=Math.min(minY,n.y-r);maxX=Math.max(maxX,n.x+r);maxY=Math.max(maxY,n.y+r);});
  if(!any) return;
  pad=pad||60;
  var w=maxX-minX, h=maxY-minY; if(w<=0||h<=0) return;
  var scale=Math.min(maxScale||4,Math.max(0.12,0.92*Math.min(W()/(w+2*pad),H()/(h+2*pad))));
  var tx=W()/2-scale*(minX+maxX)/2, ty=H()/2-scale*(minY+maxY)/2;
  svg.transition().duration(450).call(zoom.transform, d3.zoomIdentity.translate(tx,ty).scale(scale));
}

// ---- toolbar ----
function relayout(){if(sim)sim.alpha(0.9).alphaTarget(0).restart();}
function applyForces(){
  if(!sim) return;
  sim.force('charge').strength(function(d){return (-130-11*d.deg)*fx.repel;});
  sim.force('link').distance(function(l){return (64+radius(l.source)+radius(l.target))*fx.linkDist;});
  sim.force('x').strength(fx.cluster);
  sim.force('y').strength(fx.cluster);
  sim.alpha(0.6).restart();
}
function setFx(key,val,spanId){fx[key]=parseFloat(val);var s=document.getElementById(spanId);if(s)s.textContent=val;applyForces();}
function toggleFx(){document.getElementById('fxpanel').classList.toggle('hidden');}
function toggleViewMenu(e){
  if(e) e.stopPropagation();
  var m=document.getElementById('viewmenu');
  if(m) m.classList.toggle('hidden');
}
function closeViewMenu(){
  var m=document.getElementById('viewmenu');
  if(m) m.classList.add('hidden');
}
function toggleEdgeType(type,btnId){
  hiddenTypes[type]=!hiddenTypes[type];
  var b=document.getElementById(btnId); if(b)b.classList.toggle('active',hiddenTypes[type]);
  if(linkSel){
    linkSel.select('path.vis').style('opacity',function(l){return hiddenTypes[l.type]?0:edgeOpacity(l);});
    linkSel.select('path.hit').style('pointer-events',function(l){return hiddenTypes[l.type]?'none':'stroke';});
  }
}
// Hide/show all test file nodes (and any edge touching one). Persists across redraws and other view states.
function applyTestVisibility(){
  if(nodeSel) nodeSel.style('display',function(d){return (hideTests&&d.isTest)?'none':null;});
  if(linkSel) linkSel.style('display',function(l){return (hideTests&&(l.source.isTest||l.target.isTest))?'none':null;});
}
function toggleTestFiles(btnId){
  hideTests=!hideTests;
  var b=document.getElementById(btnId); if(b)b.classList.toggle('active',hideTests);
  applyTestVisibility();
}
function rememberSearchCamera(){
  if(!searchCameraBefore&&svg) searchCameraBefore=d3.zoomTransform(svg.node());
}
function restoreSearchCamera(){
  if(!searchCameraBefore) return false;
  var t=searchCameraBefore;
  searchCameraBefore=null;
  svg.transition().duration(350).call(zoom.transform,t);
  return true;
}
function clearTopSearches(restoreCamera){
  var fs=document.getElementById('search'), ms=document.getElementById('method-search');
  if(fs) fs.value='';
  if(ms) ms.value='';
  if(restoreCamera) restoreSearchCamera();
}
function searchNodes(q){
  if(!nodeSel) return;
  q=(q||'').toLowerCase().trim();
  if(q){var ms=document.getElementById('method-search');if(ms)ms.value='';}
  if(!q){hoverLock=false;resetCircleStrokes();restoreView();restoreSearchCamera();return;}
  rememberSearchCamera();
  hoverLock=true; stepMode=false; closeMethodPanel();
  var keep={}, matches=[];
  rawNodes.forEach(function(n){if(n.label.toLowerCase().indexOf(q)>=0){keep[n.id]=1;matches.push(n);}});
  nodeSel.style('opacity',function(d){return keep[d.id]?1:0.07;});
  nodeSel.select('text').style('display',function(d){return keep[d.id]?'block':'none';}).style('opacity',1);
  nodeSel.select('circle').attr('stroke',function(d){return keep[d.id]?'#fff':(d.isTest?'#5fad65':'rgba(0,0,0,0.38)');}).attr('stroke-width',function(d){return keep[d.id]?3:1.5;});
  linkSel.select('path.vis').style('opacity',function(l){return (keep[l.source.id]&&keep[l.target.id])?edgeOpacity(l):0.03;});
  if(matches.length) fitTo(matches,135,2.45);
}

function searchMethods(q){
  if(!nodeSel) return;
  q=(q||'').toLowerCase().trim();
  if(q){var fs=document.getElementById('search');if(fs)fs.value='';}
  if(!q){hoverLock=false;resetCircleStrokes();restoreView();restoreSearchCamera();document.getElementById('selected-info').textContent='';return;}
  rememberSearchCamera();
  hoverLock=true; stepMode=false; closeMethodPanel();
  var keepNodes={}, keepLinks={}, frameNodes={}, matches=[], frameMatches=[];
  rawLinks.forEach(function(l){
    if(l.type!=='calls') return;
    var calls=l.methodCalls||[];
    for(var i=0;i<calls.length;i++){
      var callerMatch=methodNameMatches(calls[i].callerMethod,q);
      var targetMatch=methodNameMatches(calls[i].targetMethod,q);
      if(callerMatch||targetMatch){
        keepLinks[l.id]=1;
        frameNodes[l.si]=1;
        frameNodes[l.ti]=1;
        if(callerMatch) keepNodes[l.si]=1;
        if(targetMatch) keepNodes[l.ti]=1;
      }
    }
  });
  rawNodes.forEach(function(n){if(keepNodes[n.id])matches.push(n);});
  rawNodes.forEach(function(n){if(frameNodes[n.id])frameMatches.push(n);});
  nodeSel.style('opacity',function(d){return keepNodes[d.id]?1:0.07;});
  nodeSel.select('text').style('display',function(d){return keepNodes[d.id]?'block':'none';}).style('opacity',1);
  nodeSel.select('circle')
    .attr('stroke',function(d){return keepNodes[d.id]?'#d6ae58':(d.isTest?'#5fad65':'rgba(0,0,0,0.38)');})
    .attr('stroke-width',function(d){return keepNodes[d.id]?3:1.5;});
  linkSel.select('path.vis').style('opacity',function(l){
    if(hiddenTypes[l.type]) return 0;
    return keepLinks[l.id]?0.96:0.03;
  });
  document.getElementById('selected-info').textContent=matches.length?('▶ method: '+q+' · '+matches.length+' files'):'No method matches';
  if(frameMatches.length) fitTo(frameMatches,135,2.45);
}

function methodNameMatches(methodId,q){
  methodId=methodId||'';
  return methodLabel(methodId).toLowerCase().indexOf(q)>=0 || methodId.toLowerCase().indexOf(q)>=0;
}

function resetView(){
  clearTopSearches(false);
  selectedId=null; hoverLock=false; stepMode=false;
  closeViewMenu();
  closeMethodPanel();
  document.getElementById('steppanel').classList.add('hidden');
  clearStepMethods();
  resetCircleStrokes(); restoreView();
  document.getElementById('selected-info').textContent='';
  fitAll();
}

// ---- method panel (file-to-file edge -> its method calls) ----
function openMethodPanel(d){
  currentMethodCalls=d.methodCalls||[];
  document.getElementById('steppanel').classList.add('hidden');
  document.getElementById('methodpanel').classList.remove('hidden');
  document.getElementById('mp-title').textContent='File Calls';
  document.getElementById('mp-summary').textContent=label(d.source.id)+' → '+label(d.target.id)+' · '+currentMethodCalls.length+' method calls';
  resetMethodSearch();
  var html='';
  currentMethodCalls.forEach(function(c){
    var caller=escapeAttr(c.callerMethod||''), target=escapeAttr(c.targetMethod||'');
    html+='<div class="mp-call" data-search="'+escapeAttr(methodSearchText(c))+'">'
      +'<div class="mp-line"><span class="mp-method" ondblclick="navigateMethod(\''+caller+'\')" title="'+caller+'">'+escapeHtml(methodLabel(c.callerMethod))+'</span>'
      +'<span>→</span>'
      +'<span class="mp-method" ondblclick="navigateMethod(\''+target+'\')" title="'+target+'">'+escapeHtml(methodLabel(c.targetMethod))+'</span></div>'
      +'<div class="mp-actions"><button class="btn" onclick="explainMethod(\''+caller+'\')">Explain caller</button>'
      +'<button class="btn" onclick="explainMethod(\''+target+'\')">Explain target</button></div>'
      +'</div>';
  });
  document.getElementById('mp-methods').innerHTML=html||'<div class="sp-step-e">No method calls recorded.</div>';
}
function closeMethodPanel(){document.getElementById('methodpanel').classList.add('hidden');resetMethodSearch();}

function resetMethodSearch(){
  var input=document.getElementById('mp-search');
  if(input) input.value='';
}

function methodSearchText(c){
  return [
    c.callerMethod||'', c.targetMethod||'',
    methodLabel(c.callerMethod||''), methodLabel(c.targetMethod||'')
  ].join(' ').toLowerCase();
}

function filterMethodPanel(q){
  q=(q||'').toLowerCase().trim();
  var calls=document.querySelectorAll('#mp-methods .mp-call');
  for(var i=0;i<calls.length;i++){
    var show=!q||(calls[i].getAttribute('data-search')||'').indexOf(q)>=0;
    calls[i].style.display=show?'':'none';
  }
  var groups=document.querySelectorAll('#mp-methods .mp-group');
  for(var g=0;g<groups.length;g++){
    var visible=0, chips=groups[g].querySelectorAll('.mp-chip');
    for(var c=0;c<chips.length;c++){
      var text=(chips[c].getAttribute('data-search')||chips[c].getAttribute('title')||'').toLowerCase();
      var keep=!q||text.indexOf(q)>=0;
      chips[c].style.display=keep?'':'none';
      if(keep) visible++;
    }
    groups[g].style.display=visible?'':'none';
  }
}

// ---- node panel (file node -> every method call, grouped by the other file) ----
// Clicking a method on a chip reveals its connection in the graph and opens the method in the editor (no AI).
function revealMethod(navId,cm,tm){
  var link=null;
  for(var i=0;i<rawLinks.length&&!link;i++){
    var l=rawLinks[i]; if(l.type!=='calls') continue;
    var calls=l.methodCalls||[];
    for(var j=0;j<calls.length;j++){if(calls[j].callerMethod===cm&&calls[j].targetMethod===tm){link=l;break;}}
  }
  if(link){
    edgeClick(link);
    var pts=[link.source,link.target].filter(function(n){return n&&n.x!=null;});
    if(pts.length) fitTo(pts,120);
  }
  if(window.navigateToNode) window.navigateToNode(navId);
}
function nodeChip(c,defined){
  var cm=escapeAttr(c.callerMethod||''), tm=escapeAttr(c.targetMethod||'');
  return '<span class="mp-chip'+(defined?' defined':'')+'" title="'+cm+' → '+tm+'">'
    +'<span class="mp-cm" onclick="revealMethod(\''+cm+'\',\''+cm+'\',\''+tm+'\')">'+escapeHtml(methodLabel(c.callerMethod))+'</span>'
    +'<span class="mp-arrow">→</span>'
    +'<span class="mp-cm" onclick="revealMethod(\''+tm+'\',\''+cm+'\',\''+tm+'\')">'+escapeHtml(methodLabel(c.targetMethod))+'</span>'
    +'</span>';
}
function groupArr(groups){
  return Object.keys(groups).map(function(id){var g=groups[id];return {id:id,out:g.out,inc:g.inc,n:g.out.length+g.inc.length};});
}
// Shared grouped-connection panel: groups sorted by size; per group, outgoing/defined chips (accented) then incoming.
function renderGroupPanel(titleText,labelText,emptyMsg,arr){
  document.getElementById('steppanel').classList.add('hidden');
  document.getElementById('methodpanel').classList.remove('hidden');
  document.getElementById('mp-title').textContent=titleText;
  resetMethodSearch();
  if(!arr.length){
    document.getElementById('mp-summary').textContent=labelText+' · no connections';
    document.getElementById('mp-methods').innerHTML='<div class="sp-step-e">'+escapeHtml(emptyMsg)+'</div>';
    return;
  }
  arr.sort(function(a,b){return b.n-a.n;});
  var total=arr.reduce(function(s,g){return s+g.n;},0);
  document.getElementById('mp-summary').textContent=labelText+' · '+arr.length+' files · '+total+' calls';
  var html='';
  arr.forEach(function(g){
    html+='<div class="mp-group"><div class="mp-group-h" title="'+escapeAttr(label(g.id))+'">'+escapeHtml(label(g.id))+' <span class="mp-count">'+g.n+'</span></div><div class="mp-chips">';
    g.out.forEach(function(c){html+=nodeChip(c,true);});
    g.inc.forEach(function(c){html+=nodeChip(c,false);});
    html+='</div></div>';
  });
  document.getElementById('mp-methods').innerHTML=html;
}
function openNodePanel(d){
  var groups={};
  rawLinks.forEach(function(l){
    if(l.type!=='calls') return;
    if(l.si!==d.id&&l.ti!==d.id) return;
    var other=(l.si===d.id)?l.ti:l.si, outgoing=(l.si===d.id);
    var g=groups[other]||(groups[other]={out:[],inc:[]});
    (l.methodCalls||[]).forEach(function(c){(outgoing?g.out:g.inc).push(c);});
  });
  renderGroupPanel(d.label,d.label,'This file has no recorded method-call connections.',groupArr(groups));
}
// Open in Graph View: one function's connections — methods it calls (outgoing) + methods that call it (incoming).
function openMethodConnections(mid){
  if(!rawNodes.length){setTimeout(function(){openMethodConnections(mid);},320);return;}
  hoverLock=true; stepMode=false; selectedId=null;
  clearStepMethods();
  var groups={}, edgeIds={}, fileIds={};
  rawLinks.forEach(function(l){
    if(l.type!=='calls') return;
    (l.methodCalls||[]).forEach(function(c){
      var isOut=c.callerMethod===mid, isIn=c.targetMethod===mid;
      if(!isOut&&!isIn) return;
      var other=isOut?l.ti:l.si;
      var g=groups[other]||(groups[other]={out:[],inc:[]});
      (isOut?g.out:g.inc).push(c);
      edgeIds[l.id]=1; fileIds[l.si]=1; fileIds[l.ti]=1;
    });
  });
  var arr=groupArr(groups);
  renderGroupPanel(methodLabel(mid),methodLabel(mid),'This function has no recorded cross-file connections.',arr);
  document.getElementById('selected-info').textContent='▶ '+methodLabel(mid);
  if(arr.length){
    resetCircleStrokes();
    nodeSel.style('opacity',function(n){return fileIds[n.id]?1:0.08;});
    nodeSel.select('text').style('display',function(n){return fileIds[n.id]?'block':'none';}).style('opacity',1);
    linkSel.select('path.vis').style('opacity',function(l){return edgeIds[l.id]?0.95:0.04;});
    var fn=[]; Object.keys(fileIds).forEach(function(id){if(nodeById[id])fn.push(nodeById[id]);});
    fitTo(fn,110);
  } else {
    restoreView();
  }
}

// ---- AI explain: steps + method overlay ----
function openPanelLoading(){
  closeMethodPanel(); stepMode=true; hoverLock=true;
  currentSteps=[];
  document.getElementById('steppanel').classList.remove('hidden');
  document.getElementById('sp-summary').textContent='Generating explanation…';
  document.getElementById('sp-detail').textContent='';
  document.getElementById('sp-steps').innerHTML='';
  document.getElementById('sp-risks').innerHTML='';
  resetChat();
  setChatEnabled(false);
}

function showExplanation(resp){
  currentSteps=(resp&&resp.steps)||[];
  stepMode=true; hoverLock=true;
  closeMethodPanel();
  document.getElementById('steppanel').classList.remove('hidden');
  document.getElementById('sp-summary').textContent=(resp&&resp.summary)||'';
  document.getElementById('sp-detail').textContent=currentSteps.length?(currentSteps[0].detail||currentSteps[0].explanation||''):'';
  var html='';
  currentSteps.forEach(function(s,i){
    var c=colorByType(s.type);
    html+='<div class="sp-step" onclick="playStep('+i+')">'
      +'<div class="sp-step-h"><span class="sp-badge" title="'+escapeAttr(s.type)+'" style="background:'+c+'">'+escapeHtml(shortType(s.type))+'</span>'
      +'<span class="sp-step-t">'+(i+1)+'. '+escapeHtml(s.title)+'</span></div>'
      +'<div class="sp-step-e">'+escapeHtml(s.explanation)+'</div></div>';
  });
  document.getElementById('sp-steps').innerHTML=html;
  var risks=(resp&&resp.riskNotes)||[];
  document.getElementById('sp-risks').innerHTML=risks.length?('⚠ '+risks.map(escapeHtml).join('<br>⚠ ')):'';
  resetChat();
  setChatEnabled(true);
  if(currentSteps.length) playStep(0);
}

function resetChat(){
  var chat=document.getElementById('sp-chat'), log=document.getElementById('sp-chat-log');
  if(chat) chat.classList.remove('has-response');
  if(log) log.innerHTML='';
  chatPending=false;
  chatEffort='medium';
  var sel=document.getElementById('sp-chat-effort-select');
  if(sel) sel.value='medium';
}

function setChatEnabled(enabled){
  var q=document.getElementById('sp-chat-q'), b=document.getElementById('sp-chat-send'), sel=document.getElementById('sp-chat-effort-select');
  if(q) q.disabled=!enabled||chatPending;
  if(b){b.disabled=!enabled||chatPending;b.textContent=chatPending?'...':'Send';}
  if(sel) sel.disabled=!enabled||chatPending;
}

function setChatEffort(v){
  chatEffort=(v==='low'||v==='high'||v==='xhigh')?v:'medium';
}

function chatKey(e){
  if(e.key==='Enter'){e.preventDefault();sendChat();}
}

function sendChat(){
  if(chatPending) return;
  var input=document.getElementById('sp-chat-q');
  if(!input) return;
  var q=input.value.trim();
  if(!q) return;
  input.value='';
  appendChatMessage('user',q);
  chatPending=true;
  setChatEnabled(true);
  if(window.codemapAsk) window.codemapAsk(q,chatEffort);
  else appendChatAnswer('Follow-up chat is not ready yet.', true);
}

function appendChatMessage(role,text){
  var log=document.getElementById('sp-chat-log');
  if(!log) return;
  var msg=document.createElement('div');
  msg.className='chat-msg '+role;
  msg.textContent=text||'';
  log.appendChild(msg);
  log.scrollTop=log.scrollHeight;
}

function appendChatAnswer(text,isError){
  var chat=document.getElementById('sp-chat');
  if(chat) chat.classList.add('has-response');
  appendChatMessage(isError?'error':'ai',text);
  chatPending=false;
  setChatEnabled(currentSteps.length>0);
  var q=document.getElementById('sp-chat-q');
  if(q) q.focus();
}

function playStep(i){
  var s=currentSteps[i]; if(!s) return;
  stepMode=true; hoverLock=true;
  document.getElementById('sp-detail').textContent=s.detail||s.explanation||'';
  var items=document.querySelectorAll('.sp-step');
  for(var k=0;k<items.length;k++) items[k].classList.toggle('active',k===i);
  var uF={}, uM={}, cF={}, cM={};
  for(var j=0;j<=i;j++){var sj=currentSteps[j];var c=colorByType(sj.type);
    (sj.focusNodes||[]).forEach(function(id){uF[id]=1;cF[id]=c;});
    if(sj.focusMethod){uM[sj.focusMethod]=1;cM[sj.focusMethod]=c;}
  }
  applyStepHighlight(uF,uM,cF,cM,s.focusMethod);
  var fn=[]; Object.keys(uF).forEach(function(id){if(nodeById[id])fn.push(nodeById[id]);});
  fitTo(fn.length?fn:rawNodes,170,2.0);
}

function showAllSteps(){
  if(!currentSteps.length) return;
  stepMode=true; hoverLock=true;
  var uF={}, uM={}, cF={}, cM={};
  currentSteps.forEach(function(s){var c=colorByType(s.type);
    (s.focusNodes||[]).forEach(function(id){uF[id]=1;cF[id]=c;});
    if(s.focusMethod){uM[s.focusMethod]=1;cM[s.focusMethod]=c;}});
  applyStepHighlight(uF,uM,cF,cM,null);
  var fn=[]; Object.keys(uF).forEach(function(id){if(nodeById[id])fn.push(nodeById[id]);});
  fitTo(fn.length?fn:rawNodes,150,2.0);
  var items=document.querySelectorAll('.sp-step');
  for(var k=0;k<items.length;k++) items[k].classList.remove('active');
}

function applyStepHighlight(uF,uM,cF,cM,curMethod){
  nodeSel.style('opacity',function(d){return uF[d.id]?1:0.07;});
  nodeSel.select('circle')
    .attr('stroke',function(d){return uF[d.id]?cF[d.id]:(d.isTest?'#5fad65':'rgba(0,0,0,0.38)');})
    .attr('stroke-width',function(d){return uF[d.id]?4:1.5;})
    .attr('filter',function(d){return uF[d.id]?'url(#glow)':null;});
  nodeSel.select('text').style('display',function(d){return uF[d.id]?'block':'none';}).style('opacity',1);
  linkSel.select('path.vis').style('opacity',0.04);
  gOverlay.selectAll('g.om').style('opacity',function(d){return uM[d.id]?1:0;})
    .select('circle').attr('stroke',function(d){return cM[d.id]||d.color;})
    .attr('stroke-width',function(d){return d.id===curMethod?3.4:2.2;})
    .attr('filter',function(d){return uM[d.id]?'url(#glow)':null;});
  gOverlay.selectAll('g.om').select('text').style('opacity',function(d){return uM[d.id]?1:0;});
  gOverlay.selectAll('line.mo').style('opacity',function(d){return (uF[d.file]&&uM[d.id])?0.85:0;});
  gOverlay.selectAll('path.mc').style('opacity',function(d){return (uM[d.source]&&uM[d.target])?1:0;});
  pulseOverlay(curMethod);
}

function pulseOverlay(id){
  if(!id) return;
  var sel=gOverlay.selectAll('g.om').filter(function(d){return d.id===id;}).select('circle');
  sel.transition().duration(320).attr('r',21).transition().duration(320).attr('r',15);
}

function closePanel(skipFit){
  document.getElementById('steppanel').classList.add('hidden');
  stepMode=false; hoverLock=false;
  clearStepMethods();
  resetCircleStrokes();
  restoreView();
  if(!skipFit) fitAll();
}

// ---- method overlay (clustered method nodes around their file, step view only) ----
function setStepMethods(data){
  clearStepMethods();
  if(!data||!data.methods) return;
  var byFile={};
  data.methods.forEach(function(m){(byFile[m.file]=byFile[m.file]||[]).push(m);});
  overlayMethods=[];
  Object.keys(byFile).forEach(function(fid){
    var list=byFile[fid];
    list.forEach(function(m,idx){
      overlayMethods.push({id:m.id,label:m.label,file:fid,color:m.color||'#d6ae58',ang:(2*Math.PI*idx/Math.max(list.length,1))-Math.PI/2});
    });
  });
  overlayCalls=(data.edges||[]).slice();
  drawOverlay();
}

function omById(id){for(var i=0;i<overlayMethods.length;i++)if(overlayMethods[i].id===id)return overlayMethods[i];return null;}
function omPos(m){
  var f=nodeById[m.file];
  if(!f||f.x==null) return {x:0,y:0};
  var rr=radius(f)+34;
  return {x:f.x+rr*Math.cos(m.ang), y:f.y+rr*Math.sin(m.ang)};
}

function drawOverlay(){
  var mo=gOverlay.selectAll('line.mo').data(overlayMethods,function(d){return d.id;});
  mo.exit().remove();
  mo.enter().append('line').attr('class','mo').attr('stroke','#6f737a').attr('stroke-width',1.3).style('opacity',0);
  var mc=gOverlay.selectAll('path.mc').data(overlayCalls,function(d){return d.source+'__'+d.target;});
  mc.exit().remove();
  mc.enter().append('path').attr('class','mc').attr('fill','none').attr('stroke','#d6ae58').attr('stroke-width',2.4)
    .attr('stroke-dasharray','5,4').attr('marker-end','url(#ary-mc)').style('opacity',0);
  var om=gOverlay.selectAll('g.om').data(overlayMethods,function(d){return d.id;});
  om.exit().remove();
  var oe=om.enter().append('g').attr('class','om').style('opacity',0).style('cursor','pointer')
    .on('click',function(e,d){e.stopPropagation();if(window.navigateToNode)window.navigateToNode(d.id);});
  oe.append('circle').attr('r',15).attr('fill','#2b2d30').attr('stroke-width',2.2);
  oe.append('text').attr('text-anchor','middle').attr('dy',27).attr('class','omlabel');
  var oall=oe.merge(om);
  oall.select('circle').attr('stroke',function(d){return d.color;});
  oall.select('text').text(function(d){return d.label;});
  positionOverlay();
}

function positionOverlay(){
  if(!overlayMethods.length) return;
  gOverlay.selectAll('g.om').attr('transform',function(d){var p=omPos(d);return 'translate('+p.x+','+p.y+')';});
  gOverlay.selectAll('line.mo').attr('x1',function(d){var f=nodeById[d.file];return f?f.x:0;}).attr('y1',function(d){var f=nodeById[d.file];return f?f.y:0;})
    .attr('x2',function(d){return omPos(d).x;}).attr('y2',function(d){return omPos(d).y;});
  gOverlay.selectAll('path.mc').attr('d',function(d){var a=omById(d.source),b=omById(d.target);if(!a||!b)return '';var pa=omPos(a),pb=omPos(b);var dx=pb.x-pa.x,dy=pb.y-pa.y,L=Math.sqrt(dx*dx+dy*dy)||1;var x2=pb.x-dx/L*17,y2=pb.y-dy/L*17;return 'M'+pa.x+','+pa.y+'L'+x2+','+y2;});
}

function clearStepMethods(){overlayMethods=[];overlayCalls=[];if(gOverlay)gOverlay.selectAll('*').remove();}

init();
</script></body></html>"""
}
