package santa

// Authored wire vectors — a PropertyCall with explicit type arguments never reads its RECEIVER's type.
//
// `PropertyCallSerializer.parse` (sigma-state 6.0.7, `:30-52`) has two branches:
//   explicit type args   reads one type per declared type argument after the object and calls
//                        `withConcreteTypes` only — the object's own type is NEVER read
//   anything else        calls `specializeFor(obj.tpe, …)`, which reads it
//
// A receiver whose type READ throws is one whose `tpe` casts: `Filter.tpe` casts `input.tpe` to
// `SCollection` (`transformers.scala:121`), and `Filter(IntConstant(1), …)` is itself built
// without a type read (the `tree_parse_acceptance` C2 rows). So the same ill-typed receiver is
// invisible on the explicit-type branch and fatal on the other, which is what these three entries
// pin. An `If` would not hide the throw — `Quadruple.opType` reads all three child types when the
// node is built (`trees.scala:1313`).
//
// The cast raises a ClassCastException, NOT a ValidationException, so the size bit does not turn
// it into a degrade (contrast rule 1020, `Box.zero_width_collection`): the control is REJECTED
// outright, tree, box and transaction alike. That is the difference the reject arm carries, and
// the reason the accept arms are not merely "it parses": on fork master `920419cb` sigma-rust
// reads the receiver's type on BOTH branches, so it refuses the accepting boxes — and with them
// any transaction carrying such an output. An over-reject in block validation, reachable since v6
// activated.
//
// Measured on the JVM at activated 3 / tree version 3 by
// `santa.spike.PropertyCallReceiverTypeProbe`; the three encodings are the sigma-rust session's
// (prompts/santa-probe-property-call-receiver-type.md), checked here against the ASTs the JVM
// actually builds, so a changed serializer fails this blesser instead of rotting.
//
// extract() re-derives every verdict through WireCanonicalize — the path rudolph grades with.

import io.circe.Json
import scorex.util.encode.Base16
import sigma.VersionContext
import sigma.serialization.ErgoTreeSerializer
import org.ergoplatform.ErgoBox

import RentFixtures._

object AuthoredWirePropertyCallReceiverType {
  val V3: Byte = VersionContext.V6SoftForkVersion // activated AND ergoTree: the node's v6 context
  val OpErgoTree    = "ErgoTree.property_call_receiver_type"
  val OpBox         = "Box.property_call_receiver_type"
  val OpTransaction = "Transaction.property_call_receiver_type"
  val Source        = "santa:authored-property-call-receiver-type"

  /** `slug` names the entry; `what` opens its description; `rejects` picks the arm. */
  private case class Case(slug: String, what: String, treeHex: String, rejects: Boolean)

  /** v3 size-flagged trees, no constant segregation. The receiver of 1 and 3 is the same
    * `Filter(IntConstant(1), (x: Int) => true)`; only the call over it differs. */
  private val Cases: Seq[Case] = Seq(
    Case("none-over-filter",
      "sigmaProp(Filter(1, (x: Int) => true).none[Int].isDefined) — a PropertyCall to " +
      "SGlobal.none (method 10, one explicit type argument T = SInt, the trailing `04`) whose " +
      "RECEIVER is a Filter over an Int. The explicit-type branch reads one type after the " +
      "object and never reads the object's own type, so the ill-typed receiver is invisible: " +
      "the tree PARSES. (The spend would fail at eval — `none`'s invoke needs a Global receiver " +
      "— which the wire tier does not reach.)",
      "0b0fd1e6db6a0ab50402d9010104010104", rejects = false),
    Case("getreg-propcall",
      "sigmaProp(SELF.getReg[Int].isDefined) written as a ZERO-ARGUMENT PropertyCall (`a7` is " +
      "SELF, `04` is T = SInt) — the same branch with a well-typed receiver, the type byte read. " +
      "The positive control for the branch itself: an implementation that refuses this refuses " +
      "the branch, not the receiver. (Eval throws on arity — getReg takes a register index.)",
      "0b07d1e6db6313a704", rejects = false),
    Case("indices-over-filter",
      "CONTROL sigmaProp(Filter(1, (x: Int) => true).indices.size == 0) — `SCollection.indices` " +
      "(method 14) as a PropertyCall with NO explicit type arguments, over the SAME receiver as " +
      "the first entry. Parse takes the other branch, `specializeFor(obj.tpe, …)` READS the " +
      "receiver's type, and `Filter.tpe` casts SInt to SCollection: a ClassCastException, not a " +
      "ValidationException, so the size bit does NOT degrade it. REJECTED outright. Both sides " +
      "should agree here — it is what separates the two branches.",
      "0b11d193b1db0c0eb50402d90101040101010400", rejects = true))

  // ── carriers ─────────────────────────────────────────────────────────────────────────────────
  /** `sigmaProp(true)`'s bytes — the plain tree a carrier is built with, then spliced over. A
    * splice, not a build: the control's tree cannot be deserialized into an `ErgoTree` at all. */
  private val PlainTreeHex = "0008d3"

  private def replaceUnique(in: String, from: String, to: String): String = {
    val i = in.indexOf(from)
    require(i >= 0 && in.indexOf(from, i + 1) < 0, s"marker $from must occur exactly once in $in")
    in.take(i) + to + in.drop(i + from.length)
  }

  /** A full ErgoBox (candidate fields + transaction id + output index) carrying `treeHex`. */
  private def boxWithTree(slug: String, treeHex: String): String = VersionContext.withVersions(V3, V3) {
    val plain = hex(ErgoBox.sigmaSerializer.toBytes(box(s"santa:pcrt:$slug", 1000000L, 1)))
    replaceUnique(plain, PlainTreeHex, treeHex)
  }

  /** A transaction whose only output carries `treeHex`, spending one sigmaProp(true) box. */
  private def txWithTree(treeHex: String): String = VersionContext.withVersions(V3, V3) {
    val spent = box("santa:pcrt:spent", 1000000000L, 1)
    val plain = hex(txBytes(tx(Seq(input(spent, ext())), Seq(candidate(1000000L, 1)))))
    // the output's tree, its creation height and its two empty counts — unambiguous together
    replaceUnique(plain, PlainTreeHex + "01" + "0000", treeHex + "01" + "0000")
  }

  // ── entries ──────────────────────────────────────────────────────────────────────────────────
  private def version: Json =
    Json.obj("activated" -> Json.fromInt(V3.toInt), "ergoTree" -> Json.fromInt(V3.toInt))

  private def canonical(kind: String, in: String): String =
    WireCanonicalize.canonicalize(kind, in, V3, V3)

  private def entry(kind: String, name: String, description: String, in: String,
                    extra: (String, Json)*): Json =
    Json.obj(Seq(
      "name" -> Json.fromString(name), "kind" -> Json.fromString(kind),
      "source" -> Json.fromString(Source), "description" -> Json.fromString(description),
      "bytes_hex" -> Json.fromString(in)) ++ extra ++ Seq("version" -> version): _*)

  /** The JVM must round-trip these bytes to themselves. */
  private def accept(kind: String, name: String, description: String, in: String): Json = {
    val out = canonical(kind, in)
    require(out == in, s"$name: the JVM must round-trip an accept vector to itself — in $in, out $out")
    entry(kind, name, description, in)
  }

  /** The JVM must throw at parse, and throw the receiver-type CAST — not a validation rule. */
  private def reject(kind: String, name: String, description: String, in: String): Json = {
    scala.util.Try(canonical(kind, in)) match {
      case scala.util.Success(out) => sys.error(s"$name: must REJECT at parse, round-tripped to $out")
      case scala.util.Failure(t) =>
        val causes = Iterator.iterate(t: Throwable)(_.getCause).takeWhile(_ != null)
          .map(c => s"${c.getClass.getName}: ${c.getMessage}").toList
        require(causes.exists(_.contains("ClassCastException")) && causes.exists(_.contains("SCollection")),
          s"$name: rejected for the wrong reason: ${causes.mkString(" <- ")}")
    }
    entry(kind, name, description, in, "error" -> Json.fromString("errored"))
  }

  private def arm(kind: String, suffix: String, c: Case, i: Int, tail: String): Json = {
    val name = s"receiver-type-${c.slug}-$suffix#$i"
    val carrier = kind match {
      case "ErgoTree"    => c.treeHex
      case "Box"         => boxWithTree(c.slug, c.treeHex)
      case "Transaction" => txWithTree(c.treeHex)
    }
    val description = s"${c.what} $tail"
    if (c.rejects) reject(kind, name, description, carrier)
    else accept(kind, name, description, carrier)
  }

  /** The AST the JVM builds for a tree that parses — the blesser's own check that an entry's
    * bytes still mean what its description says. Throws for the control, by design. */
  def astOf(treeHex: String): String = VersionContext.withVersions(V3, V3) {
    ErgoTreeSerializer.DefaultSerializer
      .deserializeErgoTree(Base16.decode(treeHex).get).root.fold(u => s"DEGRADED ${u.error}", _.toString)
  }

  def extract(): Map[String, Json] = {
    val treeEntries = Cases.zipWithIndex.map { case (c, i) =>
      arm("ErgoTree", "tree", c, i,
        "The tree alone: the wire tier cannot tell a parse from a degrade (both echo the input " +
        "bytes), so this arm carries only accept-versus-reject; the degrade question is answered " +
        "by the cast's exception CLASS, in the blesser.")
    }
    val boxEntries = Cases.zipWithIndex.map { case (c, i) =>
      arm("Box", "box", c, i,
        "The same tree inside a full ErgoBox — the form block validation meets it in, and the " +
        "form an implementation that reads the receiver's type on the explicit-type branch " +
        "refuses.")
    }
    val txEntries = Cases.zipWithIndex.map { case (c, i) =>
      arm("Transaction", "tx", c, i,
        "The same tree as a transaction's only output: a refusal here costs the whole " +
        "transaction, not one box.")
    }

    def envelope(op: String, entries: Seq[Json]): Json = Json.obj(
      "schema"     -> Json.fromString("santa-wire/v1"),
      "op"         -> Json.fromString(op),
      "blessed_by" -> Json.fromString("jvm:sigma-state-6.0.7"),
      "entries"    -> Json.arr(entries: _*))
    Map(
      OpErgoTree    -> envelope(OpErgoTree, treeEntries),
      OpBox         -> envelope(OpBox, boxEntries),
      OpTransaction -> envelope(OpTransaction, txEntries))
  }

  /** The case trees, for the test's byte-exactness and AST pins. */
  def caseTrees: Seq[(String, String, Boolean)] = Cases.map(c => (c.slug, c.treeHex, c.rejects))

  def writeVectors(outDir: java.nio.file.Path): Unit =
    SpecExtract.writeStaging("AuthoredWirePropertyCallReceiverType", extract(), outDir)
}
