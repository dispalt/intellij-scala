package org.jetbrains.plugins.scala
package finder

import com.intellij.ide.highlighter.{JavaClassFileType, JavaFileType}
import com.intellij.ide.scratch.{ScratchFileService, ScratchFileType}
import com.intellij.openapi.fileTypes.{FileType, FileTypeRegistry, LanguageFileType}
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.searches.{MethodReferencesSearch, ReferencesSearch}
import com.intellij.psi.search.{GlobalSearchScope, LocalSearchScope, SearchScope}

/**
 * User: Alexander Podkhalyuzin
 * Date: 17.02.2010
 */
sealed abstract class FilterScope protected(delegate: GlobalSearchScope)
                                           (implicit project: Project)
  extends GlobalSearchScope(project) {

  private val fileIndex =
    ProjectRootManager.getInstance(project).getFileIndex

  protected final def isInSourceContent(file: VirtualFile): Boolean =
    fileIndex.isInSourceContent(file)

  protected final def isInLibraryClasses(file: VirtualFile): Boolean =
    fileIndex.isInLibraryClasses(file)

  protected def isValid(file: VirtualFile): Boolean

  override def contains(file: VirtualFile): Boolean =
    (delegate == null || delegate.contains(file)) && isValid(file)

  override def compare(file1: VirtualFile, file2: VirtualFile): Int =
    if (delegate != null) delegate.compare(file1, file2) else 0

  override def isSearchInModuleContent(aModule: Module): Boolean =
    delegate == null || delegate.isSearchInModuleContent(aModule)

  override def isSearchInLibraries: Boolean =
    delegate == null || delegate.isSearchInLibraries
}

final class ScalaFilterScope private(scope: GlobalSearchScope)
                                    (implicit project: Project)
  extends FilterScope(scope) {

  override protected def isValid(file: VirtualFile): Boolean =
    FileTypeRegistry.getInstance.getFileTypeByFile(file) match {
      case _: JavaClassFileType =>
        isInLibraryClasses(file)
      case fileType: LanguageFileType =>
        substitutedLanguage(fileType, file).exists(_.isKindOf(ScalaLanguage.INSTANCE)) ||
          ScalaLanguageDerivative.existsFor(fileType)
      case _ => false
    }

  private def substitutedLanguage(fileType: LanguageFileType,
                                  file: VirtualFile) = fileType match {
    case _: ScratchFileType => Option(ScratchFileService.getInstance.getScratchesMapping.getMapping(file))
    case _ if isInSourceContent(file) => Some(fileType.getLanguage)
    case _ => None
  }
}

object ScalaFilterScope {

  def apply(scope: GlobalSearchScope)
           (implicit project: Project) =
    new ScalaFilterScope(scope)

  def apply(parameters: ReferencesSearch.SearchParameters): SearchScope =
    apply(parameters.getEffectiveSearchScope)(parameters.getProject)

  def apply(parameters: MethodReferencesSearch.SearchParameters): SearchScope =
    apply(parameters.getEffectiveSearchScope)(parameters.getProject)

  def apply(scope: SearchScope)
           (implicit project: Project): SearchScope = scope match {
    case global: GlobalSearchScope => apply(global)
    case local: LocalSearchScope => new LocalSearchScope(
      local.getScope.filter(_.getLanguage.isKindOf(ScalaLanguage.INSTANCE)),
      local.getDisplayName + " in scala",
      local.isIgnoreInjectedPsi
    )
    case _ => scope
  }
}

final class SourceFilterScope private(scope: GlobalSearchScope, fileTypes: Seq[FileType])
                                     (implicit project: Project)
  extends FilterScope(GlobalSearchScope.getScopeRestrictedByFileTypes(scope, fileTypes: _*)) {

  override protected def isValid(file: VirtualFile): Boolean = isInSourceContent(file)
}

object SourceFilterScope {

  import GlobalSearchScope.projectScope

  def apply(fileTypes: Seq[FileType])
           (implicit project: Project) =
    new SourceFilterScope(projectScope(project), fileTypes)

  def apply(scope: GlobalSearchScope)
           (implicit project: Project) =
    new SourceFilterScope(scope, Seq(ScalaFileType.INSTANCE, JavaFileType.INSTANCE))
}
