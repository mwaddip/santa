package santa

// Authored wire vectors — an `SAny`-typed operand, i.e. an item of a non-pair tuple, at a binary
// operation. Request: `~/projects/sigma-rust/prompts/santa-probe-tuple-values.md` row N13 and the
// session's correction of it (2026-11-11); measured by `santa.spike.TupleValuesProbe`.
// Eval-tier companions: `Tuple.non_pair_value_gates` (the rows that parse and fail at EVAL) and
// `Tuple.non_pair_value_ops` (the operations that accept such a value).
//
// WHY THESE ROWS ARE HERE AND NOT IN THE EVAL TIER. The eval tier grades `errored` coarsely, so it
// flattens "the reader refused the tree" and "the evaluator threw" into one outcome. This family
// is about WHICH of the two happens, and the wire tier is the only place that grades it: an input
// either round-trips, degrades behind the size bit, or is rejected.
//
// THE MEASUREMENT, and it is a three-way split one byte apart:
//
//   comparison, `SAny` against a concrete type  REJECTED  `ConstraintFailed` (OnlyNumeric2)
//   comparison, `SAny` against `SAny`           REJECTED  `ConstraintFailed` (OnlyNumeric2)
//   equality,   `SAny` against a concrete type  REJECTED  `ConstraintFailed` (SameType2)
//   equality,   `SAny` against `SAny`           ACCEPTED, and round-trips to its own bytes
//
// `TransformingSigmaBuilder` (which `DeserializationSigmaBuilder` extends, `SigmaBuilder.scala:750`)
// runs `check2(OnlyNumeric)` for comparisons at `:699` BEFORE the SameType check at `:702`, and
// `check2(SameType)` alone for equality at `:691`. `SAny` is not numeric, so a comparison has no
// escape; equality's check is satisfied when BOTH operands are `SAny`, which is why that one row
// parses — and then dies at eval with `Unknown type SAny`, which is the eval family's G1/G2.
//
// AND THE SIZE BIT BUYS NOTHING. `ConstraintFailed` extends `BuilderException`
// (`exceptions/ConstraintFailed.scala:5`), not `ValidationException`, so the degrade gate
// (`ErgoTreeSerializer.scala:197`) does not take it: every refusal here is a REJECT at the sized v3
// form and at the unsized v0 form alike. That is the opposite of the `methodcall_type_id_order`
// family, where the same shape of input degrades at one version and rejects at the other — so these
// two families together pin that the gate is the exception CLASS and not the presence of a size bit.
//
// All rows are SigmaProp-rooted. A bare Boolean root degrades on rule 1001
// (`CheckDeserializedScriptIsSigmaProp`) for a reason that has nothing to do with tuples, which
// would make an accept row say nothing; under `sigmaProp` the accepts are real accepts, and they
// are also the only form a box can carry.

import io.circe.Json
import scorex.util.encode.Base16
import sigma.VersionContext
import org.ergoplatform.ErgoBox

import RentFixtures._

object AuthoredWireNonPairTupleOperand {
  val OpErgoTree    = "ErgoTree.non_pair_tuple_operand"
  val OpBox         = "Box.non_pair_tuple_operand"
  val OpTransaction = "Transaction.non_pair_tuple_operand"
  val Source        = "santa:authored-non-pair-tuple-operand"
  private val Activated: Byte = 3
  private val CF = "ConstraintFailed"

  private def hx(bs: Array[Byte]): String = Base16.encode(bs)
  private def ctx[A](f: => A): A = VersionContext.withVersions(Activated, Activated)(f)
  private val PlainTree = "0008d3"

  private def replaceUnique(in: String, from: String, to: String): String = {
    val i = in.indexOf(from)
    require(i >= 0 && in.indexOf(from, i + 1) < 0, s"marker $from must occur exactly once")
    in.take(i) + to + in.drop(i + from.length)
  }
  private def boxWithTree(slug: String, treeHex: String): String = ctx {
    replaceUnique(hx(ErgoBox.sigmaSerializer.toBytes(box(s"santa:npto:$slug", 1000000L, 1))),
      PlainTree, treeHex)
  }
  private def txWithTree(treeHex: String): String = ctx {
    val plain = hx(txBytes(tx(Seq(input(box("santa:npto:spent", 1000000000L, 1), ext())),
      Seq(candidate(1000000000L, 1)))))
    replaceUnique(plain, PlainTree + "01" + "0000", treeHex + "01" + "0000")
  }

  private def version(treeVersion: Int): Json =
    Json.obj("activated" -> Json.fromInt(Activated.toInt), "ergoTree" -> Json.fromInt(treeVersion))

  private def entry(kind: String, name: String, description: String, in: String, treeVersion: Int,
                    extra: (String, Json)*): Json =
    Json.obj(Seq(
      "name" -> Json.fromString(name), "kind" -> Json.fromString(kind),
      "source" -> Json.fromString(Source), "description" -> Json.fromString(description),
      "bytes_hex" -> Json.fromString(in)) ++ extra ++ Seq("version" -> version(treeVersion)): _*)

  private def canonical(kind: String, in: String, treeVersion: Byte): String =
    WireCanonicalize.canonicalize(kind, in, Activated, treeVersion)

  private def accept(kind: String, name: String, description: String, in: String,
                     treeVersion: Byte): Json = {
    val out = canonical(kind, in, treeVersion)
    require(out == in, s"$name: the JVM must round-trip an accept vector to itself — in $in, out $out")
    entry(kind, name, description, in, treeVersion.toInt)
  }

  private def reject(kind: String, name: String, description: String, in: String,
                     treeVersion: Byte, wantClass: String): Json = {
    scala.util.Try(canonical(kind, in, treeVersion)) match {
      case scala.util.Success(out) => sys.error(s"$name: must be REFUSED, round-tripped to $out")
      case scala.util.Failure(t) =>
        val classes = Iterator.iterate(t: Throwable)(_.getCause).takeWhile(_ != null)
          .map(_.getClass.getName).mkString(" <- ")
        require(classes.contains(wantClass),
          s"$name: refused by the wrong exception (want $wantClass): $classes")
    }
    entry(kind, name, description, in, treeVersion.toInt, "error" -> Json.fromString("errored"))
  }

  /** `tv` is the version the header declares; `carrier` whether the row also rides in a Box and a
    * Transaction (the v0 unsized twins stay ErgoTree-only — a box's tree is written sized, so the
    * unsized form is an ErgoTree-kind question by construction). */
  private case class Row(slug: String, treeHex: String, tv: Byte, wantClass: Option[String],
                         carrier: Boolean, what: String)

  private val rows: Seq[Row] = Seq(
    Row("eq-any-concrete-v3", "0b0fd193b2480404040204060400000402", Activated, Some(CF), true,
      "`sigmaProp(ByIndex((1,2,3),0) == 1)` — an `SAny` operand against an `SInt` one. " +
      "`check2(SameType)` in the deserialization builder refuses it, `ConstraintFailed`, so the " +
      "tree never parses. THE ROW THE SIGMA-RUST SESSION CORRECTED ITSELF ON: it first read this as " +
      "an eval-time `checkType` failure, then re-read the builder and expected a parse refusal. It " +
      "is a parse refusal. **SEPARATING:** an implementation that parses this and fails later " +
      "accepts a box the JVM cannot even read."),
    Row("eq-any-concrete-v0", "00d193b2480404040204060400000402", 0.toByte, Some(CF), false,
      "The same body UNSIZED. REJECTED identically — **the no-degrade pin.** `ConstraintFailed` is " +
      "a `BuilderException`, not a `ValidationException`, so the degrade gate never sees it and the " +
      "size bit changes nothing. Compare `methodcall_type_id_order`, where the sized/unsized pair " +
      "DOES split: that is what makes this pair evidence about the exception class rather than " +
      "about the size bit. **SEPARATING.**"),
    Row("gt-any-concrete-v3", "0b0fd191b2480404040204060400000402", Activated, Some(CF), true,
      "`sigmaProp(ByIndex((1,2,3),0) > 1)` — the same operands under a COMPARISON. Refused too, but " +
      "by a different constraint: `comparisonOp` runs `check2(OnlyNumeric)` at `:699` first, and " +
      "`SAny` is not numeric. Same outcome, different reason, which matters to anyone implementing " +
      "one check and reusing it. **SEPARATING.**"),
    Row("gt-any-any-v3", "0b18d191b248040404020406040000b248040404020406040200", Activated, Some(CF), true,
      "`sigmaProp(t(0) > t(1))` — BOTH operands `SAny`. Still REJECTED: the numeric constraint is " +
      "about each operand's type and not about the two agreeing, so **a comparison has no escape** " +
      "where equality does. The row that proves the next one is not a general leniency. " +
      "**SEPARATING.**"),
    Row("gt-any-any-v0", "00d191b248040404020406040000b248040404020406040200", 0.toByte, Some(CF), false,
      "The unsized twin of the row above — REJECTED, no degrade. **SEPARATING.**"),
    Row("eq-any-any-v3", "0b18d193b248040404020406040000b248040404020406040200", Activated, None, true,
      "`sigmaProp(t(0) == t(1))` — BOTH operands `SAny`, under EQUALITY, and it **ACCEPTS**: " +
      "`check2(SameType)` is satisfied, so the tree parses and round-trips to its own bytes. THE " +
      "KEYSTONE OF THE FAMILY, and a cross-tier pair: the eval tier's `Tuple.non_pair_value_gates` " +
      "G1/G2 is this very tree failing at EVAL with `Unknown type SAny`. So this is a box anyone " +
      "can create, whose script no one can satisfy. **SEPARATING BOTH WAYS:** an implementation " +
      "that refuses it at parse over-rejects a valid box, and one that evaluates the comparison " +
      "over-accepts a spend."),
    Row("eq-any-any-v0", "00d193b248040404020406040000b248040404020406040200", 0.toByte, None, false,
      "The same UNSIZED, also accepted and byte-identical on the way back. The accept does not " +
      "depend on the size bit either — which, with the two rejected v0 rows, is what says the " +
      "version dimension is inert for this whole family."),
    Row("eq-concrete-control-v3", "0b0dd193b210030204060400000402", Activated, None, true,
      "**THE CONTROL** — `sigmaProp(ByIndex(Coll[Int](1,2,3),0) == 1)`, the same shape over an " +
      "ordinary collection: ACCEPTED, round-trip identity, and it evaluates to a `SigmaProp` too. " +
      "It is what shows the four rejects above are the `SAny` operand's doing and not `ByIndex` " +
      "under an equality."))

  private def rowsFor(kind: String, suffix: String): Seq[Json] = {
    var i = -1
    def next(): Int = { i += 1; i }
    rows.filter(r => kind == "ErgoTree" || r.carrier).map { r =>
      val carrier = kind match {
        case "ErgoTree"    => r.treeHex
        case "Box"         => boxWithTree(s"${r.slug}-$suffix", r.treeHex)
        case "Transaction" => txWithTree(r.treeHex)
      }
      val name = s"non-pair-tuple-operand-${r.slug}-$suffix#${next()}"
      r.wantClass match {
        case Some(cls) => reject(kind, name, r.what, carrier, r.tv, cls)
        case None      => accept(kind, name, r.what, carrier, r.tv)
      }
    }
  }

  def extract(): Map[String, Json] = {
    def envelope(op: String, entries: Seq[Json]): Json = Json.obj(
      "schema"     -> Json.fromString("santa-wire/v1"),
      "op"         -> Json.fromString(op),
      "blessed_by" -> Json.fromString("jvm:sigma-state-6.0.7"),
      "entries"    -> Json.arr(entries: _*))
    Map(
      OpErgoTree    -> envelope(OpErgoTree, rowsFor("ErgoTree", "tree")),
      OpBox         -> envelope(OpBox, rowsFor("Box", "box")),
      OpTransaction -> envelope(OpTransaction, rowsFor("Transaction", "tx")))
  }

  def writeVectors(outDir: java.nio.file.Path): Unit =
    SpecExtract.writeStaging("AuthoredWireNonPairTupleOperand", extract(), outDir)
}
