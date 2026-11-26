package santa

import org.ergoplatform.ErgoBox
import scorex.util.encode.Base16
import scorex.util.bytesToId
import sigma.ast.ErgoTree

/** The tree the canonical SELF holds (the user's ruling, 2026-11-25: as a node holds it). A size-flagged tree whose
  * root is not a SigmaProp is kept as its BYTES, as a node's deserializer keeps it; an unsized one has no node form
  * (a node refuses the box) and keeps the lenient parse; a SigmaProp root is the parse. */
class SelfTreeFormTest extends munit.FunSuite {
  private def b(h: String): Array[Byte] = Base16.decode(h).get
  private def selfOf(h: String): ErgoTree = EvalCore.selfTreeOf(b(h), sigma.santa.LenientErgoTree.deserialize(b(h)))
  private def boxOf(t: ErgoTree): ErgoBox =
    new ErgoBox(1000000L, t, transactionId = bytesToId(Array.fill(32)(0: Byte)), index = 0.toShort, creationHeight = 0)

  // the five size-flagged, non-SigmaProp trees with a 0- or 1-item tuple type constant (sigma-rust's request)
  private val five = Seq("08068c6001040a01", "0b03b16000", "0b05b160010402", "0b11b1dc0c151003020406021001006001040e",
    "0b17740e0d1b0b02040e040ed1937300730160010400100112")

  test("a size-flagged tree whose root is not a SigmaProp: kept as its bytes, so SELF has an id") {
    five.foreach { h =>
      val t = selfOf(h)
      assert(t.root.isLeft, s"$h: SELF must hold the tree unparsed, as a node does")
      assertEquals(Base16.encode(t.bytes), h)
      assert(scala.util.Try(boxOf(t).id).isSuccess, s"$h: the node's box has an id")
      assert(scala.util.Try(boxOf(sigma.santa.LenientErgoTree.deserialize(b(h))).id).isFailure,
        s"$h: the lenient parse's box has none — which is why the form matters")
    }
  }

  test("an unsized tree whose root is not a SigmaProp has no node form, and keeps the lenient parse") {
    val unsized = "00b16000" // SizeOf(()) at v0, header 00 (no size flag)
    assert(selfOf(unsized).root.isRight, "the lenient parse, as today")
    assert(scala.util.Try(sigma.serialization.ErgoTreeSerializer.DefaultSerializer.deserializeErgoTree(b(unsized))).isFailure,
      "a node refuses it outright")
  }

  test("a size-flagged tree a node cannot read (its size field claims more bytes than there are) keeps the parse") {
    Seq("0b06b160010402", "0b2ab160010402").foreach { h => // SizeOf((1)), the size field one / 37 bytes long
      assert(scala.util.Try(sigma.serialization.ErgoTreeSerializer.DefaultSerializer.deserializeErgoTree(b(h))).isFailure,
        s"$h: a node refuses it")
      assert(selfOf(h).root.isRight, s"$h: SELF holds the parse, as for any tree with no node form")
      val (_, out) = EvalCore.evalApplied(h, io.circe.Json.obj("kind" -> io.circe.Json.fromString("Int"),
        "value" -> io.circe.Json.fromInt(0)), 3)
      assertEquals(out.map(_._1.noSpaces), Right("""{"kind":"Int","value":1}"""), s"$h: the entry answers, not errored")
    }
  }

  test("a size-flagged tree whose root's type read throws: a node cannot read it either, so SELF holds the parse") {
    // sigma-rust's rows (2026-11-26): a function whose body is a Filter over an Int, and that Filter as the root
    val input = io.circe.Json.obj("kind" -> io.circe.Json.fromString("Int"), "value" -> io.circe.Json.fromInt(0))
    val fn = "0b0cd9010104b5040ad90102047f"
    assert(scala.util.Try(selfOf(fn)).isSuccess, "classifying the tree must not throw")
    val (_, f) = EvalCore.evalAppliedOutcome(fn, input, 3)
    assertEquals(f.map(_._2), Right(5L), s"the function row evaluates, as before the SELF-tree rule: $f")
    val (_, r) = EvalCore.evalAppliedOutcome("0b08b5040ad90102047f", input, 3)
    assert(r.left.exists(t => String.valueOf(t.getMessage).contains("java.lang.Integer")),
      s"the Filter root errors at EVALUATION (Integer -> Coll), not at a type read: $r")
  }

  test("a SigmaProp root is the parse") {
    val h = "0b0cd193b1480404040204060406" // sigmaProp(SizeOf((1,2,3)) == 3)
    val t = selfOf(h)
    assert(t.root.isRight)
    assertEquals(Base16.encode(t.bytes), h)
  }
}
