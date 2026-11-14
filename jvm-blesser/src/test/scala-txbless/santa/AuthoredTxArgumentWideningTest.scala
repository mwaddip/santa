package santa

import io.circe.Json

/** Anchors the argument-widening TRANSACTION vectors. `extract()` requires each verdict; pinned here: the split, the
  * widened spends' costs (the round's reply measured W1c @12110, W1 @12113, W9c/W9/W9s @12116/12119/12116 in this
  * context's predecessor; recorded here as blessed), each lie costing its control + 3, and the refusals refused for
  * the script, not the carrier. */
class AuthoredTxArgumentWideningTest extends munit.FunSuite {
  private val B = AuthoredTxArgumentWidening
  private lazy val blessed = B.extract()
  private def entries: List[Json] =
    blessed.hcursor.downField("entries").as[List[Json]].fold(e => fail(s"entries: $e"), identity)
  private def id(e: Json): String =
    e.hcursor.get[String]("description").toOption.getOrElse(fail("description")).takeWhile(_ != ']').drop(1)
  private def valid(e: Json): Boolean = e.hcursor.downField("expected").get[Boolean]("valid").toOption.get
  private def cost(i: String): Long =
    entries.find(id(_) == i).flatMap(_.hcursor.downField("expected").get[Long]("cost").toOption).getOrElse(fail(i))

  test("the split: W1n, X1, W1d, N1 and the W10! value pin invalid; the rest spend") {
    assertEquals(entries.map(id), B.Rows.map(_.id).toList)
    assertEquals(entries.filterNot(valid).map(id).toSet, Set("W10!", "W1n", "X1", "W1d", "N1"))
  }

  test("a widened argument costs what its lie costs at eval over the control: the lie's If and branch") {
    assertEquals(cost("W1") - cost("W1c"), cost("W9") - cost("W9c"))
    assertEquals(cost("W9s"), cost("W9c"), "a Short constant index costs the control's")
  }

  test("write vectors") {
    B.writeVectors(java.nio.file.Paths.get("..", "vectors"))
    assert(java.nio.file.Files.exists(java.nio.file.Paths.get("..", "vectors", B.Path)))
  }
}
