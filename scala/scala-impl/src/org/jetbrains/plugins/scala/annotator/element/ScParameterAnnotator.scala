package org.jetbrains.plugins.scala
package annotator
package element

import com.intellij.lang.annotation.AnnotationHolder
import org.jetbrains.plugins.scala.lang.psi.api.base.ScMethodLike
import org.jetbrains.plugins.scala.lang.psi.api.expr.ScFunctionExpr
import org.jetbrains.plugins.scala.lang.psi.api.statements.params.{ScClassParameter, ScParameter}
import org.jetbrains.plugins.scala.project.ScalaLanguageLevel

object ScParameterAnnotator extends ElementAnnotator[ScParameter] {

  override def annotate(element: ScParameter, typeAware: Boolean = true)
                       (implicit holder: AnnotationHolder): Unit = {
    element.owner match {
      case null =>
        holder.createErrorAnnotation(element, "Parameter without an owner: " + element.name)
      case _: ScMethodLike =>
        element.typeElement match {
          case None =>
            holder.createErrorAnnotation(element, "Missing type annotation for parameter: " + element.name)
          case _ =>
        }
        if (element.isCallByNameParameter)
          annotateCallByNameParameter(element)
      case _: ScFunctionExpr =>
        element.typeElement match {
          case None =>
            element.expectedParamType match {
              case None =>
                holder.createErrorAnnotation(element, "Missing parameter type: " + element.name)
              case _ =>
            }
          case _ =>
        }
    }
  }

  private def annotateCallByNameParameter(element: ScParameter)
                                         (implicit holder: AnnotationHolder): Any = {
    def errorWithMessageAbout(topic: String): Unit = {
      val message = s"$topic parameters may not be call-by-name"
      holder.createErrorAnnotation(element, message)
    }
    // TODO move to ScClassParameter
    element match {
      case cp: ScClassParameter if cp.isVal => errorWithMessageAbout("""'val'""")
      case cp: ScClassParameter if cp.isVar => errorWithMessageAbout("""'var'""")
      case cp: ScClassParameter if cp.isCaseClassVal => errorWithMessageAbout("case class")
      case p if p.isImplicitParameter && p.scalaLanguageLevel.forall(_ < ScalaLanguageLevel.Scala_2_13) =>
          errorWithMessageAbout("implicit")
      case _ =>
    }
  }
}
