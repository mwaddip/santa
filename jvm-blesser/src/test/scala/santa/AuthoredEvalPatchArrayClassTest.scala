package santa

import io.circe.Json

/** Anchors the patch-array-class eval families. `SpecExtract` fails loud if an entry's outcome changes between the label
  * and the bless; pinned here is what would rot silently while still grading green:
  *
  *  - the four files, their entry counts, and each entry's version pair (its slot);
  *  - every outcome and cost agrees with the jars' own measurement (sigma-rust's probe on the same 6.0.7), an
  *    independent second reading, and so does every value but those holding OUR header's, pre-header's or
  *    LastBlockUtxoRootHash's bytes (the jars' table clips a long value: there ours must start with theirs);
  *  - the eval path's own parse refuses exactly what a parse under the row's (activated, tree version) refuses;
  *  - a tree-v0 row answers the same at activated 2 and 3;
  *  - the labels' premises: a value comes from a `ctl` patch or one of the receiver's own item type, a throw from
  *    another item type or a tuple constant; CONTROL / MOVE exactly there;
  *  - the context entries carry sigma-rust's SELF and extension, read back as a node reads them.
  */
class AuthoredEvalPatchArrayClassTest extends munit.FunSuite {
  override val munitTimeout = scala.concurrent.duration.Duration(20, "min")
  private val B = AuthoredEvalPatchArrayClass
  private lazy val rows = B.rows()
  private lazy val outcomes: Map[(String, Int), B.Outcome] = rows.map(r => (r.name, r.activated) -> B.outcome(r)).toMap
  private lazy val v = B.extract()
  private def entries(op: String): List[Json] =
    v(op).hcursor.downField("entries").as[List[Json]].fold(e => fail(s"entries: $e"), identity)
  private def str(e: Json, f: String): String = e.hcursor.get[String](f).toOption.getOrElse(fail(f))
  private def ver(e: Json, f: String): Int = e.hcursor.downField("version").get[Int](f).toOption.getOrElse(fail(f))
  private def err(e: Json): Option[String] = e.hcursor.downField("expected").get[Option[String]]("error").toOption.flatten
  private def rowName(e: Json): String = str(e, "script").drop(1).takeWhile(_ != ']')

  test("four files; versions in their slot") {
    val want = Map(
      "Coll.patch_array_class" -> 1034, "Coll.patch_array_class_v6" -> 2145,
      "Coll.patch_array_class_context" -> 349, "Coll.patch_array_class_context_v6" -> 698)
    assertEquals(v.keySet, want.keySet)
    want.foreach { case (op, n) => assertEquals(entries(op).size, n, op) }
    assertEquals(rows.size, 4226)
    assertEquals(rows.map(_.name).distinct.size, 2843)
    val byKey = rows.map(r => (r.op, r.name) -> r).toMap
    v.keys.foreach { op =>
      val a = if (op.endsWith("_v6")) 3 else 2
      assertEquals(v(op).hcursor.get[String]("schema").toOption,
        Some(if (op.contains("_context")) "santa-eval/v6-fullctx" else "santa-eval/v2"), op)
      entries(op).foreach { e =>
        assertEquals(ver(e, "activated"), a, s"$op ${str(e, "name")}")
        assert(Set(0, 3)(ver(e, "ergoTree")), s"$op ${str(e, "name")}")
        if (a == 2) assertEquals(ver(e, "ergoTree"), 0, s"$op ${str(e, "name")}: a v3 tree in the v5 slot")
        if (op.contains("_context")) {
          val r = byKey((op, rowName(e)))
          assertEquals(e.hcursor.downField("context").focus, Some(B.context(a, if (r.readsExtension) B.Ext else Map())))
          val ext = e.hcursor.downField("context").downField("extension").keys.map(_.toSet).getOrElse(Set())
          assertEquals(ext, if (r.receiver.endsWith(".getVar")) Set("1", "2") else Set[String](), r.name)
        }
        else assertEquals(e.hcursor.downField("input").focus, Some(B.IntInput))
      }
    }
    assertEquals(rows.count(r => r.inContext && r.readsExtension), 75)
  }

  test("every outcome and cost agrees with the jars' own measurement, and every value not holding our header; " +
       "on the pre-header's parentId and votes a node's moves answer the receiver unchanged") {
    val byKey = rows.map(r => (r.op, r.name) -> r).toMap
    v.keys.foreach(op => entries(op).foreach { e =>
      val r = byKey((op, rowName(e)))
      assertEquals(str(e, "tree_bytes_hex"), r.treeHex, r.name)
      assertEquals(ver(e, "ergoTree"), r.treeVersion, r.name)
      outcomes((r.name, r.activated)) match {
        case B.ThrowsAtEval(t) =>
          assertEquals(r.jarsOutcome, "throws", s"${r.name} a${r.activated}")
          assert(!r.nodeBuiltGeneric, s"${r.name}: a generic receiver throws")
          assert(B.chain(t).map(_.getClass.getSimpleName).contains(r.jarsValue), s"${r.name}: ${B.chain(t)} vs ${r.jarsValue}")
          assertEquals(err(e), Some("errored"), r.name)
        case B.Answer(value, cost) if r.nodeBuiltGeneric && r.jarsOutcome == "throws" =>
          // the jars' cost counter at the throw is the cost charged before the call, which is all the call costs
          assertEquals(cost.toString, r.jarsCost, s"${r.name} a${r.activated}")
          assertEquals(err(e), None, r.name)
          val ctl = rows.find(c => c.receiver == r.receiver && c.patch == "ctl0" && c.treeVersion == r.treeVersion &&
            c.activated == r.activated).getOrElse(fail(s"${r.name}: no ctl0"))
          outcomes((ctl.name, ctl.activated)) match {
            case B.Answer(cv, _) => assertEquals(value, cv, s"${r.name}: the receiver, unchanged")
            case o               => fail(s"${ctl.name}: ${B.said(o)}")
          }
        case B.Answer(value, cost) =>
          assertEquals(r.jarsOutcome, "value", s"${r.name} a${r.activated}")
          assertEquals(err(e), None, r.name)
          assertEquals(cost.toString, r.jarsCost, s"${r.name} a${r.activated}")
          val ours = B.text(value)
          if (r.holdsOurHeader) assertNotEquals(ours, r.jarsValue, s"${r.name}: our header's bytes, theirs differ")
          else if (r.jarsValue.endsWith(")")) assertEquals(ours, r.jarsValue, s"${r.name} a${r.activated}")
          else assert(ours.startsWith(r.jarsValue), s"${r.name} a${r.activated}: $ours does not start with ${r.jarsValue}")
        case o => fail(s"${r.name} a${r.activated}: jars ${r.jarsOutcome}, ours ${B.said(o)}")
      }
    })
    assertEquals(rows.count(r => r.nodeBuiltGeneric && r.jarsOutcome == "throws"), 48)
  }

  test("the eval path's parse refuses exactly what the node's parse refuses") {
    rows.foreach { r =>
      val explicit = outcomes((r.name, r.activated)).isInstanceOf[B.RefusedAtParse]
      val default = scala.util.Try(sigma.santa.LenientErgoTree.deserialize(scorex.util.encode.Base16.decode(r.treeHex).get))
        .fold(_ => true, _.root.isLeft)
      assertEquals(default, explicit, s"${r.name} a${r.activated}")
    }
  }

  test("a tree-v0 row answers the same at activated 2 and 3 (a value holding our header: the same cost)") {
    def key(o: B.Outcome, header: Boolean): String = o match {
      case B.Answer(value, cost) => s"${if (header) "<the header of block activated + 1>" else value.noSpaces} @$cost"
      case B.RefusedAtParse(_)   => "refused"
      case B.ThrowsAtEval(t)     => s"throws ${t.getClass.getName}"
    }
    rows.filter(_.treeVersion == 0).groupBy(_.name).foreach { case (n, rs) =>
      val h = rs.head.holdsOurHeader
      assertEquals(key(outcomes((n, 2)), h), key(outcomes((n, 3)), h), n)
    }
  }

  test("the labels' premises; CONTROL, MOVE and NODE'S CLASS exactly there") {
    rows.foreach { r =>
      val sameItem = r.patch.head == r.receiver.head
      val held = r.control || (sameItem && !r.patch.startsWith("triple"))
      outcomes((r.name, r.activated)) match {
        case B.Answer(_, _)    => assert(held || r.nodeBuiltGeneric, r.name)
        case B.ThrowsAtEval(_) => assert(!held && !r.nodeBuiltGeneric, r.name)
        case o                 => fail(s"${r.name}: ${B.said(o)}")
      }
      assertEquals(r.jarsOutcome == "value", held, s"${r.name}: the jars' table")
      assert(r.body.startsWith("dc0c13"), s"${r.name}: not a patch call")
      assert(r.body.endsWith("0400"), s"${r.name}: replaces something")
    }
    val byKey = rows.map(r => (r.op, r.name) -> r).toMap
    v.keys.foreach(op => entries(op).foreach { e =>
      val s = str(e, "script")
      val r = byKey((op, rowName(e)))
      assertEquals(s.contains("CONTROL:"), r.jarsOutcome == "value", rowName(e))
      assertEquals(s.contains("MOVE:"), err(e).contains("errored"), rowName(e))
      assertEquals(s.contains("NODE'S CLASS:"), r.nodeBuiltGeneric && r.jarsOutcome == "throws", rowName(e))
      assert(!s.matches(".*@[0-9a-f]{6,}.*"), s"${rowName(e)}: an identity hash in the script")
    })
  }

  test("the context: sigma-rust's SELF and extension, read back as a node reads them") {
    val self = sigma.VersionContext.withVersions(3, 3)(org.ergoplatform.ErgoBox.sigmaSerializer.parse(
      sigma.serialization.SigmaSerializer.startReader(scorex.util.encode.Base16.decode(B.SelfHex).get)))
    assertEquals(sigma.VersionContext.withVersions(3, 3)(scorex.util.encode.Base16.encode(self.bytes)), B.SelfHex)
    assertEquals(self.additionalRegisters.size, 3)
    assertEquals(self.additionalTokens.length, 1)
    val ext = sigma.VersionContext.withVersions(3, 3)(sigma.interpreter.ContextExtension.serializer.parse(
      sigma.serialization.SigmaSerializer.startReader(scorex.util.encode.Base16.decode(
        "02" + "01" + B.Var1Bytes + "02" + B.Var2Bytes).get)))
    assertEquals(ext.values.keySet, Set[Byte](1, 2))
  }

  test("envelope + write staging files") {
    v.values.foreach(j => assertEquals(j.hcursor.get[String]("blessed_by").toOption, Some(B.Stamp)))
    val outDir = java.nio.file.Paths.get("target", "eval-authored", "patch-array-class")
    B.writeVectors(outDir)
    assertEquals(java.nio.file.Files.list(outDir).count(), 4L)
  }
}
