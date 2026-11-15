package santa

import scala.util.Try

import io.circe.Json
import scorex.util.encode.Base16

import sigma.VersionContext
import sigmastate.interpreter.{CErgoTreeEvaluator, CostAccumulator}
import sigmastate.interpreter.CErgoTreeEvaluator.DefaultEvalSettings
import sigma.ast.JitCost

/** Anchors the three reference-store families (probe 3's rows and their parse twins included). `SpecExtract` already fails loud if an accept does not evaluate
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
    Try {
      val tree = sigma.santa.LenientErgoTree.deserialize(Base16.decode(treeHex).get)
      VersionContext.withVersions(B.V3, tree.version) {
      val ctx = EvalCore.contextWithVar1(tree, B.V3, EvalCore.decodeBinding(input))
      val acc = new CostAccumulator(JitCost.fromBlockCost(0), Some(JitCost.fromBlockCost(1000000)))
      CErgoTreeEvaluator.eval(ctx.toSigmaContext(), acc, tree.constants,
        tree.toProposition(replaceConstants = false), DefaultEvalSettings)
    }}.fold(t => Iterator.iterate(t)(_.getCause).takeWhile(_ != null)
      .map(c => c.getClass.getName + ": " + Option(c.getMessage).getOrElse("")).mkString(" <- "),
      r => fail(s"expected a throw, got $r"))
  }

  test("99 rows: 91 / 4 / 4, with 43 / 2 / 2 refusals; the roots, at tree v3") {
    assertEquals(entries(B.OpOps).size, 91)
    assertEquals(entries(B.OpRuntime).size, 4)
    assertEquals(entries(B.OpAppend).size, 4)
    assertEquals(entries(B.OpOps).count(errored), 43)
    assertEquals(entries(B.OpRuntime).count(errored), 2)
    assertEquals(entries(B.OpAppend).count(errored), 2)
    // appended, never renumbered: the first 40 are probe 1 and 2, then probe 3's 30 rows, then its 21 parse twins
    val ids = entries(B.OpOps).map(id)
    assertEquals(ids.take(40).count(_.startsWith("3.")), 0)
    assert(ids.slice(40, 70).forall(i => i.startsWith("3.") && !i.endsWith("|parse")), ids.slice(40, 70))
    assert(ids.drop(70).forall(_.endsWith("|parse")), ids.drop(70))
    assertEquals(ids.drop(70).size, 21)
    val refusedAtParse = B.ParseRefused3 ++ B.ParseRefused3.map(_ + "|parse")
    List(B.OpOps, B.OpRuntime, B.OpAppend).flatMap(entries).foreach { e =>
      val t = str(e, "tree_bytes_hex")
      val i = id(e)
      assert(t.startsWith("0b"), str(e, "name"))
      val root = Try(VersionContext.withVersions(B.V3, B.V3)(
        sigma.santa.LenientErgoTree.deserialize(Base16.decode(t).get).toProposition(false)))
      if (refusedAtParse(i)) assert(root.isFailure, s"$i: the JVM refuses it at parse, got ${root.map(_.toString.take(80))}")
      else {
        val r = root.getOrElse(fail(s"$i: does not parse"))
        if (i.endsWith("|parse")) assert(r.isInstanceOf[sigma.ast.If[_]], s"$i: a parse twin's root is an If")
        else if (Set("3.R9", "3.R7m")(i)) assert(r.isInstanceOf[sigma.ast.EQ[_]], s"$i: root must be an EQ")
        else assert(r.isInstanceOf[sigma.ast.SizeOf[_]], s"$i: root must be a SizeOf")
      }
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
    // a parse twin wraps the bare row's root expression, header and size stripped
    assertEquals(str(row(B.OpOps, "3.X13|parse"), "tree_bytes_hex"), "0b119501010101d801d664b183006701540100")
    assertEquals(str(row(B.OpOps, "3.X1h|parse"), "tree_bytes_hex"), "0b0f9501010101d801d664b10c62020100")
    B.Rows.filter(_.id.endsWith("|parse")).foreach { t =>
      val bare = B.Rows.find(_.id == t.id.stripSuffix("|parse")).getOrElse(fail(t.id))
      assert(t.treeHex.contains("d801d664" + bare.treeHex.drop(4)), s"${t.id} must wrap ${bare.id}'s root")
    }
  }

  test("every returned value and cost") {
    def int(n: Int) = Json.obj("kind" -> Json.fromString("Int"), "value" -> Json.fromInt(n))
    val yes = Json.obj("kind" -> Json.fromString("Boolean"), "value" -> Json.True)
    val twinCost = 20L   // as every twin of MethodCall.unspecialised_call_names_v6: the else branch is never evaluated
    val want3: Map[String, (Json, Long)] = Map[String, (Json, Long)](
      "3.R1" -> (int(2), 90), "3.R3" -> (int(2), 115), "3.R2" -> (int(1), 123), "3.Q1" -> (int(2), 130),
      "3.R9" -> (yes, 147), "3.P3" -> (int(1), 115), "3.R7m" -> (yes, 167), "3.X11" -> (int(1), 75),
      "3.X9" -> (int(1), 183)) ++
      B.Rows.filter(r => r.id.endsWith("|parse") && r.accept).map(_.id -> (yes, twinCost))
    assertEquals(B.Rows.count(r => r.id.endsWith("|parse") && r.accept), 17)
    val want: Map[String, (Int, Long)] = Map(
      "A3" -> (1, 124), "A4" -> (3, 59), "B2" -> (1, 110), "B3" -> (2, 150), "B5" -> (6, 70), "B6" -> (6, 100),
      "B7" -> (4, 90), "B8" -> (3, 85), "B10" -> (2, 110), "C2" -> (1, 135), "C3" -> (1, 95), "C5" -> (1, 175),
      "D2" -> (1, 90), "D4" -> (1, 163), "D8" -> (1, 80), "F1" -> (11, 72), "F2" -> (10, 70), "F3" -> (12, 72),
      "R1" -> (1, 86), "R4" -> (1, 80), "P2" -> (4, 66), "P4" -> (1, 86),
      "G2" -> (1, 115), "G4" -> (1, 175), "H1" -> (0, 138), "H2" -> (0, 138))
    val accepts = B.Rows.filter(_.accept)
    assertEquals(accepts.map(_.id).toSet, want.keySet ++ want3.keySet)
    accepts.foreach { r =>
      val x = row(r.op, r.id).hcursor.downField("expected")
      want3.get(r.id) match {
        case Some((value, cost)) =>
          assertEquals(x.downField("value").focus, Some(value), r.id)
          assertEquals(x.get[Long]("cost").toOption, Some(cost), r.id)
        case None =>
          assertEquals(x.downField("value").get[Int]("value").toOption, Some(want(r.id)._1), r.id)
          assertEquals(x.get[Long]("cost").toOption, Some(want(r.id)._2), r.id)
      }
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
      Seq("A2", "B1", "C1", "C4", "D3", "X1", "X2", "X3", "X5").map(_ -> Seq("ArrayStoreException: sigma.data.CGroupElement")) ++
      Probe3Mechanism ++ B.ParseRefused3.map(i => s"$i|parse" -> Probe3Mechanism(i))
    val refusals = B.Rows.filterNot(_.accept)
    assertEquals(refusals.map(_.id).toSet, want.keySet)
    refusals.foreach { r =>
      val c = chain(row(r.op, r.id).hcursor.get[String]("tree_bytes_hex").toOption.get)
      want(r.id).foreach(s => assert(c.contains(s), s"${r.id} must show '$s', got: ${c.take(240)}"))
    }
    // G1's store is in the MapCollection NODE's fromArray, with no reflective invoke around it
    val g1 = chain(row(B.OpOps, "G1").hcursor.get[String]("tree_bytes_hex").toOption.get)
    assert(g1.startsWith("java.lang.ArrayStoreException"), s"G1 must be unwrapped, got: ${g1.take(160)}")
    // probe 3: a method call reaches its implementation through a reflective invoke, a node does not
    val wrapped = Set("3.R4", "3.R5", "3.R8", "3.R8e", "3.A2", "3.X7", "3.X8")
    val unwrapped = Set("3.R6", "3.P1", "3.P2", "3.P4", "3.X4", "3.X5", "3.X6", "3.X10", "3.X12", "3.X13")
    (wrapped ++ unwrapped).foreach { i =>
      val c = chain(row(B.OpOps, i).hcursor.get[String]("tree_bytes_hex").toOption.get)
      assertEquals(c.startsWith("java.lang.reflect.InvocationTargetException"), wrapped(i), s"$i: ${c.take(160)}")
    }
    assertEquals(wrapped ++ unwrapped ++ B.ParseRefused3, B.Rows.filter(r => r.id.startsWith("3.") &&
      !r.id.endsWith("|parse") && !r.accept).map(_.id).toSet)
  }

  /** Probe 3's refusals, each from the oracle's full cause chain (spike ReferenceStoreProbe3). */
  private val Probe3Mechanism: Map[String, Seq[String]] = Map(
    "3.R4" -> Seq("ClassCastException", "java.lang.Integer", "scala.runtime.BoxedUnit"),
    "3.R5" -> Seq("ArrayStoreException: java.lang.Integer"),
    "3.R6" -> Seq("ArrayStoreException", "can not copy sigma.Box[] into scala.runtime.BoxedUnit[]"),
    "3.R7" -> Seq("ConstraintFailed", "SameType2"),
    "3.R8" -> Seq("Don't know how to convert SType OV to RType"),
    "3.R8e" -> Seq("Don't know how to convert SType OV to RType"),
    "3.A2" -> Seq("Don't know how to convert SType OV to RType"),
    "3.P1" -> Seq("ArrayStoreException: sigma.data.CGroupElement"),
    "3.P2" -> Seq("ArrayStoreException: sigma.data.CGroupElement"),
    "3.P4" -> Seq("ArrayStoreException: sigma.data.CGroupElement"),
    "3.X1h" -> Seq("ValidationRule(1020", "zero-width element type SUnit"),
    "3.X1z" -> Seq("ValidationRule(1020", "zero-width element type SUnit"),
    "3.X2h" -> Seq("ValidationRule(1020", "zero-width element type SUnit"),
    "3.X4" -> Seq("InterpreterException", "Invalid type returned by evaluator", "expected type: SUnit"),
    "3.X5" -> Seq("ClassCastException", "java.lang.Integer", "scala.runtime.BoxedUnit"),
    "3.X6" -> Seq("ArrayStoreException: java.lang.Integer"),
    "3.X7" -> Seq("ArrayStoreException: java.lang.Integer"),
    "3.X8" -> Seq("ClassCastException", "java.lang.Integer", "scala.runtime.BoxedUnit"),
    "3.X10" -> Seq("Cannot find eval method def append_eval", "NoSuchMethodException"),
    "3.X12" -> Seq("Don't know how to convert SType T to RType"),
    "3.X13" -> Seq("Don't know how to convert SType T to RType"))

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
