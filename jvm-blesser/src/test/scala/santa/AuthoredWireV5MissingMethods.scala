package santa

// Authored wire vectors — the probe's seven wrapped trees, at v0 UNSIZED and v3 SIZE-FLAGGED.
//
// All fourteen PARSE identity on the JVM and round-trip verbatim. **v0 is the separating version:**
// an implementation that refuses the method id at lookup has nothing to degrade behind at an
// unsized tree and REJECTS the carrier, where the JVM accepts it — an over-reject on a box the JVM
// takes. At v3 such an implementation DEGRADES and echoes the very bytes the JVM echoes, and the
// wire tier is degrade-blind, so the v3 row cannot tell a correct parse from a degrade and is a
// control. That is the same mirror as the small-tuples and type-id-order families, and each row's
// description says which side it is on.
//
// This arm is the one that matters for these fifteen ids, because eleven of them cannot be invoked
// on the JVM either (`V5MissingMethodTrees`): at EVAL the JVM and a refusing implementation both
// fail, for different reasons the tier cannot separate. Only `exp`, `multiply`, `isDefined` and
// `get` evaluate, so only they are over-rejects at spend — but ALL FIFTEEN are over-rejects at the
// v0 PARSE, which is what these rows pin.
//
// Two of the trees (`get`, `get_or_else`) ask for `getVar[Boolean](1)` while the canonical eval
// context binds var 1 as an Int, so their EVAL errors for a reason that is not the method under
// test. Harmless here — this tier grades the round-trip and nothing else — and flagged in each
// description so they are never reused as eval or spend vectors.

import io.circe.Json

object AuthoredWireV5MissingMethods {
  val Op     = "ErgoTree.v5_methods_missing_in_rust"
  val Source = "santa:authored-v5-methods-missing"
  private val A: Byte = V5MissingMethodTrees.Activated

  private def accept(name: String, description: String, in: String, tv: Byte): Json = {
    val out = WireCanonicalize.canonicalize("ErgoTree", in, A, tv)
    require(out == in, s"$name: the JVM must round-trip an accept vector to itself — in $in, out $out")
    Json.obj(
      "name" -> Json.fromString(name), "kind" -> Json.fromString("ErgoTree"),
      "source" -> Json.fromString(Source), "description" -> Json.fromString(description),
      "bytes_hex" -> Json.fromString(in),
      "version" -> Json.obj("activated" -> Json.fromInt(A.toInt), "ergoTree" -> Json.fromInt(tv.toInt)))
  }

  def extract(): Map[String, Json] = {
    var i = -1
    def next(): Int = { i += 1; i }
    val entries = V5MissingMethodTrees.wrapped.flatMap { case (slug, t0, t3) =>
      val blind =
        if (V5MissingMethodTrees.evalBlindTrees.contains(slug))
          " NOTE: this tree asks for `getVar[Boolean](1)` while the canonical eval context binds " +
          "var 1 as an Int, so its EVAL errors for a reason that is NOT the method under test. " +
          "Harmless on this tier, which grades the round-trip only — but do not reuse it as an " +
          "eval or spend vector."
        else ""
      Seq(
        accept(s"v5-missing-$slug-v0-accept#${next()}",
          s"The probe's `$slug` tree at tree version 0, UNSIZED. The JVM parses it and round-trips " +
          "it verbatim. **THE SEPARATING ROW:** an implementation whose method table lacks this id " +
          "refuses it at the lookup, and at an unsized tree it has no degrade path, so it REJECTS " +
          s"a carrier the JVM accepts — an over-reject.$blind", t0, V5MissingMethodTrees.V0),
        accept(s"v5-missing-$slug-v3-accept#${next()}",
          s"The same `$slug` tree SIZE-FLAGGED at v3. **THE CONTROL OF THIS PAIR:** a refusing " +
          "implementation now degrades and echoes the very bytes the JVM echoes, so the row is " +
          "green whether the method id was understood or skipped — the wire tier is degrade-blind " +
          s"and cannot tell those apart. Its v0 twin is where the disagreement shows.$blind",
          t3, A))
    }
    Map(Op -> Json.obj(
      "schema"     -> Json.fromString("santa-wire/v1"),
      "op"         -> Json.fromString(Op),
      "blessed_by" -> Json.fromString("jvm:sigma-state-6.0.7"),
      "entries"    -> Json.arr(entries: _*)))
  }

  def writeVectors(outDir: java.nio.file.Path): Unit =
    SpecExtract.writeStaging("AuthoredWireV5MissingMethods", extract(), outDir)
}
