package org.jetbrains.plugins.scala.lang.resolve

import org.jetbrains.plugins.scala.base.ScalaLightCodeInsightFixtureTestAdapter
import org.jetbrains.plugins.scala.lang.psi.api.statements.ScFunctionDefinition
import org.junit.Assert

class ExpectedTypeDrivenOverloadingResolutionTest
  extends ScalaLightCodeInsightFixtureTestAdapter
  with SimpleResolveTestBase {
  import SimpleResolveTestBase._

  def testSCL16251(): Unit = {
    val (src, _) = setupResolveTest(
      s"""
         |val xs: Array[BigInt] = Arr${REFSRC}ay(1, 2, 3)
         |""".stripMargin -> "Test.scala"
    )

    val result = src.resolve()
    result match {
      case fn: ScFunctionDefinition =>
        fn.`type`()
          .foreach(tpe => Assert.assertEquals("T => ClassTag[T] => Array[T]", tpe.presentableText))
      case _ => Assert.fail("Invalid resolve result.")
    }
  }
}
