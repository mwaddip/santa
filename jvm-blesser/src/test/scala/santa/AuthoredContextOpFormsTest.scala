package santa

import java.nio.file.Files

/** Guard for the Context op-form witnesses (ergots Ask 13 remainder). The trees are the
  * 3-byte dedicated-opcode forms (0x10 0x00 0xa6 / 0xac) — asserted byte-exact — and the
  * op-form-vs-PropertyCall cost split is pinned (op-form 15 vs the committed PropertyCall
  * twin's 20 for LastBlockUtxoRootHash). */
class AuthoredContextOpFormsTest extends munit.FunSuite {

  private lazy val vectors = AuthoredContextOpForms.extract()
  private lazy val entries: Vector[io.circe.Json] =
    vectors(AuthoredContextOpForms.Op).hcursor.downField("entries").values.get.toVector
  private def byName(name: String): io.circe.Json =
    entries.find(_.hcursor.get[String]("name").toOption.contains(name))
      .getOrElse(fail(s"missing entry $name"))

  test("one family, two entries, byte-exact 3-byte op-form trees") {
    assertEquals(vectors.size, 1)
    assertEquals(entries.size, 2)
    assertEquals(byName("lastblockutxoroothash-opform#0").hcursor.get[String]("tree_bytes_hex").toOption,
      Some("1000a6"))
    assertEquals(byName("minerpubkey-opform#1").hcursor.get[String]("tree_bytes_hex").toOption,
      Some("1000ac"))
  }

  test("op-form costs: LastBlockUtxoRootHash 15 (vs PropertyCall twin 20), MinerPubkey 20") {
    val lb = byName("lastblockutxoroothash-opform#0").hcursor.downField("expected")
    assertEquals(lb.get[Long]("cost").toOption, Some(15L))
    assertEquals(lb.downField("value").get[String]("kind").toOption, Some("AvlTree"))
    val mp = byName("minerpubkey-opform#1").hcursor.downField("expected")
    assertEquals(mp.get[Long]("cost").toOption, Some(20L))
    val items = mp.downField("value").downField("items").values.get.toVector
    assertEquals(items.size, 33)
    assertEquals(items.head.hcursor.get[Int]("value").toOption, Some(2)) // canonical generator lead
    // the PropertyCall twin (Context.properties family) blessed the same property at 20 —
    // the op-form is 5 cheaper: forms cost by wire shape, not by property
    val twin = AuthoredContextProps.extract().values
      .flatMap(_.hcursor.downField("entries").values.get)
      .find(_.hcursor.get[String]("name").toOption.contains("CONTEXT.LastBlockUtxoRootHash#dummy"))
    twin.foreach { e =>
      assertEquals(e.hcursor.downField("expected").get[Long]("cost").toOption, Some(20L),
        "PropertyCall twin cost moved — update the op-form comparison docs")
    }
  }

  test("version pinned at {activated 2, ergoTree 0}") {
    entries.foreach { e =>
      val v = e.hcursor.downField("version")
      assertEquals(v.get[Int]("activated").toOption, Some(2))
      assertEquals(v.get[Int]("ergoTree").toOption, Some(0))
    }
  }

  test("staging: writes the family file") {
    val out = java.nio.file.Paths.get("target/authored-staging")
    AuthoredContextOpForms.writeVectors(out)
    assert(Files.exists(out.resolve("Context.op_forms.json")))
  }
}
