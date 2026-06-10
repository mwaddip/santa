package santa

// ─────────────────────────────────────────────────────────────────────────────
// Authored Context OP-FORM witnesses (ergots Ask 13 remainder). The committed
// Context.properties family pins the PropertyCall WIRE forms (0xdb typeId=101
// methodId=9/10 — byte-verified); MethodCallSerializer.parse builds MethodCall nodes
// (no fold back to the dedicated ops), so the bare OP-FORM wire bytes — the single
// opcodes LastBlockUtxoRootHash (0xa6) and MinerPubkey (0xac), which is what ErgoScript
// `CONTEXT.LastBlockUtxoRootHash` actually compiles to — were never exercised. (The
// coverage manifest's op_codes field is declared-from-irInfo, not observed — these
// entries make the observed surface real.)
//
// Bonus pin: the two wire forms of the SAME property COST DIFFERENTLY — the op-form
// charges the op's fixed cost only (LastBlockUtxoRootHash → 15), the PropertyCall form
// adds the MethodCall machinery (the committed PropertyCall twin blessed at 20). A
// conformer must cost the forms by their wire shape, not by the property.
//
// Trees are the 3-byte segregated-v0 forms 0x10 0x00 0xa6 / 0x10 0x00 0xac. Values come
// from the contract's canonical eval context (§2): lastBlockUtxoRoot = AvlTreeData.dummy,
// minerPubkey = the canonical generator encoding. Spike-confirmed identical at
// activated 2 and 3 → pinned {activated 2, ergoTree 0}.
// ─────────────────────────────────────────────────────────────────────────────

import io.circe.Json
import scorex.util.encode.Base16
import sigma.VersionContext
import sigma.ast.{LastBlockUtxoRootHash, MinerPubkey, SType, Value}
import sigma.ast.ErgoTree.ZeroHeader

object AuthoredContextOpForms {

  val Activated: Byte = 2
  val ErgoTreeV0: Int = 0
  val Source = "santa:authored-context-op-forms"

  val Op = "Context.op_forms"

  private val dummyInput: Json = Json.obj("kind" -> Json.fromString("Int"), "value" -> Json.fromInt(0))

  private def hexAtV0(root: Value[SType]): String =
    VersionContext.withVersions(0.toByte, 0.toByte) {
      Base16.encode(sigma.santa.LenientErgoTree.serialize(ZeroHeader, root))
    }

  def extract(): Map[String, Json] = {
    val entries = Seq(
      SpecExtract.authoredEntryV(Op,
        "{ CONTEXT.LastBlockUtxoRootHash }  // the dedicated OP-FORM (0xa6) — costs 15 vs the PropertyCall form's 20 (Context.properties twin)",
        hexAtV0(LastBlockUtxoRootHash), "lastblockutxoroothash-opform#0", dummyInput, Activated, ErgoTreeV0),
      SpecExtract.authoredEntryV(Op,
        "{ CONTEXT.minerPubKey }  // the dedicated OP-FORM (0xac) — the canonical generator pk bytes from the contract context",
        hexAtV0(MinerPubkey), "minerpubkey-opform#1", dummyInput, Activated, ErgoTreeV0))
    Map(Op -> SpecExtract.authoredEnvelope(Op, entries, Source))
  }

  def writeVectors(outDir: java.nio.file.Path): Unit =
    SpecExtract.writeStaging("AuthoredContextOpForms", extract(), outDir)
}
