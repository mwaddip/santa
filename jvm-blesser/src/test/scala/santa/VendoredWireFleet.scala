package santa

import io.circe.Json
import io.circe.parser.{parse => parseJson}

/** Authored wire-tier vectors harvested from Fleet SDK's serializer test vectors
  * (packages/serializer/src/_test-vectors, vendored under src/test/resources/fleet-wire/,
  * whose bytes are Fleet-serializer-produced) and re-anchored to the canonical JVM
  * serializer via WireCanonicalize. The committed bytes are the JVM's; a JVM-vs-Fleet
  * mismatch is reported (a finding) and the JVM bytes win. Round-trip-to-self: an entry's
  * bytes_hex IS its expected. Provenance: source "fleet:serializer/_test-vectors",
  * vectors/wire/v5/authored/. Sibling of VendoredWireErgots (ergots); shares WireCanonicalize +
  * VendoredWireErgots.writeVectors, leaving the ergots harvest path untouched.
  *
  * Fleet seed shape differs from ergots': a JSON array of `{ hex, json }` (no `entries`
  * wrapper, no per-entry `name`). The entry name is synthesised from the object's tx id
  * (`json.id`); a seed the JVM cannot PARSE is caught per-entry, EXCLUDED from the
  * round-trip corpus, and reported as a reject finding (the future wire reject arm). */
object VendoredWireFleet {
  val Source = "fleet:serializer/_test-vectors"
  val V5activated: Byte = 2 // v5 activation byte (cf. tools/validate version map v5->2)
  val V5ergoTree: Byte  = 2

  /** vendored seed file -> (op, kind). Fleet shapes: tx is `{ hex, json:{id} }`; constants
    * and boxes are `{ name, hex }` (extracted from constantVectors.ts / boxVectors.ts). */
  private val seeds: Seq[(String, String, String)] = Seq(
    ("signedTransactions.json", "Transaction", "Transaction"),
    ("constants.json",          "Constant",    "Constant"),
    ("boxes.json",              "Box",         "Box"))

  private val seedDir = java.nio.file.Paths.get("src", "test", "resources", "fleet-wire")

  /** (op -> wire envelope, diffs, rejects).
    *   diffs   = entries the JVM parsed but reserialized to DIFFERENT bytes (bytes-differ findings).
    *   rejects = entries the JVM could not parse at all — excluded from the round-trip corpus. */
  def extract(): (Map[String, Json], Seq[String], Seq[String]) = {
    val diffs   = scala.collection.mutable.ArrayBuffer.empty[String]
    val rejects = scala.collection.mutable.ArrayBuffer.empty[String]
    val vectors = seeds.flatMap { case (file, op, kind) =>
      val text = new String(java.nio.file.Files.readAllBytes(seedDir.resolve(file)),
        java.nio.charset.StandardCharsets.UTF_8)
      val arr = parseJson(text).fold(e => sys.error(s"VendoredWireFleet: bad seed $file: $e"), identity)
        .asArray.getOrElse(sys.error(s"VendoredWireFleet: $file is not a JSON array"))
      val entries = arr.toList.zipWithIndex.flatMap { case (se, i) =>
        val sc = se.hcursor
        val seedHex = sc.get[String]("hex").fold(e => sys.error(s"$file[$i] hex: $e"), identity)
        // Name + description per seed shape: constants carry a `name`; txs carry `json.id`.
        val (name, desc) = sc.get[String]("name").toOption.filter(_.nonEmpty) match {
          case Some(n) => (n, s"Fleet $kind $n")
          case None =>
            sc.downField("json").get[String]("id").toOption match {
              case Some(txid) => (s"tx_${txid.take(8)}", s"Fleet signed tx $txid")
              case None       => (s"${op}_$i", s"Fleet $kind #$i")
            }
        }
        val canonicalOpt =
          try Some(WireCanonicalize.canonicalize(kind, seedHex, V5activated, V5ergoTree))
          catch {
            case t: Throwable =>
              rejects += s"$op/$name: JVM rejects (${t.getClass.getSimpleName}: " +
                s"${Option(t.getMessage).getOrElse("")}) fleet=$seedHex"
              None
          }
        canonicalOpt.map { canonical =>
          if (canonical != seedHex) diffs += s"$op/$name: jvm=$canonical fleet=$seedHex"
          Json.obj(
            "name"        -> Json.fromString(name),
            "kind"        -> Json.fromString(kind),
            "source"      -> Json.fromString(Source),
            "description" -> Json.fromString(desc),
            "bytes_hex"   -> Json.fromString(canonical),
            "version"     -> Json.obj("activated" -> Json.fromInt(V5activated.toInt),
                                      "ergoTree"  -> Json.fromInt(V5ergoTree.toInt)))
        }
      }
      // Entry names key the runners' actuals — a duplicate would silently overwrite. Fail loud.
      val names = entries.flatMap(_.hcursor.get[String]("name").toOption)
      val dupes = names.groupBy(identity).filter(_._2.size > 1).keys
      if (dupes.nonEmpty)
        sys.error(s"VendoredWireFleet: duplicate entry names in $op (id prefix collision): ${dupes.mkString(", ")}")
      if (entries.nonEmpty) Some(op -> envelope(op, entries)) else None
    }.toMap
    (vectors, diffs.toSeq, rejects.toSeq)
  }

  private def envelope(op: String, entries: Seq[Json]): Json =
    Json.obj(
      "schema"     -> Json.fromString("santa-wire/v1"),
      "op"         -> Json.fromString(op),
      "blessed_by" -> Json.fromString("jvm:sigma-state-6.0.3"),
      "entries"    -> Json.arr(entries: _*))

  /** Persist to a staging dir (build artifact; cp into vectors/wire/v5/authored/ once
    * inspected). Reuses VendoredWireErgots.writeVectors (generic, slug-collision-guarded). */
  def writeVectors(vectors: Map[String, Json], outDir: java.nio.file.Path): Unit =
    VendoredWireErgots.writeVectors(vectors, outDir)
}
