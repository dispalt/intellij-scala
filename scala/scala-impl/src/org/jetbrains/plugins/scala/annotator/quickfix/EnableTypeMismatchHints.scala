package org.jetbrains.plugins.scala.annotator.quickfix

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import org.jetbrains.plugins.scala.annotator.TypeMismatchHints
import org.jetbrains.plugins.scala.lang.psi.impl.source.ScalaCodeFragment
import org.jetbrains.plugins.scala.settings.ScalaProjectSettings

private[annotator] object EnableTypeMismatchHints extends IntentionAction {

  override def getText = "Enable type mismatch hints"

  override def startInWriteAction = false

  override def isAvailable(project: Project, editor: Editor, file: PsiFile) =
    !file.isInstanceOf[ScalaCodeFragment] && !ScalaProjectSettings.in(project).isTypeMismatchHints

  override def invoke(project: Project, editor: Editor, file: PsiFile) {
    ScalaProjectSettings.in(project).setTypeMismatchHints(true)
    TypeMismatchHints.refreshIn(project)
  }

  override def getFamilyName: String = getText
}