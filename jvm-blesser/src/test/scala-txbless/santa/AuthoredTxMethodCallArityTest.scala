package santa

import io.circe.Json

/** Anchors the wrong-arity transaction vectors. extract() blesses each spend through
  * TxEngine.validateBytes and fails loud if a verdict is not the one the source predicts, so
  * this test pins the split: a `userDefinedInvoke` method tolerates a surplus argument, a
  * reflectively invoked one does not. */
class AuthoredTxMethodCallArityTest extends munit.FunSuite {
  private lazy val blessed = AuthoredTxMethodCallArity.extract()

  private def entries: List[Json] =
    blessed.hcursor.downField("entries").as[List[Json]].fold(e => fail(s"entries: $e"), identity)
  private def str(e: Json, field: String): String =
    e.hcursor.get[String](field).toOption.getOrElse(fail(field))
  private def valid(e: Json): Boolean =
    e.hcursor.downField("expected").get[Boolean]("valid").toOption.getOrElse(fail("valid"))
  private def cost(e: Json): Option[Long] =
    e.hcursor.downField("expected").get[Long]("cost").toOption

  test("the split: userDefinedInvoke tolerates a surplus argument, reflection does not") {
    assertEquals(entries.map(valid), List(true, false, false, true))
  }

  test("the surplus argument is FREE — row 1 costs exactly what the legal-arity control costs") {
    val surplus = cost(entries.head)
    val control = cost(entries(3))
    assert(surplus.isDefined, "the valid surplus-argument row must carry a cost")
    assertEquals(surplus, control,
      s"the handler reads other.head and ignores the rest: $surplus vs $control")
  }

  test("the two invalid rows carry no cost and a verification reason") {
    Seq(entries(1), entries(2)).foreach { e =>
      assert(!valid(e), str(e, "name"))
      assertEquals(cost(e), None, s"${str(e, "name")}: an invalid spend has no cost")
      assert(e.hcursor.downField("expected").get[String]("reason").toOption.exists(_.nonEmpty),
        s"${str(e, "name")}: must say why")
    }
  }

  test("each entry spends the tree its wire twin round-trips") {
    entries.zip(AuthoredWireMethodCallArity.Trees).foreach { case (e, (name, _, treeHex)) =>
      assert(str(e, "name").contains(name), s"${str(e, "name")} pairs with $name")
      val boxHex = e.hcursor.downField("input_boxes_hex").downArray.as[String]
        .fold(err => fail(s"input_boxes_hex: $err"), identity)
      assert(boxHex.contains(treeHex), s"${str(e, "name")}: the spent box must carry $treeHex")
    }
  }

  test("envelope: santa-transaction/v1, blessed by ergo-core 6.0.7") {
    val c = blessed.hcursor
    assertEquals(c.get[String]("schema").toOption, Some("santa-transaction/v1"))
    assertEquals(c.get[String]("op").toOption, Some("method-call-arity"))
    assertEquals(c.get[String]("blessed_by").toOption, Some("jvm:ergo-core-6.0.7-validateStateful"))
    entries.foreach { e =>
      assertEquals(e.hcursor.downField("version").get[Int]("activated").toOption, Some(3))
      assertEquals(e.hcursor.get[String]("source").toOption, Some("santa:authored-method-call-arity"))
    }
  }

  test("write vectors") {
    AuthoredTxMethodCallArity.writeVectors(java.nio.file.Paths.get("..", "vectors"))
  }
}
