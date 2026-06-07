package com.hackdelft.codegraph

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiReferenceExpression

// Map the current code in Codesidian: reveal the method under the caret if there is one,
// otherwise reveal the current file. Works from the editor and the Project view right-click.
class OpenInGraphViewAction : AnAction("Open in Codesidian") {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val methodId = methodIdAtCaret(e)
        val filePath = targetFilePath(e)
        if (methodId == null && filePath == null) return
        val tw = ToolWindowManager.getInstance(project).getToolWindow("Codesidian") ?: return
        tw.show {
            val panel = CodeGraphPanel.getInstance(project) ?: return@show
            if (methodId != null) panel.openMethodInGraph(methodId)
            else if (filePath != null) panel.openFileInGraph(filePath)
        }
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = methodIdAtCaret(e) != null || targetFilePath(e) != null
    }

    // The file under the editor caret, or the file selected in the Project view; null for directories.
    private fun targetFilePath(e: AnActionEvent): String? {
        e.getData(CommonDataKeys.PSI_FILE)?.virtualFile?.let { return it.path }
        val vf = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return null
        return if (vf.isDirectory) null else vf.path
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
