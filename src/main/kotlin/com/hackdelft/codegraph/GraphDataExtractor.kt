package com.hackdelft.codegraph

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaRecursiveElementVisitor
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiModifier
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.AllClassesSearch
import java.awt.Color

// 1. Added 'color' field to data classes
data class GraphNode(val id: String, val label: String, val type: String, val filePath: String, val color: String = "")
data class GraphEdge(val source: String, val target: String, val type: String, val color: String = "")
data class GraphData(val nodes: List<GraphNode>, val edges: List<GraphEdge>)

class GraphDataExtractor(private val project: Project) {

  // 2. Map to remember the random color assigned to each class
  private val classColors = mutableMapOf<String, String>()

  private fun getRandomColor(): String {
    val hue = Math.random().toFloat()
    val saturation = (0.5 + Math.random() * 0.5).toFloat() // Bright, saturated colors
    val brightness = (0.7 + Math.random() * 0.3).toFloat() // Keep them visible on dark backgrounds
    val rgb = Color.getHSBColor(hue, saturation, brightness).rgb
    return String.format("#%06X", 0xFFFFFF and rgb)
  }

  fun extract(): GraphData = ReadAction.compute<GraphData, Exception> {
    val nodes = mutableMapOf<String, GraphNode>()
    val edges = mutableListOf<GraphEdge>()
    classColors.clear()
    val scope = GlobalSearchScope.projectScope(project)

    try {
      AllClassesSearch.search(scope, project).forEach { psiClass ->
        addStructureNodes(psiClass, nodes, edges)
      }
      AllClassesSearch.search(scope, project).forEach { psiClass ->
        addCallEdges(psiClass, nodes, edges)
      }
    } catch (_: Exception) {}

    GraphData(nodes.values.toList(), edges)
  }

  private fun addStructureNodes(
    psiClass: PsiClass,
    nodes: MutableMap<String, GraphNode>,
    edges: MutableList<GraphEdge>
  ) {
    val filePath = psiClass.containingFile?.virtualFile?.path ?: return
    val qualifiedName = psiClass.qualifiedName ?: psiClass.name ?: return

    val classType = when {
      psiClass.isInterface -> "interface"
      psiClass.hasModifierProperty(PsiModifier.ABSTRACT) -> "abstract"
      else -> "class"
    }

    val classId = "class:$qualifiedName"
    // 3. Assign and grab a color for this class
    val baseColor = classColors.getOrPut(classId) { getRandomColor() }

    if (!nodes.containsKey(classId)) {
      nodes[classId] = GraphNode(classId, psiClass.name ?: "?", classType, filePath, baseColor)
    }

    // 4. Extract Inheritance (extends / implements)
    psiClass.supers.forEach { superClass ->
      val superQN = superClass.qualifiedName ?: return@forEach
      if (superQN != "java.lang.Object") { // Ignore the implicit Object inheritance
        val superId = "class:$superQN"
        // Inherits edges get a neutral color, Cytoscape handles the styling
        edges += GraphEdge(classId, superId, "inherits", "#c0caf5")
      }
    }

    // 5. Attach methods with Abstract check and matching class color
    psiClass.methods.forEach { method ->
      val methodId = "method:$qualifiedName.${method.name}"
      val methodType = if (method.hasModifierProperty(PsiModifier.ABSTRACT)) "abstract_method" else "method"

      if (!nodes.containsKey(methodId)) {
        nodes[methodId] = GraphNode(methodId, method.name, methodType, filePath, baseColor)
        edges += GraphEdge(classId, methodId, "contains", baseColor)
      }
    }
  }

  private fun addCallEdges(
    psiClass: PsiClass,
    nodes: MutableMap<String, GraphNode>,
    edges: MutableList<GraphEdge>
  ) {
    val qualifiedName = psiClass.qualifiedName ?: return
    val callerClassId = "class:$qualifiedName"
    // Grab the class color so outgoing calls match the origin class
    val callerColor = classColors[callerClassId] ?: "#f7768e"

    psiClass.methods.forEach { method ->
      val callerId = "method:$qualifiedName.${method.name}"
      if (!nodes.containsKey(callerId)) return@forEach

      method.accept(object : JavaRecursiveElementVisitor() {
        override fun visitMethodCallExpression(expression: PsiMethodCallExpression) {
          super.visitMethodCallExpression(expression)
          val resolved = expression.resolveMethod() ?: return
          val targetClass = resolved.containingClass ?: return
          val targetQN = targetClass.qualifiedName ?: return
          val targetId = "method:$targetQN.${resolved.name}"

          if (nodes.containsKey(targetId) && callerId != targetId) {
            edges += GraphEdge(callerId, targetId, "calls", callerColor)
          }
        }
      })
    }
  }
}
