package santa

import scala.util.Try

import io.circe.Json
import scorex.util.encode.Base16

import sigma.VersionContext
import sigma.ast._
import sigmastate.interpreter.{CErgoTreeEvaluator, CostAccumulator}
import sigmastate.interpreter.CErgoTreeEvaluator.DefaultEvalSettings

/** Anchors the four method-type-variable-name families. `SpecExtract` already fails loud if an accept does not
  * evaluate or a reject does; pinned here is what would rot silently while still grading green:
  *
  *  - the counts, versions and tree framing (v6 = sized v3 at activated 3, v5 = unsized v0 at activated 2);
  *  - every returned value and cost;
  *  - each refusal's MECHANISM, from the cause chain of a direct evaluation: a `ser` row's SerializerException, a
  *    `plus` row's `KeyNotFoundException: key 103`, a parse row's `ConstraintFailed` on `SameType2`;
  *  - THE RULE ITSELF, from the jar: the tables' variable names, `specializeFor` leaving a call unspecialised on
  *    a mismatched name, and the reader typing `deserializeTo[Coll[T]]` as `Coll[Coll[T]]`.
  */
class AuthoredEvalMethodTypeVariableNamesTest extends munit.FunSuite {
  private val B = AuthoredEvalMethodTypeVariableNames
  private lazy val v = B.extract()
  private def entries(op: String): List[Json] =
    v(op).hcursor.downField("entries").as[List[Json]].fold(e => fail(s"entries: $e"), identity)
  private def str(e: Json, f: String): String = e.hcursor.get[String](f).toOption.getOrElse(fail(f))
  private def id(e: Json): String = str(e, "script").takeWhile(_ != ']').drop(1)
  private def row(op: String, i: String): Json = entries(op).find(id(_) == i).getOrElse(fail(s"no row $op $i"))
  private def errored(e: Json): Boolean =
    e.hcursor.downField("expected").get[String]("error").toOption.contains("errored")

  private def chain(r: B.Row): String =
    Try {
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

  test("87 rows: Coll v6 42 (14 refusals), Option v6 15 (6), Coll v5 18 (7), Option v5 12 (5); versions + framing") {
    val want = Map(B.OpColl6 -> (42, 14, 3, 3), B.OpOpt6 -> (15, 6, 3, 3),
      B.OpColl5 -> (18, 7, 2, 0), B.OpOpt5 -> (12, 5, 2, 0))
    want.foreach { case (op, (n, refused, act, tv)) =>
      assertEquals(entries(op).size, n, op)
      assertEquals(entries(op).count(errored), refused, op)
      entries(op).foreach { e =>
        assertEquals(e.hcursor.downField("version").get[Int]("activated").toOption, Some(act), str(e, "name"))
        assertEquals(e.hcursor.downField("version").get[Int]("ergoTree").toOption, Some(tv), str(e, "name"))
        assertEquals(e.hcursor.downField("input").focus, Some(B.IntInput), str(e, "name"))
      }
    }
    B.Rows.foreach { r =>
      val tree = str(row(r.op, r.id), "tree_bytes_hex")
      if (r.v6) assert(tree.startsWith("0b") && tree.endsWith(r.body), s"${r.id}: sized v3 around the body")
      else assertEquals(tree, "00" + r.body, s"${r.id}: unsized v0 around the body")
    }
    // sigma-rust's 57 bodies, each once
    assertEquals((B.Coll6 ++ B.Opt6).map(_.body).distinct.size, 57)
  }

  test("every returned value and cost") {
    def bytes(xs: Int*): Json = Json.arr(xs.map(x => Json.obj("kind" -> Json.fromString("Byte"),
      "value" -> Json.fromInt(x))): _*)
    def int(n: Int): Json = Json.fromInt(n)
    val T = Json.True
    val want: Map[(String, String), (Json, Long)] = Map(
      (B.OpColl6, "P3") -> (bytes(4, 6, 2, 2, 4), 105), (B.OpColl6, "P4") -> (int(4), 126),
      (B.OpColl6, "P7") -> (bytes(4, 6, 2, 2, 4), 105), (B.OpColl6, "P8") -> (int(4), 126),
      (B.OpColl6, "P9") -> (bytes(4, 6, 2, 2, 4), 90), (B.OpColl6, "P10") -> (int(4), 111),
      (B.OpColl6, "U3") -> (bytes(3, 6, 14, 4), 86), (B.OpColl6, "U4") -> (int(4), 110),
      (B.OpColl6, "U7") -> (bytes(3, 6, 14, 4), 86), (B.OpColl6, "U8") -> (int(4), 110),
      (B.OpColl6, "U9") -> (bytes(3, 6, 14, 4), 71), (B.OpColl6, "U10") -> (int(4), 95),
      (B.OpColl6, "M3") -> (bytes(3, 6, 2, 4), 87), (B.OpColl6, "M4") -> (int(4), 111),
      (B.OpColl6, "M7") -> (bytes(3, 6, 2, 4), 87), (B.OpColl6, "M8") -> (int(4), 111),
      (B.OpColl6, "M9") -> (bytes(3, 6, 2, 4), 72), (B.OpColl6, "M10") -> (int(4), 96),
      (B.OpColl6, "Z2") -> (int(4), 105), (B.OpColl6, "Z3") -> (bytes(2, 6, 6, 2, 2), 59),
      (B.OpColl6, "Z4") -> (int(4), 90),
      (B.OpColl6, "R1") -> (bytes(3, 4, 2, 6), 62), (B.OpColl6, "R2") -> (int(3), 86),
      (B.OpColl6, "G1") -> (bytes(1, 2), 67), (B.OpColl6, "G2") -> (int(2), 79),
      (B.OpColl6, "X3") -> (T, 20), (B.OpColl6, "X6") -> (T, 20), (B.OpColl6, "X7") -> (T, 20),
      (B.OpOpt6, "A1") -> (bytes(1, 14), 96), (B.OpOpt6, "A2") -> (int(8), 108),
      (B.OpOpt6, "A5") -> (bytes(1, 14), 81), (B.OpOpt6, "A6") -> (int(8), 93),
      (B.OpOpt6, "F1") -> (bytes(1, 2), 96), (B.OpOpt6, "F2") -> (int(2), 108),
      (B.OpOpt6, "F5") -> (bytes(1, 2), 81), (B.OpOpt6, "F6") -> (int(2), 93),
      (B.OpOpt6, "X4") -> (T, 20),
      (B.OpColl5, "P4") -> (int(4), 126), (B.OpColl5, "P8") -> (int(4), 126), (B.OpColl5, "P10") -> (int(4), 111),
      (B.OpColl5, "U4") -> (int(4), 110), (B.OpColl5, "U8") -> (int(4), 110), (B.OpColl5, "U10") -> (int(4), 95),
      (B.OpColl5, "M4") -> (int(4), 111), (B.OpColl5, "M8") -> (int(4), 111), (B.OpColl5, "M10") -> (int(4), 96),
      (B.OpColl5, "Z2") -> (int(4), 105), (B.OpColl5, "Z4") -> (int(4), 90),
      (B.OpOpt5, "A2") -> (int(8), 99), (B.OpOpt5, "A6") -> (int(8), 99),
      (B.OpOpt5, "F2") -> (int(1), 99), (B.OpOpt5, "F6") -> (int(1), 99),
      (B.OpOpt5, "N1") -> (T, 20), (B.OpOpt5, "N4") -> (T, 20), (B.OpOpt5, "N5") -> (T, 20))
    val accepts = B.Rows.filter(_.accept)
    assertEquals(accepts.map(r => (r.op, r.id)).toSet, want.keySet)
    accepts.foreach { r =>
      val x = row(r.op, r.id).hcursor.downField("expected")
      val got = x.downField("value").downField("value").focus.orElse(x.downField("value").downField("items").focus)
      assertEquals(got, Some(want((r.op, r.id))._1), s"${r.op} ${r.id}")
      assertEquals(x.get[Long]("cost").toOption, Some(want((r.op, r.id))._2), s"${r.op} ${r.id}")
    }
  }

  test("THE MECHANISM: ser → SerializerException, plus → key 103 (STypeVar), parse → ConstraintFailed SameType2") {
    B.Rows.filterNot(_.accept).foreach { r =>
      val c = chain(r)
      val ok =
        if (r.id.startsWith("X") || r.id.startsWith("N"))
          c.startsWith("sigma.exceptions.ConstraintFailed") && c.contains("SameType2")
        else if (r.slug.contains("-ser-")) c.contains("sigma.serialization.SerializerException")
        else c.contains("java.util.NoSuchElementException: key not found") || c.contains("KeyNotFoundException: key 103 was not found")
      assert(ok, s"${r.op} ${r.id} (${r.slug}) threw for another reason: ${c.take(300)}")
    }
  }

  test("THE RULE, from the jar: the tables' names, specializeFor on a mismatched name, the double substitution") {
    val IV = STypeVar("IV"); val OV = STypeVar("OV"); val Tv = STypeVar("T"); val R = STypeVar("R")
    VersionContext.withVersions(3, 3) {
      val patch = SMethod.fromIds(12.toByte, 19.toByte)
      assertEquals(patch.name, "patch")
      assertEquals(patch.stype.tDom.head, SCollection(IV))
      val zip = SMethod.fromIds(12.toByte, 29.toByte)
      assertEquals(zip.stype.tRange, SCollection(STuple(IV, OV)))
      val map = SMethod.fromIds(36.toByte, 7.toByte)
      assertEquals(map.stype.tDom, IndexedSeq(SOption(Tv), SFunc(Tv, R)))
      val filter = SMethod.fromIds(36.toByte, 8.toByte)
      assertEquals(filter.stype.tDom.head, SOption(Tv))
      // a mismatched name leaves the call unspecialised; the declared name binds
      val args = Seq(SInt, SCollection(SInt), SInt)
      assertEquals(patch.specializeFor(SCollection(Tv), args).stype.tRange, SCollection(IV))
      assertEquals(patch.specializeFor(SCollection(IV), args).stype.tRange, SCollection(SInt))
      assertEquals(map.specializeFor(SOption(IV), Seq(SFunc(SInt, SInt))).stype.tRange, SOption(R))
      assertEquals(map.specializeFor(SOption(Tv), Seq(SFunc(SInt, SInt))).stype.tRange, SOption(SInt))
    }
    // X7's receiver: deserializeTo[Coll[T]] is typed Coll[Coll[T]] by the reader
    val x7 = B.Coll6.find(_.id == "X7").get
    val tree = sigma.santa.LenientErgoTree.deserialize(Base16.decode(x7.treeHex).get)
    val root = VersionContext.withVersions(3, 3)(tree.toProposition(false))
    val eq = root.asInstanceOf[If[SType]].falseBranch.asInstanceOf[EQ[SType]]
    assertEquals(eq.right.tpe, SCollection(SCollection(Tv)))
    assertEquals(eq.left.tpe, SCollection(SCollection(Tv)))
  }

  test("envelope + write staging files") {
    B.Ops.foreach { op =>
      assertEquals(v(op).hcursor.get[String]("blessed_by").toOption, Some(B.Stamp))
      assertEquals(v(op).hcursor.get[String]("source").toOption, Some(B.Source))
    }
    val outDir = java.nio.file.Paths.get("target", "eval-authored")
    B.writeVectors(outDir)
    B.Ops.foreach(op => assert(java.nio.file.Files.exists(outDir.resolve(s"$op.json"))))
  }
}
