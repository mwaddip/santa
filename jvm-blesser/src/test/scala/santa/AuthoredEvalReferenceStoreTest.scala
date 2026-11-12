package santa

import scala.util.Try

import io.circe.Json
import scorex.util.encode.Base16

import sigma.VersionContext
import sigmastate.interpreter.{CErgoTreeEvaluator, CostAccumulator}
import sigmastate.interpreter.CErgoTreeEvaluator.DefaultEvalSettings
import sigma.ast.JitCost

/** Anchors the three reference-store families. `SpecExtract` already fails loud if an accept does not evaluate
  * or a reject does; pinned here is what would rot silently while still grading green:
  *
  *  - each tree: the probe's rows verbatim, the rows built here at the hex the spike measured;
  *  - every returned value and cost;
  *  - each refusal's MECHANISM, from the FULL cause chain of a direct evaluation (the eval tier's error keeps
  *    only the top-level `InvocationTargetException`): the item's class in the `ArrayStoreException`, the
  *    unbox's `ClassCastException`, the `PairType` cast, the closure's type check, the Append node's arraycopy.
  */
class AuthoredEvalReferenceStoreTest extends munit.FunSuite {
  private val B = AuthoredEvalReferenceStore
  private lazy val v = B.extract()
  private def entries(op: String): List[Json] =
    v(op).hcursor.downField("entries").as[List[Json]].fold(e => fail(s"entries: $e"), identity)
  private def str(e: Json, f: String): String = e.hcursor.get[String](f).toOption.getOrElse(fail(f))
  private def id(e: Json): String = str(e, "script").takeWhile(_ != ']').drop(1)
  private def row(op: String, i: String): Json = entries(op).find(id(_) == i).getOrElse(fail(s"no row $i"))
  private def errored(e: Json): Boolean =
    e.hcursor.downField("expected").get[String]("error").toOption.contains("errored")

  private val input = Json.obj("kind" -> Json.fromString("Int"), "value" -> Json.fromInt(0))
  private def chain(treeHex: String): String = {
    val tree = sigma.santa.LenientErgoTree.deserialize(Base16.decode(treeHex).get)
    Try(VersionContext.withVersions(B.V3, tree.version) {
      val ctx = EvalCore.contextWithVar1(tree, B.V3, EvalCore.decodeBinding(input))
      val acc = new CostAccumulator(JitCost.fromBlockCost(0), Some(JitCost.fromBlockCost(1000000)))
      CErgoTreeEvaluator.eval(ctx.toSigmaContext(), acc, tree.constants,
        tree.toProposition(replaceConstants = false), DefaultEvalSettings)
    }).fold(t => Iterator.iterate(t)(_.getCause).takeWhile(_ != null)
      .map(c => c.getClass.getName + ": " + Option(c.getMessage).getOrElse("")).mkString(" <- "),
      r => fail(s"expected a throw, got $r"))
  }

  test("48 rows: 40 / 4 / 4, with 18 / 2 / 2 refusals; every root a SizeOf at tree v3") {
    assertEquals(entries(B.OpOps).size, 40)
    assertEquals(entries(B.OpRuntime).size, 4)
    assertEquals(entries(B.OpAppend).size, 4)
    assertEquals(entries(B.OpOps).count(errored), 18)
    assertEquals(entries(B.OpRuntime).count(errored), 2)
    assertEquals(entries(B.OpAppend).count(errored), 2)
    List(B.OpOps, B.OpRuntime, B.OpAppend).flatMap(entries).foreach { e =>
      val t = str(e, "tree_bytes_hex")
      assert(t.startsWith("0b"), str(e, "name"))
      assert(VersionContext.withVersions(B.V3, B.V3)(
        sigma.santa.LenientErgoTree.deserialize(Base16.decode(t).get).toProposition(false)).isInstanceOf[sigma.ast.SizeOf[_]],
        s"${str(e, "name")}: root must be a SizeOf")
    }
  }

  test("the rows built here are at the hex the spike measured") {
    val want = Map(
      "X1" -> "0b0eb1dc0c1483010606010102040082", "X2" -> "0b0eb1dc0c14830128e3070402040082",
      "X3" -> "0b15b1dc0c1483017001040400d9010104720102040082", "X4" -> "0b0bb1dc0c140e010102040082",
      "X5" -> "0b09b1dc0c14a402040082",
      "R1" -> "0b09b1b383006383010782", "R2" -> "0b10b1dc0c14b383006383010782020400a7",
      "R3" -> "0b16b1dc0c1495010083010404028301050502020400040a", "R4" -> "0b11b1db0c1e95010083010404028301050502",
      "P1" -> "0b0ab1b31003020406830005", "P2" -> "0b0cb1b31003020406830104040e",
      "P3" -> "0b0ab1b3830163a783010782", "P4" -> "0b09b1b3830163a7830007")
    B.Rows.foreach { r =>
      val e = row(r.op, r.id)
      r.src match {
        case Left(hex) => assertEquals(str(e, "tree_bytes_hex"), hex, s"${r.id}: the probe's tree, verbatim")
        case Right(_)  => assertEquals(str(e, "tree_bytes_hex"), want(r.id), s"${r.id}")
      }
    }
    assertEquals(B.Rows.count(_.src.isRight), want.size)
  }

  test("every returned value and cost") {
    val want: Map[String, (Int, Long)] = Map(
      "A3" -> (1, 124), "A4" -> (3, 59), "B2" -> (1, 110), "B3" -> (2, 150), "B5" -> (6, 70), "B6" -> (6, 100),
      "B7" -> (4, 90), "B8" -> (3, 85), "B10" -> (2, 110), "C2" -> (1, 135), "C3" -> (1, 95), "C5" -> (1, 175),
      "D2" -> (1, 90), "D4" -> (1, 163), "D8" -> (1, 80), "F1" -> (11, 72), "F2" -> (10, 70), "F3" -> (12, 72),
      "R1" -> (1, 86), "R4" -> (1, 80), "P2" -> (4, 66), "P4" -> (1, 86),
      "G2" -> (1, 115), "G4" -> (1, 175), "H1" -> (0, 138), "H2" -> (0, 138))
    val accepts = B.Rows.filter(_.accept)
    assertEquals(accepts.map(_.id).toSet, want.keySet)
    accepts.foreach { r =>
      val x = row(r.op, r.id).hcursor.downField("expected")
      assertEquals(x.downField("value").get[Int]("value").toOption, Some(want(r.id)._1), r.id)
      assertEquals(x.get[Long]("cost").toOption, Some(want(r.id)._2), r.id)
    }
  }

  test("THE MECHANISM of every refusal, from the oracle's own cause chain") {
    val want: Map[String, Seq[String]] = Map(
      "A5" -> Seq("ArrayStoreException: java.lang.Integer"), "B4" -> Seq("ArrayStoreException: java.lang.Integer"),
      "B9" -> Seq("ClassCastException", "PairType"),
      "D1" -> Seq("InterpreterException", "Invalid type returned by evaluator"),
      "D7" -> Seq("InterpreterException", "Invalid type returned by evaluator"),
      "X4" -> Seq("ClassCastException", "java.lang.Byte"),
      "R2" -> Seq("ArrayStoreException: sigma.data.CBox"),
      "R3" -> Seq("ClassCastException", "java.lang.Integer", "java.lang.Long"),
      "P1" -> Seq("ArrayStoreException", "can not copy long[] into int[]"),
      "P3" -> Seq("ArrayStoreException", "arraycopy"),
      "G1" -> Seq("ArrayStoreException: sigma.data.CGroupElement"),
      "G3" -> Seq("InvocationTargetException", "ArrayStoreException: sigma.data.CGroupElement"),
      "H4" -> Seq("ClassCastException", "java.lang.Long", "java.lang.Byte")) ++
      Seq("A2", "B1", "C1", "C4", "D3", "X1", "X2", "X3", "X5").map(_ -> Seq("ArrayStoreException: sigma.data.CGroupElement"))
    val refusals = B.Rows.filterNot(_.accept)
    assertEquals(refusals.map(_.id).toSet, want.keySet)
    refusals.foreach { r =>
      val c = chain(row(r.op, r.id).hcursor.get[String]("tree_bytes_hex").toOption.get)
      want(r.id).foreach(s => assert(c.contains(s), s"${r.id} must show '$s', got: ${c.take(240)}"))
    }
    // G1's store is in the MapCollection NODE's fromArray, with no reflective invoke around it
    val g1 = chain(row(B.OpOps, "G1").hcursor.get[String]("tree_bytes_hex").toOption.get)
    assert(g1.startsWith("java.lang.ArrayStoreException"), s"G1 must be unwrapped, got: ${g1.take(160)}")
  }

  test("envelope + write staging files") {
    List(B.OpOps, B.OpRuntime, B.OpAppend).foreach { op =>
      assertEquals(v(op).hcursor.get[String]("blessed_by").toOption, Some(B.Stamp))
      assertEquals(v(op).hcursor.get[String]("source").toOption, Some(B.Source))
    }
    val outDir = java.nio.file.Paths.get("target", "eval-authored")
    B.writeVectors(outDir)
    List(B.OpOps, B.OpRuntime, B.OpAppend).foreach(op => assert(java.nio.file.Files.exists(outDir.resolve(s"$op.json"))))
  }
}
