package santa

import scala.util.Try

import io.circe.Json
import scorex.util.encode.Base16

import sigma.VersionContext
import sigma.ast.JitCost
import sigmastate.interpreter.{CErgoTreeEvaluator, CostAccumulator}
import sigmastate.interpreter.CErgoTreeEvaluator.DefaultEvalSettings

/** Anchors the runtime-type families: counts and versions; every non-control row is refused and every control
  * returns; each refusal's MECHANISM is the type-resolution failure (`Don't know how to convert SType … to RType`)
  * and nothing else; every root is a writable type (the wrapping that keeps a lenient answer from hiding). */
class AuthoredEvalRuntimeTypeSitesTest extends munit.FunSuite {
  private val B = AuthoredEvalRuntimeTypeSites
  private lazy val v = B.extract()
  private def entries(op: String): List[Json] =
    v(op).hcursor.downField("entries").as[List[Json]].fold(e => fail(s"entries: $e"), identity)
  private def errored(e: Json): Boolean =
    e.hcursor.downField("expected").get[String]("error").toOption.contains("errored")

  test("v6: 228 rows (30 controls), v5: 72 rows (10 controls); every non-control refused, every control returns") {
    assertEquals(B.Rows.count(_.v6), 228)
    assertEquals(B.Rows.count(!_.v6), 72)
    B.Rows.foreach(r => assertEquals(r.accept, r.control, s"${r.op} ${r.id}"))
    Seq((B.Op6, 3, 3), (B.Op5, 2, 0)).foreach { case (op, act, tv) =>
      assertEquals(entries(op).count(e => !errored(e)), B.Rows.count(r => r.op == op && r.control), op)
      entries(op).foreach { e =>
        assertEquals(e.hcursor.downField("version").get[Int]("activated").toOption, Some(act))
        assertEquals(e.hcursor.downField("version").get[Int]("ergoTree").toOption, Some(tv))
      }
    }
  }

  test("THE MECHANISM: every refusal is the runtime-type resolution, at parse or at evaluation") {
    B.Rows.filterNot(_.accept).foreach { r =>
      val c = Try {
        val tree = sigma.santa.LenientErgoTree.deserialize(Base16.decode(r.treeHex).get)
        VersionContext.withVersions(r.activated, tree.version) {
          val ctx = EvalCore.contextWithVar1(tree, r.activated, EvalCore.decodeBinding(B.IntInput))
          val acc = new CostAccumulator(JitCost.fromBlockCost(0), Some(JitCost.fromBlockCost(1000000)))
          CErgoTreeEvaluator.eval(ctx.toSigmaContext(), acc, tree.constants,
            tree.toProposition(replaceConstants = false), DefaultEvalSettings)
        }
      }.fold(t => Iterator.iterate(t)(_.getCause).takeWhile(_ != null)
        .map(c => c.getClass.getName + ": " + Option(c.getMessage).getOrElse("")).mkString(" <- "),
        res => fail(s"${r.op} ${r.id}: expected a throw, got $res"))
      assert(c.contains("Don't know how to convert SType") && c.contains("to RType"),
        s"${r.op} ${r.id}: refused for another reason: ${c.take(300)}")
    }
  }

  test("every root is writable: a collection is sized, an option tested, a box valued") {
    B.Rows.foreach { r =>
      val head = r.body.take(2)
      assert(Set("b1", "e6", "c1")(head), s"${r.id}: the root ${r.body.take(8)} is not a writable consumer")
    }
  }

  test("envelope + write staging files") {
    B.Ops.foreach(op => assertEquals(v(op).hcursor.get[String]("source").toOption, Some(B.Source)))
    val outDir = java.nio.file.Paths.get("target", "eval-authored")
    B.writeVectors(outDir)
    B.Ops.foreach(op => assert(java.nio.file.Files.exists(outDir.resolve(s"$op.json"))))
  }
}
