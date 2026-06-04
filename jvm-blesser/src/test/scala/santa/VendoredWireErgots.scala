package santa

import io.circe.Json
import io.circe.parser.{parse => parseJson}

/** Authored wire-tier vectors: harvest ergots' fixture-gen wire corpus (committed seeds
  * under src/test/resources/ergots-wire/, whose bytes_hex are sigma-rust-serialized) and
  * re-anchor each to the canonical JVM serializer via WireCanonicalize. The committed
  * bytes are the JVM's; a JVM-vs-sigma-rust mismatch is reported (a finding) and the JVM
  * bytes win. Round-trip-to-self: an entry's bytes_hex IS its expected. Provenance:
  * source "ergots:fixture-gen/wire", vectors/wire/v5/authored/. Mirrors AuthoredSerialize.
  *
  * A seed the JVM cannot PARSE (e.g. sbox_boundary: creation_height = u32::MAX, which
  * sigma-state's getUIntExact rejects with Int overflow while sigma-rust's u32 accepts) is
  * not a JVM-canonical round-trip vector — it is the isWireEncodable analog: caught
  * per-entry, EXCLUDED from the round-trip corpus, and reported as a reject finding (a seed
  * for the future wire reject arm). See docs/findings/wire-jvm-vs-sigma-rust.md. */
object VendoredWireErgots {
  val Source = "ergots:fixture-gen/wire"
  val V5activated: Byte = 2 // v5 activation byte (cf. tools/validate version map v5->2)
  val V5ergoTree: Byte  = 2

  /** seed file -> (op, kind). Both seeds are bytes_hex-shaped. */
  private val seeds: Seq[(String, String, String)] = Seq(
    ("sbox-roundtrip.json",         "Box",          "Box"),
    ("sigma-boolean-variants.json", "SigmaBoolean", "SigmaBoolean"))

  private val seedDir = java.nio.file.Paths.get("src", "test", "resources", "ergots-wire")

  /** (op -> wire envelope, diffs, rejects).
    *   diffs   = entries the JVM parsed but reserialized to DIFFERENT bytes (bytes-differ findings).
    *   rejects = entries the JVM could not parse at all — excluded from the round-trip corpus
    *             (reject-arm findings). */
  def extract(): (Map[String, Json], Seq[String], Seq[String]) = {
    val diffs   = scala.collection.mutable.ArrayBuffer.empty[String]
    val rejects = scala.collection.mutable.ArrayBuffer.empty[String]
    val vectors = seeds.flatMap { case (file, op, kind) =>
      val text = new String(java.nio.file.Files.readAllBytes(seedDir.resolve(file)),
        java.nio.charset.StandardCharsets.UTF_8)
      val root = parseJson(text).fold(e => sys.error(s"VendoredWireErgots: bad seed $file: $e"), identity)
      val seedEntries = root.hcursor.downField("entries").as[List[Json]]
        .fold(e => sys.error(s"VendoredWireErgots: $file entries: $e"), identity)
      val entries = seedEntries.flatMap { se =>
        val sc = se.hcursor
        val name = sc.get[String]("name").fold(e => sys.error(s"$file entry name: $e"), identity)
        val seedHex = sc.get[String]("bytes_hex").fold(e => sys.error(s"$file/$name bytes_hex: $e"), identity)
        val canonicalOpt =
          try Some(WireCanonicalize.canonicalize(kind, seedHex, V5activated, V5ergoTree))
          catch {
            case t: Throwable =>
              rejects += s"$op/$name: JVM rejects (${t.getClass.getSimpleName}: " +
                s"${Option(t.getMessage).getOrElse("")}) sigma-rust=$seedHex"
              None
          }
        canonicalOpt.map { canonical =>
          if (canonical != seedHex) diffs += s"$op/$name: jvm=$canonical sigma-rust=$seedHex"
          val desc = sc.get[String]("description").getOrElse("")
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
    * inspected). Fails loud on a slug collision (mirrors SpecExtract.writeVectors). */
  def writeVectors(vectors: Map[String, Json], outDir: java.nio.file.Path): Unit = {
    java.nio.file.Files.createDirectories(outDir)
    val collisions = vectors.keys.groupBy(SpecExtract.slug).filter(_._2.size > 1)
    if (collisions.nonEmpty)
      sys.error("VendoredWireErgots.writeVectors: slug collision would silently drop entries — " +
        collisions.map { case (s, ops) => s"'$s.json' <- ${ops.mkString(" / ")}" }.mkString("; "))
    vectors.foreach { case (op, json) =>
      java.nio.file.Files.write(outDir.resolve(s"${SpecExtract.slug(op)}.json"),
        json.spaces2.getBytes(java.nio.charset.StandardCharsets.UTF_8))
    }
  }
}
