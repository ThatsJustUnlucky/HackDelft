package com.hackdelft.codegraph

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.psi.PsiMethod
import com.intellij.psi.util.PsiTreeUtil

// Flow 3: right-click a method in the editor, open Codesidian, and run AI focus mode.
class ExplainWithCodeMapAction : AnAction("Explain with Codesidian") {

  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project ?: return
    val nodeId = targetNodeId(e) ?: return
    val tw = ToolWindowManager.getInstance(project).getToolWindow("Codesidian") ?: return
    tw.show { CodeGraphPanel.getInstance(project)?.explainNode(nodeId) }
  }

  override fun update(e: AnActionEvent) {
    e.presentation.isEnabledAndVisible = targetNodeId(e) != null
  }

  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

  private fun targetNodeId(e: AnActionEvent): String? {
    val editor = e.getData(CommonDataKeys.EDITOR) ?: return null
    val file = e.getData(CommonDataKeys.PSI_FILE) ?: return null
    val el = file.findElementAt(editor.caretModel.offset) ?: return null
    val method = PsiTreeUtil.getParentOfType(el, PsiMethod::class.java) ?: return null
    val cls = method.containingClass?.qualifiedName ?: return null
    return "method:$cls.${method.name}"
  }
}
