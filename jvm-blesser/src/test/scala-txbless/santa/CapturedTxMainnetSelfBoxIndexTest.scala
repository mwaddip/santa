package santa

import io.circe.Json

/** Bless the selfBoxIndex spend at mainnet height 342,964 and its activated-2 twin. blessAll() fails loud if the
  * oracle rejects the captured (chain-valid) spend, accepts the twin, or rejects the twin for a reason that does not
  * name input 2. */
class CapturedTxMainnetSelfBoxIndexTest extends munit.FunSuite {
  private lazy val blessed: Seq[(String, Json)] = CapturedTxMainnetSelfBoxIndex.blessAll()
  private def only(env: Json): Json =
    env.hcursor.downField("entries").as[List[Json]].fold(e => fail(s"entries: $e"), identity) match {
      case List(e) => e
      case es      => fail(s"expected one entry, got ${es.size}")
    }

  test("the captured spend is valid at activated 0; the twin at activated 2 is invalid") {
    assertEquals(blessed.map(_._1), Seq(
      "transaction/v4/captured/selfboxindex-342964.json",
      "transaction/v5/authored/selfboxindex-342964-activated2.json"))
    val Seq(captured, twin) = blessed.map(b => only(b._2).hcursor)
    assertEquals(captured.downField("expected").get[Boolean]("valid").toOption, Some(true))
    assert(captured.downField("expected").get[Long]("cost").isRight, "captured: cost")
    assertEquals(captured.downField("version").get[Int]("activated").toOption, Some(0))
    assertEquals(twin.downField("expected").get[Boolean]("valid").toOption, Some(false))
    assertEquals(twin.downField("version").get[Int]("activated").toOption, Some(2))
    assertEquals(twin.downField("parameters").get[Int]("blockVersion").toOption, Some(3))
    assertEquals(captured.downField("headers_hex").as[List[String]].map(_.size).toOption,
      Some(AuthoredTxStorageRent.ScriptHeaders), "the headers a script sees")
  }

  test("print the blessed verdicts") {
    blessed.foreach { case (rel, env) =>
      val c = only(env).hcursor.downField("expected")
      println(s"  [$rel] valid=${c.get[Boolean]("valid").getOrElse("?")} cost=${c.get[Long]("cost").getOrElse(-1L)} " +
        s"reason=${c.get[String]("reason").getOrElse("-")}")
    }
  }

  test("write step: files land under vectors/transaction/") {
    val root = java.nio.file.Paths.get("..", "vectors")
    CapturedTxMainnetSelfBoxIndex.writeVectors(blessed, root)
    blessed.foreach { case (rel, _) => assert(java.nio.file.Files.exists(root.resolve(rel)), s"not written: $rel") }
  }
}
