package santa

import io.circe.Json

/** Bless the mainnet storage-rent sample. blessAll() fails loud if the 6.0.6 oracle rejects any captured
  * (chain-valid) tx, or if the JVM's rent-path inputs differ from the capture's; this pins the shape,
  * prints the costs, and writes the vectors under transaction/v5|v6/captured/. */
class CapturedTxMainnetRentTest extends munit.FunSuite {
  private lazy val blessed: Seq[(String, Json)] = CapturedTxMainnetRent.blessAll()
  private def entries(env: Json): List[Json] =
    env.hcursor.downField("entries").as[List[Json]].fold(e => fail(s"entries: $e"), identity)

  test("every capture blesses valid, with a cost, under a mainnet: source") {
    val all = blessed.flatMap { case (_, env) => entries(env) }
    assertEquals(all.size, CapturedTxMainnetRent.captures().size)
    all.foreach { e =>
      val c = e.hcursor
      assertEquals(c.downField("expected").get[Boolean]("valid").toOption, Some(true))
      assert(c.downField("expected").get[Long]("cost").isRight, "cost")
      assert(c.get[String]("source").toOption.exists(_.startsWith("mainnet:rent-")), "source")
      assertEquals(c.downField("headers_hex").as[List[String]].map(_.size).toOption, Some(10), "ten headers")
    }
  }

  test("print blessed costs") {
    val sb = new StringBuilder("\n===== mainnet storage-rent sample =====\n")
    blessed.foreach { case (rel, env) =>
      sb.append(s"  [$rel]\n")
      entries(env).foreach { e =>
        val c = e.hcursor
        sb.append(f"    ${c.get[String]("name").getOrElse("?")}%-44s cost=${c.downField("expected").get[Long]("cost").getOrElse(-1L)}\n")
      }
    }
    println(sb.toString)
  }

  test("write step: files land under vectors/transaction/v5|v6/captured/") {
    val root = java.nio.file.Paths.get("..", "vectors")
    CapturedTxMainnetRent.writeVectors(blessed, root)
    blessed.foreach { case (rel, _) => assert(java.nio.file.Files.exists(root.resolve(rel)), s"not written: $rel") }
  }
}
