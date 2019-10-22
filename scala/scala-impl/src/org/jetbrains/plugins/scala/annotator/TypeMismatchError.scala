package org.jetbrains.plugins.scala.annotator

import com.intellij.lang.annotation.{Annotation, AnnotationHolder}
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import org.jetbrains.plugins.scala.ScalaBundle
import org.jetbrains.plugins.scala.annotator.annotationHolder.DelegateAnnotationHolder
import org.jetbrains.plugins.scala.annotator.quickfix.{EnableTypeMismatchHints, ReportHighlightingErrorQuickFix}
import org.jetbrains.plugins.scala.lang.psi.api.expr.ScBlockExpr
import org.jetbrains.plugins.scala.lang.psi.types.api.ScTypePresentation
import org.jetbrains.plugins.scala.lang.psi.types.{ScLiteralType, ScType, TypePresentationContext}
import org.jetbrains.plugins.scala.settings.ScalaProjectSettings

private object TypeMismatchError {
  def register(element: PsiElement, expectedType: ScType, actualType: ScType, blockLevel: Int = 0, canBeHint: Boolean = true)
              (formatMessage: (String, String) => String)
              (implicit holder: AnnotationHolder): Annotation = {
    // TODO update the test data, SCL-15483
    val message = {
      val wideActualType = (expectedType, actualType) match {
        case (_: ScLiteralType, t2: ScLiteralType) => t2
        case (_, t2: ScLiteralType) => t2.wideType
        case (_, t2) => t2
      }

      val (actualTypeText, expectedTypeText) = ScTypePresentation.different(wideActualType, expectedType)

      if (ApplicationManager.getApplication.isUnitTestMode) formatMessage(expectedTypeText, actualTypeText)
      else ScalaBundle.message("type.mismatch.message", expectedTypeText, actualTypeText)
    }

    val annotatedElement = elementAt(element, blockLevel)

    val highlightExpression = !ScalaProjectSettings.in(element.getProject).isTypeMismatchHints || !canBeHint

    // TODO type mismatch hints are experimental (SCL-15250), don't affect annotator / highlighting tests
    val annotation = if (ApplicationManager.getApplication.isUnitTestMode || highlightExpression) {
      holder.createErrorAnnotation(annotatedElement, message)
    } else {
      val annotation = holder.createErrorAnnotation(lastLineRangeOf(annotatedElement), message)
      adjustTextAttributesOf(annotation)
      annotation
    }

    implicit val context = TypePresentationContext(annotatedElement)

    annotation.setTooltip(if (highlightExpression) TypeMismatchHints.tooltipFor(expectedType, actualType) else null)
    annotation.registerFix(ReportHighlightingErrorQuickFix)
    annotation.registerFix(EnableTypeMismatchHints)

    if (!highlightExpression) {
      val delegateElement = holder match {
        // handle possible element mapping (e.g. ScGeneratorAnnotator)
        case DelegateAnnotationHolder(element) => element
        case _ => annotatedElement
      }

      // TODO Can we detect a "current" color scheme in a "current" editor somehow?
      implicit val scheme = EditorColorsManager.getInstance().getGlobalScheme

      TypeMismatchHints.createFor(delegateElement, expectedType, actualType).putTo(delegateElement)
    }

    annotation
  }

  private def elementAt(element: PsiElement, blockLevel: Int) = blockLevel match {
    case 2 =>
      (element, element.getParent) match {
        case (b: ScBlockExpr, _) => b.getRBrace.map(_.getPsi).getOrElse(element)
        case (_, b: ScBlockExpr) => b.getRBrace.map(_.getPsi).getOrElse(element)
        case _ => element
      }
    case 1 =>
      element match {
        case b: ScBlockExpr => b.getRBrace.map(_.getPsi).getOrElse(b)
        case _ => element
      }
    case 0 => element
  }

  private def adjustTextAttributesOf(annotation: Annotation) = {
    val errorStripeColor = annotation.getTextAttributes.getDefaultAttributes.getErrorStripeColor
    val attributes = new TextAttributes()
    attributes.setEffectType(null)
    attributes.setErrorStripeColor(errorStripeColor)
    annotation.setEnforcedTextAttributes(attributes)
  }

  private def lastLineRangeOf(element: PsiElement) = {
    val range = element.getTextRange
    val text = element.getText
    val lastLineOffset = range.getStartOffset + Option(text.lastIndexOf("\n")).filterNot(_ == -1).map(_ + 1).getOrElse(0)
    TextRange.create(lastLineOffset, range.getEndOffset)
  }
}
