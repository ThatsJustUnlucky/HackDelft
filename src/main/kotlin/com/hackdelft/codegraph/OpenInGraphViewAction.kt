package com.hackdelft.codegraph

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiReferenceExpression

// Reveal the connections of the specific function under the caret (the method's name, or a call to it).
class OpenInGraphViewAction : AnAction("Open in Graph View") {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val methodId = methodIdAtCaret(e) ?: return
        val tw = ToolWindowManager.getInstance(project).getToolWindow("Code Graph") ?: return
        tw.show { CodeGraphPanel.getInstance(project)?.openMethodInGraph(methodId) }
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = methodIdAtCaret(e) != null
    }

    // Only resolves when the caret sits on a function: its declaration name, or a reference/call to it.
    private fun methodIdAtCaret(e: AnActionEvent): String? {
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return null
        val file = e.getData(CommonDataKeys.PSI_FILE) ?: return null
        val el = file.findElementAt(editor.caretModel.offset) ?: return null
        val parent = el.parent
        val method = when {
            parent is PsiMethod && parent.nameIdentifier === el -> parent
            parent is PsiReferenceExpression && parent.referenceNameElement === el -> parent.resolve() as? PsiMethod
            else -> null
        } ?: return null
        val cls = method.containingClass?.qualifiedName ?: return null
        return "method:$cls.${method.name}"
    }
}
