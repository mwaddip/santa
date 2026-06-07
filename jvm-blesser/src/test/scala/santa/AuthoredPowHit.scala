package santa

// ─────────────────────────────────────────────────────────────────────────────
// Authored `Global.powHit` k-parameterized vectors (v6/authored) — the ergots
// vector request (v6 P5c).
//
// `Global.powHit(k, msg, nonce, h, N)` is the Autolykos-2 PoW hit value for custom params
// (raw SUnsignedBigInt, no target compare), V3-gated (methods.scala powHitMethod, methodId 8).
// The ONLY JVM-blessed powHit value vector (LanguageSpecificationV6, k=32) coincides with the
// header-verify path's hardcoded k=32 — `genIndexes` produces exactly 32 indices there — so a
// correct k=32 result says nothing about the general `(0 until k)` index generation powHit adds
// for k ∈ [2,32]. These bless k ∈ {2,16,31} (value+cost) to pin the generalization, plus the
// `require(k≥2, k≤32, N≥16)` boundary (Autolykos2PowValidation) as eval-fail rejects.
//
// Construction: closed `Global.powHit(IntConstant(k), msg, nonce, h, IntConstant(N))` — all five
// args constant, "vary only k" literally true, the result a non-SigmaProp SUnsignedBigInt root
// serialized via the lenient encoder. The entry input is an ignored dummy Int at var 1
// (authoredEntry/RejectEntry require one). msg/nonce/h reuse the blessed inputs from
// BasicOpsTests/LanguageSpecificationV6 so the costs line up with the k=32 spec vector. The
// per-k tree overhead is identical (trees differ only in IntConstant(k)), so the blessed cost
// delta is the pure powHit `(k+1)·7` coefficient. Manual AST; mirrors AuthoredAtLeast /
// AuthoredPowHitHof. The UnsignedBigInt result crosses the SANTA bridge exactly as the k=32
// spec vector's does (EvalCore valueToJson → {kind:UnsignedBigInt}); no codec/schema change.
// ─────────────────────────────────────────────────────────────────────────────

import scorex.util.encode.Base16

import io.circe.Json

import sigma.VersionContext
import sigma.ast.{ByteArrayConstant, ErgoTree, Global, IntConstant, MethodCall, SGlobalMethods,
  SType, Value}
import sigma.ast.ErgoTree.{HeaderType, ZeroHeader}

object AuthoredPowHit {

  /** Pinned target version: full v6 (activated=3, ergoTree=3). `Global.powHit` is V3-gated. */
  val V3: Byte = VersionContext.V6SoftForkVersion

  val Source   = "santa:authored-powhit"
  val OpValue  = "Global.powHit varying k"
  val OpReject = "Global.powHit require boundary"

  // Reuse the blessed inputs (LanguageSpecificationV6:1589 / BasicOpsTests:104), vary only k.
  private val MsgHex   = "0a101b8c6a4f2e"     // 7-byte message
  private val NonceHex = "000000000000002c"   // 8-byte nonce
  private val HHex     = "00000000"           // 4-byte height padding
  private val Msg   = Base16.decode(MsgHex).get
  private val Nonce = Base16.decode(NonceHex).get
  private val H     = Base16.decode(HHex).get
  private val N     = 1048576                  // 1024 * 1024

  /** Closed `Global.powHit(k, msg, nonce, h, n)` (all args constant) serialized at v6 via the
    * lenient (non-SigmaProp-root) encoder — the result type is powHitMethod.t_range
    * (SUnsignedBigInt). Returns (script, treeHex). */
  private def tree(k: Int, n: Int): (String, String) =
    VersionContext.withVersions(V3, V3) {
      val root: Value[SType] = MethodCall(Global, SGlobalMethods.powHitMethod,
        IndexedSeq(IntConstant(k), ByteArrayConstant(Msg), ByteArrayConstant(Nonce),
                   ByteArrayConstant(H), IntConstant(n)), Map())
      val header: HeaderType = ErgoTree.setSizeBit(ErgoTree.headerWithVersion(ZeroHeader, V3))
      (s"""{ Global.powHit($k, fromBase16("$MsgHex"), fromBase16("$NonceHex"), fromBase16("$HHex"), $n) }""",
       Base16.encode(sigma.santa.LenientErgoTree.serialize(header, root)))
    }

  /** Ignored dummy input at var 1 — the trees are closed; authoredEntry requires one. */
  private def dummyInput: Json = Json.obj("kind" -> Json.fromString("Int"), "value" -> Json.fromInt(0))

  /** Two ops: the k≠32 value+cost vectors and the require-boundary reject vectors. */
  def extract(): Map[String, Json] = {
    val valueEntries = Seq(2, 16, 31).zipWithIndex.map { case (k, i) =>
      val (script, hex) = tree(k, N)
      SpecExtract.authoredEntry(OpValue, script, hex, s"k=$k#$i", dummyInput, V3)
    }
    // (name, k, N) — one require-guard out of range each; eval must FAIL (authoredRejectEntry asserts it).
    val rejectCases: Seq[(String, Int, Int)] = Seq(
      ("reject-k=1",  1,  N),    // k < 2
      ("reject-k=33", 33, N),    // k > 32
      ("reject-N=15", 2,  15))   // N < 16 (k valid)
    val rejectEntries = rejectCases.zipWithIndex.map { case ((name, k, n), i) =>
      val (script, hex) = tree(k, n)
      SpecExtract.authoredRejectEntry(OpReject, script, hex, s"$name#$i", dummyInput, V3)
    }
    Map(
      OpValue  -> SpecExtract.authoredEnvelope(OpValue, valueEntries, Source),
      OpReject -> SpecExtract.authoredEnvelope(OpReject, rejectEntries, Source))
  }

  /** Persist authored vectors to a staging dir (build artifact — copied into
    * vectors/eval/v6/authored/ once inspected). Fails loud on a slug collision. */
  def writeVectors(vectors: Map[String, Json], outDir: java.nio.file.Path): Unit = {
    java.nio.file.Files.createDirectories(outDir)
    val collisions = vectors.keys.groupBy(SpecExtract.slug).filter(_._2.size > 1)
    if (collisions.nonEmpty)
      sys.error("AuthoredPowHit.writeVectors: slug collision would silently drop entries — " +
        collisions.map { case (stem, ops) => s"'$stem.json' ← ${ops.mkString(" / ")}" }.mkString("; "))
    vectors.foreach { case (op, json) =>
      val path = outDir.resolve(s"${SpecExtract.slug(op)}.json")
      java.nio.file.Files.write(path, json.spaces2.getBytes(java.nio.charset.StandardCharsets.UTF_8))
    }
  }
}
