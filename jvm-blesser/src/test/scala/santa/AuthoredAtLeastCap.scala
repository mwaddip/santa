package santa

// ─────────────────────────────────────────────────────────────────────────────
// Authored atLeast 255-children-cap witnesses (ergots Ask 15). The cap is checked
// BEFORE the degenerate reductions: `CSigmaDslBuilder.atLeast` throws on
// `props.length > MaxChildrenCountForAtLeastOp (255)` as its FIRST statement
// (CSigmaDslBuilder.scala:101-107, IllegalArgumentException "Expected input elements
// count should not exceed 255"), and only then calls `AtLeast.reduce`, where the
// degenerate arms live (bound<=0 → TrueProp, bound>n → FalseProp; trees.scala:340-359).
// So `atLeast(0, 256 props)` ERRORS — the cap overrides the bound<=0 degenerate — the
// load-bearing ordering pin. A conformer with the cap in the non-degenerate path only
// (or no cap) over-accepts it as TrueProp.
//
// ConcreteCollection counts are u16, so 256 children are wire-constructible (the trees
// are ~1.2KB: 256 segregated TrivialProp.TrueProp constants + the bound). Spike-confirmed
// identical at activated 2 and 3 → pinned {activated 2, ergoTree 0} (segregated v0),
// co-located with the degenerate-bound family (which covers small collections only and
// never touches the cap).
//
//   cap-overrides-degenerate-bound-errored#0  atLeast(0, 256)   → errored (NOT TrueProp)
//   cap-256-errored#1                         atLeast(2, 256)   → errored
//   cap-exclusive-bound0-TrueProp#2           atLeast(0, 255)   → TrueProp (cap exclusive)
//   bound-2-of-255-TrueProp#3                 atLeast(2, 255)   → TrueProp
//   bound-256-of-255-FalseProp#4              atLeast(256, 255) → FalseProp (the bound>n
//     degenerate still fires at the cap boundary — the bound itself is uncapped)
// ─────────────────────────────────────────────────────────────────────────────

import io.circe.Json
import scorex.util.encode.Base16
import sigma.VersionContext
import sigma.ast.{AtLeast, ConcreteCollection, IntConstant, SSigmaProp, SType, SigmaPropConstant, Value}
import sigma.ast.ErgoTree.ZeroHeader
import sigma.data.TrivialProp

object AuthoredAtLeastCap {

  val Activated: Byte = 2 // v5 / mainnet surface (spike: identical at activated 3)
  val ErgoTreeV0: Int = 0 // segregated-v0 wire forms (header 0x10)
  val Source = "santa:authored-atleast-cap"

  val Op = "atLeast.children_cap"

  // closed trees (no var read) → the dummy input is ignored
  private val dummyInput: Json = Json.obj("kind" -> Json.fromString("Int"), "value" -> Json.fromInt(0))

  private def props(n: Int) =
    ConcreteCollection(IndexedSeq.fill(n)(SigmaPropConstant(TrivialProp.TrueProp): Value[SSigmaProp.type]), SSigmaProp)

  private def tree(bound: Int, n: Int): Value[SType] = AtLeast(IntConstant(bound), props(n))

  /** Serialize a root as a segregated ErgoTree-v0 wire form (header 0x10). */
  private def hexAtV0(root: Value[SType]): String =
    VersionContext.withVersions(0.toByte, 0.toByte) {
      Base16.encode(sigma.santa.LenientErgoTree.serialize(ZeroHeader, root))
    }

  def extract(): Map[String, Json] = {
    val entries = Seq(
      SpecExtract.authoredRejectEntryV(Op,
        "{ atLeast(0, Coll of 256 sigmaProps) }  // JVM: 'Expected input elements count should not exceed 255' — the cap throws BEFORE reduce's bound<=0 → TrueProp degenerate (the ordering pin)",
        hexAtV0(tree(0, 256)), "cap-overrides-degenerate-bound-errored#0", dummyInput, Activated, ErgoTreeV0),
      SpecExtract.authoredRejectEntryV(Op,
        "{ atLeast(2, Coll of 256 sigmaProps) }  // JVM: same cap error on a non-degenerate bound",
        hexAtV0(tree(2, 256)), "cap-256-errored#1", dummyInput, Activated, ErgoTreeV0),
      SpecExtract.authoredEntryV(Op,
        "{ atLeast(0, Coll of 255 sigmaProps) }  // ACCEPTS TrueProp — the cap is exclusive at 255; bound<=0 degenerate fires normally",
        hexAtV0(tree(0, 255)), "cap-exclusive-bound0-TrueProp#2", dummyInput, Activated, ErgoTreeV0),
      SpecExtract.authoredEntryV(Op,
        "{ atLeast(2, Coll of 255 TrueProps) }  // ACCEPTS TrueProp — non-degenerate reduce at the cap boundary",
        hexAtV0(tree(2, 255)), "bound-2-of-255-TrueProp#3", dummyInput, Activated, ErgoTreeV0),
      SpecExtract.authoredEntryV(Op,
        "{ atLeast(256, Coll of 255 sigmaProps) }  // ACCEPTS FalseProp — the bound>n degenerate fires at the cap boundary (the bound itself is uncapped)",
        hexAtV0(tree(256, 255)), "bound-256-of-255-FalseProp#4", dummyInput, Activated, ErgoTreeV0))
    Map(Op -> SpecExtract.authoredEnvelope(Op, entries, Source))
  }

  def writeVectors(outDir: java.nio.file.Path): Unit =
    SpecExtract.writeStaging("AuthoredAtLeastCap", extract(), outDir)
}
