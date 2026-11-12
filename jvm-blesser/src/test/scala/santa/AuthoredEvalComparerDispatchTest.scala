package santa

import scala.util.Try

import io.circe.Json
import scorex.util.encode.Base16

import sigma.VersionContext
import sigma.ast.{FixedCost, JitCost}
import sigma.data.DataValueComparer
import sigmastate.interpreter.{CErgoTreeEvaluator, CostAccumulator}
import sigmastate.interpreter.CErgoTreeEvaluator.DefaultEvalSettings

/** Anchors the two comparer-dispatch families. `SpecExtract` already fails loud if an accept does not evaluate or
  * a reject does; pinned here is what would rot silently while still grading green:
  *
  *  - each tree: the probe's rows verbatim, the extras at the hex the spike measured; each row's binding;
  *  - every returned value and cost;
  *  - each refusal's MECHANISM, from the full cause chain of a direct evaluation: K1-K5 the comparer's own
  *    `sys.error`, S0 the operand check's `Unknown type SString` (and NOT the `InterpreterException`);
  *  - THE LEFT DECIDES THE CHARGE: for each lie and its mirror, X1 − X2 = k − EQ_Prim with k the left case's
  *    constant read from the jar's `DataValueComparer`, and the String case's three costs.
  */
class AuthoredEvalComparerDispatchTest extends munit.FunSuite {
  private val B = AuthoredEvalComparerDispatch
  private lazy val v = B.extract()
  private def entries(op: String): List[Json] =
    v(op).hcursor.downField("entries").as[List[Json]].fold(e => fail(s"entries: $e"), identity)
  private def all: List[Json] = entries(B.OpIndexOf) ++ entries(B.OpEq)
  private def str(e: Json, f: String): String = e.hcursor.get[String](f).toOption.getOrElse(fail(f))
  private def id(e: Json): String = str(e, "script").takeWhile(_ != ']').drop(1)
  private def row(i: String): Json = all.find(id(_) == i).getOrElse(fail(s"no row $i"))
  private def cost(i: String): Long =
    row(i).hcursor.downField("expected").get[Long]("cost").toOption.getOrElse(fail(s"$i has no cost"))
  private def errored(e: Json): Boolean =
    e.hcursor.downField("expected").get[String]("error").toOption.contains("errored")

  private def chain(r: B.Row): String = {
    val tree = sigma.santa.LenientErgoTree.deserialize(Base16.decode(r.treeHex).get)
    Try(VersionContext.withVersions(B.V3, tree.version) {
      val ctx = EvalCore.contextWithVar1(tree, B.V3, EvalCore.decodeBinding(r.input))
      val acc = new CostAccumulator(JitCost.fromBlockCost(0), Some(JitCost.fromBlockCost(1000000)))
      CErgoTreeEvaluator.eval(ctx.toSigmaContext(), acc, tree.constants,
        tree.toProposition(replaceConstants = false), DefaultEvalSettings)
    }).fold(t => Iterator.iterate(t)(_.getCause).takeWhile(_ != null)
      .map(c => c.getClass.getName + ": " + Option(c.getMessage).getOrElse("")).mkString(" <- "),
      res => fail(s"${r.id}: expected a throw, got $res"))
  }

  test("55 rows: 18 indexOf (no refusal) / 37 EQ (6 refusals), all v3 at activated 3; the H rows bind the header") {
    assertEquals(entries(B.OpIndexOf).size, 18)
    assertEquals(entries(B.OpEq).size, 37)
    assertEquals(entries(B.OpIndexOf).count(errored), 0)
    assertEquals(entries(B.OpEq).count(errored), 6)
    all.foreach { e =>
      assertEquals(e.hcursor.downField("version").get[Int]("ergoTree").toOption, Some(3), str(e, "name"))
      assertEquals(e.hcursor.downField("version").get[Int]("activated").toOption, Some(3), str(e, "name"))
      val want = if (Set("H1", "H1c", "H2")(id(e))) B.HeaderInput else B.IntInput
      assertEquals(e.hcursor.downField("input").focus, Some(want), str(e, "name"))
    }
    // the four probe rows the corpus already holds are left out, by their trees
    val leftOut = Set("0b15dc0c1adb0c0e0e0301020302950100040205020400", "0b16dc0c1a83030404000402040402950100040205020400",
      "0b0a93950100040205020402", "0b12938602950100040205020404860204020404")
    assert(all.forall(e => !leftOut(str(e, "tree_bytes_hex"))))
  }

  test("the probe's rows verbatim, the extras at the hex the spike measured") {
    val want = Map(
      "O3" -> "0b14dc0c1a830128e3010402950100e3010405020400", "O3c" -> "0b0fdc0c1a830128e3010402e301040400",
      "H1" -> "0b1a938602e4e3016804049501008602e4e301680404860205020404",
      "H1c" -> "0b11938602e4e3016804048602e4e301680404",
      "H2" -> "0b1a939501008602e4e3016804048602050204048602e4e301680404",
      "P1" -> "0b1a938602db6503fe04049501008602db6503fe0404860205020404",
      "P1c" -> "0b11938602db6503fe04048602db6503fe0404",
      "P2" -> "0b1a939501008602db6503fe04048602050204048602db6503fe0404",
      "V1" -> "0b14938602a604049501008602a60404860205020404", "V1c" -> "0b0b938602a604048602a60404",
      "V2" -> "0b14939501008602a604048602050204048602a60404",
      "O1" -> "0b18938602e3010404049501008602e301040404860205020404", "O1c" -> "0b0f938602e3010404048602e301040404",
      "O2" -> "0b18939501008602e3010404048602050204048602e301040404")
    B.Rows.foreach { r =>
      r.src match {
        case Left(hex) => assertEquals(str(row(r.id), "tree_bytes_hex"), hex, s"${r.id}: the probe's tree, verbatim")
        case Right(_)  => assertEquals(str(row(r.id), "tree_bytes_hex"), want(r.id), r.id)
      }
    }
    assertEquals(B.Rows.count(_.src.isRight), want.size)
  }

  test("every returned value and cost") {
    val T = Json.True; val F = Json.False; def I(n: Int) = Json.fromInt(n)
    val want: Map[String, (Json, Long)] = Map(
      "A3" -> (I(0), 87), "A3c" -> (I(0), 72), "A4" -> (I(0), 67),
      "D1" -> (I(-1), 95), "D1c" -> (I(0), 85), "D2" -> (I(-1), 89), "D2c" -> (I(0), 74),
      "D3" -> (I(-1), 105), "D3c" -> (I(0), 127), "D4" -> (I(-1), 108), "D4c" -> (I(0), 119),
      "D5" -> (I(-1), 261), "D5c" -> (I(0), 251), "D6" -> (I(-1), 92), "D7" -> (I(-1), 87), "D8" -> (I(-1), 87),
      "O3" -> (I(-1), 93), "O3c" -> (I(0), 86),
      "B3" -> (T, 75), "B3c" -> (T, 60), "B4" -> (T, 75), "B5" -> (F, 75), "B6" -> (F, 72), "B7" -> (T, 75),
      "B8" -> (F, 72), "B9" -> (T, 150), "B9c" -> (T, 135),
      "C1" -> (F, 74), "C1c" -> (T, 62), "C2" -> (F, 72), "C3" -> (F, 246), "C4" -> (F, 77),
      "H1" -> (F, 95), "H1c" -> (T, 103), "H2" -> (F, 92), "P1" -> (F, 88), "P1c" -> (T, 91), "P2" -> (F, 87),
      "V1" -> (F, 85), "V1c" -> (T, 83), "V2" -> (F, 82), "O1" -> (F, 78), "O1c" -> (T, 74), "O2" -> (F, 77),
      "S1" -> (T, 35), "S2" -> (F, 32), "S3" -> (F, 15), "S4" -> (F, 50), "S5" -> (F, 52))
    val accepts = B.Rows.filter(_.accept)
    assertEquals(accepts.map(_.id).toSet, want.keySet)
    accepts.foreach { r =>
      val x = row(r.id).hcursor.downField("expected")
      assertEquals(x.downField("value").downField("value").focus, Some(want(r.id)._1), r.id)
      assertEquals(x.get[Long]("cost").toOption, Some(want(r.id)._2), r.id)
    }
  }

  test("THE MECHANISM: K1-K5 the comparer's sys.error; S0 the operand check's own RuntimeException") {
    val refusals = B.Rows.filterNot(_.accept)
    assertEquals(refusals.map(_.id).toSet, Set("K1", "K2", "K3", "K4", "K5", "S0"))
    refusals.filter(_.id.startsWith("K")).foreach { r =>
      val c = chain(r)
      assert(c.startsWith("java.lang.RuntimeException: Cannot compare") && c.contains("unknown type"),
        s"${r.id} must throw from the comparer's default case, got: ${c.take(200)}")
    }
    val s0 = chain(B.Rows.find(_.id == "S0").get)
    assert(s0.startsWith("java.lang.RuntimeException: Unknown type SString"), s"S0, got: ${s0.take(200)}")
    assert(!s0.contains("InterpreterException"), s"S0 must not be checkType's InterpreterException: ${s0.take(200)}")
  }

  test("THE LEFT DECIDES THE CHARGE: lie − mirror = the left case's constant − EQ_Prim, read from the jar") {
    def k(c: FixedCost): Long = c.cost.value.toLong
    val prim = k(DataValueComparer.CostKind_EQ_Prim)
    assertEquals(prim, 3L)
    Seq(
      ("C1", "C2", k(DataValueComparer.CostKind_EQ_BigInt)),
      ("C3", "C4", k(DataValueComparer.CostKind_EQ_GroupElement)),
      ("H1", "H2", k(DataValueComparer.CostKind_EQ_Header)),
      ("P1", "P2", k(DataValueComparer.CostKind_EQ_PreHeader)),
      ("V1", "V2", k(DataValueComparer.CostKind_EQ_AvlTree)),
      ("O1", "O2", k(DataValueComparer.CostKind_EQ_Option)),
      ("S4", "S5", k(DataValueComparer.CostKind_MatchType))
    ).foreach { case (lie, mirror, kk) =>
      assertEquals(cost(lie) - cost(mirror), kk - prim, s"$lie − $mirror")
    }
    // the String case: the Int item after an equal String (S1 − S2), and EQ_COA_Short over one char, which the
    // length bail skips (S2 − S3)
    val coa = DataValueComparer.CostKind_EQ_COA_Short
    assertEquals(cost("S1") - cost("S2"), prim)
    assertEquals(cost("S2") - cost("S3"), (coa.baseCost.value + coa.perChunkCost.value).toLong)
  }

  test("envelope + write staging files") {
    List(B.OpIndexOf, B.OpEq).foreach { op =>
      assertEquals(v(op).hcursor.get[String]("blessed_by").toOption, Some(B.Stamp))
      assertEquals(v(op).hcursor.get[String]("source").toOption, Some(B.Source))
    }
    val outDir = java.nio.file.Paths.get("target", "eval-authored")
    B.writeVectors(outDir)
    List(B.OpIndexOf, B.OpEq).foreach(op => assert(java.nio.file.Files.exists(outDir.resolve(s"$op.json"))))
  }
}
