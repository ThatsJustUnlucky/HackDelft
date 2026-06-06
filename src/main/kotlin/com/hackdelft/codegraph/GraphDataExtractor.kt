package com.hackdelft.codegraph

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaRecursiveElementVisitor
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.AllClassesSearch

data class GraphNode(val id: String, val label: String, val type: String, val filePath: String)
data class GraphEdge(val source: String, val target: String, val type: String)
data class GraphData(val nodes: List<GraphNode>, val edges: List<GraphEdge>)

class GraphDataExtractor(private val project: Project) {

    fun extract(): GraphData = ReadAction.compute<GraphData, Exception> {
        val nodes = mutableMapOf<String, GraphNode>()
        val edges = mutableListOf<GraphEdge>()
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
        val file = psiClass.containingFile ?: return
        val filePath = file.virtualFile?.path ?: return
        val qualifiedName = psiClass.qualifiedName ?: psiClass.name ?: return

        val fileId = "file:$filePath"
        nodes.getOrPut(fileId) { GraphNode(fileId, file.name, "file", filePath) }

        val classId = "class:$qualifiedName"
        if (!nodes.containsKey(classId)) {
            nodes[classId] = GraphNode(classId, psiClass.name ?: "?", "class", filePath)
            edges += GraphEdge(fileId, classId, "contains")
        }

        psiClass.methods.forEach { method ->
            val methodId = "method:$qualifiedName.${method.name}"
            if (!nodes.containsKey(methodId)) {
                nodes[methodId] = GraphNode(methodId, method.name, "method", filePath)
                edges += GraphEdge(classId, methodId, "contains")
            }
        }
    }

    private fun addCallEdges(
        psiClass: PsiClass,
        nodes: MutableMap<String, GraphNode>,
        edges: MutableList<GraphEdge>
    ) {
        val qualifiedName = psiClass.qualifiedName ?: return
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
                        edges += GraphEdge(callerId, targetId, "calls")
                    }
                }
            })
        }
    }
}
