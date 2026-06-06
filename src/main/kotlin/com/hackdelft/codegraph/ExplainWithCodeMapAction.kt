package com.hackdelft.codegraph

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiMethod
import com.intellij.psi.util.PsiTreeUtil

// Flow 3: right-click a method/class in the editor → open Code Graph and run AI focus mode on it.
class ExplainWithCodeMapAction : AnAction("Explain with CodeMap") {

  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project ?: return
    val nodeId = targetNodeId(e) ?: return
    val tw = ToolWindowManager.getInstance(project).getToolWindow("Code Graph") ?: return
    tw.show { CodeGraphPanel.getInstance(project)?.explainNode(nodeId) }
  }

  override fun update(e: AnActionEvent) {
    e.presentation.isEnabledAndVisible = targetNodeId(e) != null
  }

  private fun targetNodeId(e: AnActionEvent): String? {
    val editor = e.getData(CommonDataKeys.EDITOR) ?: return null
    val file = e.getData(CommonDataKeys.PSI_FILE) ?: return null
    val el = file.findElementAt(editor.caretModel.offset) ?: return null

    val method = PsiTreeUtil.getParentOfType(el, PsiMethod::class.java)
    if (method != null) {
      val cls = method.containingClass?.qualifiedName ?: return null
      return "method:$cls.${method.name}"
    }
    val cls = PsiTreeUtil.getParentOfType(el, PsiClass::class.java) ?: return null
    return cls.qualifiedName?.let { "class:$it" }
  }
}
