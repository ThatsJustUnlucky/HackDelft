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
        val method = method(e) ?: return
        val cls = method.containingClass?.qualifiedName ?: return
        val nodeId = "method:$cls.${method.name}"
        val tw = ToolWindowManager.getInstance(project).getToolWindow("Code Graph") ?: return
        tw.show { CodeGraphPanel.getInstance(project)?.focusNode(nodeId) }
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = method(e) != null
    }

    private fun method(e: AnActionEvent): PsiMethod? {
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return null
        val file = e.getData(CommonDataKeys.PSI_FILE) ?: return null
        val el = file.findElementAt(editor.caretModel.offset) ?: return null
        return PsiTreeUtil.getParentOfType(el, PsiMethod::class.java)
    }
}
