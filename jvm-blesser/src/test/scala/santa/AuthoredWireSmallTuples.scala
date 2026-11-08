package santa

// Authored wire vectors — tuples of fewer than two items, and the 128-item script-tuple boundary,
// as a bare ERGOTREE, as a BOX and as a TRANSACTION OUTPUT, in both tree forms.
//
// The three asymmetries, which row separates, and why, are all in `SmallTupleTrees`. The short
// version, because it decides how to read a red:
//
//   * the two `..._type` rows are REJECTED at both versions and only their **v3** row can
//     separate — an implementation that refuses the tuple at parse degrades behind the size bit
//     and accepts, where the JVM rejects on the WRITE. Their **v0** twin grades green on both
//     sides and is a control, not evidence;
//   * `tuple_expr_128_items` is REJECTED at both versions and BOTH separate: an implementation
//     reading the count unsigned parses it and accepts, and no degrade is involved on either side;
//   * the `tuple_expr_{one,zero}_item` rows are ACCEPTED at both versions and are the exact
//     MIRROR of the `_type` pair — only their **v0** row separates (an implementation whose tuples
//     hold 2..255 refuses a box the JVM takes), because at v3 a refusing implementation degrades
//     and echoes the same bytes the JVM echoes. Measured, not predicted: at fork `6f49c97c` the
//     v0 rows are red on all three kinds and the v3 rows are green;
//   * the `*_control` rows and `const_triple_type` are writable twins, green by construction.
//
// The register arms are carrier-only (no ErgoTree kind): `register_one_item_type` is a REJECT and
// a CONTROL — a register value sits outside the tree, so the size bit cannot degrade it and both
// sides refuse — while `register_nested_tuple_expr` is an ACCEPT the JVM writes itself.
//
// extract() re-derives every verdict through `WireCanonicalize` — the path rudolph grades with —
// and each reject asserts the REASON out of the cause chain, not just the refusal.

import io.circe.Json
import scorex.util.encode.Base16
import sigma.VersionContext
import org.ergoplatform.ErgoBox

import RentFixtures._
import SmallTupleTrees._

object AuthoredWireSmallTuples {
  val OpErgoTree    = "ErgoTree.small_tuples"
  val OpBox         = "Box.small_tuples"
  val OpTransaction = "Transaction.small_tuples"
  val Source        = "santa:authored-small-tuples"

  private def hx(bs: Array[Byte]): String = Base16.encode(bs)
  private def ctx[A](f: => A): A = VersionContext.withVersions(Activated, Activated)(f)

  private def replaceUnique(in: String, from: String, to: String): String = {
    val i = in.indexOf(from)
    require(i >= 0 && in.indexOf(from, i + 1) < 0, s"marker $from must occur exactly once")
    in.take(i) + to + in.drop(i + from.length)
  }

  /** A box over `sigmaProp(true)`, its tree spliced out for `treeHex`. A splice, not a build: the
    * JVM cannot WRITE a box whose tree holds a 0/1-item tuple type, which is the fact under test. */
  private def boxWithTree(slug: String, treeHex: String): String = ctx {
    replaceUnique(hx(ErgoBox.sigmaSerializer.toBytes(box(s"santa:st:$slug", 1000000L, 1))),
      PlainTreeHex, treeHex)
  }
  /** A transaction spending a plain box and creating ONE output whose tree is `treeHex`. The
    * output carries the whole input value, so the Erg-balance check cannot mask anything. */
  private def txWithTree(treeHex: String): String = ctx {
    val plain = hx(txBytes(tx(Seq(input(box("santa:st:spent", 1000000000L, 1), ext())),
      Seq(candidate(1000000000L, 1)))))
    replaceUnique(plain, PlainTreeHex + "01" + "0000", treeHex + "01" + "0000")
  }

  /** A box whose R4 is `regHex`, built over a four-byte placeholder the JVM does write. */
  private def boxWithRegister(slug: String, regHex: String): String = ctx {
    val plain = hx(ErgoBox.sigmaSerializer.toBytes(
      box(s"santa:st:$slug", 1000000L, 1, regs = Map(ErgoBox.R4 -> RegisterPlaceholder))))
    replaceUnique(plain, RegisterPlaceholderHex, regHex)
  }
  private def txWithRegister(slug: String, regHex: String): String = ctx {
    val plain = hx(txBytes(tx(Seq(input(box(s"santa:st:$slug:spent", 1000000000L, 1), ext())),
      Seq(candidate(1000000000L, 1, regs = Map(ErgoBox.R4 -> RegisterPlaceholder))))))
    replaceUnique(plain, RegisterPlaceholderHex, regHex)
  }

  private def version(treeVersion: Int): Json =
    Json.obj("activated" -> Json.fromInt(Activated.toInt), "ergoTree" -> Json.fromInt(treeVersion))

  private def canonical(kind: String, in: String, treeVersion: Byte): String =
    WireCanonicalize.canonicalize(kind, in, Activated, treeVersion)

  private def entry(kind: String, name: String, description: String, in: String, treeVersion: Int,
                    extra: (String, Json)*): Json =
    Json.obj(Seq(
      "name" -> Json.fromString(name), "kind" -> Json.fromString(kind),
      "source" -> Json.fromString(Source), "description" -> Json.fromString(description),
      "bytes_hex" -> Json.fromString(in)) ++ extra ++ Seq("version" -> version(treeVersion)): _*)

  private def accept(kind: String, name: String, description: String, in: String,
                     treeVersion: Byte): Json = {
    val out = canonical(kind, in, treeVersion)
    require(out == in, s"$name: the JVM must round-trip an accept vector to itself — in $in, out $out")
    entry(kind, name, description, in, treeVersion.toInt)
  }

  /** A reject whose REASON is asserted: every fragment must appear somewhere in the cause chain. */
  private def reject(kind: String, name: String, description: String, in: String,
                     treeVersion: Byte, reasonMust: Seq[String]): Json = {
    scala.util.Try(canonical(kind, in, treeVersion)) match {
      case scala.util.Success(out) => sys.error(s"$name: must be REFUSED, round-tripped to $out")
      case scala.util.Failure(t) =>
        val causes = Iterator.iterate(t: Throwable)(_.getCause).takeWhile(_ != null)
          .map(c => s"${c.getClass.getName}: ${c.getMessage}").mkString(" <- ")
        reasonMust.foreach(frag => require(causes.contains(frag),
          s"$name: refused for the wrong reason (want '$frag'): $causes"))
    }
    entry(kind, name, description, in, treeVersion.toInt, "error" -> Json.fromString("errored"))
  }

  /** The per-arm, per-version sentence that says whether this row can separate. The whole point of
    * the family: a green on a control means nothing, a green on a separating row means parity. */
  private def role(arm: Arm, treeVersion: Byte): String = (arm, treeVersion) match {
    case (TypeWrite, V0) =>
      "THE CONTROL OF THIS PAIR, not evidence. The JVM refuses on the WRITE, after the parse " +
      "succeeded; an implementation that refuses the tuple at PARSE has no degrade path at an " +
      "unsized tree and refuses too. Both sides refuse for different reasons and the row grades " +
      "GREEN. Its v3 twin is where the disagreement shows."
    case (TypeWrite, _) =>
      "THE SEPARATING ROW. The JVM's answer is the same as at v0 — it parses the tree and then " +
      "cannot write it back — but an implementation that refuses the tuple at PARSE now has the " +
      "size bit to degrade behind: it stores the tree as opaque bytes and ACCEPTS the carrier " +
      "the JVM rejects. An over-accept, and the only row of this pair that can show it."
    case (CountBound, _) =>
      "SEPARATING AT BOTH VERSIONS, and no degrade is involved: an implementation that reads the " +
      "count UNSIGNED parses 128 items successfully and accepts the carrier. The JVM's refusal is " +
      "a NegativeArraySizeException, which is not a ValidationException, so the size bit cannot " +
      "turn it into a degrade either."
    case (ExprAccept, V0) =>
      "THE SEPARATING ROW, and the exact MIRROR of the `_type` pair. `TupleSerializer` writes a " +
      "count and no type, so the JVM round-trips this carrier verbatim. An implementation whose " +
      "tuples hold 2..255 refuses it, and at an unsized tree it has nothing to degrade behind — " +
      "an over-reject of a box the JVM accepts, reachable since v6 activated."
    case (ExprAccept, _) =>
      "THE CONTROL OF THIS PAIR, not evidence — the mirror of the `_type` pair, whose control is " +
      "the v0 row. This row is GREEN whether an implementation parses the tuple correctly (and " +
      "echoes the bytes) or refuses it at parse and DEGRADES behind the size bit (and echoes the " +
      "same bytes). The wire tier is degrade-blind, so the row cannot tell those two apart and is " +
      "not evidence of either. Its v0 twin is where the disagreement shows."
    case (Control, _) =>
      "A WRITABLE TWIN: green by construction, identical in shape to the row it guards, with a " +
      "tuple the JVM can write. It is here so that a red on its partner cannot be blamed on the " +
      "shape of the carrier."
  }

  private def rows(kind: String, suffix: String): Seq[Json] = {
    var i = -1
    def next(): Int = { i += 1; i }

    def carrier(slug: String, treeHex: String): String = kind match {
      case "ErgoTree"    => treeHex
      case "Box"         => boxWithTree(slug, treeHex)
      case "Transaction" => txWithTree(treeHex)
    }

    val treeRows = for {
      c <- cases
      (treeVersion, tag) <- Seq((V0, "v0"), (Activated, "v3"))
    } yield {
      val treeHex = if (treeVersion == V0) v0(c.body) else v3(c.body)
      val name = s"small-tuple-${c.slug}-$tag-$suffix#${next()}"
      val description = s"${c.what}. At tree version ${treeVersion.toInt}, " +
        (if (treeVersion == V0) "UNSIZED" else "SIZE-FLAGGED") + s". ${role(c.arm, treeVersion)}"
      val slug = s"${c.slug}-$tag"
      if (c.arm == TypeWrite)
        reject(kind, name, description, carrier(slug, treeHex), treeVersion,
          Seq("Invalid Tuple type with less than 2 items"))
      else if (c.arm == CountBound)
        reject(kind, name, description, carrier(slug, treeHex), treeVersion,
          Seq("NegativeArraySizeException"))
      else accept(kind, name, description, carrier(slug, treeHex), treeVersion)
    }

    // The register arms exist only inside a carrier, so the ErgoTree kind has none.
    val registerRows =
      if (kind == "ErgoTree") Nil
      else {
        val oneItem = s"small-tuple-register_one_item_type-$suffix#${next()}"
        val nested  = s"small-tuple-register_nested_tuple_expr-$suffix#${next()}"
        def reg(slug: String, regHex: String): String =
          if (kind == "Box") boxWithRegister(slug, regHex) else txWithRegister(slug, regHex)
        Seq(
          reject(kind, oneItem,
            "R4 holds a CONSTANT of type `(Int)` — a one-item tuple type in a REGISTER rather " +
            "than in the tree, on a box guarded by `sigmaProp(true)`. The box PARSES and the " +
            "register reads back as `ConstantNode(Coll(1),(SInt$))`, a one-element `Coll[Any]` " +
            "carrying a declared type of `(Int)`; writing it again throws in `w.putValue` " +
            "(`ErgoBoxCandidate.scala:175`). ALSO A CONTROL: a register value sits outside the " +
            "tree, so there is no size bit to degrade behind at any version and an " +
            "implementation that refuses the tuple at parse refuses too. Green on both sides " +
            "today; it is here to catch the over-accept if one ever starts reading such a type.",
            reg("reg_one_item", OneItemConst), V0,
            Seq("Invalid Tuple type with less than 2 items")),
          accept(kind, nested,
            "R4 holds `Tuple(Tuple(1), 1)` — a pair whose FIRST item is a one-item tuple, as an " +
            "EXPRESSION. `TupleSerializer` writes a count and no type, so the JVM writes this " +
            "register itself and the carrier is valid. SEPARATING: an implementation whose " +
            "tuples hold 2..255 refuses the nested one and so refuses a box the JVM accepts. " +
            "The transaction tier carries its spend — `SELF.R4[((Int),Int)].isDefined` is true, " +
            "and the value is `Coll(Coll(1),1)`, from which no field can be selected.",
            reg("reg_nested", NestedTupleRegisterHex), V0))
      }

    treeRows ++ registerRows
  }

  def extract(): Map[String, Json] = {
    def envelope(op: String, entries: Seq[Json]): Json = Json.obj(
      "schema"     -> Json.fromString("santa-wire/v1"),
      "op"         -> Json.fromString(op),
      "blessed_by" -> Json.fromString("jvm:sigma-state-6.0.7"),
      "entries"    -> Json.arr(entries: _*))
    Map(
      OpErgoTree    -> envelope(OpErgoTree, rows("ErgoTree", "tree")),
      OpBox         -> envelope(OpBox, rows("Box", "box")),
      OpTransaction -> envelope(OpTransaction, rows("Transaction", "tx")))
  }

  def writeVectors(outDir: java.nio.file.Path): Unit =
    SpecExtract.writeStaging("AuthoredWireSmallTuples", extract(), outDir)
}
