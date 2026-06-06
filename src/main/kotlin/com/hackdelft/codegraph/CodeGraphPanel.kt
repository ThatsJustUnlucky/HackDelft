package com.hackdelft.codegraph

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.pom.Navigatable
import com.intellij.psi.JavaPsiFacade
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
  private val jsQuery = JBCefJSQuery.create(browser)
  private val bg = Executors.newSingleThreadExecutor()

  companion object {
    private val instances = mutableMapOf<Project, CodeGraphPanel>()
    fun getInstance(project: Project) = instances[project]
  }

  init {
    instances[project] = this

    jsQuery.addHandler { nodeId ->
      navigateTo(nodeId)
      null
    }

    browser.jbCefClient.addLoadHandler(object : org.cef.handler.CefLoadHandlerAdapter() {
      override fun onLoadEnd(b: org.cef.browser.CefBrowser?, frame: org.cef.browser.CefFrame?, code: Int) {
        if (frame?.isMain == true) {
          val bridge = "window.navigateToNode=function(id){${jsQuery.inject("id")}};"
          browser.cefBrowser.executeJavaScript(bridge, "", 0)
          refreshGraph()
        }
      }
    }, browser.cefBrowser)

    browser.loadHTML(html())
    component.add(browser.component, BorderLayout.CENTER)
  }

  fun focusNode(nodeId: String) {
    val escaped = nodeId.e()
    browser.cefBrowser.executeJavaScript("focusNode(\"$escaped\");", "", 0)
  }

  fun refreshGraph() {
    bg.submit {
      try {
        val data = extractor.extract()
        val json = toJson(data)
        browser.cefBrowser.executeJavaScript("updateGraph($json);", "", 0)
      } catch (_: Exception) {}
    }
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

      else -> null
    }
  }

  private fun toJson(data: GraphData): String {
    val nodes = data.nodes.joinToString(",") { n ->
      """{"data":{"id":"${n.id.e()}","label":"${n.label.e()}","type":"${n.type}","fp":"${n.filePath.e()}","color":"${n.color}"}}"""
    }
    val edges = data.edges.mapIndexed { i, e ->
      """{"data":{"id":"e$i","source":"${e.source.e()}","target":"${e.target.e()}","type":"${e.type}","color":"${e.color}"}}"""
    }.joinToString(",")
    return """{"nodes":[$nodes],"edges":[$edges]}"""
  }

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
</style></head><body>

<div id="topbar">
  <input id="search" type="text" placeholder="Search nodes..." oninput="searchNodes(this.value)">
  <div class="sep"></div>
  <button class="btn" onclick="cy.fit(null,40)">Fit All</button>
  <button class="btn" id="btn-struct" onclick="togBtn('contains','btn-struct')">Hide Structure</button>
  <button class="btn" id="btn-calls" onclick="togBtn('calls','btn-calls')">Hide Calls</button>
  <button class="btn" onclick="layout()">Re-layout</button>
  <button class="btn" onclick="resetView()">Reset</button>
</div>

<div id="cy"></div>

<div id="statusbar">
  <div class="legend"><div class="dot" style="background:#ff9e64; border-radius:4px"></div>Class</div>
  <div class="legend"><div class="dot" style="background:#bb9af7; border-radius:4px"></div>Abstract</div>
  <div class="legend"><div class="dot" style="background:#7aa2f7; border-radius:4px"></div>Interface</div>
  <div class="legend"><div class="dot" style="background:#9ece6a"></div>Method</div>
  <div class="legend"><div class="dash" style="background:#c0caf5"></div>Inherits</div>
  <div class="sep"></div>
  <div id="stats">Loading...</div>
  <div id="selected-info"></div>
</div>

<script>
// FIX 2: State variables moved to the top so they aren't 'undefined' when Search resets
var hidden = {};
var hiddenNodes = {};

var cy=cytoscape({
  container:document.getElementById('cy'),
  style:[
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
    {selector:'.search-match',style:{'border-color':'#ffffff','border-width':3}}
  ],
  layout:{name:'cose'}
});

function layout(){
  var structuralElements = cy.elements().filter(function(ele) {
    return ele.isNode() || ele.data('type') === 'contains';
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
  document.getElementById('stats').textContent=nc+' nodes • '+ec+' edges';
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
  var classColors = {};

  // 1. Give every class/interface/abstract a new random color
  cy.nodes('[type="class"], [type="abstract"], [type="interface"]').forEach(function(node) {
      var c = getRandomColor();
      classColors[node.id()] = c;
      node.data('color', c); // Cytoscape instantly repaints the node!
  });

  // 2. Pass that parent color down to its methods and the 'contains' edges
  cy.edges('[type="contains"]').forEach(function(edge) {
      var parentColor = classColors[edge.source().id()];
      if (parentColor) {
          edge.data('color', parentColor);
          edge.target().data('color', parentColor); // Updates the method node
      }
  });

  // 3. Update 'calls' edges to match the calling method's new color
  cy.edges('[type="calls"]').forEach(function(edge) {
      var callerColor = edge.source().data('color');
      if (callerColor) {
          edge.data('color', callerColor);
      }
  });
}

function resetView(){
  cy.elements().removeClass('dim highlight search-match');
  cy.elements().show();
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
  cy.elements().not(node.closedNeighborhood()).addClass('dim');
  node.addClass('highlight');
  document.getElementById('selected-info').textContent='▶ '+node.data('label')+' ('+node.data('type')+')';
  if(window.navigateToNode)window.navigateToNode(node.id());
});
cy.on('tap',function(e){
  if(e.target===cy){
    cy.elements().removeClass('dim highlight');
    document.getElementById('selected-info').textContent='';
  }
});
</script></body></html>"""
}
