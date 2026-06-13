package santa

// ─────────────────────────────────────────────────────────────────────────────
// Authored ContextExtension key-domain witnesses — ergots f5-batch6 Ask 20.
//
// ContextExtension keys are a signed `Byte` (-128..127). ErgoLikeContext.toSigmaContext
// builds the contextVars as `new Array(maxKey+1)` indexed by the signed key
// (ErgoLikeContext.scala:140-146), so a key >= 0x80 (signed-negative) crashes context
// CONSTRUCTION — NegativeArraySizeException (max key) / ArrayIndexOutOfBoundsException
// (id == -1) — BEFORE any bytecode. The crash is on the key's PRESENCE (a GetVar-free
// tree still crashes); the spend does not validate. ergots/sigma-rust represent keys as
// unsigned 0..255 and ACCEPT — a mainnet-REACHABLE consensus fork (the extension is
// attacker-supplied). Spike-proven (ContextExtensionKeySpike): 0..127 build, >=128 throw;
// exact boundary 0x7f accept / 0x80 reject; GetVar of a negative/>=0x80 id with the key
// ABSENT -> None both sides (so the divergence is PURELY at construction, not the read).
//
// santa-eval/v5: the entry carries the SELF box's top-level ContextExtension as
// `extension` = {key (0..255, the unsigned wire byte) -> SValue}. v5 surface,
// {activated 2, ergoTree 0}; all expecteds oracle-emitted (EvalCore.evalWithTopExtension).
// ─────────────────────────────────────────────────────────────────────────────

import io.circe.Json
import scorex.util.encode.Base16
import sigma.VersionContext
import sigma.ast.{BoolToSigmaProp, ErgoTree, GetVar, SInt, SType, TrueLeaf, Value}
import sigma.ast.ErgoTree.ZeroHeader

object AuthoredContextExtensionKey {

  val Activated: Byte = 2
  val ErgoTreeV0: Int = 0
  val Source = "santa:authored-ctx-extension-key"
  val Op = "Context.extension_key_domain"

  private def hexV0(root: Value[SType]): String =
    VersionContext.withVersions(0.toByte, 0.toByte) {
      Base16.encode(sigma.santa.LenientErgoTree.serialize(ZeroHeader, root))
    }

  // A trivial GetVar-free tree (the crash is on key PRESENCE, so the tree is irrelevant to the
  // reject/accept arms) and a GetVar(0x80) tree for the isolation arm.
  private val trueHex   = hexV0(BoolToSigmaProp(TrueLeaf).asInstanceOf[Value[SType]])
  private val getNegHex = hexV0(GetVar((-128).toByte, SInt).asInstanceOf[Value[SType]])

  private def i42: Json = Json.obj("kind" -> Json.fromString("Int"), "value" -> Json.fromInt(42))

  def extract(): Map[String, Json] = {
    val entries = Seq(
      SpecExtract.authoredV5RejectEntry(Op,
        "{ true }  // SELF ContextExtension carries key 0x80 (128): the signed-negative Byte makes " +
          "toSigmaContext's `new Array(maxKey+1)` throw BEFORE any bytecode -> the spend FAILS. " +
          "ergots/sigma-rust treat keys as unsigned 0..255 and ACCEPT — the consensus-fork divergence " +
          "(the extension is attacker-supplied, so mainnet-reachable).",
        trueHex, "key-0x80-present-errored#0", Map(128 -> i42), Activated, ErgoTreeV0),
      SpecExtract.authoredV5Entry(Op,
        "{ true }  // SELF ContextExtension carries key 0x7f (127), the inclusive max: the context " +
          "builds and the tree evals normally — the accept boundary, contrast to 0x80.",
        trueHex, "key-0x7f-present-accept#1", Map(127 -> i42), Activated, ErgoTreeV0),
      SpecExtract.authoredV5Entry(Op,
        "{ getVar[Int](0x80) }  // GetVar of id 0x80 with the key ABSENT (empty extension) -> None, no " +
          "crash: the divergence is PURELY at context construction (key PRESENT), not at the read — " +
          "clean None both sides.",
        getNegHex, "getvar-0x80-absent-none#2", Map.empty, Activated, ErgoTreeV0))
    Map(Op -> SpecExtract.authoredV5Envelope(Op, entries, Source))
  }

  def writeVectors(outDir: java.nio.file.Path): Unit =
    SpecExtract.writeStaging("AuthoredContextExtensionKey", extract(), outDir)
}
