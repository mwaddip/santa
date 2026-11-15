package santa

import io.circe.Json
import scorex.util.encode.Base16

import sigma.VersionContext
import sigma.serialization.{SigmaSerializer, ValueSerializer}

/** Anchors the pre-activation families. `SpecExtract` fails loud if an entry's outcome changes between the label and
  * the bless; pinned here is what would rot silently while still grading green:
  *
  *  - the seven files, their entry counts, and each entry's version pair (v4 = activated 0/1, never a tree above);
  *  - OUR trees: the JVM writer re-serializes every vendored expression byte-identically;
  *  - every outcome, value and cost of a value agrees with the jars' own measurement (sigma-rust's context-free probe
  *    on the same 6.0.7), an independent second reading;
  *  - every SEPARATING / CONTROL label is what the other side of the activation answers, re-measured here;
  *  - the argument order of `Global.xor` below the activation, and a value whose items are of another class than its
  *    item type, by name.
  */
class AuthoredEvalPreActivationTest extends munit.FunSuite {
  private val B = AuthoredEvalPreActivation
  private lazy val rows = B.rows()
  private lazy val v = B.extract()
  private def entries(op: String): List[Json] =
    v(op).hcursor.downField("entries").as[List[Json]].fold(e => fail(s"entries: $e"), identity)
  private def str(e: Json, f: String): String = e.hcursor.get[String](f).toOption.getOrElse(fail(f))
  private def ver(e: Json, f: String): Int = e.hcursor.downField("version").get[Int](f).toOption.getOrElse(fail(f))
  private def err(e: Json): Option[String] = e.hcursor.downField("expected").get[Option[String]]("error").toOption.flatten
  private def value(e: Json): Json = e.hcursor.downField("expected").downField("value").focus.getOrElse(fail("value"))
  private def cost(e: Json): Long = e.hcursor.downField("expected").get[Long]("cost").toOption.getOrElse(fail("cost"))
  private def rowName(e: Json): String = str(e, "script").drop(1).takeWhile(_ != ']')
  private def entry(op: String, name: String, a: Int): Json =
    entries(op).find(e => rowName(e) == name && ver(e, "activated") == a).getOrElse(fail(s"no $name at $a in $op"))

  test("seven files: v4 append 343, zip 292, xor 11; v5 xor 6; v6 append 343, zip 230, xor 6; versions in their slot") {
    val want = Map("Coll.append_concat_v4" -> 343, "Coll.zip_unequal_sides_v4" -> 292, "Xor.unequal_sides_v4" -> 11,
      "Xor.unequal_sides" -> 6, "Coll.append_concat_v6" -> 343, "Coll.zip_unequal_sides_v6" -> 230,
      "Xor.unequal_sides_v6" -> 6)
    assertEquals(v.keySet, want.keySet)
    want.foreach { case (op, n) => assertEquals(entries(op).size, n, op) }
    assertEquals(rows.size, want.values.sum)
    v.keys.foreach { op =>
      val allowed = if (op.endsWith("_v4")) Set(0, 1) else if (op.endsWith("_v6")) Set(3) else Set(2)
      entries(op).foreach { e =>
        assert(allowed(ver(e, "activated")), s"$op ${str(e, "name")}")
        assert(ver(e, "ergoTree") <= ver(e, "activated"), s"$op ${str(e, "name")}: tree above activated")
        assertEquals(e.hcursor.downField("input").focus, Some(B.IntInput))
      }
    }
  }

  test("our trees: the JVM writer re-serializes every vendored expression byte-identically") {
    rows.foreach { r =>
      val again = VersionContext.withVersions(r.activated.toByte, r.treeVersion.toByte) {
        val e = ValueSerializer.deserialize(SigmaSerializer.startReader(Base16.decode(r.body).get))
        val w = SigmaSerializer.startWriter(); ValueSerializer.serialize(e, w); Base16.encode(w.toBytes).toLowerCase
      }
      assertEquals(again, r.body, s"${r.name} a${r.activated}")
    }
  }

  test("every outcome, value and cost agrees with the jars' own measurement, and the tree is the vendored body") {
    val byKey = rows.map(r => (r.op, r.name, r.activated) -> r).toMap
    v.keys.foreach(op => entries(op).foreach { e =>
      val r = byKey((op, rowName(e), ver(e, "activated")))
      assertEquals(str(e, "tree_bytes_hex"), r.treeHex, r.name)
      assertEquals(ver(e, "ergoTree"), r.treeVersion, r.name)
      r.jarsOutcome match {
        case "throws" => assertEquals(err(e), Some("errored"), s"${r.name} a${r.activated}")
        case "value" =>
          assertEquals(err(e), None, s"${r.name} a${r.activated}")
          assertEquals(B.text(value(e)), r.jarsValue, s"${r.name} a${r.activated}")
          assertEquals(cost(e).toString, r.jarsCost, s"${r.name} a${r.activated}")
      }
    })
  }

  test("every SEPARATING / CONTROL label is what the other side of the activation answers") {
    var sep = Map.empty[String, Int].withDefaultValue(0)
    rows.foreach { r =>
      val e = entry(r.op, r.name, r.activated)
      val s = B.separating(r)
      assert(str(e, "script").contains(if (s) "SEPARATING" else "CONTROL"), s"${r.name} a${r.activated}")
      if (s) sep += r.op -> (sep(r.op) + 1)
    }
    // sigma-rust's table, from its fork's side: 336 append and 185 + 36 zip rows differ below the activation
    assertEquals(sep("Coll.append_concat_v4"), 336)
    assertEquals(sep("Coll.zip_unequal_sides_v4"), 221)
  }

  test("Global.xor below the activation: a longer FIRST operand throws, a longer second one does not") {
    Seq(0, 1).foreach { a =>
      assertEquals(err(entry("Xor.unequal_sides_v4", "global-xor-left-longer|v0", a)), Some("errored"), s"a$a")
      assertEquals(err(entry("Xor.unequal_sides_v4", "node-xor-left-longer|v0", a)), Some("errored"), s"a$a")
      assertEquals(B.text(value(entry("Xor.unequal_sides_v4", "global-xor-left-shorter|v0", a))), "Coll(0,0)", s"a$a")
    }
    assertEquals(B.text(value(entry("Xor.unequal_sides", "global-xor-left-longer|v0", 2))), "Coll(0,0)")
    assertEquals(B.text(value(entry("Xor.unequal_sides_v6", "global-xor-left-longer|v0", 3))), "Coll(0,0)")
  }

  test("a value whose items are of another class than its item type, and a Coll[AvlTree] value, encode as such") {
    val mixed = value(entry("Coll.append_concat_v4", "append|node|direct|BigInts+Bytes|v0", 1))
    assertEquals(mixed.hcursor.downField("elem").get[String]("tag").toOption, Some("SBigInt"))
    assertEquals(mixed.hcursor.downField("items").as[List[Json]].toOption.get.map(_.hcursor.get[String]("kind").toOption.get),
      List("BigInt", "BigInt", "Byte", "Byte"))
    val trees = value(entry("Coll.append_concat_v4", "append|node|direct|Trees+Bytes|v0", 1))
    assertEquals(trees.hcursor.downField("elem").get[String]("tag").toOption, Some("SAvlTree"))
    v.values.foreach(f => assert(!f.noSpaces.contains("SUnknown"), "an SUnknown tag reached a vector"))
  }

  test("envelope + write staging files") {
    v.foreach { case (op, f) =>
      assertEquals(f.hcursor.get[String]("blessed_by").toOption, Some(B.Stamp), op)
      assertEquals(f.hcursor.get[String]("source").toOption, Some(B.Source), op)
      assertEquals(f.hcursor.get[String]("schema").toOption, Some("santa-eval/v2"), op)
    }
    val outDir = java.nio.file.Paths.get("target", "eval-authored")
    B.writeVectors(outDir)
    v.keys.foreach(op => assert(java.nio.file.Files.exists(outDir.resolve(s"$op.json")), op))
  }
}
