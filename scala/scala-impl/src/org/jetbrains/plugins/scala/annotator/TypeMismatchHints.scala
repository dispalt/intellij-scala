package org.jetbrains.plugins.scala.annotator

import com.intellij.openapi.editor.colors.{CodeInsightColors, EditorColorsScheme}
import com.intellij.openapi.project.Project
import com.intellij.psi.{PsiElement, PsiWhiteSpace}
import com.intellij.util.ui.StartupUiUtil
import com.intellij.xml.util.XmlStringUtil.escapeString
import org.jetbrains.plugins.scala.ScalaBundle
import org.jetbrains.plugins.scala.annotator.Tree.{Leaf, Node}
import org.jetbrains.plugins.scala.annotator.TypeDiff.{Match, Mismatch}
import org.jetbrains.plugins.scala.annotator.hints.{Text, _}
import org.jetbrains.plugins.scala.caches.CachesUtil.fileModCount
import org.jetbrains.plugins.scala.codeInsight.ScalaCodeInsightSettings
import org.jetbrains.plugins.scala.extensions._
import org.jetbrains.plugins.scala.lang.psi.api.expr.{ScInfixExpr, ScPostfixExpr}
import org.jetbrains.plugins.scala.lang.psi.types.{ScType, TypePresentationContext}
import org.jetbrains.plugins.scala.settings.ScalaProjectSettings

// TODO experimental feature (SCL-15250)
private object TypeMismatchHints {
  def createFor(element: PsiElement, expectedType: ScType, actualType: ScType)(implicit scheme: EditorColorsScheme, context: TypePresentationContext): AnnotatorHints = {
    val needsParentheses = element.is[ScInfixExpr, ScPostfixExpr]

    val prefix =
      if (needsParentheses) Seq(Hint(Seq(Text("(")), element, suffix = false))
      else Seq.empty

    val parts = Text(": ") +: partsOf(expectedType, actualType, tooltipFor(expectedType, actualType)) |> { parts =>
      if (needsParentheses) Text(")") +: parts else parts
    }

    val margin = if (needsParentheses) None else Hint.leftInsetLikeChar(' ')

    val offsetDelta = Option(element.getContainingFile)
      .flatMap(file => Option(file.findElementAt(element.getTextRange.getEndOffset)))
      .filter(_.is[PsiWhiteSpace])
      .map(_.getText.takeWhile(_ != '\n').length)
      .getOrElse(0)

    val hints = prefix :+ Hint(parts, element, margin = margin, suffix = true, relatesToPrecedingElement = true, offsetDelta = offsetDelta, menu = Some("TypeHintsMenu"))

    AnnotatorHints(hints, fileModCount(element.getContainingFile))
  }

  private def partsOf(expected: ScType, actual: ScType, message: String)(implicit scheme: EditorColorsScheme, context: TypePresentationContext): Seq[Text] = {
    def toText(diff: Tree[TypeDiff]): Text = diff match {
      case Node(diffs @_*) =>
        Text(foldedString,
          foldedAttributes(diff.flatten.exists(_.is[Mismatch])),
          expansion = Some(() => diffs.map(toText)))
      case Leaf(Match(text, tpe)) =>
        Text(text,
          tooltip = tpe.map(_.canonicalText.replaceFirst("_root_.", "")),
          navigatable = tpe.flatMap(_.extractClass))
      case Leaf(Mismatch(text, tpe)) =>
        Text(text,
          attributes = Some(scheme.getAttributes(CodeInsightColors.ERRORS_ATTRIBUTES)),
          tooltip = tpe.map(_.canonicalText.replaceFirst("_root_.", "")),
          navigatable = tpe.flatMap(_.extractClass))
    }
    TypeDiff.forActual(expected, actual)
      .flattenTo(TypeDiff.lengthOf(nodeLength = foldedString.length), maxLength = ScalaCodeInsightSettings.getInstance.presentationLength)
      .map(toText)
      .map(_.copy(errorTooltip = Some(message)))
  }

  def tooltipFor(expectedType: ScType, actualType: ScType)(implicit context: TypePresentationContext): String = {
    def format(diff: Tree[TypeDiff], f: String => String) = {
      val parts = diff.flatten.map {
        case Leaf(Match(text, _)) => text
        case Leaf(Mismatch(text, _)) => f(text)
      } map {
        "<td style=\"text-align:center\">" + _ + "</td>"
      }
      parts.mkString
    }

    // com.intellij.codeInsight.daemon.impl.analysis.HighlightUtil.redIfNotMatch
    def red(text: String) = {
      val color = if (StartupUiUtil.isUnderDarcula) "FF6B68" else "red"
      "<font color='" + color + "'><b>" + escapeString(text) + "</b></font>"
    }

    val (diff1, diff2) = TypeDiff.forBoth(expectedType, actualType)

    ScalaBundle.message("type.mismatch.tooltip", format(diff1, s => s"<b>$s</b>"), format(diff2, red))
  }

  // TODO Use a dedicated pass when built-in "advanced" hint API will be available in IDEA, SCL-14502
  def refreshIn(project: Project): Unit = {
    if (!ScalaProjectSettings.in(project).isTypeMismatchHints) {
      AnnotatorHints.clearIn(project)
    }

    Class.forName("org.jetbrains.plugins.scala.codeInsight.implicits.ImplicitHints")
      .getDeclaredMethod("updateInAllEditors")
      .invoke(null)
  }
}
