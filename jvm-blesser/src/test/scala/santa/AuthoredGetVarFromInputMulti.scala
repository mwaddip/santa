package santa

// ─────────────────────────────────────────────────────────────────────────────
// Authored `Context.getVarFromInput` vectors — multi-input context + 0xFF varId
// — v6/authored — ergots vector request P7a-3.
//
// Companion to AuthoredGetVarFromInput (which covers the single-input cases at
// inputIdx=0, varId=11). This file adds two groups:
//
// Group 1 — spec's 4 verifyCases replicated with multi-input context.
//
//   The spec's LanguageSpecificationV6.scala:1889-1919 uses
//   `getVarFromInput[Boolean](0, 11)` with a single spending input whose
//   extension carries var 11. Here we replicate the same 4 logical outcomes but
//   target inputIdx=1 (a second input) in a 2-input spending transaction:
//
//   Case A — "multi-input-no-var-at-idx1":
//     tx has 2 inputs; input[0] has var 11 = Boolean(true); input[1] has NO var 11.
//     Script reads input[1], var 11 → absent → Some(None).
//     (Mirrors spec's ctx: tx has 0 inputs → index out-of-range → None)
//
//   Case B — "multi-input-present-true-at-idx1":
//     tx has 2 inputs; input[0] has no extension; input[1] has var 11 = Boolean(true).
//     Script reads input[1], var 11 → Some(true).
//     (Mirrors spec's ctx2: 1 input with Boolean(true))
//
//   Case C — "multi-input-wrong-type-at-idx1":
//     tx has 2 inputs; input[0] has no extension; input[1] has var 11 = Int(0).
//     Script reads input[1], var 11 → type mismatch → None.
//     (Mirrors spec's ctx3: 1 input with IntConstant(0))
//
//   Case D — "multi-input-present-false-at-idx1":
//     tx has 2 inputs; input[0] has no extension; input[1] has var 11 = Boolean(false).
//     Script reads input[1], var 11 → Some(false).
//     (Mirrors spec's ctx4: 1 input with Boolean(false))
//
//   Tree: `{ getVarFromInput[Boolean](1, 11) }` — inputIdx ShortConstant(1), varId ByteConstant(11).
//
// Group 2 — negative-varId pin: byte-identity matching at 0xFF.
//
//   The JVM's own spec suite never exercises varIds >= 0x80 (the ByteConstant in the
//   AST is a signed Byte; extension keys in the wire are unsigned 0-255).
//   ContextExtension keys are Byte (signed), and `extension.get(-1.toByte)` looks up
//   the same slot as extension key 0xFF (they ARE the same Byte value on the JVM).
//   EvalCore.evalWithInputExtensions writes extension keys as `id & 0xff` in the JSON
//   (the "255" string key), but the Scala Map is keyed by Byte, so -1.toByte == 0xFF.
//
//   Case E — "negative-varid-0xff":
//     Script: `{ getVarFromInput[Boolean](0, -1) }` (ByteConstant(-1) = 0xFF wire).
//     tx has 1 input; input[0] extension carries key (-1.toByte = 0xFF) = Boolean(true).
//     Oracle verdict: whatever the JVM returns IS the pin.
//
// Schema: santa-eval/v3 (inputs[].extension map).
// Output: vectors/eval/v6/authored/Context.getVarFromInput_multi_input.json
// ─────────────────────────────────────────────────────────────────────────────

import scorex.util.encode.Base16
import io.circe.Json
import sigma.VersionContext
import sigma.ast.{ByteConstant, Context, ErgoTree, MethodCall, SBoolean, SContextMethods, SType, ShortConstant}
import sigma.ast.ErgoTree.{HeaderType, ZeroHeader}

object AuthoredGetVarFromInputMulti {

  val V3: Byte = VersionContext.V6SoftForkVersion
  val Source   = "santa:authored-getvarfrominput-multi"
  val Op       = "Context.getVarFromInput"

  /** Serialized `{ getVarFromInput[Boolean](inputIdx, varId) }` tree at v6.
    * Returns (script string, tree_bytes_hex). */
  private def tree(inputIdx: Short, varId: Byte): (String, String) =
    VersionContext.withVersions(V3, V3) {
      val mc = MethodCall(Context, SContextMethods.getVarFromInputMethod,
        IndexedSeq(ShortConstant(inputIdx), ByteConstant(varId)), Map(SType.tT -> SBoolean))
      val header: HeaderType = ErgoTree.setSizeBit(ErgoTree.headerWithVersion(ZeroHeader, V3))
      (s"{ getVarFromInput[Boolean]($inputIdx, $varId) }",
       Base16.encode(sigma.santa.LenientErgoTree.serialize(header, mc)))
    }

  private def boolJson(b: Boolean): Json =
    Json.obj("kind" -> Json.fromString("Boolean"), "value" -> Json.fromBoolean(b))
  private def intJson(n: Int): Json =
    Json.obj("kind" -> Json.fromString("Int"), "value" -> Json.fromInt(n))

  /** Produce all authored entries and wrap in the v3 envelope. */
  def extract(): Map[String, Json] = {

    // ── Group 1: 4 multi-input spec cases, reading from inputIdx=1 ──────────────
    val (scriptIdx1, hexIdx1) = tree(1, 11)

    // Each scenario is a Seq[Map[Byte, Json]] — one map per input.
    // Index 0 = empty extension (or carries a distracting var), index 1 = the target.
    val multiInputCases: Seq[(String, Seq[Map[Byte, Json]])] = Seq(
      // Case A: input[1] has no var 11 → absent → None
      "multi-input-no-var-at-idx1" -> Seq(
        Map(11.toByte -> boolJson(true)),   // input[0]: has var 11 (irrelevant to the read)
        Map.empty[Byte, Json]               // input[1]: no var 11
      ),
      // Case B: input[1] has var 11 = Boolean(true) → Some(true)
      "multi-input-present-true-at-idx1" -> Seq(
        Map.empty[Byte, Json],              // input[0]: empty
        Map(11.toByte -> boolJson(true))    // input[1]: var 11 = true
      ),
      // Case C: input[1] has var 11 = Int(0) → type mismatch → None
      "multi-input-wrong-type-at-idx1" -> Seq(
        Map.empty[Byte, Json],              // input[0]: empty
        Map(11.toByte -> intJson(0))        // input[1]: var 11 = Int(0)
      ),
      // Case D: input[1] has var 11 = Boolean(false) → Some(false)
      "multi-input-present-false-at-idx1" -> Seq(
        Map.empty[Byte, Json],              // input[0]: empty
        Map(11.toByte -> boolJson(false))   // input[1]: var 11 = false
      )
    )

    val group1Entries = multiInputCases.zipWithIndex.map { case ((name, exts), i) =>
      SpecExtract.authoredV3Entry(Op, scriptIdx1, hexIdx1, s"$name#$i", exts, V3)
    }

    // ── Group 2: OOB input-index pin ────────────────────────────────────────────
    // inputIdx=5 with only 2 spending inputs: index is out-of-range.
    // The spec's own "ctx" case exercises OOB by using a 0-input tx, but that is
    // structurally different from a multi-input tx whose index overflows the
    // inputs array. An impl that branches at OOB *before* the extension lookup
    // would be untested if only the var-absent path (#0) is pinned.
    // 2-input shape kept; both extensions empty (no var to find — OOB fires first).
    val (scriptOob, hexOob) = tree(5, 11)
    val oobEntry = SpecExtract.authoredV3Entry(
      Op, scriptOob, hexOob,
      "oob-input-index#4",
      Seq(Map.empty[Byte, Json], Map.empty[Byte, Json]),  // 2 inputs, idx 5 ≥ 2 → OOB
      V3)

    // ── Group 3: negative-varId 0xFF pin ────────────────────────────────────────
    // ByteConstant(-1) in the AST corresponds to 0xFF on the wire. The JVM Byte
    // type is signed, so -1.toByte == (0xFF).toByte — they are the same JVM value.
    // Extension keys are also Byte (signed), so extension.get(-1.toByte) looks up the
    // same slot as key 255.  EvalCore stores extension keys as signed Byte in the Map;
    // the JSON writer uses `id & 0xff` to produce the unsigned string key "255".
    val (scriptNeg, hexNeg) = tree(0, (-1).toByte)
    val negVarIdEntry = SpecExtract.authoredV3Entry(
      Op, scriptNeg, hexNeg,
      "negative-varid-0xff#5",
      Seq(Map((-1).toByte -> boolJson(true))),  // extension key 0xFF = Boolean(true)
      V3)

    val allEntries = group1Entries :+ oobEntry :+ negVarIdEntry
    Map(Op -> SpecExtract.authoredV3Envelope(Op, allEntries, Source))
  }

  /** Persist to outDir (pass `vectors/eval/v6/authored/` after baseline lock). */
  def writeVector(vectors: Map[String, Json], outDir: java.nio.file.Path): Unit = {
    java.nio.file.Files.createDirectories(outDir)
    vectors.foreach { case (_, json) =>
      // File is keyed separately from the existing Context.getVarFromInput.json by the
      // "_multi_input" suffix; slug collision with the sibling would crash here.
      val path = outDir.resolve("Context.getVarFromInput_multi_input.json")
      java.nio.file.Files.write(path, json.spaces2.getBytes(java.nio.charset.StandardCharsets.UTF_8))
    }
  }
}
