package org.jetbrains.plugins.scala.macroAnnotations

import scala.annotation.StaticAnnotation
import scala.language.experimental.macros
import scala.reflect.macros.whitebox

/**
 * This annotation generates code that caches result of the function. Caches are invalidated on `dependencyItem` change.
 *
 * Computation is also guarded against recursive calls on the same data.
 * See
 * `org.jetbrains.plugins.scala.caches.RecursionManager`
 * `org.jetbrains.plugins.scala.caches.CachesUtil.handleRecursiveCall`
 * `org.jetbrains.plugins.scala.macroAnnotations.CachedMacroUtil.handleProbablyRecursiveException`
 *
 * Author: Svyatoslav Ilinskiy, Nikolay.Tropin
 * Date: 9/28/15.
 */
class CachedWithRecursionGuard(element: Any, defaultValue: => Any, dependecyItem: Object) extends StaticAnnotation {
  def macroTransform(annottees: Any*): Any = macro CachedWithRecursionGuard.cachedWithRecursionGuardImpl
}

object CachedWithRecursionGuard {
  import CachedMacroUtil._
  def cachedWithRecursionGuardImpl(c: whitebox.Context)(annottees: c.Tree*): c.Expr[Any] = {
    import c.universe._
    implicit val x: c.type = c

    def parameters: (Tree, Tree, Tree) = c.prefix.tree match {
      case q"new CachedWithRecursionGuard(..$params)" if params.length == 3 =>
        (params.head, params(1), modCountParamToModTracker(c)(params(2), params.head))
      case _ => abort("Wrong annotation parameters!")
    }

    val (element, defaultValue, modTracker) = parameters

    annottees.toList match {
      case DefDef(mods, name, tpParams, paramss, retTp, rhs) :: Nil =>
        if (retTp.isEmpty) {
          abort("You must specify return type")
        }

        //function parameters
        val flatParams = paramss.flatten
        val parameterTypes = flatParams.map(_.tpt)
        val parameterNames: List[c.universe.TermName] = flatParams.map(_.name)
        val hasParams = flatParams.nonEmpty

        //generated types
        val dataType = if (hasParams) tq"(..$parameterTypes)" else tq"Unit"
        val resultType = box(c)(retTp)
        val elementType = psiElementType

        //generated names
        val keyId = c.freshName(name.toString + "cacheKey")
        val tracerName = generateTermName(name.toString, "$tracer")
        val cacheName = withClassName(name)
        val guard = generateTermName(name.toString, "guard")
        val defValueName = generateTermName(name.toString, "defaultValue")
        val elemName = generateTermName(name.toString, "element")
        val dataName = generateTermName(name.toString, "data")
        val keyVarName = generateTermName(name.toString, "key")
        val holderName = generateTermName(name.toString, "holder")
        val resultName = generateTermName(name.toString, "result")
        val dataForGuardName = generateTermName(name.toString, "dataForGuard")

        val dataValue = if (hasParams) q"(..$parameterNames)" else q"()"
        val getOrCreateCachedHolder =
          if (hasParams)
            q"$cachesUtilFQN.getOrCreateCachedMap[$elementType, $dataType, $resultType]($elemName, $keyVarName, () => $modTracker)"
          else
            q"$cachesUtilFQN.getOrCreateCachedRef[$elementType, $resultType]($elemName, $keyVarName, () => $modTracker)"

        val getFromHolder =
          if (hasParams) q"$holderName.get($dataName)"
          else q"$holderName.get()"

        val updateHolder = new UpdateHolderGenerator[c.type](c) {
          override def apply(resultName: c.universe.TermName): c.universe.Tree =
            if (hasParams) q"$holderName.putIfAbsent($dataName, $resultName)"
            else q"$holderName.compareAndSet(null, $resultName)"
        }

        val dataForGuardType =
          if (hasParams) tq"($elementType, $dataType)"
          else tq"$elementType"

        val dataForRecursionGuard =
          if (hasParams) q"($elemName, $dataName)"
          else q"$elemName"

        val guardedCalculation = withUIFreezingGuard(c)(rhs, retTp)
        val withProbablyRecursiveException = handleProbablyRecursiveException(c)(elemName, dataName, keyVarName, guardedCalculation)
        val withCaching = doCaching(c)(withProbablyRecursiveException, resultName, guard, dataForGuardName, updateHolder)
        val cachedCalculationWithAllTheChecks = doPreventingRecursion(c)(withCaching, guard, dataForGuardName, retTp)

        val updatedRhs = q"""
          val $elemName = $element
          val $dataName = $dataValue
          val $dataForGuardName = $dataForRecursionGuard
          val $keyVarName = ${getOrCreateKey(c, hasParams)(q"$keyId", dataType, resultType)}
          def $defValueName: $resultType = $defaultValue

          val $tracerName = $internalTracer($keyId, $cacheName)
          $tracerName.invocation()

          val $holderName = $getOrCreateCachedHolder
          val fromCachedHolder = $getFromHolder
          if (fromCachedHolder != null) return fromCachedHolder

          val $guard = $recursionGuardFQN[$dataForGuardType, $resultType]($keyVarName.toString)
          if ($guard.checkReentrancy($dataForGuardName))
            return $cachesUtilFQN.handleRecursiveCall($elemName, $dataName, $keyVarName, $defValueName)

          $tracerName.calculationStart()

          try {
            val ($resultName, shouldCacheLocally) = $cachedCalculationWithAllTheChecks
            if (shouldCacheLocally) {
              $guard.cacheInLocalCache($dataForGuardName, $resultName)
            }
            $resultName
          } finally {
            $tracerName.calculationEnd()
          }
          """

        val updatedDef = DefDef(mods, name, tpParams, paramss, retTp, updatedRhs)

        debug(updatedDef)

        c.Expr(updatedDef)
      case _ => abort("You can only annotate one function!")
    }
  }
}
