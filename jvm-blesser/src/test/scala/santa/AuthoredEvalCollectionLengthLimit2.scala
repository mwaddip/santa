package santa

// Authored eval vectors for sigma-rust's santa-probe-collection-length-limit-2 (2026-11-18, the addendum to the
// collection length limit, queue item 33, that AuthoredEvalCollectionLengthLimit (829693b) vectored): when `flatMap`
// refuses, and the fan-out. Measured first in santa.spike.CollectionLengthLimit2Probe (local): all 216 row-activations
// as the jars on outcome, exception class, the count its message names, value and the evaluator's counter (at the throw
// too); under `SizeOf` every one answers as the row (39 accepted, at 14 more). The reply is
// prompts/drafts/sigma-rust-collection-length-limit-2-reply.md.
//
// What the addendum adds (sigma-rust's measurement on the 6.0.7 jars, re-measured here): `flatMap` is
// `builder.fromArray(toArray.flatMap(x => f(x).toArray))` (6.0.6 `CollsOverArrays.scala:70-73`, `:410-411`), so
//  * an item the result's array cannot hold is refused at its part, before the next lambda runs, though the parts
//    before it are over the limit already (`ClassCastException`; a part holding no pairs under a pair result,
//    `ArrayStoreException`);
//  * for a result of pairs a component of another class is refused after the last lambda (`fromBoxedPairs`), the left
//    component before the right, and before the length; nested, the left side's own pairs before the length, the right
//    side's only within the limit;
//  * the length last, at a counter without `flatMap`'s own cost;
//  * the FAN-OUT: one shared collection returned by every call makes a result of any length at a price that does not
//    grow with it (131,072 and 4,194,304 items, both refused at 658,619). THE FILE HOLDS ONLY THESE TWO: with 1,024
//    items a call the fork's base returned a value at a cost of 84 million, with 4,096 it aborted under a 12 GB cap,
//    and with 65,536 the jars gave no answer in 300 s (sigma-rust's caution).
//
// THE FILES: APPEND to 829693b's `Coll.flatMap_length_limit{,_v6}` (67 / 140 entries), every row a `flatMap` over a
// collection, authored under `SizeOf` as the first request's (the generator's `treeHex`). LEFT OUT: a row-activation
// the file (or an earlier row) already holds: the same tree and activated version, the same outcome (pinned).

import io.circe.Json

object AuthoredEvalCollectionLengthLimit2 {
  val E: AuthoredEvalCollectionLengthLimit.type = AuthoredEvalCollectionLengthLimit
  val RowsFile: java.nio.file.Path =
    java.nio.file.Paths.get("src", "test", "resources", "sigma-rust-collection-length-limit-2", "rows.tsv")
  def allRows(): Vector[E.Row] = E.rowsOf(RowsFile)
  /** The entries each file held at 829693b, which the addendum appends to. The base is these entries, not the whole
    * committed file, so a re-run after the addendum is committed reproduces it. */
  val Before: Map[String, Int] = Map("Coll.flatMap_length_limit" -> 67, "Coll.flatMap_length_limit_v6" -> 140)
  def base(op: String): List[Json] = {
    val n = Before(op)
    E.committed(op).toList.flatMap(E.entriesOf).take(n).ensuring(_.size == n, s"$op: $n committed entries")
  }
  def entryKey(e: Json): (String, Int) = (e.hcursor.get[String]("tree_bytes_hex").toOption.get,
    e.hcursor.downField("version").get[Int]("activated").toOption.get)

  /** Per op: the rows it appends, and the rows left out as a tree and activated version its file already holds. */
  def plan(): Map[String, (Vector[E.Row], Vector[E.Row])] = allRows().groupBy(E.op).map { case (op, rs) =>
    require(Before.contains(op), s"$op: the addendum appends to the flatMap files only")
    val held = collection.mutable.Set[(String, Int)]() ++ base(op).map(entryKey)
    op -> rs.partition(r => held.add(E.key(r)))
  }

  /** Every file: its 829693b entries first, unchanged, then the addendum's. */
  def extract(): Map[String, Json] = plan().map { case (op, (rs, _)) =>
    val before = base(op)
    val entries = before ++ rs.zipWithIndex.map { case (r, i) => E.entry(op, r, before.size + i) }
    op -> E.committed(op).get.mapObject(_.add("entries", Json.arr(entries: _*)))
  }

  def writeVectors(outDir: java.nio.file.Path): Unit =
    SpecExtract.writeStaging("AuthoredEvalCollectionLengthLimit2", extract(), outDir)
}
