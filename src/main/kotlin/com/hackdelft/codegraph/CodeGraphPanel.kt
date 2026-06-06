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
import javax.swing.JButton
import javax.swing.JPanel

class CodeGraphPanel(private val project: Project) {

    val component: JPanel = JPanel(BorderLayout())
    private val browser = JBCefBrowser()
    private val extractor = GraphDataExtractor(project)
    private val jsQuery = JBCefJSQuery.create(browser)
    private val bg = Executors.newSingleThreadExecutor()

    init {
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

        val toolbar = JPanel()
        JButton("Refresh Graph").also {
            it.addActionListener { refreshGraph() }
            toolbar.add(it)
        }
        component.add(toolbar, BorderLayout.NORTH)
        component.add(browser.component, BorderLayout.CENTER)
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
            """{"data":{"id":"${n.id.e()}","label":"${n.label.e()}","type":"${n.type}","fp":"${n.filePath.e()}"}}"""
        }
        val edges = data.edges.mapIndexed { i, e ->
            """{"data":{"id":"e$i","source":"${e.source.e()}","target":"${e.target.e()}","type":"${e.type}"}}"""
        }.joinToString(",")
        return """{"nodes":[$nodes],"edges":[$edges]}"""
    }

    private fun String.e() = replace("\\", "\\\\").replace("\"", "\\\"")
        .replace("\n", "\\n").replace("\r", "")

    private fun html() = """<!DOCTYPE html>
<html><head><meta charset="UTF-8"><style>
body{margin:0;background:#1e1e2e;font-family:sans-serif}
#cy{width:100vw;height:100vh}
#bar{position:fixed;top:8px;right:8px;z-index:9;display:flex;flex-direction:column;gap:4px}
.btn{background:#313244;color:#cdd6f4;border:1px solid #45475a;padding:6px 14px;cursor:pointer;border-radius:6px;font-size:12px}
.btn:hover{background:#45475a}
#legend{position:fixed;bottom:8px;left:8px;color:#a6adc8;font-size:11px;display:flex;gap:14px;align-items:center}
.li{display:flex;align-items:center;gap:5px}
.dot{width:11px;height:11px;border-radius:50%}
#info{position:fixed;top:8px;left:8px;color:#6c7086;font-size:11px}
</style></head><body>
<div id="info">Waiting for data...</div>
<div id="cy"></div>
<div id="bar">
  <button class="btn" onclick="cy.fit()">Fit All</button>
  <button class="btn" onclick="tog('contains')">Toggle Structure</button>
  <button class="btn" onclick="tog('calls')">Toggle Calls</button>
  <button class="btn" onclick="layout()">Re-layout</button>
</div>
<div id="legend">
  <div class="li"><div class="dot" style="background:#89b4fa"></div>File</div>
  <div class="li"><div class="dot" style="background:#fab387"></div>Class</div>
  <div class="li"><div class="dot" style="background:#a6e3a1"></div>Method</div>
  <div class="li"><div class="dot" style="background:#f38ba8;border-radius:2px;height:3px;width:16px"></div>Calls</div>
</div>
<script src="https://cdnjs.cloudflare.com/ajax/libs/cytoscape/3.28.1/cytoscape.min.js"></script>
<script>
var cy=cytoscape({container:document.getElementById('cy'),style:[
  {selector:'node[type="file"]',style:{'background-color':'#89b4fa','label':'data(label)','color':'#1e1e2e','font-size':'11px','font-weight':'bold','text-valign':'center','width':100,'height':36,'shape':'round-rectangle','text-wrap':'wrap','text-max-width':'94px'}},
  {selector:'node[type="class"]',style:{'background-color':'#fab387','label':'data(label)','color':'#1e1e2e','font-size':'10px','font-weight':'bold','text-valign':'center','width':70,'height':70,'shape':'ellipse','text-wrap':'wrap','text-max-width':'64px'}},
  {selector:'node[type="method"]',style:{'background-color':'#a6e3a1','label':'data(label)','color':'#1e1e2e','font-size':'9px','text-valign':'center','width':48,'height':48,'shape':'ellipse','text-wrap':'wrap','text-max-width':'44px'}},
  {selector:'edge[type="contains"]',style:{'line-color':'#45475a','target-arrow-color':'#45475a','target-arrow-shape':'chevron','curve-style':'bezier','width':1,'opacity':0.5}},
  {selector:'edge[type="calls"]',style:{'line-color':'#f38ba8','target-arrow-color':'#f38ba8','target-arrow-shape':'triangle','curve-style':'bezier','width':2,'line-style':'dashed','opacity':0.9}},
  {selector:'node:selected',style:{'border-width':3,'border-color':'#cba6f7'}},
  {selector:'.dim',style:{opacity:0.12}}
],layout:{name:'cose'}});

function updateGraph(d){
  cy.elements().remove();
  var ids=new Set(d.nodes.map(function(n){return n.data.id;}));
  cy.add(d.nodes);
  cy.add(d.edges.filter(function(e){return ids.has(e.data.source)&&ids.has(e.data.target);}));
  layout();
  document.getElementById('info').textContent=d.nodes.length+' nodes • '+d.edges.length+' edges';
}
function layout(){cy.layout({name:'cose',animate:true,animationDuration:500,nodeRepulsion:6000,gravity:0.25,numIter:1000}).run();}
var hidden={};
function tog(t){
  var sel='edge[type="'+t+'"]';
  if(hidden[t]){cy.$(sel).show();hidden[t]=false;}
  else{cy.$(sel).hide();hidden[t]=true;}
}
cy.on('tap','node',function(e){
  cy.elements().removeClass('dim');
  cy.elements().not(e.target.closedNeighborhood()).addClass('dim');
  if(window.navigateToNode)window.navigateToNode(e.target.id());
});
cy.on('tap',function(e){if(e.target===cy)cy.elements().removeClass('dim');});
</script></body></html>"""
}
