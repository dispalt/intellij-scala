package org.jetbrains.plugins.scala.finder

import java.util

import com.intellij.openapi.project.{DumbService, Project}
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.{PsiClass, PsiElementFinder, PsiPackage}
import org.jetbrains.plugins.scala.caches.ScalaShortNamesCacheManager
import org.jetbrains.plugins.scala.lang.psi.ScalaPsiUtil
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.typedef.{ScObject, ScTrait, ScTypeDefinition}
import org.jetbrains.plugins.scala.lang.psi.impl.ScalaPsiManager

import scala.collection.JavaConverters._

class ScalaClassFinder(project: Project) extends PsiElementFinder {
  private def psiManager  : ScalaPsiManager             = ScalaPsiManager.instance(project)
  private def cacheManager: ScalaShortNamesCacheManager = ScalaShortNamesCacheManager.getInstance(project)

  override def findClasses(qualifiedName: String, scope: GlobalSearchScope): Array[PsiClass] = {
    if (psiManager.isInJavaPsiFacade) {
      return Array.empty
    }

    val classesWoSuffix: String => Seq[PsiClass] = (suffix: String) => {
      if (qualifiedName.endsWith(suffix)) {
        cacheManager.getClassesByFQName(qualifiedName.stripSuffix(suffix), scope)
      } else {
        Nil
      }
    }

    val x: Seq[Option[PsiClass]] = classesWoSuffix("").collect {
      case o: ScObject if o.isPackageObject => None
      case o: ScObject                      => o.fakeCompanionClass
      case td: ScTypeDefinition             => Some(td)
    }.distinct

    val x$: Seq[Option[PsiClass]] = classesWoSuffix("$").collect {
      case o: ScObject         => Some(o)
      case c: ScTypeDefinition => ScalaPsiUtil.getCompanionModule(c)
    }.distinct

    val x$class: Seq[Option[PsiClass]] = classesWoSuffix("$class").collect {
      case c: ScTrait =>
        Option(c.fakeCompanionClass)
    }
    (x ++ x$ ++ x$class).flatten.toArray
  }

  override def findClass(qualifiedName: String, scope: GlobalSearchScope): PsiClass = {
    findClasses(qualifiedName, scope).headOption.orNull
  }

  override def findPackage(qName: String): PsiPackage = {
    if (DumbService.isDumb(project)) {
      return null
    }
    psiManager.syntheticPackage(qName)
  }

  override def getClassNames(psiPackage: PsiPackage, scope: GlobalSearchScope): util.Set[String] = {
    psiManager.getJavaPackageClassNames(psiPackage, scope).asJava
  }

  override def getClasses(psiPackage: PsiPackage, scope: GlobalSearchScope): Array[PsiClass] = {
    if (psiManager.isInJavaPsiFacade) {
      return Array.empty
    }
    psiManager.getJavaPackageClassNames(psiPackage, scope)
      .flatMap { clsName =>
        val qualifiedName = psiPackage.getQualifiedName + "." + clsName
        psiManager.getCachedClasses(scope, qualifiedName) ++ findClasses(qualifiedName, scope)
      }
      .toArray
  }
}
