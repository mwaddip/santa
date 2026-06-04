package santa

// ─────────────────────────────────────────────────────────────────────────────
// Authored `atLeast(bound, children)` degenerate-bound vectors (v5/authored) —
// sigma-rust fix/atleast-degenerate-bound.
//
// The JVM `AtLeast.reduce` reduces a degenerate bound to a trivial prop, never errors for a
// valid tree: `bound ≤ 0` → TrueProp; `bound > nChildren` (incl. `> 255`, since a valid tree
// has nChildren ≤ 255) → FalseProp. sigma-rust's `AtLeast` eval had eager error guards BEFORE
// reduce — throwing on `bound > size`, `bound > 255`, `bound < 0` (i32→u8) — and forked the node
// off testnet block 184,137 on `atLeast(1, <empty Coll[SigmaProp]>)`.
//
// Eval-catchable (unlike the 2666 tree_version / DeserializeContext-substitution cases): the bug
// is in `AtLeast::eval` itself, with bound + children read straight from the tree and no pre-set
// state to mask it — the eval runner reduces it directly. ACCEPT vectors: eval yields a SigmaProp
// (TrueProp / FalseProp / CAND / COR), which EvalCore encodes (valueToJson SigmaProp → raw_hex).
// Value-only — AtLeast cost is charged before the bound check regardless. Manual AST; mirrors
// AuthoredPowHitHof.
//
// Two DISTINCT props (ProveDlog vs ProveDHTuple) so CAND/COR are genuine, not collapsed. The trees
// are closed (bound + props are constants); the entry input is an ignored dummy Int at var 1
// (authoredEntry requires one). atLeast is a base op and the bug is version-agnostic (the real
// tree is V0), so v5/authored is the honest home.
// ─────────────────────────────────────────────────────────────────────────────

import scorex.util.encode.Base16

import io.circe.Json

import sigma.VersionContext
import sigma.ast.{AtLeast, ConcreteCollection, ErgoTree, IntConstant, SigmaPropConstant, SSigmaProp, Value}
import sigma.ast.ErgoTree.{HeaderType, ZeroHeader}
import sigma.crypto.{CryptoConstants, EcPointType}
import sigma.data.{CSigmaProp, ProveDHTuple, ProveDlog}

object AuthoredAtLeast {

  /** v5 (activated=2, ergoTree=2). */
  val V2: Byte = VersionContext.JitActivationVersion

  val Source = "santa:authored-atleast"
  val Op     = "atLeast with a degenerate bound"

  private val gen: EcPointType = CryptoConstants.dlogGroup.generator
  // two distinct sigma props → CAND/COR(p1,p2) are genuine (no dedup/collapse)
  private val p1: Value[SSigmaProp.type] = SigmaPropConstant(CSigmaProp(ProveDlog(gen)))
  private val p2: Value[SSigmaProp.type] = SigmaPropConstant(CSigmaProp(ProveDHTuple(gen, gen, gen, gen)))

  /** `atLeast(bound, Coll[SigmaProp](props))` serialized at v5 (lenient — SigmaProp root). */
  private def tree(bound: Int, props: Seq[Value[SSigmaProp.type]]): (String, String) =
    VersionContext.withVersions(V2, V2) {
      val root = AtLeast(IntConstant(bound), ConcreteCollection(props, SSigmaProp))
      val header: HeaderType = ErgoTree.setSizeBit(ErgoTree.headerWithVersion(ZeroHeader, V2))
      (s"{ atLeast($bound, Coll[SigmaProp] of size ${props.size}) }",
       Base16.encode(sigma.santa.LenientErgoTree.serialize(header, root)))
    }

  /** Ignored dummy input at var 1 — the trees are closed; authoredEntry requires an input. */
  private def dummyInput: Json = Json.obj("kind" -> Json.fromString("Int"), "value" -> Json.fromInt(0))

  /** op -> v2 envelope. ✗ marks the bounds sigma-rust's eager guard errored on (the catch);
    * the others are already-correct reductions (anchors + the strict-`>` boundary). */
  def extract(): Map[String, Json] = {
    val pair = Seq(p1, p2)
    val cases: Seq[(String, Int, Seq[Value[SSigmaProp.type]])] = Seq(
      ("bound-0-TrueProp",       0,   pair),                          // bound ≤ 0 → TrueProp
      ("bound-neg1-TrueProp",    -1,  pair),                          // bound < 0 → TrueProp     ✗
      ("bound-1-COR",            1,   pair),                          // → COR(p1, p2)
      ("bound-2-CAND",           2,   pair),                          // bound == size → CAND     (NOT false)
      ("bound-3-gt-size-False",  3,   pair),                          // bound > size → FalseProp ✗ (boundary)
      ("bound-256-gt-255-False", 256, pair),                          // bound > 255 → FalseProp  ✗
      ("empty-input-False",      1,   Seq.empty[Value[SSigmaProp.type]])) // empty → FalseProp    ✗ (block 184137)
    val entries = cases.zipWithIndex.map { case ((name, bound, props), i) =>
      val (script, treeHex) = tree(bound, props)
      SpecExtract.authoredEntry(Op, script, treeHex, s"$name#$i", dummyInput, V2)
    }
    Map(Op -> SpecExtract.authoredEnvelope(Op, entries, Source))
  }

  /** Persist authored vectors to a staging dir (build artifact — copied into
    * vectors/eval/v5/authored/ once inspected). Fails loud on a slug collision. */
  def writeVectors(vectors: Map[String, Json], outDir: java.nio.file.Path): Unit = {
    java.nio.file.Files.createDirectories(outDir)
    val collisions = vectors.keys.groupBy(SpecExtract.slug).filter(_._2.size > 1)
    if (collisions.nonEmpty)
      sys.error("AuthoredAtLeast.writeVectors: slug collision would silently drop entries — " +
        collisions.map { case (stem, ops) => s"'$stem.json' ← ${ops.mkString(" / ")}" }.mkString("; "))
    vectors.foreach { case (op, json) =>
      val path = outDir.resolve(s"${SpecExtract.slug(op)}.json")
      java.nio.file.Files.write(path, json.spaces2.getBytes(java.nio.charset.StandardCharsets.UTF_8))
    }
  }
}
