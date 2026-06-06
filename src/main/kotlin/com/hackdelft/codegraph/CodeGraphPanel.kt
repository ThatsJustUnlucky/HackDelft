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

  fun focusNode(nodeId: String) {
    val escaped = (graphNodeId(nodeId) ?: nodeId).e()
    browser.cefBrowser.executeJavaScript("focusNode(\"$escaped\");", "", 0)
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
    bg.submit {
      try {
        val full = currentGraph ?: extractAndCache().also { currentGraph = it }
        val focused = FocusedSubgraphExtractor.extract(full, nodeId, methodFilePath(nodeId))
        val snippets = collectSnippets(focused, nodeId)
        val response = aiService.explain(nodeId, focused, snippets)
        browser.cefBrowser.executeJavaScript("showExplanation(${explanationToJson(response)});", "", 0)
        focusNode(nodeId)
      } catch (_: Exception) {}
    }
  }

  private fun extractAndCache(): GraphData = extractor.extract().also { cache.save(it) }

  private fun handleMessage(msg: String) {
    val obj = try { JsonParser.parseString(msg).asJsonObject } catch (_: Exception) { return }
    when (obj.get("t")?.asString) {
      "nav" -> obj.get("id")?.asString?.let { navigateTo(it) }
      "explain" -> obj.get("id")?.asString?.let { explainNode(it) }
      "refresh" -> refreshGraph(force = true)
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
      """{"id":"${s.id.e()}","title":"${s.title.e()}","type":"${s.type.e()}","explanation":"${s.explanation.e()}","detail":"${s.detail.e()}","focusNodes":[$fn]}"""
    }
    val risks = r.riskNotes.joinToString(",") { "\"${it.e()}\"" }
    return """{"summary":"${r.summary.e()}","steps":[$steps],"riskNotes":[$risks]}"""
  }

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
      """{"data":{"id":"${n.id.e()}","label":"${n.label.e()}","type":"${n.type}","fp":"${n.filePath.e()}","folder":"${n.folder.e()}","color":"${n.color}"}}"""
    }
    val edges = data.edges.filter { it.type != "calls" || it.methodCalls.isNotEmpty() }.mapIndexed { i, e ->
      val calls = e.methodCalls.joinToString(",") { c ->
        """{"callerMethod":"${c.callerMethod.e()}","targetMethod":"${c.targetMethod.e()}","callerFile":"${c.callerFile.e()}","targetFile":"${c.targetFile.e()}","callerLine":${c.callerLine},"targetLine":${c.targetLine}}"""
      }
      """{"data":{"id":"e$i","source":"${e.source.e()}","target":"${e.target.e()}","type":"${e.type}","color":"${e.color}","methodCalls":[$calls]}}"""
    }.joinToString(",")
    return """{"nodes":[$nodes],"edges":[$edges]}"""
  }

  private fun graphNodeId(nodeId: String): String? {
    if (nodeId.startsWith("file:")) return nodeId
    return methodFilePath(nodeId)?.let { "file:$it" }
  }

  private fun methodFilePath(nodeId: String): String? =
    if (nodeId.startsWith("method:")) {
      ReadAction.compute<String?, Exception> { resolvePsi(nodeId)?.containingFile?.virtualFile?.path?.replace('\\', '/') }
    } else null

  private fun String.e() = replace("\\", "\\\\").replace("\"", "\\\"")
    .replace("\n", "\\n").replace("\r", "")

  private fun html() = """<!DOCTYPE html>
<html><head><meta charset="UTF-8">
<script src="https://cdnjs.cloudflare.com/ajax/libs/cytoscape/3.26.0/cytoscape.min.js"></script>
<style>
*{box-sizing:border-box;margin:0;padding:0}
html,body{width:100%;height:100%;overflow:hidden;background:#1a1b26;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif}
body{display:flex;flex-direction:column}
#topbar{flex-shrink:0;height:52px;background:#16161e;border-bottom:1px solid #2a2b3d;display:flex;align-items:center;gap:8px;padding:0 12px}
#cy{flex:1;min-height:0}
#statusbar{flex-shrink:0;height:32px;background:#16161e;border-top:1px solid #2a2b3d;display:flex;align-items:center;padding:0 12px;gap:20px}
#search{flex:1;max-width:260px;background:#1e1f2e;border:1px solid #2a2b3d;border-radius:8px;padding:7px 12px;color:#c0caf5;font-size:13px;outline:none}
#search:focus{border-color:#7aa2f7}
#search::placeholder{color:#565f89}
.btn{background:#1e1f2e;color:#c0caf5;border:1px solid #2a2b3d;padding:7px 14px;cursor:pointer;border-radius:8px;font-size:12px;font-weight:500;transition:all .15s;white-space:nowrap}
.btn:hover{background:#292b3e;border-color:#7aa2f7;color:#7aa2f7}
.btn.active{background:#1d3054;border-color:#7aa2f7;color:#7aa2f7}
.sep{width:1px;height:28px;background:#2a2b3d;margin:0 2px}
#stats{color:#565f89;font-size:11px}
#selected-info{color:#7aa2f7;font-size:11px;flex:1;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.legend{display:flex;align-items:center;gap:5px;color:#565f89;font-size:11px}
.dot{width:10px;height:10px;border-radius:50%;flex-shrink:0}
.dash{width:16px;height:3px;border-radius:2px;flex-shrink:0}
#steppanel{position:fixed;top:52px;right:0;bottom:32px;width:340px;background:#16161e;border-left:1px solid #2a2b3d;display:flex;flex-direction:column;z-index:10;box-shadow:-4px 0 16px rgba(0,0,0,.35)}
#steppanel.hidden{display:none}
#sp-head{flex-shrink:0;display:flex;align-items:center;justify-content:space-between;padding:12px 14px;border-bottom:1px solid #2a2b3d}
#sp-title{color:#bb9af7;font-weight:600;font-size:13px}
.sp-x{background:none;border:none;color:#565f89;cursor:pointer;font-size:14px}
.sp-x:hover{color:#f7768e}
#sp-summary{flex-shrink:0;padding:12px 14px;color:#a9b1d6;font-size:12px;line-height:1.5;border-bottom:1px solid #2a2b3d}
#sp-detail{flex-shrink:0;padding:12px 14px;color:#c0caf5;font-size:12px;line-height:1.5;border-bottom:1px solid #2a2b3d;max-height:150px;overflow:auto}
#sp-steps{flex:1;overflow:auto;padding:8px}
.sp-step{background:#1e1f2e;border:1px solid #2a2b3d;border-radius:8px;padding:10px;margin-bottom:8px;cursor:pointer;transition:all .15s}
.sp-step:hover{border-color:#7aa2f7}
.sp-step.active{border-color:#7aa2f7;background:#1d3054}
.sp-step-h{display:flex;align-items:center;gap:8px;margin-bottom:5px}
.sp-badge{font-size:9px;font-weight:700;color:#16161e;padding:2px 7px;border-radius:10px;text-transform:uppercase;letter-spacing:.4px;white-space:nowrap}
.sp-step-t{color:#c0caf5;font-size:12px;font-weight:600;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.sp-step-e{color:#787c99;font-size:11px;line-height:1.5}
#sp-risks{flex-shrink:0;padding:0 14px;color:#e0af68;font-size:11px;line-height:1.5;max-height:90px;overflow:auto}
#sp-risks:empty{display:none}
#sp-actions{flex-shrink:0;padding:10px 14px;border-top:1px solid #2a2b3d}
#sp-actions .btn{width:100%}
#methodpanel{position:fixed;top:52px;right:0;bottom:32px;width:380px;background:#16161e;border-left:1px solid #2a2b3d;display:flex;flex-direction:column;z-index:11;box-shadow:-4px 0 16px rgba(0,0,0,.35)}
#methodpanel.hidden{display:none}
#mp-head{flex-shrink:0;display:flex;align-items:center;justify-content:space-between;padding:12px 14px;border-bottom:1px solid #2a2b3d}
#mp-title{color:#7aa2f7;font-weight:600;font-size:13px}
#mp-summary{flex-shrink:0;padding:10px 14px;color:#a9b1d6;font-size:12px;line-height:1.4;border-bottom:1px solid #2a2b3d}
#mp-methods{flex:1;overflow:auto;padding:8px}
.mp-call{background:#1e1f2e;border:1px solid #2a2b3d;border-radius:8px;padding:9px;margin-bottom:8px}
.mp-line{display:flex;align-items:center;gap:6px;color:#787c99;font-size:11px;margin-bottom:8px}
.mp-method{color:#c0caf5;font-size:11px;font-weight:600;cursor:pointer;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;max-width:145px}
.mp-method:hover{color:#7aa2f7}
.mp-actions{display:flex;gap:6px}
.mp-actions .btn{flex:1;padding:5px 8px;font-size:11px}
</style></head><body>

<div id="topbar">
  <input id="search" type="text" placeholder="Search nodes..." oninput="searchNodes(this.value)">
  <div class="sep"></div>
  <button class="btn" onclick="cy.fit(null,40)">Fit All</button>
  <button class="btn" id="btn-calls" onclick="togBtn('calls','btn-calls')">Hide Calls</button>
  <button class="btn" onclick="layout()">Re-layout</button>
  <button class="btn" onclick="resetView()">Reset</button>
  <button class="btn" onclick="window.codemapRefresh&&window.codemapRefresh()">Refresh</button>
</div>

<div id="cy"></div>

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
</div>

<div id="methodpanel" class="hidden">
  <div id="mp-head">
    <span id="mp-title">File Calls</span>
    <button class="sp-x" onclick="closeMethodPanel()">x</button>
  </div>
  <div id="mp-summary"></div>
  <div id="mp-methods"></div>
</div>

<div id="statusbar">
  <div class="legend"><div class="dot" style="background:#7aa2f7; border-radius:4px"></div>File</div>
  <div class="legend"><div class="dash" style="background:#7aa2f7"></div>Calls</div>
  <div class="sep"></div>
  <div id="stats">Loading...</div>
  <div id="selected-info"></div>
</div>

<script>
// FIX 2: State variables moved to the top so they aren't 'undefined' when Search resets
var hidden = {};
var hiddenNodes = {};
var selectedNodeId = null;
var selectedEdgeId = null;
var currentSteps = [];
var currentMethodCalls = [];
var TYPE_COLORS = {input:'#7dcfff',validation:'#e0af68',processing:'#7aa2f7',persistence:'#9ece6a','external-call':'#bb9af7',ui:'#2ac3de','error-handling':'#f7768e',test:'#9ece6a',orchestration:'#bb9af7',unknown:'#c0caf5'};
function colorByType(t){return TYPE_COLORS[t]||'#c0caf5';}
function escapeHtml(s){return (s||'').replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;');}
function escapeAttr(s){return escapeHtml(s).replace(/'/g,'&#39;').replace(/"/g,'&quot;');}
function methodLabel(id){var s=(id||'').replace(/^method:/,'');return s.substring(s.lastIndexOf('.')+1);}
function navigateMethod(id){if(window.navigateToNode)window.navigateToNode(id);}
function explainMethod(id){openPanelLoading();if(window.explainNode)window.explainNode(id);}

var cy=cytoscape({
  container:document.getElementById('cy'),
  style:[
    {selector:'node[type="file"]',style:{
      'background-color':'#1f2335','border-color':'data(color)','border-width':3,
      'label':'data(label)','color':'#c0caf5','font-size':'12px','font-weight':'bold',
      'text-valign':'center','text-halign':'center',
      'shape':'round-rectangle','width':120,'height':48,
      'text-wrap':'ellipsis','text-max-width':'105px','z-index':100
    }},
    {selector:'node[type="class"]',style:{
      'background-color':'#2d1f0e','border-color':'data(color)','border-width':3,
      'label':'data(label)','color':'#c0caf5','font-size':'14px','font-weight':'bold',
      'text-valign':'center','text-halign':'center',
      'shape':'round-rectangle', 'width':120,'height':45,
      'text-wrap':'ellipsis','text-max-width':'100px', 'z-index': 100
    }},
    {selector:'node[type="abstract"]',style:{
      'background-color':'#2a1a3a','border-color':'data(color)','border-width':3,
      'label':'data(label)','color':'#c0caf5','font-size':'13px','font-weight':'bold',
      'text-valign':'center','text-halign':'center',
      'shape':'round-rectangle', 'width':80,'height':80,
      'text-wrap':'ellipsis','text-max-width':'70px', 'z-index': 100
    }},
    {selector:'node[type="interface"]',style:{
      'background-color':'#1a2035','border-color':'data(color)','border-width':3,
      'label':'data(label)','color':'#c0caf5','font-size':'13px','font-weight':'bold',
      'text-valign':'center','text-halign':'center',
      'shape':'round-rectangle', 'width':80,'height':80,
      'text-wrap':'ellipsis','text-max-width':'70px', 'z-index': 100
    }},
    {selector:'node[type="method"]',style:{
      'background-color':'#1a2e10','border-color':'data(color)','border-width':2,
      'label':'data(label)','color':'#c0caf5','font-size':'11px',
      'text-valign':'center','text-halign':'center',
      'width':65,'height':65,'shape':'ellipse',
      'text-wrap':'ellipsis','text-max-width':'55px'
    }},
    {selector:'node[type="abstract_method"]',style:{
      'background-color':'#2a1a3a','border-color':'data(color)','border-width':2,
      'border-style':'dashed',
      'label':'data(label)','color':'#bb9af7','font-size':'11px','font-style':'italic',
      'text-valign':'center','text-halign':'center',
      'width':65,'height':65,'shape':'ellipse',
      'text-wrap':'ellipsis','text-max-width':'55px'
    }},
    {selector:'edge[type="contains"]',style:{
      'line-color':'data(color)','target-arrow-color':'data(color)',
      'target-arrow-shape':'chevron', 'curve-style':'bezier',
      'width':1.5,'opacity':0.7,'arrow-scale':0.8
    }},
    {selector:'edge[type="calls"]',style:{
      'line-color':'data(color)','target-arrow-color':'data(color)',
      'target-arrow-shape':'triangle', 'curve-style':'unbundled-bezier',
      'width':2,'line-style':'dashed','opacity':0.85,
      'arrow-scale':0.9,'control-point-step-size':40
    }},
    {selector:'edge[type="inherits"]',style:{
      'line-color':'#c0caf5','target-arrow-color':'#c0caf5',
      'target-arrow-shape':'triangle-backcurve', 'target-arrow-fill':'hollow',
      'curve-style':'bezier','width':2,'opacity':1.0,
      'arrow-scale':1.5
    }},
    {selector:'node:selected',style:{'border-width':3,'border-color':'#ffffff','shadow-blur':15,'shadow-color':'#ffffff','shadow-opacity':0.8}},
    {selector:'.dim',style:{opacity:0.07}},
    {selector:'.highlight',style:{'border-color':'#ffffff','border-width':3}},
    {selector:'.search-match',style:{'border-color':'#ffffff','border-width':3}},
    {selector:'.stepglow',style:{'border-width':4,'shadow-blur':20,'shadow-color':'#ffffff','shadow-opacity':0.55,'z-index':300}},
    {selector:'.stepedge',style:{'line-color':'#ffffff','target-arrow-color':'#ffffff','width':3,'opacity':1,'z-index':300}}
  ],
  layout:{name:'cose'}
});

function layout(){
  var structuralElements = cy.elements().filter(function(ele) {
    return ele.isNode() || ele.data('type') === 'calls';
  });

  structuralElements.layout({
    name: 'cose',
    animate: true,
    animationDuration: 600,
    nodeOverlap: 8000,
    nodeDimensionsIncludeLabels: true,
    idealEdgeLength: 250,
    numIter: 4000,
    initialTemp: 500,
    coolingFactor: 0.99,
    gravity: 0.05,
    nodeRepulsion: function(node) {
      var t = node.data('type');
      if (t === 'file') return 2500000;
      if (t === 'class' || t === 'abstract' || t === 'interface') return 4000000;
      return 800000;
    },
    edgeElasticity: 0.1
  }).run();
}

function updateGraph(d){
  cy.elements().remove();
  var ids=new Set(d.nodes.map(function(n){return n.data.id;}));
  cy.add(d.nodes);
  cy.add(d.edges.filter(function(e){return ids.has(e.data.source)&&ids.has(e.data.target);}));
  layout();
  var nc=d.nodes.length,ec=d.edges.filter(function(e){return ids.has(e.data.source)&&ids.has(e.data.target);}).length;
  var mc=d.edges.reduce(function(sum,e){return sum+((e.data.methodCalls||[]).length);},0);
  document.getElementById('stats').textContent=nc+' files / '+ec+' edges / '+mc+' calls';
}

function focusNode(id){
  var node=cy.getElementById(id);
  if(!node.length)return;
  cy.elements().removeClass('dim highlight');
  cy.elements().not(node.closedNeighborhood()).addClass('dim');
  node.addClass('highlight');
  cy.animate({center:{eles:node},zoom:Math.max(cy.zoom(),1.4)},{duration:350});
  node.select();
  document.getElementById('selected-info').textContent='▶ '+node.data('label')+' ('+node.data('type')+')';
}

function searchNodes(q){
  cy.elements().removeClass('search-match dim');
  cy.elements().show();

  if(!q) {
    restoreHiddenStates();
    cy.fit(null, 40);
    return;
  }

  q = q.toLowerCase();

  var matches = cy.nodes().filter(function(n){
    return n.data('label').toLowerCase().indexOf(q) >= 0;
  });

  if(matches.length === 0) {
    cy.elements().hide();
    return;
  }

  var toShow = cy.collection().union(matches);
  var neighbors = matches.closedNeighborhood();
  toShow = toShow.union(neighbors);

  // FIX 3: Added 'abstract_method' so they don't lose their parent classes!
  var methodsInView = toShow.filter('node[type="method"], node[type="abstract_method"]');
  var parentEdges = cy.edges('[type="contains"]').filter(function(e){
    return methodsInView.contains(e.target());
  });

  toShow = toShow.union(parentEdges).union(parentEdges.sources());
  cy.elements().not(toShow).hide();
  matches.addClass('search-match');

  cy.animate({
    fit: { eles: toShow, padding: 40 },
    duration: 350
  });
}

function restoreHiddenStates() {
  if (hidden['contains']) cy.$('edge[type="contains"]').hide();
  if (hidden['calls']) cy.$('edge[type="calls"]').hide();
  if (hiddenNodes['class']) cy.$('node[type="class"]').hide();
  if (hiddenNodes['abstract']) cy.$('node[type="abstract"]').hide();
  if (hiddenNodes['interface']) cy.$('node[type="interface"]').hide();
}

// Generates a bright, visible HSL color
function getRandomColor() {
  var h = Math.floor(Math.random() * 360);
  var s = Math.floor(60 + Math.random() * 40); // 60-100% saturation
  var l = Math.floor(60 + Math.random() * 20); // 60-80% lightness
  return 'hsl(' + h + ', ' + s + '%, ' + l + '%)';
}

function shuffleColors() {
  var fileColors = {};

  cy.nodes('[type="file"]').forEach(function(node) {
      var c = getRandomColor();
      fileColors[node.id()] = c;
      node.data('color', c);
  });

  cy.edges('[type="calls"]').forEach(function(edge) {
      var callerColor = fileColors[edge.source().id()] || edge.source().data('color');
      if (callerColor) {
          edge.data('color', callerColor);
      }
  });
}

function resetView(){
  cy.elements().removeClass('dim highlight search-match');
  cy.elements().show();
  closeMethodPanel();
  restoreHiddenStates();

  document.getElementById('search').value='';
  document.getElementById('selected-info').textContent='';
  shuffleColors();
  cy.fit(null,40);
}

function togBtn(type,btnId){
  var sel='edge[type="'+type+'"]';
  var btn=document.getElementById(btnId);
  if(hidden[type]){
    cy.$(sel).show();hidden[type]=false;
    btn.textContent='Hide '+(type==='contains'?'Structure':'Calls');
    btn.classList.remove('active');
  }else{
    cy.$(sel).hide();hidden[type]=true;
    btn.textContent='Show '+(type==='contains'?'Structure':'Calls');
    btn.classList.add('active');
  }
}

function toggleNodes(nodeType, btnId) {
  var sel = 'node[type="' + nodeType + '"]';
  var btn = document.getElementById(btnId);

  if (hiddenNodes[nodeType]) {
    cy.$(sel).show();
    hiddenNodes[nodeType] = false;
    btn.textContent = 'Hide ' + nodeType.charAt(0).toUpperCase() + nodeType.slice(1) + 's';
    btn.classList.remove('active');
  } else {
    cy.$(sel).hide();
    hiddenNodes[nodeType] = true;
    btn.textContent = 'Show ' + nodeType.charAt(0).toUpperCase() + nodeType.slice(1) + 's';
    btn.classList.add('active');
  }
}

cy.on('tap','node',function(e){
  cy.elements().removeClass('dim highlight');
  var node=e.target;
  selectedNodeId=node.id();
  selectedEdgeId=null;
  cy.elements().not(node.closedNeighborhood()).addClass('dim');
  node.addClass('highlight');
  document.getElementById('selected-info').textContent='▶ '+node.data('label')+' ('+node.data('type')+')';
  if(window.navigateToNode)window.navigateToNode(node.id());
});
cy.on('tap','edge',function(e){
  cy.elements().removeClass('dim highlight stepedge');
  var edge=e.target;
  selectedNodeId=null;
  selectedEdgeId=edge.id();
  edge.addClass('stepedge');
  cy.elements().not(edge.connectedNodes().union(edge)).addClass('dim');
  openMethodPanel(edge);
});
cy.on('tap',function(e){
  if(e.target===cy){
    selectedNodeId=null;
    selectedEdgeId=null;
    cy.elements().removeClass('dim highlight');
    closeMethodPanel();
    document.getElementById('selected-info').textContent='';
  }
});

function openMethodPanel(edge){
  currentMethodCalls=edge.data('methodCalls')||[];
  document.getElementById('steppanel').classList.add('hidden');
  document.getElementById('methodpanel').classList.remove('hidden');
  document.getElementById('mp-summary').textContent=edge.source().data('label')+' -> '+edge.target().data('label')+' / '+currentMethodCalls.length+' method calls';
  var html='';
  currentMethodCalls.forEach(function(c,i){
    var caller=escapeAttr(c.callerMethod||'');
    var target=escapeAttr(c.targetMethod||'');
    html+='<div class="mp-call">'
      +'<div class="mp-line"><span class="mp-method" ondblclick="navigateMethod(\''+caller+'\')" title="'+caller+'">'+escapeHtml(methodLabel(c.callerMethod))+'</span>'
      +'<span>-></span>'
      +'<span class="mp-method" ondblclick="navigateMethod(\''+target+'\')" title="'+target+'">'+escapeHtml(methodLabel(c.targetMethod))+'</span></div>'
      +'<div class="mp-actions"><button class="btn" onclick="explainMethod(\''+caller+'\')">Explain caller</button>'
      +'<button class="btn" onclick="explainMethod(\''+target+'\')">Explain target</button></div>'
      +'</div>';
  });
  document.getElementById('mp-methods').innerHTML=html||'<div class="sp-step-e">No method calls recorded for this edge.</div>';
  document.getElementById('selected-info').textContent=edge.source().data('label')+' -> '+edge.target().data('label');
}

function closeMethodPanel(){
  document.getElementById('methodpanel').classList.add('hidden');
}

// ---- AI Explain focus mode (steps + animations) ----
function explainSelected(){
  if(!selectedNodeId){document.getElementById('selected-info').textContent='Select a node first, then Explain';return;}
  openPanelLoading();
  if(window.explainNode)window.explainNode(selectedNodeId);
}

function openPanelLoading(){
  closeMethodPanel();
  document.getElementById('steppanel').classList.remove('hidden');
  document.getElementById('sp-summary').textContent='Generating explanation…';
  document.getElementById('sp-detail').textContent='';
  document.getElementById('sp-steps').innerHTML='';
  document.getElementById('sp-risks').innerHTML='';
}

function showExplanation(resp){
  currentSteps=(resp&&resp.steps)||[];
  closeMethodPanel();
  document.getElementById('steppanel').classList.remove('hidden');
  document.getElementById('sp-summary').textContent=(resp&&resp.summary)||'';
  document.getElementById('sp-detail').textContent=currentSteps.length?(currentSteps[0].detail||currentSteps[0].explanation||''):'';
  var html='';
  currentSteps.forEach(function(s,i){
    var c=colorByType(s.type);
    html+='<div class="sp-step" onclick="playStep('+i+')">'
      +'<div class="sp-step-h"><span class="sp-badge" style="background:'+c+'">'+escapeHtml(s.type)+'</span>'
      +'<span class="sp-step-t">'+(i+1)+'. '+escapeHtml(s.title)+'</span></div>'
      +'<div class="sp-step-e">'+escapeHtml(s.explanation)+'</div></div>';
  });
  document.getElementById('sp-steps').innerHTML=html;
  var risks=(resp&&resp.riskNotes)||[];
  document.getElementById('sp-risks').innerHTML=risks.length?('⚠ '+risks.map(escapeHtml).join('<br>⚠ ')):'';
  if(currentSteps.length)playStep(0);
}

function stepNodes(s){
  var nodes=cy.collection();
  (s.focusNodes||[]).forEach(function(id){nodes=nodes.union(cy.getElementById(id));});
  return nodes;
}

function pulse(nodes){
  nodes.forEach(function(n){
    n.stop();
    n.animate({style:{'border-width':9}},{duration:350,complete:function(){
      n.animate({style:{'border-width':4}},{duration:350});
    }});
  });
}

function clearStepStyles(){
  cy.elements().removeClass('dim stepglow stepedge');
  cy.nodes().removeStyle('border-color border-width');
}

function playStep(i){
  var s=currentSteps[i];
  if(!s)return;
  document.getElementById('sp-detail').textContent=s.detail||s.explanation||'';
  var items=document.querySelectorAll('.sp-step');
  for(var k=0;k<items.length;k++)items[k].classList.toggle('active',k===i);
  clearStepStyles();
  var nodes=stepNodes(s);
  if(nodes.length===0)return;
  var nb=nodes.closedNeighborhood();
  cy.elements().not(nb).addClass('dim');
  var color=colorByType(s.type);
  nodes.style('border-color',color);
  nodes.addClass('stepglow');
  nodes.edgesWith(nodes).addClass('stepedge');
  pulse(nodes);
  cy.animate({fit:{eles:nb,padding:90}},{duration:450});
}

function showAllSteps(){
  clearStepStyles();
  var all=cy.collection();
  currentSteps.forEach(function(s){
    var color=colorByType(s.type);
    stepNodes(s).forEach(function(n){
      if(n.length){n.style('border-color',color);n.addClass('stepglow');all=all.union(n);}
    });
  });
  if(all.length===0)return;
  var nb=all.closedNeighborhood();
  cy.elements().not(nb).addClass('dim');
  cy.animate({fit:{eles:nb,padding:80}},{duration:450});
  var items=document.querySelectorAll('.sp-step');
  for(var k=0;k<items.length;k++)items[k].classList.remove('active');
}

function closePanel(){
  document.getElementById('steppanel').classList.add('hidden');
  clearStepStyles();
  resetView();
}
</script></body></html>"""
}
