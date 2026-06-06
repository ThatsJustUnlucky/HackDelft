package com.hackdelft.codegraph

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.psi.PsiMethod
import com.intellij.psi.util.PsiTreeUtil

class OpenInGraphViewAction : AnAction("Open in Graph View") {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val method = resolveMethod(e) ?: return
        val containingClass = method.containingClass ?: return
        val qualifiedName = containingClass.qualifiedName ?: return
        val nodeId = "method:$qualifiedName.${method.name}"

        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Code Graph") ?: return
        toolWindow.show {
            CodeGraphPanel.getInstance(project)?.focusNode(nodeId)
        }
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = resolveMethod(e) != null
    }

    private fun resolveMethod(e: AnActionEvent): PsiMethod? {
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return null
        val psiFile = e.getData(CommonDataKeys.PSI_FILE) ?: return null
        val element = psiFile.findElementAt(editor.caretModel.offset) ?: return null
        return PsiTreeUtil.getParentOfType(element, PsiMethod::class.java)
    }
}
