package santa

import io.circe.Json
import io.circe.parser.{parse => parseJson}

/** Transform: read the 61 oracle-blessed full-context goldens from ergots'
  * captured corpus and write them as santa-eval/v6-fullctx vectors under
  * vectors/eval/v6/captured/.
  *
  * Source schema: ergots-captured/v6-fullctx-golden@1
  *   { schema, tree_bytes_hex, context, expected:{tree_version,value,cost}, provenance }
  *
  * Target schema: santa-eval/v6-fullctx
  *   one file per golden, one entry per file, name = <basename-without-.json>#in<input_index>
  *   version.activated = 3 (v6 path-guard constant; non-behavioural metadata)
  *   version.ergoTree  = golden.expected.tree_version
  *   context copied verbatim (field names match the schema)
  *   expected = { value, cost, error: null }   (tree_version promoted to version.ergoTree)
  *
  * Run: sbt "Test/runMain santa.VendorV6FullCtx"
  */
object VendorV6FullCtx {

  val ActivatedVersion = 3
  val BlessedBy        = "jvm:sigma-state-6.0.3"
  val CapturedDir      = "/home/mwaddip/projects/ergots/tools/mainnet-validate/captured"
  val OutDir           = "/home/mwaddip/projects/santa/vectors/eval/v6/captured"

  def main(args: Array[String]): Unit = {
    val capturedPath = java.nio.file.Paths.get(CapturedDir)
    val outPath      = java.nio.file.Paths.get(OutDir)
    java.nio.file.Files.createDirectories(outPath)

    // Only direct children *.json (not the divergences/ subdir)
    val goldenFiles: Seq[java.nio.file.Path] = {
      val dir = new java.io.File(CapturedDir)
      val files = dir.listFiles(f => f.isFile && f.getName.endsWith(".json"))
      if (files == null) sys.error(s"VendorV6FullCtx: cannot list $CapturedDir")
      files.map(_.toPath).sorted
    }

    var written = 0
    for (goldenFile <- goldenFiles) {
      val basename = goldenFile.getFileName.toString          // e.g. h2571-3d8b8635ca52.json
      val opBase   = basename.stripSuffix(".json")            // e.g. h2571-3d8b8635ca52

      val text = new String(
        java.nio.file.Files.readAllBytes(goldenFile),
        java.nio.charset.StandardCharsets.UTF_8)

      val root = parseJson(text).fold(
        e => sys.error(s"VendorV6FullCtx: cannot parse $basename: $e"),
        identity)

      val c = root.hcursor

      val treeBytesHex = c.get[String]("tree_bytes_hex")
        .fold(e => sys.error(s"$basename tree_bytes_hex: $e"), identity)

      val context = c.downField("context").focus
        .getOrElse(sys.error(s"$basename: missing context"))

      val expectedC   = c.downField("expected")
      val treeVersion = expectedC.get[Int]("tree_version")
        .fold(e => sys.error(s"$basename expected.tree_version: $e"), identity)
      val value       = expectedC.downField("value").focus
        .getOrElse(sys.error(s"$basename: missing expected.value"))
      val cost        = expectedC.get[Int]("cost")
        .fold(e => sys.error(s"$basename expected.cost: $e"), identity)

      val provenanceC = c.downField("provenance")
      val txId        = provenanceC.get[String]("tx_id")
        .fold(e => sys.error(s"$basename provenance.tx_id: $e"), identity)
      val height      = provenanceC.get[Int]("height")
        .fold(e => sys.error(s"$basename provenance.height: $e"), identity)
      val inputIndex  = provenanceC.get[Int]("input_index")
        .fold(e => sys.error(s"$basename provenance.input_index: $e"), identity)
      val provenance  = provenanceC.focus
        .getOrElse(sys.error(s"$basename: missing provenance"))

      val entryName = s"$opBase#in$inputIndex"
      val source    = s"testnet:$txId@$height"
      val op        = s"fullctx.$opBase"

      val entry = Json.obj(
        "name"           -> Json.fromString(entryName),
        "tree_bytes_hex" -> Json.fromString(treeBytesHex),
        "version"        -> Json.obj(
          "activated" -> Json.fromInt(ActivatedVersion),
          "ergoTree"  -> Json.fromInt(treeVersion)),
        "context"        -> context,
        "provenance"     -> provenance,
        "expected"       -> Json.obj(
          "value" -> value,
          "cost"  -> Json.fromInt(cost),
          "error" -> Json.Null))

      val vector = Json.obj(
        "schema"     -> Json.fromString("santa-eval/v6-fullctx"),
        "op"         -> Json.fromString(op),
        "blessed_by" -> Json.fromString(BlessedBy),
        "source"     -> Json.fromString(source),
        "entries"    -> Json.arr(entry))

      val outFile = outPath.resolve(basename)
      java.nio.file.Files.write(outFile,
        vector.spaces2.getBytes(java.nio.charset.StandardCharsets.UTF_8))
      written += 1
    }

    println(s"VendorV6FullCtx: wrote $written files to $OutDir")
  }
}
