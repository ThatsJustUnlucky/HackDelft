package com.hackdelft.codegraph

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaRecursiveElementVisitor
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.AllClassesSearch
import java.awt.Color

data class GraphNode(
  val id: String,
  val label: String,
  val type: String,
  val filePath: String,
  val color: String = "",
  val folder: String = ""
)

data class MethodCall(
  val callerMethod: String,
  val targetMethod: String,
  val callerFile: String,
  val targetFile: String,
  val callerLine: Int = -1,
  val targetLine: Int = -1
)

data class GraphEdge(
  val source: String,
  val target: String,
  val type: String,
  val color: String = "",
  val methodCalls: List<MethodCall> = emptyList()
)

data class GraphData(val nodes: List<GraphNode>, val edges: List<GraphEdge>)

class GraphDataExtractor(private val project: Project) {

  private val folderColors = mutableMapOf<String, String>()
  private val rootPath = project.basePath?.normalizePath()

  private fun getRandomColor(): String {
    val hue = Math.random().toFloat()
    val saturation = (0.45 + Math.random() * 0.4).toFloat()
    val brightness = (0.72 + Math.random() * 0.24).toFloat()
    val rgb = Color.getHSBColor(hue, saturation, brightness).rgb
    return String.format("#%06X", 0xFFFFFF and rgb)
  }

  fun extract(): GraphData = ReadAction.compute<GraphData, Exception> {
    val nodes = linkedMapOf<String, GraphNode>()
    val edgeCalls = linkedMapOf<Pair<String, String>, MutableList<MethodCall>>()
    folderColors.clear()
    val scope = GlobalSearchScope.projectScope(project)

    try {
      AllClassesSearch.search(scope, project).forEach { psiClass ->
        addFileNode(psiClass, nodes)
      }
      AllClassesSearch.search(scope, project).forEach { psiClass ->
        addCallEdges(psiClass, nodes, edgeCalls)
      }
    } catch (_: Exception) {
    }

    val edges = edgeCalls.map { (pair, calls) ->
      GraphEdge(
        source = pair.first,
        target = pair.second,
        type = "calls",
        color = nodes[pair.first]?.color ?: "#7aa2f7",
        methodCalls = calls.distinctBy { "${it.callerMethod}->${it.targetMethod}:${it.callerLine}" }
      )
    }
    GraphData(nodes.values.toList(), edges)
  }

  private fun addFileNode(psiClass: PsiClass, nodes: MutableMap<String, GraphNode>): GraphNode? {
    val filePath = psiClass.containingFile?.virtualFile?.path?.normalizePath() ?: return null
    if (!isProjectFile(filePath)) return null
    return nodes.getOrPut(fileId(filePath)) {
      val relative = relativePath(filePath)
      val folder = relative.substringBeforeLast('/', "")
      GraphNode(
        id = fileId(filePath),
        label = relative.substringAfterLast('/'),
        type = "file",
        filePath = filePath,
        color = folderColors.getOrPut(folder.ifBlank { "." }) { getRandomColor() },
        folder = folder
      )
    }
  }

  private fun addCallEdges(
    psiClass: PsiClass,
    nodes: MutableMap<String, GraphNode>,
    edgeCalls: MutableMap<Pair<String, String>, MutableList<MethodCall>>
  ) {
    val callerFileNode = addFileNode(psiClass, nodes) ?: return

    psiClass.methods.forEach { callerMethod ->
      val callerMethodId = methodId(callerMethod) ?: return@forEach
      callerMethod.accept(object : JavaRecursiveElementVisitor() {
        override fun visitMethodCallExpression(expression: PsiMethodCallExpression) {
          super.visitMethodCallExpression(expression)
          val targetMethod = expression.resolveMethod() ?: return
          val targetClass = targetMethod.containingClass ?: return
          val targetFileNode = addFileNode(targetClass, nodes) ?: return
          if (callerFileNode.id == targetFileNode.id) return

          val call = MethodCall(
            callerMethod = callerMethodId,
            targetMethod = methodId(targetMethod) ?: return,
            callerFile = callerFileNode.filePath,
            targetFile = targetFileNode.filePath,
            callerLine = lineNumber(expression),
            targetLine = lineNumber(targetMethod)
          )
          edgeCalls.getOrPut(callerFileNode.id to targetFileNode.id) { mutableListOf() }.add(call)
        }
      })
    }
  }

  private fun methodId(method: PsiMethod): String? {
    val qualifiedName = method.containingClass?.qualifiedName ?: return null
    return "method:$qualifiedName.${method.name}"
  }

  private fun lineNumber(element: com.intellij.psi.PsiElement): Int {
    val file = element.containingFile ?: return -1
    val document = PsiDocumentManager.getInstance(project).getDocument(file) ?: return -1
    return document.getLineNumber(element.textOffset) + 1
  }

  private fun fileId(path: String) = "file:${path.normalizePath()}"

  private fun relativePath(path: String): String {
    val root = rootPath ?: return path.substringAfterLast('/')
    return path.removePrefix(root).trimStart('/').ifBlank { path.substringAfterLast('/') }
  }

  private fun isProjectFile(path: String): Boolean {
    val root = rootPath ?: return true
    return path.normalizePath().startsWith("$root/")
  }

  private fun String.normalizePath() = replace('\\', '/')
}
