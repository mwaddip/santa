package santa

// ─────────────────────────────────────────────────────────────────────────────
// AuthoredSBoxTokenWindow — ergots Ask 18 (f5-batch5): the JVM data layer has NO
// token-COUNT rule; the real gate is the 4096-byte parse window over the box
// CANDIDATE span (r.positionLimit, ErgoBoxCandidate.scala:191-192; crossing it
// at a read = CheckPositionLimit, rule 1014). sigma-rust/ergots bake a 122-count
// cap into the data layer (BoxTokens BoundedVec, ergo_box.rs:37/:87) — divergent
// in BOTH directions. Spike-proven (SBoxTokenWindowSpike, 9/9):
//
//   minimal candidate lengths: 122 → 4038B · 123 → 4071B · 124 → 4104B
//   (minimal token = 32B id + 1B amount; box overhead 10B; +34B txId/index)
//
//   vectors/eval/v5/authored/Box.token_window_const.json (1):
//     the 122-token CONSTANT-form control — value 122, cost 34. Also pins the
//     embeddability edge: a 122-token BoxConstant tree is 4081B ≤ 4096 (the
//     LARGEST tree-embeddable minimal-token count); ≥123 constant forms die at
//     deserializeErgoTree's MaxPropositionSize cap REGARDLESS of the size bit —
//     so the >122 pins must travel as context data, not tree bytes.
//
//   vectors/eval/v6/authored/Global.deserializeTo_Box_token_window.json (5, v4
//   envelope — box bytes ride context var 1 as Coll[Byte], no byte window):
//     destobox-122-accept-control   value 122, the same-seam control
//     destobox-123-accept           value 123 — candidate 4071 ≤ 4096 FITS: the
//                                   over-reject pin (ergots' count cap throws
//                                   'sbox-tokens-out-of-range' today)
//     destobox-124-errored          candidate 4104: the token loop's next read
//                                   fires the window check at position 4103 >
//                                   4096 → rule 1014 (reject parity)
//     destobox-fat-trailing-accept  2 tokens + R4 = 4200B Coll[Byte] LAST field:
//                                   candidate 4281 > 4096 yet ACCEPTS — the
//                                   window is checked BEFORE each read, so an
//                                   overrun on the candidate's FINAL field
//                                   escapes (no subsequent candidate read). The
//                                   over-reject guard for any "candidate ≤ 4096"
//                                   strict-size fix.
//     destobox-fat-then-reg-errored same fat R4 + a small R5 AFTER it: R5's
//                                   read begins past the limit → rule 1014.
//
// Every expected is ORACLE-EMITTED (authoredEntryV / authoredV4Entry /
// authoredV4RejectEntry); per-entry byte lengths are recorded in the script
// docs (ergots asked for the exact fit boundary).
// ─────────────────────────────────────────────────────────────────────────────

import io.circe.Json
import scorex.util.bytesToId
import scorex.util.encode.Base16
import sigma.VersionContext
import sigma.ast.{BoolToSigmaProp, BoxConstant, ByteArrayConstant, ErgoTree, GetVar, Global,
  IntConstant, MethodCall, OptionGet, SBox, SBoxMethods, SCollection, SGlobalMethods, SType,
  STypeVar, SizeOf, TrueLeaf, Value}
import sigma.ast.syntax._
import sigma.ast.ErgoTree.ZeroHeader
import sigma.data.{CBox, Digest32Coll}
import sigma.Colls
import org.ergoplatform.ErgoBox

object AuthoredSBoxTokenWindow {

  val OpConst = "Box.token_window_const"
  val OpDesTo = "Global.deserializeTo_Box_token_window"
  val Source  = "santa:authored-sbox-token-window"

  private val ActivatedV5: Byte = 2
  private val ActivatedV6: Byte = 3

  private val dummyInput: Json = Json.obj("kind" -> Json.fromString("Int"), "value" -> Json.fromInt(0))

  private val trueTree = ErgoTree.fromProposition(BoolToSigmaProp(TrueLeaf))

  /** Minimal-token box: n distinct 32-byte ids (i mod 251 in every byte), amount 1
    * (1-byte VLQ) — 33B per token; value 1000000 + tiny true-tree + height 0. */
  private def mkBox(n: Int,
                    regs: Map[ErgoBox.NonMandatoryRegisterId, sigma.ast.EvaluatedValue[_ <: SType]] = Map.empty) =
    new ErgoBox(
      value = 1000000L,
      ergoTree = trueTree,
      additionalTokens = Colls.fromArray(Array.tabulate(n) { i =>
        (Digest32Coll @@ Colls.fromArray(Array.fill[Byte](32)((i % 251).toByte)), 1L)
      }),
      additionalRegisters = regs,
      transactionId = bytesToId(Array.fill[Byte](32)(0x11)),
      index = 0.toShort,
      creationHeight = 0)

  private val fatColl = ByteArrayConstant(Colls.fromArray(Array.fill[Byte](4200)(0x55)))

  private def fatTrailing = mkBox(2, regs = Map(ErgoBox.R4 -> fatColl))
  private def fatThenReg  = mkBox(2, regs = Map(ErgoBox.R4 -> fatColl, ErgoBox.R5 -> IntConstant(7)))

  /** AuthoredGeCanonical's hexAt — v=0 → ZeroHeader; v>0 → size-bit + version header. */
  private def hexAt(root: Value[SType], v: Byte): String =
    VersionContext.withVersions(v, v) {
      val header = if (v == 0) ZeroHeader
                   else ErgoTree.setSizeBit(ErgoTree.headerWithVersion(ZeroHeader, v))
      Base16.encode(sigma.santa.LenientErgoTree.serialize(header, root))
    }

  private def tokensSize(b: Value[SType]): Value[SType] =
    SizeOf(MethodCall.typed[Value[SType]](b, SBoxMethods.tokensMethod,
      IndexedSeq.empty, Map.empty).asCollection[SType])

  /** { deserializeTo[Box](getVar[Coll[Byte]](1).get).tokens.size } — 17B, carries no box. */
  private def desToVarTree: Value[SType] =
    tokensSize(MethodCall.typed[Value[SType]](Global, SGlobalMethods.deserializeToMethod,
      IndexedSeq(OptionGet(GetVar(1.toByte, SCollection.SByteArray))),
      Map(STypeVar("T") -> SBox)))

  /** The compact byte-collection input form (runner contract §2): value_hex instead of
    * per-item Byte constants — these entries carry >4KB box payloads. */
  private def collJson(bytes: Array[Byte]): Json = Json.obj(
    "kind"      -> Json.fromString("Coll[Byte]"),
    "value_hex" -> Json.fromString(Base16.encode(bytes)))

  private def fullBytes(b: ErgoBox): Array[Byte] = ErgoBox.sigmaSerializer.toBytes(b)

  def extract(): Map[String, Json] = {
    // ── the constant-form control (v5, tree v0 — 4081B, the embeddability edge) ──
    val constEntries = Seq(
      SpecExtract.authoredEntryV(OpConst,
        "{ <122-token minimal box constant>.tokens.size }  // 122 — the count cap sigma-rust/ergots " +
          "enforce is NOT a JVM rule; box 4072B full / 4038B candidate; the tree itself is 4081B " +
          "<= MaxPropositionSize 4096: the LARGEST tree-embeddable minimal-token count (>=123 " +
          "constant forms die at deserializeErgoTree's whole-tree cap, size bit irrelevant — " +
          "bigger boxes must travel as context data, see Global.deserializeTo_Box_token_window)",
        hexAt(tokensSize(BoxConstant(CBox(mkBox(122)))), 0),
        "const-122-accept-control#0", dummyInput, ActivatedV5, 0))

    // ── the deserializeTo family (v6, v4 envelope — bytes via context var 1) ──
    val desToHex = hexAt(desToVarTree, ActivatedV6)
    def d(name: String, script: String, box: ErgoBox, reject: Boolean): Json = {
      val bytes = fullBytes(box)
      if (reject)
        SpecExtract.authoredV4RejectEntry(OpDesTo, script, desToHex, name,
          Map.empty, collJson(bytes), ActivatedV6)
      else
        SpecExtract.authoredV4Entry(OpDesTo, script, desToHex, name,
          Map.empty, collJson(bytes), ActivatedV6)
    }
    val desToEntries = Seq(
      d("destobox-122-accept-control#0",
        "{ deserializeTo[Box](getVar[Coll[Byte]](1).get).tokens.size }  // 122-token minimal box " +
          "(4072B full / 4038B candidate) -> 122: the same-seam control next to the 123 pin",
        mkBox(122), reject = false),
      d("destobox-123-accept#1",
        "{ deserializeTo[Box](getVar[Coll[Byte]](1).get).tokens.size }  // 123-token minimal box " +
          "(4105B full / 4071B candidate <= 4096) -> 123 ACCEPTS: the JVM has no 122 count cap — " +
          "the over-reject pin (a BoundedVec<.,1,122> data layer throws here; ergots " +
          "'sbox-tokens-out-of-range' today)",
        mkBox(123), reject = false),
      d("destobox-124-errored#2",
        "{ deserializeTo[Box](getVar[Coll[Byte]](1).get).tokens.size }  // 124-token minimal box " +
          "(4138B full / 4104B candidate): the token loop's NEXT read begins at position 4103 > " +
          "limit 4096 -> CheckPositionLimit rule 1014, eval-errored (reject parity)",
        mkBox(124), reject = true),
      d("destobox-fat-trailing-accept#3",
        "{ deserializeTo[Box](getVar[Coll[Byte]](1).get).tokens.size }  // 2 tokens + R4 = 4200B " +
          "Coll[Byte] as the LAST candidate field (4315B full / 4281B candidate > 4096) -> ACCEPTS " +
          "value 2: the window is checked BEFORE each read, so an overrun on the candidate's final " +
          "field escapes — a strict 'candidate <= 4096' size check OVER-REJECTS here (the guard " +
          "for ergots' planned fix)",
        fatTrailing, reject = false),
      d("destobox-fat-then-reg-errored#4",
        "{ deserializeTo[Box](getVar[Coll[Byte]](1).get).tokens.size }  // the same fat R4 + a " +
          "small R5 AFTER it (4317B full / 4283B candidate): R5's read begins past the 4096 limit " +
          "-> rule 1014, eval-errored — the crossing-followed-by-a-read arm of the window",
        fatThenReg, reject = true))

    Map(
      OpConst -> SpecExtract.authoredEnvelope(OpConst, constEntries, Source),
      OpDesTo -> SpecExtract.authoredV4Envelope(OpDesTo, desToEntries, Source))
  }

  def writeVectors(outDir: java.nio.file.Path): Unit =
    SpecExtract.writeStaging("AuthoredSBoxTokenWindow", extract(), outDir)
}
