package santa

// ─────────────────────────────────────────────────────────────────────────────
// Authored FunDef nTpeArgs count-bound witness (ergots version-signedness ask 22).
//
// The JVM `ValDefSerializer.parse` reads `nTpeArgs = r.getByte()` (SIGNED) then
// `safeNewArray[STypeVar](nTpeArgs)` — which throws NegativeArraySizeException on a
// negative size, so a count byte >= 0x80 (signed-negative) is REJECTED at DESERIALIZE.
// Serialize emits the count via `w.put(len.toByteExact)` (Byte-exact), so a FunDef with
// > 127 type args is unserializable (cap 127, NOT 255 — contrast `Tuple`'s putUByte/255).
// ergots/sigma-rust read the count unsigned and loop unbounded, over-accepting 128..255.
//
// Oracle-confirmed (spike, sigma-state 6.0.3):
//   nTpeArgs 127 (count byte 0x7f) -> safeNewArray(127) accepts -> Int 5 @ cost 13 (accept boundary)
//   nTpeArgs 128 (count byte 0x80) -> NegativeArraySizeException: -128 at DESERIALIZE
//
// Construction mirrors AuthoredValDefId: build a FunDef with 127 type args (the count
// serializes as 0x7f), then PATCH the count byte 0x7f -> 0x80 (same-length edit). 128 can't
// be serialized (toByteExact throws), so the reject can only arise from crafted wire bytes.
// The FunDef is bound-but-never-applied (applying a type-var lambda throws — see
// AuthoredFunDefTypeVar), so the accept evals to 5. v6 (type-var serialization needs v3).
// ─────────────────────────────────────────────────────────────────────────────

import scorex.util.encode.Base16

import io.circe.Json

import sigma.VersionContext
import sigma.ast.{BlockValue, ErgoTree, FuncValue, IntConstant, STypeVar, SType, ValDef, ValUse, Value}
import sigma.ast.ErgoTree.{HeaderType, ZeroHeader}

object AuthoredFunDefNTpeArgs {

  /** Pinned target: full v6 (activated=3, ergoTree=3) — FunDef type-var serialization needs v3. */
  val V3: Byte = VersionContext.V6SoftForkVersion

  val Source = "santa:authored-fundef-ntpeargs"
  val Op     = "FunDef.nTpeArgs_count_bound"

  /** Closed tree (bound-never-applied) → the dummy input is ignored. */
  private val dummyInput: Json = Json.obj("kind" -> Json.fromString("Int"), "value" -> Json.fromInt(0))

  /** `{ val f[T1..Tn] = {(x: T1) => x}; 5 }` — a FunDef with n type args, bound, never applied. */
  private def funDef(n: Int): Value[SType] = {
    val tvs = (1 to n).map(i => STypeVar("T" + i))
    val poly = FuncValue(IndexedSeq(2 -> tvs.head), ValUse(2, tvs.head))
    BlockValue(IndexedSeq(ValDef(1, tvs, poly)), IntConstant(5))
  }

  private def hexV3(root: Value[SType]): String =
    VersionContext.withVersions(V3, V3) {
      val header: HeaderType = ErgoTree.setSizeBit(ErgoTree.headerWithVersion(ZeroHeader, V3))
      Base16.encode(sigma.santa.LenientErgoTree.serialize(header, root))
    }

  // accept: 127 type args (count byte 0x7f), built via the AST.
  val acceptHex: String = hexV3(funDef(127))

  // reject: a FunDef with nTpeArgs = 128. 128 type args can't be serialized (toByteExact caps at
  // 127), so the over-max count is only reachable from crafted wire bytes. Assemble it by splicing
  // 128 copies of a single STypeVar chunk into a 1-type-var base, with the count byte = 0x80
  // (signed -128). The JVM safeNewArray(-128) throws BEFORE reading entries (errored, regardless of
  // them); an UNSIGNED reader reads all 128 valid entries + the body and OVER-ACCEPTS — the
  // divergence. (A 0x80 count over only 127 entries makes an unsigned reader EOF — a coarse
  // error-match that HIDES the over-accept; conform confirmed eni coarse-greens that weaker shape.)
  val rejectHex: String = {
    val tv = STypeVar("a")
    def funDefA(n: Int): Value[SType] =
      BlockValue(IndexedSeq(ValDef(1, Seq.fill(n)(tv), FuncValue(IndexedSeq(2 -> tv), ValUse(2, tv)))), IntConstant(5))
    val base1 = Base16.decode(hexV3(funDefA(1))).get
    val base2 = Base16.decode(hexV3(funDefA(2))).get
    val di = base1.indexWhere(_ == 0xd7.toByte)
    require(di >= 0 && base1.count(_ == 0xd7.toByte) == 1, s"FunDef 0xd7 not unique (di=$di)")
    require((base1(di + 2) & 0xff) == 0x01, s"expected count 0x01 at $di+2, got ${base1(di + 2) & 0xff}")
    val chunkLen = base2.length - base1.length            // one STypeVar("a") wire chunk
    require(chunkLen > 0, s"unexpected chunkLen=$chunkLen")
    val chunk = base1.slice(di + 3, di + 3 + chunkLen)
    val rhs   = base1.slice(di + 3 + chunkLen, base1.length)  // FuncValue rhs + the BlockValue result
    val parts: Seq[Array[Byte]] = Seq(base1.slice(0, di + 2), Array(0x80.toByte)) ++ Seq.fill(128)(chunk) ++ Seq(rhs)
    Base16.encode(Array.concat(parts: _*))
  }

  def extract(): Map[String, Json] = {
    val entries = Seq(
      SpecExtract.authoredEntry(Op,
        "{ val f[T1..T127] = {(x: T1) => x}; 5 }  // FunDef nTpeArgs = 127 (count byte 0x7f): JVM getByte() -> safeNewArray(127) accepts -> Int 5 (the accept boundary)",
        acceptHex, "nTpeArgs-127-accept#0", dummyInput, V3),
      SpecExtract.authoredRejectEntry(Op,
        "{ val f[T1..T127] = {(x: T1) => x}; 5 }  // count byte patched to 0x80 (=128): JVM ValDefSerializer reads it with the SIGNED getByte() -> -128 -> safeNewArray(-128) NegativeArraySizeException at DESERIALIZE. ergots/sigma-rust read it unsigned (unbounded loop), over-accepting 128..255 (ask 22).",
        rejectHex, "nTpeArgs-128-reject#1", dummyInput, V3))
    Map(Op -> SpecExtract.authoredEnvelope(Op, entries, Source))
  }

  def writeVectors(outDir: java.nio.file.Path): Unit =
    SpecExtract.writeStaging("AuthoredFunDefNTpeArgs", extract(), outDir)
}
