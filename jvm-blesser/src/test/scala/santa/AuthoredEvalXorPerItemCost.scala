package santa

// Authored eval vectors — the per-item cost base of `Xor`, as the NODE and as the `Global.xor`
// METHOD. Request: `~/projects/sigma-rust/prompts/santa-probe-tuple-values-2.md` §4, which is older
// than the tuple round and independent of it: that session read `Xor.xorWithCosting` as charging
// `PerItemCost(10, 2, 128)` on `min(ls.length, rs.length)` (`trees.scala:1021-1025`), with
// `xorMethod` sharing `Xor.costKind` (`methods.scala:1876-1877`), and reported that its own
// implementation charges on the LEFT operand for the node and a FIXED 10 for the method.
// Measured by `santa.spike.TupleValuesProbe`.
//
// **THE CORPUS HAD NO `Global.xor` METHOD ROW AT ALL** — `Global.xor_equivalence` is the `Xor`
// node — so half of this family is new surface rather than a tighter pin on old surface.
//
// WHAT THE MEASUREMENT SAYS, and it confirms the reading on both counts:
//
//   the NODE charges on the SHORTER operand. 22 for every min-length from 1 to 128, 24 from 129:
//   one chunk of 128 at 2 apiece over a base. (1,129), (129,1), (2,300), (300,2), (128,129) and
//   (129,128) all cost 22 — measured, six pairs — so neither operand's own length is what is
//   charged.
//
//   the METHOD charges the same per-item cost over a higher base: 31 up to 128 and 33 from 129.
//   The 9 between node and method is the MethodCall's own cost, and the per-chunk 2 is identical.
//   So a fixed cost for the method is right only while the shorter operand stays inside one chunk.
//
// Hence the four rows this family expects to separate on sigma-rust: the node row whose LEFT
// operand is the long one (it charges 24 where the JVM charges 22), and all three method rows
// (29 against 31) — plus the 129 boundary, where a fixed method cost cannot reach 33 at all.
//
// The trees are BUILT here rather than pasted: a 129-byte operand as a hex literal is unreadable
// and a mis-typed byte would change a length without changing a verdict. Each tree's bytes come
// from the JVM's own serializer and each row's cost is asserted in the test.

import io.circe.Json
import sigma.VersionContext
import sigma.ast._
import sigma.serialization.{SigmaSerializer, ValueSerializer}
import scorex.util.encode.Base16

import RentFixtures._

object AuthoredEvalXorPerItemCost {
  val Op     = "Xor.per_item_cost_base"
  val Source = "santa:authored-xor-per-item-cost"
  private val V3: Byte = 3

  private val dummyInput: Json =
    Json.obj("kind" -> Json.fromString("Int"), "value" -> Json.fromInt(0))

  private def hx(bs: Array[Byte]): String = Base16.encode(bs)
  private def ctx[A](f: => A): A = VersionContext.withVersions(V3, V3)(f)

  private def treeOf(v: Value[SType]): String = ctx {
    val w = SigmaSerializer.startWriter()
    ValueSerializer.serialize(v, w)
    val body = hx(w.toBytes)
    "0b" + hex(vlqU32((body.length / 2).toLong)) + body
  }

  /** `Xor(Coll[Byte] of `l` 0x01, Coll[Byte] of `r` 0x02)` as the node. */
  private def nodeTree(l: Int, r: Int): String = ctx(treeOf(
    Xor(ByteArrayConstant(Array.fill(l)(1: Byte)), ByteArrayConstant(Array.fill(r)(2: Byte)))
      .asInstanceOf[Value[SType]]))

  /** The same through `Global.xor`, the METHOD (SGlobal type id 106, method id 2). */
  private def methodTree(l: Int, r: Int): String = ctx(treeOf(
    MethodCall(Global, SMethod.fromIds(106.toByte, 2.toByte),
      IndexedSeq[Value[SType]](ByteArrayConstant(Array.fill(l)(1: Byte)),
                               ByteArrayConstant(Array.fill(r)(2: Byte))), Map())
      .asInstanceOf[Value[SType]]))

  private case class Row(id: String, slug: String, tree: String, what: String)

  private val rows: Seq[Row] = Seq(
    Row("N1", "node_1_1", nodeTree(1, 1),
      "`Xor(Coll[Byte](1), Coll[Byte](2))` — **cost 22**, the node's floor: one item, one chunk. " +
      "The baseline every other node row is read against. **SEPARATING** only in the sense that a " +
      "wrong BASE moves it; sigma-rust matches this row."),
    Row("N2", "node_128_128", nodeTree(128, 128),
      "128 bytes against 128 — **still 22.** The chunk is 128 wide, so everything from 1 to 128 " +
      "items costs the same. This is the row that makes N3 a boundary rather than a slope."),
    Row("N3", "node_129_129", nodeTree(129, 129),
      "129 against 129 — **24.** The second chunk costs 2. With N2 this pins both the chunk WIDTH " +
      "(128) and the per-chunk price (2), which is what `PerItemCost(10, 2, 128)` means in " +
      "practice. **SEPARATING:** an implementation charging a flat cost cannot produce 24 here."),
    Row("N4", "node_129_left_1_right", nodeTree(129, 1),
      "129 bytes on the LEFT, 1 on the right — **22, not 24.** `xorWithCosting` charges on " +
      "`min(ls.length, rs.length)`, so the long operand is free. **THE SEPARATING ROW of the node " +
      "half:** sigma-rust charges 24 here, i.e. on the left operand's length — a 2-cost " +
      "divergence on a row where the values agree, which is the kind that survives every value " +
      "test."),
    Row("N5", "node_1_left_129_right", nodeTree(1, 129),
      "The mirror: 1 on the left, 129 on the right — **22.** Its pair with N4 is the whole point: " +
      "both orders cost the same on the JVM, so an implementation that reads either operand's own " +
      "length gets exactly one of the two wrong. sigma-rust already matches THIS one, which is " +
      "what localises its fault to the choice of operand rather than to the costing."),
    Row("M1", "method_1_1", methodTree(1, 1),
      "`Global.xor(Coll[Byte](1), Coll[Byte](2))` as the METHOD — **cost 31. THE CORPUS'S FIRST " +
      "`Global.xor` METHOD ROW**; everything before this was the `Xor` node. The 9 over N1 is the " +
      "MethodCall's own cost. **SEPARATING:** sigma-rust charges 29, a flat base that is 2 low."),
    Row("M2", "method_128_128", methodTree(128, 128),
      "128 against 128 through the method — **31**, the same chunk width as the node."),
    Row("M3", "method_129_129", methodTree(129, 129),
      "129 against 129 — **33.** The method charges the SAME per-chunk 2 as the node, so a fixed " +
      "method cost is right only while the shorter operand stays inside one chunk. **THE " +
      "SEPARATING ROW of the method half:** sigma-rust's fixed 10 cannot reach 33."),
    Row("M4", "method_129_left_1_right", methodTree(129, 1),
      "129 on the left, 1 on the right, through the method — **31.** The method charges on the " +
      "shorter operand too, so the node's rule is not the node's alone. **SEPARATING** (29)."),
    Row("M5", "method_1_left_129_right", methodTree(1, 129),
      "The mirror — **31**. With M4 it pins the method's symmetry the way N4/N5 pin the node's. " +
      "**SEPARATING** (29)."))

  def extract(): Map[String, Json] = {
    val entries = rows.zipWithIndex.map { case (r, i) =>
      SpecExtract.authoredEntry(Op, s"[${r.id}] ${r.what}", r.tree,
        s"${r.slug}-accept#$i", dummyInput, V3)
    }
    Map(Op -> SpecExtract.authoredEnvelope(Op, entries, Source)
      .mapObject(_.add("blessed_by", Json.fromString("jvm:sigma-state-6.0.7"))))
  }

  def writeVectors(outDir: java.nio.file.Path): Unit =
    SpecExtract.writeStaging("AuthoredEvalXorPerItemCost", extract(), outDir)
}
