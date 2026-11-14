package santa

import scala.util.Try

import io.circe.Json
import scorex.util.encode.Base16

import sigma.VersionContext
import sigma.ast.JitCost
import sigmastate.interpreter.{CErgoTreeEvaluator, CostAccumulator}
import sigmastate.interpreter.CErgoTreeEvaluator.DefaultEvalSettings

/** Anchors the two charge-order families. `SpecExtract` already fails loud if an accept does not evaluate or a
  * reject does; pinned here is what would rot silently while still grading green:
  *
  *  - sigma-rust's 17 bodies, VERBATIM (the blesser rebuilds them from shared pieces; this table is theirs);
  *  - the derived control equals sigma-rust's own hand-built one for the built insert rows;
  *  - every value (None) and cost, and RULE 2's equal-cost pin: a row costs exactly what its first-entry control
  *    costs, so the later entry is neither read nor charged;
  *  - RULE 1's mechanism: the reflective call's `IllegalArgumentException`, and the flag-off control None.
  */
class AuthoredEvalAvlChargeOrderTest extends munit.FunSuite {
  private val B = AuthoredEvalAvlChargeOrder
  private lazy val v = B.extract()
  private def entries(op: String): List[Json] =
    v(op).hcursor.downField("entries").as[List[Json]].fold(e => fail(s"entries: $e"), identity)
  private def str(e: Json, f: String): String = e.hcursor.get[String](f).toOption.getOrElse(fail(f))
  private def id(e: Json): String = str(e, "script").takeWhile(_ != ']').drop(1)
  private def row(op: String, i: String): Json = entries(op).find(id(_) == i).getOrElse(fail(s"no row $op $i"))
  private def cost(op: String, i: String): Long =
    row(op, i).hcursor.downField("expected").get[Long]("cost").toOption.getOrElse(fail(s"$op $i has no cost"))
  private def errored(e: Json): Boolean =
    e.hcursor.downField("expected").get[String]("error").toOption.contains("errored")

  test("sigma-rust's 17 bodies, verbatim; the derived control equals theirs") {
    val theirs: Seq[(String, String)] = Seq(
    (B.FlagOffInsert, "dc640c6415cc1ba1054bf74431a2ccfc57e6051b9d0e7561f70586e5512c0ae3f3bac4fd020001000201010e0102") /* flagoff-insert-true-entries */,
    (B.FlagOffUpdate, "dc640d6415cc1ba1054bf74431a2ccfc57e6051b9d0e7561f70586e5512c0ae3f3bac4fd02000100020c3c0e0e000101") /* flagoff-update-entries-true */,
    (B.FlagOffRemove, "dc640e6415cc1ba1054bf74431a2ccfc57e6051b9d0e7561f70586e5512c0ae3f3bac4fd020001000201010e0102") /* flagoff-remove-true-keys */,
    (B.FlagOffIou, "dc64106415cc1ba1054bf74431a2ccfc57e6051b9d0e7561f70586e5512c0ae3f3bac4fd02000100020c3c0e0e000101") /* flagoff-iou-entries-true */,
    (B.FlagOffZero, "dc640c640000000000000000000000000000000000000000000000000000000000000000000020000201010e0102") /* flagoff-zero-insert-true */,
    (B.OtherInsInt, "dc640c647ca99024833a7c538e35710c94acf164ca93693117acee3a5bfdd3035810ee110107010002483c0e0e3c100e04010308000000000000001e01040105000e550385ab460a6564d1e5ded17716cd5866650d7fbb9cada7fecabd5fe21e2f80e43a039d4d9434aa05d4dc888d939d6771fe05418df5bd91101dbe63384514c1869e140202ff00000008000000000000001400010400") /* insert|other-tree|entries-second-key-Coll[Int] */,
    (B.OtherInsNo, "dc640c647ca99024833a7c538e35710c94acf164ca93693117acee3a5bfdd3035810ee110107010002483c0e0e0404010308000000000000001e0a000e550385ab460a6564d1e5ded17716cd5866650d7fbb9cada7fecabd5fe21e2f80e43a039d4d9434aa05d4dc888d939d6771fe05418df5bd91101dbe63384514c1869e140202ff00000008000000000000001400010400") /* insert|other-tree|entries-second-no-pair */,
    (B.OtherUpdInt, "dc640d647ca99024833a7c538e35710c94acf164ca93693117acee3a5bfdd3035810ee110107010002483c0e0e3c100e04010108000000000000000b01040105000e550385ab460a6564d1e5ded17716cd5866650d7fbb9cada7fecabd5fe21e2f80e43a02010200000008000000000000000a03fc29fc93353c639f7cf275ea69851dfa41d720ed53bcc9c10bda17166966d5ed00010402") /* update|other-tree|entries-second-key-Coll[Int] */,
    (B.OtherUpdNo, "dc640d647ca99024833a7c538e35710c94acf164ca93693117acee3a5bfdd3035810ee110107010002483c0e0e0404010108000000000000000b0a000e550385ab460a6564d1e5ded17716cd5866650d7fbb9cada7fecabd5fe21e2f80e43a02010200000008000000000000000a03fc29fc93353c639f7cf275ea69851dfa41d720ed53bcc9c10bda17166966d5ed00010402") /* update|other-tree|entries-second-no-pair */,
    (B.OtherIouInt, "dc6410647ca99024833a7c538e35710c94acf164ca93693117acee3a5bfdd3035810ee110107010002483c0e0e3c100e04010308000000000000001e01040105000e550385ab460a6564d1e5ded17716cd5866650d7fbb9cada7fecabd5fe21e2f80e43a039d4d9434aa05d4dc888d939d6771fe05418df5bd91101dbe63384514c1869e140202ff00000008000000000000001400010400") /* insertOrUpdate|other-tree|entries-second-key-Coll[Int] */,
    (B.OtherIouNo, "dc6410647ca99024833a7c538e35710c94acf164ca93693117acee3a5bfdd3035810ee110107010002483c0e0e0404010308000000000000001e0a000e550385ab460a6564d1e5ded17716cd5866650d7fbb9cada7fecabd5fe21e2f80e43a039d4d9434aa05d4dc888d939d6771fe05418df5bd91101dbe63384514c1869e140202ff00000008000000000000001400010400") /* insertOrUpdate|other-tree|entries-second-no-pair */,
    (B.BuiltInsInt, "dc640c6415cc1ba1054bf74431a2ccfc57e6051b9d0e7561f70586e5512c0ae3f3bac4fd0207010002483c0e0e3c100e04010108000000000000000a01040105000e550385ab460a6564d1e5ded17716cd5866650d7fbb9cada7fecabd5fe21e2f80e43a039d4d9434aa05d4dc888d939d6771fe05418df5bd91101dbe63384514c1869e140202ff00000008000000000000001400010400") /* built-insert-first-fails-second-int */,
    (B.BuiltInsNo, "dc640c6415cc1ba1054bf74431a2ccfc57e6051b9d0e7561f70586e5512c0ae3f3bac4fd0207010002483c0e0e0404010108000000000000000a0a000e550385ab460a6564d1e5ded17716cd5866650d7fbb9cada7fecabd5fe21e2f80e43a039d4d9434aa05d4dc888d939d6771fe05418df5bd91101dbe63384514c1869e140202ff00000008000000000000001400010400") /* built-insert-first-fails-second-nopair */,
    (B.BuiltUpdInt, "dc640d6415cc1ba1054bf74431a2ccfc57e6051b9d0e7561f70586e5512c0ae3f3bac4fd0207010002483c0e0e3c100e04010308000000000000001e01040105000e550385ab460a6564d1e5ded17716cd5866650d7fbb9cada7fecabd5fe21e2f80e43a02010200000008000000000000000a03fc29fc93353c639f7cf275ea69851dfa41d720ed53bcc9c10bda17166966d5ed00010402") /* built-update-first-fails-second-int */,
    (B.BuiltUpdNo, "dc640d6415cc1ba1054bf74431a2ccfc57e6051b9d0e7561f70586e5512c0ae3f3bac4fd0207010002483c0e0e0404010308000000000000001e0a000e550385ab460a6564d1e5ded17716cd5866650d7fbb9cada7fecabd5fe21e2f80e43a02010200000008000000000000000a03fc29fc93353c639f7cf275ea69851dfa41d720ed53bcc9c10bda17166966d5ed00010402") /* built-update-first-fails-second-nopair */,
    (B.BuiltIouInt, "dc64106415cc1ba1054bf74431a2ccfc57e6051b9d0e7561f70586e5512c0ae3f3bac4fd0207010002483c0e0e3c100e04010108000000000000000a01040105000e550385ab460a6564d1e5ded17716cd5866650d7fbb9cada7fecabd5fe21e2f80e43a039d4d9434aa05d4dc888d939d6771fe05418df5bd91101dbe63384514c1869e140202ff00000008000000000000001400010400") /* built-iou-first-fails-second-int */,
    (B.BuiltInsControlTheirs, "dc640c6415cc1ba1054bf74431a2ccfc57e6051b9d0e7561f70586e5512c0ae3f3bac4fd02070100020c3c0e0e01010108000000000000000a0e550385ab460a6564d1e5ded17716cd5866650d7fbb9cada7fecabd5fe21e2f80e43a039d4d9434aa05d4dc888d939d6771fe05418df5bd91101dbe63384514c1869e140202ff00000008000000000000001400010400") /* built-insert-first-fails-control */)
    theirs.foreach { case (mine, hex) => assertEquals(mine, hex) }
    assertEquals(B.firstEntryControl(B.BuiltInsInt), B.BuiltInsControlTheirs)
    assertEquals(B.firstEntryControl(B.BuiltInsNo), B.BuiltInsControlTheirs)
    val bodies = B.Rows6.map(_.body).toSet
    theirs.foreach { case (_, hex) => assert(bodies(hex), s"a body of theirs is missing: ${hex.take(40)}") }
  }

  test("v6 27 rows (5 refusals), v5 14 rows (4 refusals); versions; every accept is None") {
    Seq((B.Op6, 27, 5, 3, 3), (B.Op5, 14, 4, 2, 0)).foreach { case (op, n, refused, act, tv) =>
      assertEquals(entries(op).size, n, op)
      assertEquals(entries(op).count(errored), refused, op)
      entries(op).foreach { e =>
        assertEquals(e.hcursor.downField("version").get[Int]("activated").toOption, Some(act), str(e, "name"))
        assertEquals(e.hcursor.downField("version").get[Int]("ergoTree").toOption, Some(tv), str(e, "name"))
        if (!errored(e)) assertEquals(e.hcursor.downField("expected").downField("value").focus,
          Some(Json.obj("kind" -> Json.fromString("Option"), "value" -> Json.Null)), str(e, "name"))
      }
    }
  }

  test("every cost; RULE 2: a row costs exactly what its first-entry control costs") {
    val want6 = Map("F1c" -> 34L, "F2c" -> 34L, "F3c" -> 34L, "F4c" -> 49L, "F5c" -> 34L,
      "O1" -> 234L, "O2" -> 234L, "O1c" -> 234L, "O3" -> 324L, "O4" -> 324L, "O3c" -> 324L,
      "O5" -> 339L, "O6" -> 339L, "O5c" -> 339L, "B1" -> 244L, "B2" -> 244L, "B1c" -> 244L,
      "B3" -> 344L, "B4" -> 344L, "B3c" -> 344L, "B5" -> 359L, "B5c" -> 359L)
    want6.foreach { case (i, c) => assertEquals(cost(B.Op6, i), c, i) }
    val want5 = Map("F1c" -> 34L, "F2c" -> 34L, "F3c" -> 34L, "F5c" -> 34L,
      "O3" -> 324L, "O4" -> 324L, "O3c" -> 324L, "B3" -> 344L, "B4" -> 344L, "B3c" -> 344L)
    want5.foreach { case (i, c) => assertEquals(cost(B.Op5, i), c, i) }
    // the flag check is isInsertAllowed's (and for insertOrUpdate isUpdateAllowed's too) fixed cost, read off the jar
    val k = sigma.ast.SAvlTreeMethods.isInsertAllowed_Info.costKind.asInstanceOf[sigma.ast.FixedCost].cost.value.toLong
    assertEquals(cost(B.Op6, "F4c") - cost(B.Op6, "F1c"), k)
  }

  test("RULE 1's mechanism: the reflective call refuses the Boolean (IllegalArgumentException)") {
    B.Rows.filterNot(_.accept).foreach { r =>
      val c = Try {
        val tree = sigma.santa.LenientErgoTree.deserialize(Base16.decode(r.treeHex).get)
        VersionContext.withVersions(r.activated, tree.version) {
          val ctx = EvalCore.contextWithVar1(tree, r.activated, EvalCore.decodeBinding(B.IntInput))
          val acc = new CostAccumulator(JitCost.fromBlockCost(0), Some(JitCost.fromBlockCost(1000000)))
          CErgoTreeEvaluator.eval(ctx.toSigmaContext(), acc, tree.constants,
            tree.toProposition(replaceConstants = false), DefaultEvalSettings)
        }
      }.fold(t => Iterator.iterate(t)(_.getCause).takeWhile(_ != null).map(_.getClass.getName).mkString(" <- "),
        res => fail(s"${r.op} ${r.id}: expected a throw, got $res"))
      assert(c.startsWith("java.lang.IllegalArgumentException"), s"${r.op} ${r.id}: $c")
    }
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
