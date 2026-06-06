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
  val folder: String = "",
  val isTest: Boolean = false
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
    val inheritEdges = LinkedHashSet<Pair<String, String>>() // childFileId -> parentFileId
    val testEdges = LinkedHashSet<Pair<String, String>>()    // testFileId -> testedFileId
    val testFileIds = HashSet<String>()
    val fileBySimpleName = HashMap<String, String>()          // production class simple name -> fileId
    val testedNameByFile = LinkedHashMap<String, String>()    // testFileId -> tested class simple name
    folderColors.clear()
    val scope = GlobalSearchScope.projectScope(project)

    val classes = mutableListOf<PsiClass>()
    try {
      AllClassesSearch.search(scope, project).forEach { classes.add(it) }
    } catch (_: Exception) {
    }

    // Pass 1: file nodes, test detection, name index, inheritance edges.
    classes.forEach { psiClass ->
      val fileNode = addFileNode(psiClass, nodes) ?: return@forEach
      val test = isTestClass(psiClass)
      val name = psiClass.name
      if (test) {
        testFileIds.add(fileNode.id)
        if (name != null) testedClassName(name)?.let { testedNameByFile[fileNode.id] = it }
      } else if (name != null) {
        fileBySimpleName.putIfAbsent(name, fileNode.id)
      }
      addInheritEdges(psiClass, fileNode, nodes, inheritEdges)
    }

    // Pass 2: aggregated file-to-file call edges.
    classes.forEach { psiClass -> addCallEdges(psiClass, nodes, edgeCalls) }

    // tests edges: prefer XxxTest -> Xxx naming; else the production file the test calls into most.
    for (testId in testFileIds) {
      val target = testedNameByFile[testId]?.let { fileBySimpleName[it] }
        ?: topCallee(testId, edgeCalls, testFileIds)
      if (target != null && target != testId) testEdges.add(testId to target)
    }

    val edges = mutableListOf<GraphEdge>()
    edgeCalls.forEach { (pair, calls) ->
      edges.add(
        GraphEdge(
          source = pair.first,
          target = pair.second,
          type = "calls",
          color = nodes[pair.first]?.color ?: "#7aa2f7",
          methodCalls = calls.distinctBy { "${it.callerMethod}->${it.targetMethod}:${it.callerLine}" }
        )
      )
    }
    inheritEdges.forEach { (child, parent) -> edges.add(GraphEdge(child, parent, "inherits", "#bb9af7")) }
    testEdges.forEach { (test, tested) -> edges.add(GraphEdge(test, tested, "tests", "#9ece6a")) }

    val finalNodes = nodes.values.map { if (testFileIds.contains(it.id)) it.copy(isTest = true) else it }
    GraphData(finalNodes, edges)
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

  // file -> supertype file edges (extends + implements); library supertypes are skipped
  // because addFileNode returns null for non-project files.
  private fun addInheritEdges(
    psiClass: PsiClass,
    fileNode: GraphNode,
    nodes: MutableMap<String, GraphNode>,
    out: MutableSet<Pair<String, String>>
  ) {
    val supers = mutableListOf<PsiClass>()
    psiClass.superClass?.let { if (it.qualifiedName != "java.lang.Object") supers.add(it) }
    supers.addAll(psiClass.interfaces)
    for (s in supers) {
      val parent = addFileNode(s, nodes) ?: continue
      if (parent.id != fileNode.id) out.add(fileNode.id to parent.id)
    }
  }

  private fun isTestClass(psiClass: PsiClass): Boolean {
    val annotated = psiClass.methods.any { m ->
      m.annotations.any { (it.qualifiedName?.substringAfterLast('.') ?: "").endsWith("Test") }
    }
    if (annotated) return true
    val name = psiClass.name ?: ""
    if (name.endsWith("Test") || name.endsWith("Tests") || name.startsWith("Test")) return true
    val path = psiClass.containingFile?.virtualFile?.path?.normalizePath() ?: ""
    return path.contains("/test/")
  }

  private fun testedClassName(testName: String): String? = when {
    testName.endsWith("Tests") -> testName.removeSuffix("Tests")
    testName.endsWith("Test") -> testName.removeSuffix("Test")
    testName.startsWith("Test") -> testName.removePrefix("Test")
    else -> null
  }?.takeIf { it.isNotBlank() }

  private fun topCallee(
    testId: String,
    edgeCalls: Map<Pair<String, String>, List<MethodCall>>,
    testFileIds: Set<String>
  ): String? =
    edgeCalls.entries
      .filter { it.key.first == testId && !testFileIds.contains(it.key.second) }
      .maxByOrNull { it.value.size }
      ?.key?.second

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
