package org.jetbrains.plugins.scala
package lang
package navigation

import com.intellij.ide.util.gotoByName._
import com.intellij.openapi.application.ModalityState
import com.intellij.psi.PsiElement
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.util.concurrency.Semaphore
import org.junit.Assert._

import scala.collection.JavaConverters._

/**
 * @author Alefas
 * @since 23.12.13
 */
class GoToClassAndSymbolTest extends GoToTestBase {

  import GoToClassAndSymbolTest._

  override protected def loadScalaLibrary = false

  private var myPopup: ChooseByNamePopup = _

  private def createPopup(model: ChooseByNameModel): ChooseByNamePopup = {
    if (myPopup == null) {
      myPopup = ChooseByNamePopup.createPopup(getProject, model, /*context*/ null: PsiElement, "")
    }
    myPopup
  }

  override def tearDown(): Unit = {
    if (myPopup != null) {
      myPopup.close(false)
      myPopup.dispose()
      myPopup = null
    }
    super.tearDown()
  }

  private def gotoClassElements(text: String): Set[Any] = getPopupElements(new GotoClassModel2(getProject), text)

  private def gotoSymbolElements(text: String): Set[Any] = getPopupElements(new GotoSymbolModel2(getProject), text)

  private def getPopupElements(model: ChooseByNameModel, text: String): Set[Any] = {
    calcPopupElements(createPopup(model), text)
  }

  private def calcPopupElements(popup: ChooseByNamePopup, text: String): Set[Any] = {
    val semaphore = new Semaphore(1)
    var result: Set[Any] = null
    popup.scheduleCalcElements(text, false, ModalityState.NON_MODAL, SelectMostRelevant.INSTANCE, set => {
      result = set.asScala.toSet
      semaphore.up()
    })
    val start = System.currentTimeMillis()
    while (!semaphore.waitFor(10) && System.currentTimeMillis() - start < 10000000) {
      PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
    }
    result
  }

  private def checkContainExpected(elements: Set[Any],
                                   expected: (Any => Boolean, String)*): Unit = for {
    (predicate, expectedName) <- expected

    if elements.find(predicate).forall(!hasExpectedName(_, expectedName))
  } fail(s"Element not found: $expectedName, found: $elements")

  private def checkSize(elements: Set[Any], expectedSize: Int): Unit = assertEquals(
    s"Wrong number of elements found, found: $elements",
    expectedSize,
    elements.size
  )

  def testTrait(): Unit = {
    getFixture.addFileToProject("GoToClassSimpleTrait.scala", "trait GoToClassSimpleTrait")

    val elements = gotoClassElements("GoToClassS")

    checkSize(elements, 1)
    checkContainExpected(elements, (isTrait, "GoToClassSimpleTrait"))
  }

  def testTrait2(): Unit = {
    getFixture.addFileToProject("GoToClassSimpleTrait.scala", "trait GoToClassSimpleTrait")

    val elements = gotoClassElements("GTCS")

    checkSize(elements, 1)
    checkContainExpected(elements, (isTrait, "GoToClassSimpleTrait"))
  }

  def testObject(): Unit = {
    getFixture.addFileToProject("GoToClassSimpleObject.scala", "object GoToClassSimpleObject")

    val elements = gotoClassElements("GoToClassS")

    checkSize(elements, 1)
    checkContainExpected(elements, (isObject, "GoToClassSimpleObject$"))
  }

  def testPackageObject(): Unit = {
    getFixture.addFileToProject("foo/somePackageName/package.scala",
    """package foo
      |
      |package object somePackageName
    """.stripMargin)

    val elements = gotoClassElements("someP")

    checkSize(elements, 1)
    checkContainExpected(elements, (isPackageObject, "foo.somePackageName.package$"))
  }

  def testGoToSymbol(): Unit = {
    getFixture.addFileToProject("GoToSymbol.scala",
      """class FooClass {
        |  def fooMethod(): Unit = ()
        |}
        |
        |trait FooTrait {
        |  def fooMethod(): Unit
        |}
      """.stripMargin)

    val elements = gotoSymbolElements("foo")
    checkContainExpected(
      elements,
      (isClass, "FooClass"),
      (isTrait, "FooTrait"),
      (isFunction, "fooMethod"),
      (isFunction, "fooMethod")
    )
  }

  def testClass_:::(): Unit = {
    getFixture.addFileToProject("Semicolons.scala", "class ::: { def ::: : Unit = () }")

    val elements = gotoClassElements("::")

    checkSize(elements, 1)
    checkContainExpected(elements, (isClass, Colon + Colon + Colon))
  }

  def testSymbol_:::(): Unit = {
    getFixture.addFileToProject("Semicolons.scala", "class ::: { def ::: : Unit = () }")

    val elements = gotoSymbolElements("::")

    checkSize(elements, 2)
    checkContainExpected(elements, (isClass, Colon + Colon + Colon), (isFunction, Colon + Colon + Colon))
  }
}

object GoToClassAndSymbolTest {

  private val Colon = "$colon"
}