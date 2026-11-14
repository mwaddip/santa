package santa

import io.circe.Json

/** Anchors the JDK-UTF-8 wire family: which rows re-write, echo or are refused; the exact bytes the JVM writes for
  * the constants (one U+FFFD for a surrogate, one per byte for an overlong); a malformed transaction's id equals its
  * well-formed twin's; and the sized tree's size grows. */
class AuthoredWireJdkUtf8DecodeTest extends munit.FunSuite {
  private val B = AuthoredWireJdkUtf8Decode
  private def row(kind: String, id: String, v6: Boolean = false): B.Row =
    B.Rows.find(r => r.kind == kind && r.id == id && r.v6 == v6).getOrElse(fail(s"$kind $id"))

  test("the outcomes: re-write, echo, refuse") {
    val echo = Set("str-valid", "size0-valid", "eq-ctl", "tx-ctl-r4-repl", "tx-ctl-ext-repl", "tx-ctl-tree-repl",
      "tx-ctl-tree-tv")
    val refused = Set("eq-ctl-differ", "eq-overlong-vs-1ff", "eq-above-vs-1ff", "eq-sur-vs-3ff")
    B.Rows.foreach { r =>
      val o = B.outcome(r)
      if (refused(r.id)) assertEquals(o, Left(()), s"${r.id} v6=${r.v6}")
      else if (echo(r.id)) assertEquals(o, Right(None), s"${r.id} v6=${r.v6}")
      else assert(o.exists(_.isDefined), s"${r.id} v6=${r.v6}: the JVM must write other bytes, got $o")
    }
  }

  test("the bytes the JVM writes for the String constants") {
    val want = Map("str-sur" -> "6603efbfbd", "str-sur-ascii" -> "6604efbfbd41",
      "str-overlong3" -> "6609efbfbdefbfbdefbfbd", "str-above" -> "660cefbfbdefbfbdefbfbdefbfbd")
    want.foreach { case (i, w) => assertEquals(B.outcome(row("Constant", i)), Right(Some(w)), i) }
  }

  test("a malformed transaction has its well-formed twin's id and bytes; the sized tree grows") {
    Seq(("tx-r4-sur", "tx-ctl-r4-repl"), ("tx-ext-sur", "tx-ctl-ext-repl"), ("tx-tree-sur", "tx-ctl-tree-repl"))
      .foreach { case (bad, ctl) =>
        assertEquals(B.txId(row("Transaction", bad)), B.txId(row("Transaction", ctl)), bad)
        assertEquals(B.outcome(row("Transaction", bad)), Right(Some(row("Transaction", ctl).bytes)), bad)
      }
    val sized = B.outcome(row("Transaction", "tx-sizedtree-tv-overlong")).toOption.flatten.get
    assert(sized.contains("0812d193b183006709efbfbdefbfbdefbfbd0400"), s"the tree re-written with size 18: $sized")
  }

  test("ops per version; staging files") {
    assertEquals(B.extract(v6 = true).keySet, Set(B.OpErgoTree))
    assertEquals(B.extract(v6 = false).keySet, Set(B.OpConstant, B.OpErgoTree, B.OpTransaction))
    val outDir = java.nio.file.Paths.get("target", "wire-authored", "jdk-utf8-decode")
    B.writeVectors(outDir)
    assert(java.nio.file.Files.exists(outDir.resolve("v5").resolve(s"${B.OpTransaction}.json")))
    assert(java.nio.file.Files.exists(outDir.resolve("v6").resolve(s"${B.OpErgoTree}.json")))
  }
}
