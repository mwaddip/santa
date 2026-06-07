package santa

import io.circe.Json
import scorex.util.encode.Base16
import sigma.VersionContext
import sigma.ast._

/** Context property + CONTEXT.preHeader accessor vectors (v5/authored).
  *
  * These are CLOSED trees evaluated over the CONTRACT dummy context — the context every
  * runner constructs identically. Dummy context values:
  *   - preHeader: version=0, parentId=empty Coll[Byte], timestamp=3L, nBits=0L,
  *                height=0, minerPk=group generator, votes=empty Coll[Byte]
  *   - selfBoxIndex=0, lastBlockUtxoRoot=AvlTreeData.dummy,
  *     minerPubKey=generator (33-byte SEC1), dataInputs=empty, headers=empty
  *
  * preHeader chain vectors (Context.preHeader_accessors):
  *   Each `CONTEXT.preHeader.<acc>` tree is a two-level MethodCall — SContextMethods.preHeader
  *   (101:3) chained with the SPreHeaderMethods accessor — so one tree exercises BOTH methods.
  *   Seven entries cover all SPreHeaderMethods: version, parentId, timestamp, nBits, height,
  *   minerPk, votes.
  *
  * Context property vectors (Context.properties):
  *   Single MethodCall over Context for: dataInputs, headers, selfBoxIndex,
  *   LastBlockUtxoRootHash, minerPubKey. (INPUTS/OUTPUTS/HEIGHT/SELF/getVar are already covered
  *   by dedicated op-forms in the corpus and are excluded from this file.)
  *
  * Scope note: a 3-range preHeader.timestamp treatment (signed-view ranges, like the Header
  * timestamp vectors) is NOT expressible here — the dummy context is contract-frozen and the
  * timestamp is pinned at 3. Range treatment for preHeader accessors needs a context-parameterizing
  * schema (future design decision; not a gap in this file).
  *
  * The authoredEntry dummy-input idiom mirrors AuthoredAtLeast: closed trees don't consume
  * var 1, but authoredEntry requires a wire-encodable input — a dummy Int 0 is passed.
  * No VersionContext wrap needed: no version-gated constants in these trees.
  */
object AuthoredContextProps {

  val OpPre  = "Context.preHeader_accessors"
  val OpCtx  = "Context.properties"
  val SourcePre = "santa:authored-context-preheader"
  val SourceCtx = "santa:authored-context-properties"

  // Pinned target version: v5 (activated=2, ergoTree=2).
  private val V2: Byte = VersionContext.JitActivationVersion

  private val treeHeaderV2: ErgoTree.HeaderType =
    ErgoTree.setSizeBit(ErgoTree.headerWithVersion(ErgoTree.ZeroHeader, V2))

  /** Ignored dummy input at var 1 — the trees are closed; authoredEntry requires one. */
  private def dummyInput: Json = Json.obj("kind" -> Json.fromString("Int"), "value" -> Json.fromInt(0))

  // ── CONTEXT.preHeader.<acc> trees ──────────────────────────────────────────
  // Two-level MethodCall: Context → preHeader (SContextMethods id=3) → accessor (SPreHeaderMethods).
  // SContextMethods.preHeader is a propertyCall (no args); same for all SPreHeaderMethods.

  private def preHeaderAccessorTreeHex(accName: String): String = {
    val ctxPreHeader = MethodCall(
      Context,
      SContextMethods.getMethodByName("preHeader"),
      IndexedSeq.empty,
      Map.empty
    )
    val root = MethodCall(
      ctxPreHeader,
      SPreHeaderMethods.getMethodByName(accName),
      IndexedSeq.empty,
      Map.empty
    )
    Base16.encode(sigma.santa.LenientErgoTree.serialize(treeHeaderV2, root))
  }

  // ── CONTEXT.<prop> trees ────────────────────────────────────────────────────
  // Single MethodCall over Context.

  private def ctxPropTreeHex(propName: String): String = {
    val root = MethodCall(
      Context,
      SContextMethods.getMethodByName(propName),
      IndexedSeq.empty,
      Map.empty
    )
    Base16.encode(sigma.santa.LenientErgoTree.serialize(treeHeaderV2, root))
  }

  // All 7 SPreHeaderMethods names (exact casing from SPreHeaderMethods):
  private val preHeaderAccessorNames: Seq[String] = Seq(
    "version", "parentId", "timestamp", "nBits", "height", "minerPk", "votes"
  )

  // 5 Context properties not already covered by dedicated op-forms:
  private val ctxPropNames: Seq[String] = Seq(
    "dataInputs", "headers", "selfBoxIndex", "LastBlockUtxoRootHash", "minerPubKey"
  )

  def extract(): Map[String, Json] = {
    // preHeader accessor entries — one per SPreHeaderMethods name
    val preEntries = preHeaderAccessorNames.map { name =>
      SpecExtract.authoredEntry(
        OpPre,
        s"{ CONTEXT.preHeader.$name }",
        preHeaderAccessorTreeHex(name),
        s"preHeader.$name#dummy",
        dummyInput,
        V2
      )
    }

    // Context property entries
    val ctxEntries = ctxPropNames.map { name =>
      SpecExtract.authoredEntry(
        OpCtx,
        s"{ CONTEXT.$name }",
        ctxPropTreeHex(name),
        s"CONTEXT.$name#dummy",
        dummyInput,
        V2
      )
    }

    Map(
      OpPre -> SpecExtract.authoredEnvelope(OpPre, preEntries, SourcePre),
      OpCtx -> SpecExtract.authoredEnvelope(OpCtx, ctxEntries, SourceCtx)
    )
  }

  def writeVectors(outDir: java.nio.file.Path): Unit =
    SpecExtract.writeStaging("AuthoredContextProps", extract(), outDir)
}
