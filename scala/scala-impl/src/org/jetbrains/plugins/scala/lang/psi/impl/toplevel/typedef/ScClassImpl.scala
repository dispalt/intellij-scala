package org.jetbrains.plugins.scala
package lang
package psi
package impl
package toplevel
package typedef

import com.intellij.lang.ASTNode
import com.intellij.openapi.progress.{ProcessCanceledException, ProgressManager}
import com.intellij.openapi.project.DumbService
import com.intellij.psi._
import com.intellij.psi.util.PsiTreeUtil
import javax.swing.Icon
import org.jetbrains.plugins.scala.caches.CachesUtil
import org.jetbrains.plugins.scala.extensions._
import org.jetbrains.plugins.scala.icons.Icons
import org.jetbrains.plugins.scala.lang.parser.ScalaElementType
import org.jetbrains.plugins.scala.lang.psi.PresentationUtil.accessModifierText
import org.jetbrains.plugins.scala.lang.psi.api.ScalaElementVisitor
import org.jetbrains.plugins.scala.lang.psi.api.base.ScPrimaryConstructor
import org.jetbrains.plugins.scala.lang.psi.api.statements._
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.ScTypeParametersOwner
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.typedef._
import org.jetbrains.plugins.scala.lang.psi.light.ScLightField
import org.jetbrains.plugins.scala.lang.psi.stubs.ScTemplateDefinitionStub
import org.jetbrains.plugins.scala.lang.psi.stubs.elements.ScTemplateDefinitionElementType
import org.jetbrains.plugins.scala.lang.psi.types.ScTypeExt
import org.jetbrains.plugins.scala.lang.psi.types.api.TypeParameterType
import org.jetbrains.plugins.scala.lang.resolve.processor.BaseProcessor
import org.jetbrains.plugins.scala.macroAnnotations.CachedInUserData

/**
  * @author Alexander.Podkhalyuzin
  */
class ScClassImpl(stub: ScTemplateDefinitionStub[ScClass],
                  nodeType: ScTemplateDefinitionElementType[ScClass],
                  node: ASTNode)
  extends ScTypeDefinitionImpl(stub, nodeType, node)
    with ScClass
    with ScTypeParametersOwner {

  override def toString: String = "ScClass: " + ifReadAllowed(name)("")

  override protected final def baseIcon: Icon =
    if (this.hasAbstractModifier) Icons.ABSTRACT_CLASS
    else Icons.CLASS

  override protected def acceptScala(visitor: ScalaElementVisitor): Unit = visitor.visitClass(this)

  //do not use fakeCompanionModule, it will be used in Stubs.
  override def additionalClassJavaName: Option[String] =
    if (isCase) Some(getName() + "$") else None

  override def constructor: Option[ScPrimaryConstructor] = desugaredElement match {
    case Some(templateDefinition: ScConstructorOwner) => templateDefinition.constructor
    case _ => this.stubOrPsiChild(ScalaElementType.PRIMARY_CONSTRUCTOR)
  }

  import com.intellij.psi.scope.PsiScopeProcessor
  import com.intellij.psi.{PsiElement, ResolveState}

  override def processDeclarationsForTemplateBody(processor: PsiScopeProcessor,
                                                  state: ResolveState,
                                                  lastParent: PsiElement,
                                                  place: PsiElement): Boolean = {
    if (DumbService.getInstance(getProject).isDumb) return true

    desugaredElement match {
      case Some(td: ScTemplateDefinitionImpl[_]) => return td.processDeclarationsForTemplateBody(processor, state, getLastChild, place)
      case _ =>
    }

    if (!super.processDeclarationsForTemplateBody(processor, state, lastParent, place)) return false

    super[ScTypeParametersOwner].processDeclarations(processor, state, lastParent, place)
  }

  override def processDeclarations(processor: PsiScopeProcessor,
                                   state: ResolveState,
                                   lastParent: PsiElement,
                                   place: PsiElement): Boolean =
    processDeclarationsImpl(processor, state, lastParent, place)

  override def isCase: Boolean = hasModifierProperty("case")

  override protected def addFromCompanion(companion: ScTypeDefinition): Boolean = companion.isInstanceOf[ScObject]

  override def getConstructors: Array[PsiMethod] =
    constructor.toArray
      .flatMap(_.getFunctionWrappers) ++
      secondaryConstructors
        .flatMap(_.getFunctionWrappers(isStatic = false, isAbstract = false, Some(this)))

  private def implicitMethodText: String = {
    val constr = constructor.getOrElse(return "")
    val returnType = name + typeParametersClause.map(_ => typeParameters.map(_.name).
      mkString("[", ",", "]")).getOrElse("")
    val typeParametersText = typeParametersClause.map(tp => {
      tp.typeParameters.map(tp => {
        val baseText = tp.typeParameterText
        if (tp.isContravariant) {
          val i = baseText.indexOf('-')
          baseText.substring(i + 1)
        } else if (tp.isCovariant) {
          val i = baseText.indexOf('+')
          baseText.substring(i + 1)
        } else baseText
      }).mkString("[", ", ", "]")
    }).getOrElse("")
    val parametersText = constr.parameterList.clauses.map { clause =>
      clause.parameters.map { parameter =>
        val paramText = s"${parameter.name} : ${parameter.typeElement.map(_.getText).getOrElse("Nothing")}"
        parameter.getDefaultExpression match {
          case Some(expr) => s"$paramText = ${expr.getText}"
          case _          => paramText
        }
      }.mkString(if (clause.isImplicit) "(implicit " else "(", ", ", ")")
    }.mkString
    val accessModifier = getModifierList.accessModifier.map(am => accessModifierText(am) + " ").getOrElse("")
    s"${accessModifier}implicit def $name$typeParametersText$parametersText : $returnType = throw new Error()"
  }

  override def getSyntheticImplicitMethod: Option[ScFunction] = {
    if (hasModifierProperty("implicit") && constructor.nonEmpty)
      syntheticImplicitMethod
    else None
  }

  @CachedInUserData(this, CachesUtil.libraryAwareModTracker(this))
  private def syntheticImplicitMethod: Option[ScFunction] = {
    try {
      val method = ScalaPsiElementFactory.createMethodWithContext(implicitMethodText, this.getContext, this)
      method.syntheticNavigationElement = this
      Some(method)
    } catch {
      case p: ProcessCanceledException => throw p
      case _: Exception => None
    }
  }

  override def psiFields: Array[PsiField] = {
    val fields = constructor match {
      case Some(constr) => constr.parameters.map { param =>
        param.`type`() match {
          case Right(tp: TypeParameterType) if tp.psiTypeParameter.findAnnotation("scala.specialized") != null =>
            val psiTypeText: String = tp.toPsiType.getCanonicalText
            val lightField = ScLightField(param.getName, tp, this, PsiModifier.PUBLIC, PsiModifier.FINAL)
            Option(lightField)
          case _ => None
        }
      }
      case _ => Seq.empty
    }
    super.psiFields ++ fields.flatten
  }

  override def getTypeParameterList: PsiTypeParameterList = typeParametersClause.orNull

  override def getInterfaces: Array[PsiClass] = {
    getSupers.filter(_.isInterface)
  }
}
