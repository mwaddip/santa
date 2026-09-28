package santa

// Authored eval vector for ergots' sized-tree requests (2026-09-28): substConstants on a size-flagged template whose
// declared size is above u32. substConstants reads the template's header and size through deserializeHeaderAndSize:
// the size is a getUInt, so its u32 bound applies, and otherwise it is ignored. The result is written with the true
// size (sigmastate 6.0.6, under the node's v6 context). The tree is the spec vector's function
// `(x: (Coll[Byte], Int)) => substConstants(x._1, Coll(x._2), Coll(sigmaProp(false)))`
// (Fix_substConstants_in_v6.0_for_ErgoTree_version_0); the template comes in as its input, so each entry is one call.
// Evaluated through the same EvalCore oracle as every authored eval vector; extract() fails loud if an outcome drifts.

import io.circe.Json
import sigma.VersionContext

object AuthoredEvalSizedTreeRequests {
  val V3: Byte = VersionContext.V6SoftForkVersion
  val Source = "santa:authored-eval-sized-tree-requests"
  val OpSubstSize = "substConstants:declared_size_u32"
  /** The spec vector's substConstants function, v3. */
  val SubstTree = "1b21010100dad901014c0e748c7201018301048c720102830108d1730001e4e3014c0e"

  /** The input (template, 0): replace constant 0 of `templateHex`. */
  private def input(templateHex: String): Json = {
    val bytes = templateHex.grouped(2).map(h => Integer.parseInt(h, 16).toByte.toInt).toSeq
    Json.obj("kind" -> Json.fromString("Tuple"), "items" -> Json.arr(
      Json.obj("kind" -> Json.fromString("Coll"), "elem" -> Json.obj("tag" -> Json.fromString("SByte")),
        "items" -> Json.arr(bytes.map(v => Json.obj("kind" -> Json.fromString("Byte"), "value" -> Json.fromInt(v))): _*)),
      Json.obj("kind" -> Json.fromString("Int"), "value" -> Json.fromInt(0))))
  }

  /** A size-flagged, segregated v0 template: constant 0 = SigmaProp(true), body ConstantPlaceholder(0). */
  private def template(sizeVlq: String): String = "18" + sizeVlq + "01" + "08d3" + "7300"

  def extract(): Map[String, Json] = {
    val script = "{ (x: (Coll[Byte], Int)) => substConstants[Any](x._1, Coll[Int](x._2), Coll[Any](sigmaProp(false))) }"
    val entries = Seq(
      SpecExtract.authoredRejectEntryV(OpSubstSize,
        s"$script on the template ${template("8080808010")}, declared size 2^32  // JVM: the size is read with getUInt, " +
        "whose u32 bound fails: the call errors",
        SubstTree, "subst-declared-size-above-u32-errored#0", input(template("8080808010")), V3, ergoTree = 3),
      SpecExtract.authoredEntryV(OpSubstSize,
        s"$script on the template ${template("ffffffff0f")}, declared size 2^32 - 1  // JVM: the size fits u32 and is " +
        "otherwise ignored; the result is written with the true size: 18 05 01 08 d2 73 00",
        SubstTree, "subst-declared-size-u32-max-accept#1", input(template("ffffffff0f")), V3, ergoTree = 3),
      SpecExtract.authoredEntryV(OpSubstSize,
        s"$script on the template ${template("05")}, the true size  // JVM: 18 05 01 08 d2 73 00, the control",
        SubstTree, "subst-declared-size-true-accept#2", input(template("05")), V3, ergoTree = 3))
    // First blessed on the 6.0.6 oracle; SpecExtract's envelope still carries the 6.0.3 stamp of the older vectors.
    Map(OpSubstSize -> SpecExtract.authoredEnvelope(OpSubstSize, entries, Source)
      .mapObject(_.add("blessed_by", Json.fromString("jvm:sigma-state-6.0.6"))))
  }

  def writeVectors(outDir: java.nio.file.Path): Unit =
    SpecExtract.writeStaging("AuthoredEvalSizedTreeRequests", extract(), outDir)
}
