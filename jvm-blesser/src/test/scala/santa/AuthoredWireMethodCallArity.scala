package santa

// Authored wire vectors — a method call whose argument count is not the method's.
//
// sigmastate's `MethodCallSerializer.parse` (6.0.7, `:47-97`) checks NO arity. From tree version 3
// it asserts only `args.nonEmpty` (`:52-55`), and `specializeFor` never throws
// (`SMethod.scala:185-191`), so a call whose types do not unify keeps its unspecialized method,
// typed by its declared range. A wrong-arity call therefore PARSES and round-trips byte for byte;
// whether it EVALUATES is a separate question, answered by the transaction tier
// (`transaction/v6/authored/method-call-arity.json`, the same four trees spent).
//
// Each tree binds the call in a block and returns sigmaProp(true), so nothing reads the call's
// type at parse — an unspecialized method's type is a type variable, which a comparison would
// refuse first. The encodings came from the sigma-rust session (2026-11-07); the blesser
// re-derives every round-trip through WireCanonicalize.
//
// An implementation that gates arity at PARSE rejects all four trees — including the control,
// whose arity is legal — and so refuses trees the JVM accepts.

import io.circe.Json

object AuthoredWireMethodCallArity {
  val V3: Byte = sigma.VersionContext.V6SoftForkVersion
  val Op     = "ErgoTree.method_call_arity"
  val Source = "santa:authored-method-call-arity"

  /** The four trees, shared with the transaction-tier blesser. Tree version 3, size-flagged,
    * no constant segregation (header 0x0b). */
  val Trees: Seq[(String, String, String)] = Seq(
    ("user-defined-invoke-extra-arg",
     "{ val x = 1.bitwiseOr(2, 4); sigmaProp(true) }",
     "0b10d801d601dc04090402020404040808d3"),
    ("reflective-extra-arg",
     "{ val x = Coll(1, 2).indexOf(2, 0, 7); sigmaProp(true) }",
     "0b14d801d601dc0c1a100202040304040400040e08d3"),
    ("property-call-of-arg-taking-method",
     "{ val x = Coll(1, 2).<indexOf as a PropertyCall>; sigmaProp(true) }",
     "0b0dd801d601db0c1a1002020408d3"),
    ("legal-arity-control",
     "{ val x = 1.bitwiseOr(2); sigmaProp(true) }",
     "0b0ed801d601dc0409040201040408d3"))

  private val Descriptions = Map(
    "user-defined-invoke-extra-arg" ->
      ("`1.bitwiseOr(2, 4)` — SInt method 9, a FixedCost method with a `userDefinedInvoke` " +
       "handler, given one argument more than it takes. The parser builds the call at any arity."),
    "reflective-extra-arg" ->
      ("`Coll(1, 2).indexOf(2, 0, 7)` — SCollection method 26, a PerItemCost method with no " +
       "`userDefinedInvoke` handler, so it is invoked reflectively. Parses all the same."),
    "property-call-of-arg-taking-method" ->
      ("`Coll(1, 2).indexOf` written as a PropertyCall — a method that takes arguments, called " +
       "with none at all. `PropertyCallSerializer` reads no arguments and does not check that " +
       "the method wants any."),
    "legal-arity-control" ->
      ("`1.bitwiseOr(2)` — the same shape at the arity the method declares. The family pins " +
       "ARITY, not the construct: an implementation that gates arity at parse rejects this too."))

  private def version: Json =
    Json.obj("activated" -> Json.fromInt(V3.toInt), "ergoTree" -> Json.fromInt(V3.toInt))

  private def canonical(in: String): String = WireCanonicalize.canonicalize("ErgoTree", in, V3, V3)

  /** An accept entry: the JVM must round-trip the bytes to themselves. */
  private def accept(name: String, description: String, in: String): Json = {
    val out = canonical(in)
    require(out == in, s"$name: the JVM must round-trip an accept vector to itself — in $in, out $out")
    Json.obj(
      "name" -> Json.fromString(name), "kind" -> Json.fromString("ErgoTree"),
      "source" -> Json.fromString(Source), "description" -> Json.fromString(description),
      "bytes_hex" -> Json.fromString(in), "version" -> version)
  }

  def extract(): Map[String, Json] = {
    val entries = Trees.zipWithIndex.map { case ((name, script, treeHex), i) =>
      accept(s"method-call-arity-$name#$i",
        s"${Descriptions(name)} Script: $script. The call is bound in a block and the tree " +
        "returns sigmaProp(true), so nothing reads the call's type at parse. The JVM parses " +
        "this tree and re-serializes it to the same bytes; an implementation that gates arity " +
        "at parse rejects it — an over-reject on a tree the JVM accepts. Whether it EVALUATES " +
        "is graded by transaction/v6/authored/method-call-arity.json.",
        treeHex)
    }
    Map(Op -> Json.obj(
      "schema"     -> Json.fromString("santa-wire/v1"),
      "op"         -> Json.fromString(Op),
      "blessed_by" -> Json.fromString("jvm:sigma-state-6.0.7"),
      "entries"    -> Json.arr(entries: _*)))
  }

  def writeVectors(outDir: java.nio.file.Path): Unit =
    SpecExtract.writeStaging("AuthoredWireMethodCallArity", extract(), outDir)
}
