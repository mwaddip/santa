package santa

// Authored eval vectors for sigma-rust's ADDENDA to the collection length limit (queue item 33, vectored by
// AuthoredEvalCollectionLengthLimit at 829693b). Each addendum APPENDS to `Coll.flatMap_length_limit{,_v6}` after the
// entries the file held before it, every row a `flatMap` over a collection, authored under `SizeOf` as the first
// request's (the first generator's `treeHex`). LEFT OUT: a row-activation the file (or an earlier row) already holds:
// the same tree and activated version, the same outcome (pinned).
//
// * AuthoredEvalCollectionLengthLimit2 — santa-probe-collection-length-limit-2 (2026-11-18, 144 rows; d8946d8): when
//   `flatMap` refuses, and the fan-out. Measured first in santa.spike.CollectionLengthLimit2Probe: all 216
//   row-activations as the jars on outcome, class, the count its message names, value and the counter (at the throw
//   too). `flatMap` is `builder.fromArray(toArray.flatMap(x => f(x).toArray))` (6.0.6 `CollsOverArrays.scala:70-73`,
//   `:410-411`), so an item the result's array cannot hold is refused at its part, before the next lambda runs, though
//   the parts before it are over the limit already (`ClassCastException`; a part holding no pairs under a pair result,
//   `ArrayStoreException`); for a result of pairs a component of another class after the last lambda
//   (`fromBoxedPairs`), the left before the right, and before the length; nested, the left side's pairs before the
//   length, the right side's only within the limit; the length last, at a counter without `flatMap`'s own cost. The
//   FAN-OUT (one shared collection returned by every call): only 2 and 64 items a call (131,072 and 4,194,304 items,
//   both refused at 658,619); 1,024 a call made the fork's base answer at a cost of 84 million, 4,096 aborted it under
//   12 GB, and 65,536 gave the jars no answer in 300 s (sigma-rust's caution). Reply:
//   prompts/drafts/sigma-rust-collection-length-limit-2-reply.md.
// * AuthoredEvalCollectionLengthLimit3 — santa-probe-collection-length-limit-3 (2026-11-18, 88 rows): the part of
//   another class is a SPECIALISED collection (a `Coll[Byte]` / `Coll[Boolean]` constant, a primitive array; 3 pairs
//   by `zip`), refused at its part when its items are stored, over the limit too; or an EMPTY one, taken (no item to
//   store): a value, or the length when the right parts are over the limit. A port that judges a part by its class
//   without reading its items differs here. Measured first in santa.spike.CollectionLengthLimit3Probe: all 132
//   row-activations. Reply: prompts/drafts/sigma-rust-collection-length-limit-3-reply.md.

import io.circe.Json

/** One addendum: its rows file, and the entries each file held before it (the base it appends to; the base is these
  * entries, not the whole committed file, so a re-run after the addendum is committed reproduces it). */
abstract class AuthoredEvalCollectionLengthLimitAddendum(rowsDir: String, val Before: Map[String, Int], label: String) {
  val E: AuthoredEvalCollectionLengthLimit.type = AuthoredEvalCollectionLengthLimit
  val RowsFile: java.nio.file.Path = java.nio.file.Paths.get("src", "test", "resources", rowsDir, "rows.tsv")
  def allRows(): Vector[E.Row] = E.rowsOf(RowsFile)
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

  /** Every file: the entries it held before the addendum first, unchanged, then the addendum's. */
  def extract(): Map[String, Json] = plan().map { case (op, (rs, _)) =>
    val before = base(op)
    val entries = before ++ rs.zipWithIndex.map { case (r, i) => E.entry(op, r, before.size + i) }
    op -> E.committed(op).get.mapObject(_.add("entries", Json.arr(entries: _*)))
  }

  def writeVectors(outDir: java.nio.file.Path): Unit = SpecExtract.writeStaging(label, extract(), outDir)
}

/** The first addendum: 829693b's counts. */
object AuthoredEvalCollectionLengthLimit2 extends AuthoredEvalCollectionLengthLimitAddendum(
  "sigma-rust-collection-length-limit-2",
  Map("Coll.flatMap_length_limit" -> 67, "Coll.flatMap_length_limit_v6" -> 140), "AuthoredEvalCollectionLengthLimit2")

/** The second addendum: d8946d8's counts (829693b's + the first addendum's 72 / 144). */
object AuthoredEvalCollectionLengthLimit3 extends AuthoredEvalCollectionLengthLimitAddendum(
  "sigma-rust-collection-length-limit-3",
  Map("Coll.flatMap_length_limit" -> 139, "Coll.flatMap_length_limit_v6" -> 284), "AuthoredEvalCollectionLengthLimit3")
