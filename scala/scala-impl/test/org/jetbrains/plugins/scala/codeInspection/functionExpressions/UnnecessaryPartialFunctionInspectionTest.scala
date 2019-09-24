package org.jetbrains.plugins.scala.codeInspection.functionExpressions

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.testFramework.EditorTestUtil
import org.jetbrains.plugins.scala.codeInspection.ScalaQuickFixTestBase

class UnnecessaryPartialFunctionInspectionTest extends ScalaQuickFixTestBase {

  import EditorTestUtil.{SELECTION_END_TAG => END, SELECTION_START_TAG => START}

  override protected val classOfInspection: Class[_ <: LocalInspectionTool] = classOf[UnnecessaryPartialFunctionInspection]

  override protected val description: String = UnnecessaryPartialFunctionInspection.inspectionName

  val hint = UnnecessaryPartialFunctionQuickFix.hint

  private def testFix(text: String, fixed: String): Unit = testQuickFix(text, fixed, hint)

  def testInspectionCapturesSimpleExpression(): Unit = {
    val text = s"val f: Int => String = {${START}case$END x => x.toString}"
    val fixed = "val f: Int => String = (x => x.toString)"

    checkTextHasError(text)
    testFix(text, fixed)
  }

  def testInspectionCapturesSimpleExpressionWithBlankLines(): Unit = {
    val text =
      s"""val f: Int => String = {
         |  ${START}case$END x => x.toString
         |}""".stripMargin
    val fixed = "val f: Int => String = (x => x.toString)"

    checkTextHasError(text)
    testFix(text, fixed)
  }

  def testInspectionCapturesMultiLineExpression(): Unit = {
    val text =
      s"""val f: Int => String = {
         |  ${START}case$END x =>
         |    val value = x.toString
         |    s"value of x is $$value"
         |}""".stripMargin
    val fixed =
      s"""val f: Int => String = {
         |  x =>
         |    val value = x.toString
         |    s"value of x is $$value"
         |}""".stripMargin

    checkTextHasError(text)
    testFix(text, fixed)
  }

  def testInspectionCapturesSingleCaseWithMultiLineBlock(): Unit = {
    val text =
      s"""val f: Int => String = {
         |  ${START}case$END x => {
         |    val value = x.toString
         |    s"value of x is $$value"
         |  }
         |}""".stripMargin
    val fixed =
      s"""val f: Int => String = {
         |  x => {
         |    val value = x.toString
         |    s"value of x is $$value"
         |  }
         |}""".stripMargin

    checkTextHasError(text)
    testFix(text, fixed)
  }

  def testInspectionDoesNotCaptureSimpleExpressionIfItsTypeIsPartialFunction(): Unit = {
    val text = s"val f: PartialFunction[Int, String] = {case x => x.toString}"
    checkTextHasNoErrors(text)
  }

  def testInspectionDoesNotCaptureSimpleExpressionIfItsTypeIsPartialFunctionAlias(): Unit = {
    val text =
      s"""type Baz = PartialFunction[Int, String]
         |val f: Baz = {case x => x.toString}""".stripMargin
    checkTextHasNoErrors(text)
  }

  def testInspectionCapturesSimpleExpressionWithTypeConstraint(): Unit = {
    val text = s"def f: Int => String = {${START}case$END x: Int => x.toString}"
    val fixed = "def f: Int => String = { x: Int => x.toString }"

    checkTextHasError(text)
    testFix(text, fixed)
  }

  def testInspectionDoesNotCaptureCaseWithTypeConstraintMoreRestrictiveThanExpectedInputType(): Unit = {
    val text = s"def f: Any => String = {case x: Int  => x.toString}"

    checkTextHasNoErrors(text)
  }

  def testInspectionCapturesCaseWithTypeConstraintLessRestrictiveThanExpectedInputType(): Unit = {
    val text = s"def f: Int => String = {${START}case$END x: Any  => x.toString}"
    val fixed = "def f: Int => String = { x: Any => x.toString }"

    checkTextHasError(text)
    testFix(text, fixed)
  }

  def testInspectionCapturesCaseWithTypeConstraintWithTypeParameters(): Unit = {
    val text = s"def f[T]: Option[T] => String = {${START}case$END x: Option[_]  => x.toString}"
    val fixed = "def f[T]: Option[T] => String = { x: Option[_] => x.toString }"

    checkTextHasError(text)
    testFix(text, fixed)
  }

  def testInspectionCapturesSimpleExpressionWithWildcardCase(): Unit = {
    val text = s"""var f: Int => String = {${START}case$END _ => "foo"}"""
    val fixed = """var f: Int => String = (_ => "foo")"""
    checkTextHasError(text)
    testFix(text, fixed)
  }

  def testInspectionDoesNotCaptureConstantMatchingCase(): Unit = {
    val text = s"""def f: Int => String = {case 1 => "one"}"""
    checkTextHasNoErrors(text)
  }

  def testInspectionDoesNotCaptureCaseWithGuard(): Unit = {
    val text = s"""def f: Int => String = {case x if x % 2 == 0 => "one"}"""
    checkTextHasNoErrors(text)
  }

  def testInspectionDoesNotCapturePatternMatchingCase(): Unit = {
    val text = s"def f: Option[Int] => String = {case Some(x) => x.toString}"
    checkTextHasNoErrors(text)
  }

  def testInspectionDoesNotCaptureMultiCaseFunction(): Unit = {
    val text =
      """def f: Int => String = {
        |  case 1 => "one"
        |  case _ => "tilt"
        |}""".stripMargin
    checkTextHasNoErrors(text)
  }

  def testInspectionDoesNotCaptureCaseInMatchStatement(): Unit = {
    val text =
      """def f: Int => String = (x: Int) => x match {
        |  case a => "one"
        |}""".stripMargin
    checkTextHasNoErrors(text)
  }

  def testInspectionCapturesMethodArgument(): Unit = {
    val text =
      s"""def foo(bar: Int => String) = bar(42)
         |foo{${START}case$END x => x.toString}""".stripMargin
    val fixed =
      """def foo(bar: Int => String) = bar(42)
        |foo(x => x.toString)""".stripMargin
    checkTextHasError(text)
    testFix(text, fixed)
  }

  def testInspectionCapturesMethodArgumentWithTypeConstraint(): Unit = {
    val text =
      s"""def foo(bar: Int => String) = bar(42)
         |foo{${START}case$END x: Any => x.toString}""".stripMargin
    val fixed =
      """def foo(bar: Int => String) = bar(42)
        |foo { x: Any => x.toString }""".stripMargin
    checkTextHasError(text)
    testFix(text, fixed)
  }

  def testInspectionCapturesArgumentInMethodWithMultipleAruments(): Unit = {
    val text =
      s"""def foo(input: Int, bar: Int => String, prefix: String) = prefix + bar(input)
         |foo(42, {${START}case$END x => x.toString}, "value: ")""".stripMargin
    val fixed =
      """def foo(input: Int, bar: Int => String, prefix: String) = prefix + bar(input)
        |foo(42, (x => x.toString), "value: ")""".stripMargin
    checkTextHasError(text)
    testFix(text, fixed)
  }

  def testInspectionCapturesArgumentWithTypeConstraintInMethodWithMultipleAruments(): Unit = {
    val text =
      s"""def foo(input: Int, bar: Int => String, prefix: String) = prefix + bar(input)
         |foo(42, {${START}case$END x: Any => x.toString}, "value: ")""".stripMargin
    val fixed =
      """def foo(input: Int, bar: Int => String, prefix: String) = prefix + bar(input)
        |foo(42, { x: Any => x.toString }, "value: ")""".stripMargin
    checkTextHasError(text)
    testFix(text, fixed)
  }

  def testInspectionDoesNotCaptureMethodArgumentIfItsTypeIsPartialFunction(): Unit = {
    val text =
      s"""def foo(bar: PartialFunction[Int, String]) = bar(42)
         |foo{case x => x.toString}""".stripMargin
    checkTextHasNoErrors(text)
  }

  def testInspectionDoesNotCaptureMethodArgumentIfItsTypeIsPartialFunctionAlias(): Unit = {
    val text =
      s"""type Baz = PartialFunction[Int, String]
         |def foo(bar: Baz) = bar(42)
         |foo{case x => x.toString}""".stripMargin
    checkTextHasNoErrors(text)
  }

  def testInspectionTypeParameterCapture(): Unit = checkTextHasNoErrors(
    """
      |object Foo {
      |  type Opt[T] = Option[T]
      |
      |  // This doesn't show any warnings, as expected
      |  val options: Seq[Option[_]] = Seq()
      |  options map { case o: Option[t] => Unit }
      |
      |  // This warns "Unnecessary partial function", but removing the `case` keyword
      |  // means we can no longer match on the anonymous type parameter `t`
      |  val opts: Seq[Opt[_]] = Seq()
      |  opts map { case o: Opt[t] => Unit }
      |}
      |""".stripMargin
  )
}
