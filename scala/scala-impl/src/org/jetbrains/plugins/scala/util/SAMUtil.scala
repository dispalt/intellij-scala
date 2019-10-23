package org.jetbrains.plugins.scala.util

import com.intellij.psi._
import org.jetbrains.plugins.scala.extensions._
import org.jetbrains.plugins.scala.lang.psi.ElementScope
import org.jetbrains.plugins.scala.lang.psi.ScalaPsiUtil.MethodValue
import org.jetbrains.plugins.scala.lang.psi.api.base.ScPrimaryConstructor
import org.jetbrains.plugins.scala.lang.psi.api.expr.{ScBlock, ScExpression, ScFunctionExpr, ScUnderScoreSectionUtil}
import org.jetbrains.plugins.scala.lang.psi.api.statements.ScFunction
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.typedef.{ScClass, ScTemplateDefinition, ScTrait}
import org.jetbrains.plugins.scala.lang.psi.impl.ScalaPsiManager
import org.jetbrains.plugins.scala.lang.psi.impl.toplevel.typedef.TypeDefinitionMembers
import org.jetbrains.plugins.scala.lang.psi.types.api.{FunctionType, ParameterizedType, Variance}
import org.jetbrains.plugins.scala.lang.psi.types.recursiveUpdate.ScSubstitutor
import org.jetbrains.plugins.scala.lang.psi.types.{PhysicalMethodSignature, ScExistentialArgument, ScExistentialType, ScParameterizedType, ScType}
import org.jetbrains.plugins.scala.macroAnnotations.{CachedInUserData, ModCount}
import org.jetbrains.plugins.scala.project._


object SAMUtil {
  implicit class ScExpressionExt(private val expr: ScExpression) extends AnyVal {
    @CachedInUserData(expr, ModCount.getModificationCount)
    def samTypeParent: Option[PsiClass] =
      if (expr.isSAMEnabled && isFunctionalExpression(expr)) {
        for {
          pt  <- expr.expectedType(fromUnderscore = false)
          cls <- pt.extractClass
          if cls.isSAMable
        } yield cls
      } else None
  }

  object SAMTypeImplementation {
    def unapply(e: ScExpression): Option[PsiClass] = e.samTypeParent
  }

  def isFunctionalExpression(e: ScExpression): Boolean = e match {
    case _: ScFunctionExpr                                    => true
    case block: ScBlock if block.isAnonymousFunction          => true
    case MethodValue(_)                                       => true
    case _ if ScUnderScoreSectionUtil.underscores(e).nonEmpty => true
    case _                                                    => false
  }


  private[this] def constructorValidForSAM(constructor: PsiMethod): Boolean = {
    val isPublicAndParameterless =
      constructor.getModifierList.hasModifierProperty(PsiModifier.PUBLIC) &&
        constructor.getParameterList.getParametersCount == 0

    constructor match {
      case scalaConstr: ScPrimaryConstructor if isPublicAndParameterless => scalaConstr.effectiveParameterClauses.size < 2
      case _                                                             => isPublicAndParameterless
    }
  }

  private[this] def hasValidConstructor(td: ScTemplateDefinition): Boolean = td match {
    case cla: ScClass => cla.constructor.exists(constructorValidForSAM)
    case _: ScTrait   => true
    case _            => false
  }

  private[this] def hasValidConstructorAndSelfType(cls: PsiClass): Boolean = {
    def selfTypeValid(tdef: ScTemplateDefinition): Boolean =
      tdef.selfType match {
        case Some(selfParam: ScParameterizedType) =>
          tdef.`type`() match {
            case Right(classParamTp: ScParameterizedType) => selfParam.designator.conforms(classParamTp.designator)
            case _                                        => false
          }
        case Some(selfTp) => tdef.`type`().exists(selfTp.conforms(_))
        case _            => true
      }

    def selfTypeCorrectIfScala212(tdef: ScTemplateDefinition) =
      tdef.scalaLanguageLevelOrDefault == ScalaLanguageLevel.Scala_2_11 || selfTypeValid(tdef)

    cls match {
      case tdef: ScTemplateDefinition =>
        !tdef.isEffectivelyFinal && !tdef.isSealed && hasValidConstructor(tdef) && selfTypeCorrectIfScala212(tdef)
      case _ =>
        cls.getConstructors match {
          case Array()      => true
          case constructors => constructors.exists(constructorValidForSAM)
        }
    }
  }

  def singleAbstractMethod(cls: PsiClass): Option[PsiMethod] = cls.singleAbstractMethodWithSubstitutor.map(_._1)

  /**
    * Determines if expected can be created with a Single Abstract Method and if so return the required ScType for it
    *
    * @see SCL-6140
    * @see https://github.com/scala/scala/pull/3018/
    */
    def toSAMType(expected: ScType, element: PsiElement): Option[ScType] = {
      implicit val scope: ElementScope = element.elementScope
      val languageLevel                = element.scalaLanguageLevelOrDefault

      expected.extractClassType.flatMap {
        case (cls: PsiClass, subst) =>
          if (!hasValidConstructorAndSelfType(cls)) None
          else {
            for {
              (method, methodSubst) <- cls.singleAbstractMethodWithSubstitutor
              funType               <- functionType(method)
            } yield {
              val substituted = methodSubst.followed(subst)(funType)
              extrapolateWildcardBounds(substituted, expected, languageLevel).getOrElse(substituted)
            }
          }
      }
    }

  private def functionType(m: PsiMethod)
                          (implicit scope: ElementScope): Option[ScType] = m match {
    case fun: ScFunction => fun.`type`().toOption
    case method          =>
      val returnType = Option(method.getReturnType.toScType())
      val paramTypes = method.parameters.map(_.getTypeElement.getType.toScType())
      returnType.map(FunctionType(_, paramTypes))
  }

  private def hasSingleParameterClause(m: PsiMethod): Boolean = m match {
    case f: ScFunction => f.paramClauses.clauses.size == 1
    case _             => true
  }


  implicit class PsiClassToSAMExt(private val cls: PsiClass) extends AnyVal {
    @CachedInUserData(cls, ScalaPsiManager.instance(cls.getProject).TopLevelModificationTracker)
    def isSAMable: Boolean =
      hasValidConstructorAndSelfType(cls) && singleAbstractMethod(cls).isDefined

    @CachedInUserData(cls, ScalaPsiManager.instance(cls.getProject).TopLevelModificationTracker)
    private[SAMUtil] def singleAbstractMethodWithSubstitutor: Option[(PsiMethod, ScSubstitutor)] = {
      val abstractMembers = TypeDefinitionMembers.getSignatures(cls).allSignatures.filter(_.isAbstract).toSeq
      abstractMembers match {
        case Seq(PhysicalMethodSignature(m: PsiMethod, subst))
          if !m.hasTypeParameters && hasSingleParameterClause(m) =>
          Option((m, subst))
        case _ => None
      }
    }
  }

  /**
    * In some cases existential bounds can be simplified without losing precision
    *
    * trait Comparinator[T] { def compare(a: T, b: T): Int }
    *
    * trait Test {
    * def foo(a: Comparinator[_ >: String]): Int
    * }
    *
    * can be simplified to:
    *
    * trait Test {
    * def foo(a: Comparinator[String]): Int
    * }
    *
    * @see https://github.com/scala/scala/pull/4101
    * @see SCL-8956
    */
  private[this] def extrapolateWildcardBounds(tp: ScType, expected: ScType, scalaVersion: ScalaLanguageLevel)
                                       (implicit elementScope: ElementScope): Option[ScType] = {
    def convertParameter(tpArg: ScType, wildcards: Seq[ScExistentialArgument], variance: Variance): ScType = {
      tpArg match {
        case ParameterizedType(des, tpArgs) => ScParameterizedType(des, tpArgs.map(convertParameter(_, wildcards, variance)))
        case ScExistentialType(parameterized: ScParameterizedType, _) if scalaVersion == ScalaLanguageLevel.Scala_2_11 =>
          ScExistentialType(convertParameter(parameterized, wildcards, variance)).simplify()
        case arg: ScExistentialArgument if wildcards.contains(arg) =>
          (arg.lower, arg.upper) match {
            // todo: Produces Bad code is green
            // Problem is in Java wildcards. How to convert them if it's _ >: Lower, when generic has Upper.
            // Earlier we converted with Any upper type, but then it was changed because of type incompatibility.
            // Right now the simplest way is Bad Code is Green as otherwise we need to fix this inconsistency somehow.
            // I has no idea how yet...
            case (lo, _) if variance.isContravariant              => lo
            case (lo, hi) if lo.isNothing && variance.isCovariant => hi
            case _                                                => tpArg
          }
        case arg: ScExistentialArgument =>
          arg.copyWithBounds(convertParameter(arg.lower, wildcards, variance), convertParameter(arg.upper, wildcards, variance))
        case _ => tpArg
      }
    }

    expected match {
      case ScExistentialType(ParameterizedType(_, _), wildcards) =>
        tp match {
          case FunctionType(retTp, params) =>
            //parameter clauses are contravariant positions, return types are covariant positions
            val newParams = params.map(convertParameter(_, wildcards, Variance.Contravariant))
            val newRetTp  = convertParameter(retTp, wildcards, Variance.Covariant)
            Some(FunctionType(newRetTp, newParams))
          case _ => None
        }
      case _ => None
    }
  }
}
