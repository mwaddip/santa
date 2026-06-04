package santa

import io.circe.Json

/** Assembles the committed vendored wire corpus by merging the per-framework harvesters
  * (VendoredWireErgots, VendoredWireFleet) by op — so one slice (e.g. Box) holds vectors
  * vendored from several frameworks, each entry carrying its own `source`. The single staging
  * writer for the vendored wire vectors (VendoredWireTest drives it). */
object VendoredWire {
  /** Run both harvesters; merge their op->envelope maps by op (concat entries for a shared
    * op like Box); combine their diffs/rejects. */
  def corpus(): (Map[String, Json], Seq[String], Seq[String]) = {
    val (e1, d1, r1) = VendoredWireErgots.extract()
    val (e2, d2, r2) = VendoredWireFleet.extract()
    (mergeByOp(Seq(e1, e2)), d1 ++ d2, r1 ++ r2)
  }

  /** Concat entries for each op across the given envelope maps; rebuild one envelope per op.
    * Fails loud on a duplicate entry name within a merged op (entry names key the actuals). */
  def mergeByOp(maps: Seq[Map[String, Json]]): Map[String, Json] =
    maps.flatMap(_.keys).distinct.map { op =>
      val entries = maps.flatMap(_.get(op))
        .flatMap(_.hcursor.downField("entries").as[List[Json]].getOrElse(Nil))
      val dupes = entries.flatMap(_.hcursor.get[String]("name").toOption)
        .groupBy(identity).filter(_._2.size > 1).keys
      if (dupes.nonEmpty)
        sys.error(s"VendoredWire.mergeByOp: duplicate entry names in $op: ${dupes.mkString(", ")}")
      op -> Json.obj(
        "schema"     -> Json.fromString("santa-wire/v1"),
        "op"         -> Json.fromString(op),
        "blessed_by" -> Json.fromString("jvm:sigma-state-6.0.3"),
        "entries"    -> Json.arr(entries: _*))
    }.toMap

  /** Persist to a staging dir (build artifact; cp into vectors/wire/v5/vendored/). */
  def writeVectors(vectors: Map[String, Json], outDir: java.nio.file.Path): Unit =
    VendoredWireErgots.writeVectors(vectors, outDir)
}
