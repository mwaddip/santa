package santa

// Authored eval vectors — `Coll.getOrElse` / `size` / `apply` as MethodCalls on three of the JVM's
// runtime collection shapes. Request: `prompts/santa-probe-coll-getorelse-runtime-shapes.md`
// (sigma-rust session, 2026-11-09, found by its whole-branch review); measured by
// `santa.spike.CollRuntimeShapesProbe`.
//
// **All three of the reviewer's hypotheses hold, down to the cast target** — and the family carries
// over-accepts AND over-rejects in the same eleven rows, in three groups:
//
//   A  a collection of PAIRS is a `PairOfCols`, whose `getOrElse(i, (L,R))` overrides the interface
//      method with another erasure, so the call goes through a BRIDGE that casts the default to
//      Tuple2. A non-pair default throws AT ANY INDEX:
//        `ClassCastException: class java.lang.Boolean cannot be cast to class scala.Tuple2`
//      The A3/A4 controls give it a PAIR default and it works at cost 109 — which is what shows
//      the failure belongs to the default and not to the shape.
//
//   B  **THE FINDING: the same declared type behaves differently by PROVENANCE.** `CollOverArray`
//      is `@specialized` and a `Coll[Byte]` CONSTANT carries a static primitive type
//      (`CoreDataSerializer.scala:171-174`), so the interface call unboxes the Boolean default and
//      throws — `ClassCastException: class java.lang.Boolean cannot be cast to class
//      java.lang.Byte` — while a `ConcreteCollection` of the very same bytes stays generic and
//      RETURNS (true at index 5, Byte 1 at index 0, both cost 74). Same `Coll[Byte]`, same call,
//      different answer depending on how the collection was built. An implementation that does not
//      track provenance cannot reproduce both halves.
//
//   C  a 3-item tuple's value IS a `Coll[Any]` (`Tuple.value`, `values.scala:818-822`), so all
//      three methods simply work on it: `.size` 3 @ 23, `.apply(0)` 1 @ 44,
//      `.getOrElse(5, 9)` 9 @ 49.
//
// A NOTE ON THE CLASS, because it bit here too: `EvalCore` reports these as
// `InvocationTargetException` — the reflective wrapper — and the real `ClassCastException` is only
// in the cause. The eval tier grades `errored` coarsely and matches no string, so the vectors are
// right either way, but a conformer reading the reported class would see the wrong one.

import io.circe.Json

object AuthoredEvalCollRuntimeShapes {
  val Op     = "Coll.runtime_shapes_methodcall"
  val Source = "santa:authored-coll-runtime-shapes"
  private val V3: Byte = 3

  private val dummyInput: Json =
    Json.obj("kind" -> Json.fromString("Int"), "value" -> Json.fromInt(0))

  /** `(id, slug, tree, accepts, what)` — the probe's own hexes for A1..C3, and the AST-built
    * controls for A3/A4. */
  private val rows: Seq[(String, String, String, Boolean, String)] = Seq(
    ("A1", "pair_coll_nonpair_default_oob", "0b0edc0c0283015858020402040a0101", false,
      "`Coll((1, 2)).getOrElse(5, true)` — a collection of PAIRS is a `PairOfCols`, whose " +
      "`getOrElse(i, (L,R))` overrides the interface method with another erasure, so the call goes " +
      "through a BRIDGE that casts the default to Tuple2. The index is OUT OF RANGE and it still " +
      "throws: `ClassCastException: class java.lang.Boolean cannot be cast to class scala.Tuple2`, " +
      "wrapped in an `InvocationTargetException`. **SEPARATING:** an implementation that returns " +
      "the default here over-accepts."),
    ("A2", "pair_coll_nonpair_default_in_range", "0b0edc0c028301585802040204000101", false,
      "The same at index 0, IN range. Still throws, and with the same cast — which is the point of " +
      "the pair: the bridge casts the default before the index is ever consulted. **SEPARATING.**"),
    ("B1", "byte_constant_bool_default_oob", "0b0cdc0c020e02010202040a0101", false,
      "`Coll[Byte](1,2)` as a CONSTANT, `.getOrElse(5, true)`. `CollOverArray` is `@specialized` " +
      "and a Coll[Byte] constant carries a static primitive type, so the interface call unboxes " +
      "the Boolean default: `ClassCastException: class java.lang.Boolean cannot be cast to class " +
      "java.lang.Byte`. **SEPARATING, and half of the provenance pair** — compare B3, which is the " +
      "same declared type built differently and RETURNS."),
    ("B2", "byte_constant_bool_default_in_range", "0b0cdc0c020e0201020204000101", false,
      "The same at index 0. Still throws — the unboxing precedes the index, exactly as in A. " +
      "**SEPARATING, and half of the provenance pair** (compare B4)."),
    ("B3", "byte_concrete_bool_default_oob", "0b0fdc0c028302020201020202040a0101", true,
      "`Coll(1.toByte, 2.toByte)` as a ConcreteCollection, `.getOrElse(5, true)` — **the same " +
      "declared type `Coll[Byte]` as B1**, built generically rather than from a constant, and it " +
      "RETURNS `true` at cost 74. **THE OTHER HALF OF THE FINDING:** the JVM's answer depends on " +
      "where the collection came from. Both sides agree on this row, which is what makes B1's red " +
      "about provenance rather than about `getOrElse`."),
    ("B4", "byte_concrete_bool_default_in_range", "0b0fdc0c02830202020102020204000101", true,
      "The same at index 0: returns `Byte 1` at cost 74, where B2 throws. The pair B2/B4 is the " +
      "cleanest statement of the finding — identical declared type, identical call, identical " +
      "index, opposite outcome."),
    ("C1", "tuple3_size", "0b0adb0c0148040404020406", true,
      "`(1, 2, 3).size` as a PropertyCall. A 3-item tuple's value IS a `Coll[Any]`, so this simply " +
      "works: **3 at cost 23**. **SEPARATING the other way:** an implementation that refuses a " +
      "tuple receiver here OVER-REJECTS."),
    ("C2", "tuple3_apply", "0b0ddc0c0a48040404020406010400", true,
      "`(1, 2, 3).apply(0)` — **1 at cost 44**. Same over-reject risk as C1."),
    ("C3", "tuple3_get_or_else", "0b0fdc0c024804040402040602040a0412", true,
      "`(1, 2, 3).getOrElse(5, 9)` — **9 at cost 49**, the default, since 5 is out of range on a " +
      "3-item tuple. Note it does NOT hit A's bridge problem: a tuple is a plain `Coll[Any]`, not " +
      "a `PairOfCols`. Same over-reject risk."),
    ("A3", "pair_coll_pair_default_oob", "0b15dc0c0283015886020402040402040a8602040e0410", true,
      "`Coll((1, 2)).getOrElse(5, (7, 8))` — **THE CONTROL FOR A**, with a PAIR default. Returns " +
      "`(7, 8)` at cost 109. It is what shows A1/A2 fail because of the DEFAULT's type and not " +
      "because the receiver is a collection of pairs."),
    ("A4", "pair_coll_pair_default_in_range", "0b15dc0c028301588602040204040204008602040e0410", true,
      "The same at index 0: returns the element `(1, 2)` at cost 109. Pairs with A3 to show the " +
      "index is honoured once the default's type is right."))

  def extract(): Map[String, Json] = {
    val entries = rows.zipWithIndex.map { case ((id, slug, tree, accepts, what), i) =>
      val name = s"$slug-${if (accepts) "accept" else "errored"}#$i"
      val script = s"[$id] $what"
      if (accepts) SpecExtract.authoredEntry(Op, script, tree, name, dummyInput, V3)
      else SpecExtract.authoredRejectEntry(Op, script, tree, name, dummyInput, V3)
    }
    Map(Op -> SpecExtract.authoredEnvelope(Op, entries, Source)
      .mapObject(_.add("blessed_by", Json.fromString("jvm:sigma-state-6.0.7"))))
  }

  def writeVectors(outDir: java.nio.file.Path): Unit =
    SpecExtract.writeStaging("AuthoredEvalCollRuntimeShapes", extract(), outDir)
}
