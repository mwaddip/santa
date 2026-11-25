package santa

import io.circe.Json

/** Anchors the vectors that READ SELF's form (runner-contract §2's SELF-tree note): the same reads on a size-flagged
  * tree whose root is not a SigmaProp and whose parse cannot be written back (`u-*`, SELF holds it as its bytes) and on
  * its writable twin (`w-*`), in all five eval envelopes; and how SELF's bytes follow the size field (`span`). */
class AuthoredEvalSelfNodeFormTest extends munit.FunSuite {
  private val B = AuthoredEvalSelfNodeForm
  private lazy val v = B.extract()
  private def entries(op: String): List[Json] =
    v(op).hcursor.downField("entries").as[List[Json]].fold(e => fail(s"$op entries: $e"), identity)
  private def str(e: Json, f: String): String = e.hcursor.get[String](f).toOption.getOrElse(fail(f))
  private def expected(e: Json): Json = e.hcursor.downField("expected").focus.getOrElse(fail("expected"))
  private def errored(e: Json): Boolean = expected(e).hcursor.get[String]("error").toOption.contains("errored")
  private def row(op: String, base: String): Json =
    entries(op).filter(e => str(e, "name").matches(s"${java.util.regex.Pattern.quote(base)}-(accept|errored)#\\d+")) match {
    case List(e) => e
    case other   => fail(s"$op: want one $base, got ${other.map(str(_, "name"))}")
  }
  /** A `Coll[Byte]` SValue as hex. */
  private def hex(value: Json): String = value.hcursor.downField("items").as[List[Json]].fold(e => fail(s"items: $e"), identity)
    .map(i => f"${i.hcursor.get[Int]("value").toOption.get & 0xff}%02x").mkString

  private val reads = Seq("value", "id", "bytes", "propbytes", "bwnr", "in0_eq_self", "selfidx", "in0_id", "r4def",
    "in0_r4def", "creation")

  test("five envelopes, 22 rows each (v4 23): every read on both trees, and no tree a box cannot carry") {
    B.Envelopes.foreach { case (op, _) =>
      val names = entries(op).map(str(_, "name"))
      reads.foreach(r => Seq("u", "w").foreach(t => assert(names.exists(_.startsWith(s"$t-$r-")), s"$op: $t-$r")))
      assert(!names.exists(_.startsWith("u-sp-")), s"$op: an unwritable SigmaProp root is a tree no box can carry")
      assertEquals(names.size, if (op.endsWith("_v4")) 23 else 22, op)
    }
  }

  test("each row reads what its prose says (the opcodes in its tree)") {
    val opcode = Map("value" -> "c1a7", "id" -> "c5a7", "bytes" -> "c3a7", "propbytes" -> "c2a7", "bwnr" -> "c4a7",
      "in0_eq_self" -> "93b2a4040000a7", "selfidx" -> "db6508fe", "in0_id" -> "c5b2a4040000", "r4def" -> "e6c6a70404",
      "in0_r4def" -> "e6c6b2a40400000404", "creation" -> "c7a7")
    B.Envelopes.foreach { case (op, _) => reads.foreach { r =>
      val u = row(op, s"u-$r"); val w = row(op, s"w-$r")
      assert(str(u, "tree_bytes_hex").contains(opcode(r)) && str(w, "tree_bytes_hex").contains(opcode(r)), s"$op $r")
      assert(str(u, "tree_bytes_hex").contains("600104"), s"$op u-$r holds the (Int) constant")
      assert(!str(w, "tree_bytes_hex").contains("600104"), s"$op w-$r is the writable twin")
    } }
  }

  test("THE NOTE: on the unwritable tree SELF has an id and bytes, in every envelope, and holds the tree's own bytes") {
    B.Envelopes.foreach { case (op, _) =>
      Seq("id", "bytes", "bwnr", "in0_id", "in0_eq_self").foreach { r =>
        assert(!errored(row(op, s"u-$r")), s"$op u-$r must answer: SELF is the node's box, not an unwritable parse")
      }
      val u = row(op, "u-propbytes")
      assertEquals(hex(expected(u).hcursor.downField("value").focus.get), str(u, "tree_bytes_hex"),
        s"$op: SELF.propositionBytes is the tree as given")
      // the reads that do not see the tree give the same VALUE on both twins (the costs differ: the conditions do)
      Seq("value", "in0_eq_self", "selfidx", "r4def", "in0_r4def").foreach { r =>
        assertEquals(expected(row(op, s"u-$r")).hcursor.downField("value").focus,
          expected(row(op, s"w-$r")).hcursor.downField("value").focus, s"$op $r: the twins agree")
      }
    }
  }

  test("span: SELF holds the bytes the size field spans; a size field a node cannot read leaves SELF the parse") {
    val op = B.SpanOp
    // a size field claiming more bytes than there are, over a WRITABLE body: a node cannot read the tree, SELF holds the
    // parse, and the parse can be written, so a runner with a real box can answer
    Seq("size-lies-long-writable", "size-lies-long-by-37-writable").foreach { r =>
      val e = row(op, r); val t = scorex.util.encode.Base16.decode(str(e, "tree_bytes_hex")).get
      assertEquals(expected(e).hcursor.downField("value").focus.map(_.noSpaces),
        Some("""{"kind":"Int","value":2}"""), s"$r: the entry answers (one rule: no node form, the parse)")
      assert(scala.util.Try(sigma.serialization.ErgoTreeSerializer.DefaultSerializer.deserializeErgoTree(t)).isFailure,
        s"$r: a node cannot read it")
      assert(scala.util.Try(sigma.santa.LenientErgoTree.deserialize(t).bytes).isSuccess, s"$r: its parse can be written")
    }
    // the two over `SizeOf((1))` are retired: their SELF is a parse no box can carry (sigma-rust's grade, 2026-11-26);
    // dropped after naming, so every other entry keeps its committed name
    val names = entries(op).map(str(_, "name")).toSet
    assert(!names("size-lies-long-accept#1") && !names("size-lies-long-by-37-accept#3"), "the unwritable two are gone")
    Seq("size-true-accept#0", "size-lies-short-accept#2", "trailing-00-accept#4", "size-lies-short-bytes-accept#10",
        "size-lies-long-writable-accept#11", "size-lies-long-by-37-writable-accept#12").foreach(n => assert(names(n), n))
    Seq("trailing-05-propbytes", "trailing-w-propbytes").foreach { r =>
      val e = row(op, r); val t = str(e, "tree_bytes_hex")
      val span = 2 + Integer.parseInt(t.substring(2, 4), 16)
      assertEquals(hex(expected(e).hcursor.downField("value").focus.get), t.take(2 * span), s"$r: the size field's span")
    }
    assert(!entries(op).exists(e => str(e, "name").startsWith("unsized-")), "no unsized unwritable tree: no box carries it")
  }

  test("write staging files") {
    val out = java.nio.file.Paths.get("target", "eval-authored")
    B.writeVectors(out)
    (B.Envelopes.map(_._1) :+ B.SpanOp).foreach(op => assert(java.nio.file.Files.exists(out.resolve(s"$op.json")), op))
  }
}
