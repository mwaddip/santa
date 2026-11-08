package santa

import io.circe.Json

/** Anchors the type-id read-order family. `extract()` re-derives every verdict through
  * `WireCanonicalize` and asserts each reject's exception CLASS, so this test pins what that
  * cannot: that rows 3 and 5 really are the same body at two versions (the pair is the family's
  * entire argument), that all three failure classes survive, and that the degrade trio still says
  * the size bit is what degrades. */
class AuthoredWireMethodCallTypeIdOrderTest extends munit.FunSuite {
  private val B = AuthoredWireMethodCallTypeIdOrder
  private lazy val vectors = B.extract()
  private val ops = Seq(B.OpErgoTree, B.OpBox, B.OpTransaction)

  private def entries(op: String): List[Json] = vectors(op)
    .hcursor.downField("entries").as[List[Json]].fold(e => fail(s"entries: $e"), identity)
  private def str(e: Json, f: String): String = e.hcursor.get[String](f).toOption.getOrElse(fail(f))
  private def isReject(e: Json): Boolean = e.hcursor.get[String]("error").toOption.contains("errored")
  private def tv(e: Json): Int =
    e.hcursor.downField("version").get[Int]("ergoTree").toOption.getOrElse(fail("ergoTree"))
  private def one(op: String, frag: String): Json =
    entries(op).filter(e => str(e, "name").contains(frag)) match {
      case List(e) => e
      case other   => fail(s"want one entry in $op matching '$frag', got ${other.map(str(_, "name"))}")
    }

  test("6 ErgoTree rows; the 4 complete trees also ride a Box and a Transaction") {
    assertEquals(vectors.keySet, ops.toSet)
    assertEquals(entries(B.OpErgoTree).size, 6)
    assertEquals(entries(B.OpBox).size, 4, "the two truncated rows cannot sit in a carrier")
    assertEquals(entries(B.OpTransaction).size, 4)
    ops.foreach { op =>
      val es = entries(op)
      assertEquals(es.count(e => !isReject(e)), 1, s"$op: exactly one accept — the v1 degrade control")
      es.foreach { e =>
        assertEquals(e.hcursor.get[String]("kind").toOption, Some(op.takeWhile(_ != '.')))
        assertEquals(e.hcursor.get[String]("source").toOption, Some(B.Source))
        assertEquals(e.hcursor.downField("version").get[Int]("activated").toOption, Some(3))
        assert(e.hcursor.get[String]("expected_bytes_hex").toOption.isEmpty,
          s"${str(e, "name")}: identity round-trip or reject, never a non-identity expected")
      }
    }
  }

  test("the 3-vs-5 pair is the same body at two versions — the family's whole argument") {
    val v3 = one(B.OpErgoTree, "v3-args-assert")
    val v1 = one(B.OpErgoTree, "v1-degrades")
    assertEquals(str(v3, "bytes_hex"), "0b05" + B.SharedBody)
    assertEquals(str(v1, "bytes_hex"), "0905" + B.SharedBody)
    // identical but for the header byte — if that drifts, a red on one stops being about read order
    assertEquals(str(v3, "bytes_hex").drop(2), str(v1, "bytes_hex").drop(2))
    assertEquals(tv(v3), 3)
    assertEquals(tv(v1), 1)
    assert(isReject(v3), "the v3 row rejects: the args assert fires before fromIds")
    assert(!isReject(v1), "the v1 row degrades: nothing fails before fromIds")
  }

  test("the degrade trio: sized v1 degrades, unsized v1 rejects, sized v3 rejects") {
    // All three carry rule 1010's ValidationException; what differs is whether there is a size bit
    // to degrade behind, and whether something fires first. If the unsized row ever went green the
    // control would stop proving that the degrade is the size bit's doing.
    val unsized = one(B.OpErgoTree, "v1-unsized")
    assertEquals(str(unsized, "bytes_hex"), "00" + B.SharedBody)
    assertEquals(tv(unsized), 0)
    assert(isReject(unsized))
    assert(!isReject(one(B.OpErgoTree, "v1-degrades")))
    assert(isReject(one(B.OpErgoTree, "v3-args-assert")))
  }

  test("all three failure classes survive — re-derived, not read off the prose") {
    val classes = entries(B.OpErgoTree).filter(isReject).map { e =>
      scala.util.Try(WireCanonicalize.canonicalize("ErgoTree", str(e, "bytes_hex"), 3.toByte, tv(e).toByte))
        .fold(t => Iterator.iterate(t: Throwable)(_.getCause).takeWhile(_ != null)
                     .map(_.getClass.getSimpleName).toList.last,
              out => fail(s"${str(e, "name")}: expected a reject, round-tripped to $out"))
    }
    assertEquals(classes.count(_ == "BufferUnderflowException"), 2, classes.toString)
    assertEquals(classes.count(_ == "AssertionError"), 1, classes.toString)
    assertEquals(classes.count(_ == "SerializerException"), 2, classes.toString)
    assertEquals(classes.distinct.size, 3, s"three distinct classes: ${classes.distinct}")
  }

  test("which rows claim to separate") {
    val sep = entries(B.OpErgoTree)
      .filter(e => str(e, "description").contains("SEPARATING ROW")).map(e => str(e, "name"))
    assertEquals(sep.size, 4, s"the two truncations, the v3 assert and the SAny obj: $sep")
    assert(!str(one(B.OpErgoTree, "v1-degrades"), "description").contains("SEPARATING ROW"))
    assert(str(one(B.OpErgoTree, "v1-degrades"), "description").contains("THE CONTROL"))
  }

  test("envelopes: santa-wire/v1, the 6.0.7 stamp") {
    vectors.foreach { case (op, env) =>
      assertEquals(env.hcursor.get[String]("schema").toOption, Some("santa-wire/v1"), op)
      assertEquals(env.hcursor.get[String]("op").toOption, Some(op))
      assertEquals(env.hcursor.get[String]("blessed_by").toOption, Some("jvm:sigma-state-6.0.7"), op)
    }
  }

  test("write staging files") {
    val outDir = java.nio.file.Paths.get("target", "wire-authored")
    B.writeVectors(outDir)
    vectors.keys.foreach(op => assert(java.nio.file.Files.exists(outDir.resolve(s"$op.json"))))
  }
}
