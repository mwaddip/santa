package santa

// Authored wire vectors: a method's type variables unify by NAME, decided at PARSE. Request:
// `~/projects/sigma-rust/prompts/santa-probe-method-signatures.md` (its `parse-flip` / `parse-same` rows, 2026-11-14);
// measured by `santa.spike.MethodSignaturesProbe` (local). Eval-tier companions: AuthoredEvalMethodTypeVariableNames
// (the same expressions, X* and N*, graded there as errored against `true`, plus the consumer rows).
//
// THE RULE: `specializeFor` (SMethod.scala:193-199) unifies a method's declared domain with the receiver's and the
// arguments' static types; two type variables unify only when their NAMES are equal (`unifyTypes`,
// sigma/ast/package.scala:39-42); on a failure the call keeps the table's own variable in its type. Each row is
// `sigmaProp(if (true) true else <the call compared with a value of a named type>)`: `==` checks its operands when
// the node is built (`SameType2`, plain `t1 == t2`), so the reader refuses one name and builds another, in a branch
// that is never taken. The v6 rows also pin a second reader rule: `deserializeTo[Coll[T]]` / `none[Coll[T]]` are
// typed with the explicit argument substituted TWICE (`Coll[Coll[T]]`), because `T` is the Global method's own
// variable (MethodCallSerializer.scala:84, then `MethodCall.tpe`, values.scala:1355-1358).
//
// NO DEGRADE: `ConstraintFailed` extends `BuilderException`, not `ValidationException`, so the size bit's degrade
// gate never takes it (the same as `non_pair_tuple_operand`): a refusal is a REJECT at the sized v3 form. The v5 rows
// are unsized v0 trees, where nothing degrades at all. Every row is SigmaProp-rooted: a bare Boolean root degrades
// on rule 1001 for a reason that has nothing to do with names. The v6 rows read `Global.none`/`deserializeTo` (v6
// methods), so they have no v0 twin; the v5 rows build the same `map` call over `Coll[Option[τ]]()(0)`.

import io.circe.Json
import scorex.util.encode.Base16
import sigma.VersionContext
import sigma.serialization.SigmaSerializer
import org.ergoplatform.ErgoBox

import RentFixtures._

object AuthoredWireMethodTypeVariableNames {
  val OpErgoTree    = "ErgoTree.method_type_variable_names"
  val OpBox         = "Box.method_type_variable_names"
  val OpTransaction = "Transaction.method_type_variable_names"
  val Ops: Seq[String] = Seq(OpErgoTree, OpBox, OpTransaction)
  val Source        = "santa:authored-method-type-variable-names"
  private val CF = "sigma.exceptions.ConstraintFailed"

  private def hx(bs: Array[Byte]): String = Base16.encode(bs).toLowerCase
  private def vlq(n: Int): String = { val w = SigmaSerializer.startWriter(); w.putUInt(n.toLong); hx(w.toBytes) }
  private val PlainTree = "0008d3"

  private def replaceUnique(in: String, from: String, to: String): String = {
    val i = in.indexOf(from)
    require(i >= 0 && in.indexOf(from, i + 1) < 0, s"marker $from must occur exactly once")
    in.take(i) + to + in.drop(i + from.length)
  }

  /** One row: `body` is the expression under `sigmaProp`; `v6` rows ride a sized v3 tree at activated 3, v5 rows
    * an unsized v0 tree at activated 2. `accept` rows round-trip to their own bytes. */
  case class Row(id: String, v6: Boolean, body: String, accept: Boolean, slug: String, what: String) {
    def activated: Byte = if (v6) 3 else 2
    def treeVersion: Byte = if (v6) 3 else 0
    def treeHex: String = { val b = "d1" + body; if (v6) "0b" + vlq(b.length / 2) + b else "00" + b }
  }

  private def boxWithTree(r: Row, slug: String): String = VersionContext.withVersions(r.activated, r.activated) {
    replaceUnique(hx(ErgoBox.sigmaSerializer.toBytes(box(s"santa:mtvn:$slug", 1000000L, 1))), PlainTree, r.treeHex)
  }
  private def txWithTree(r: Row): String = VersionContext.withVersions(r.activated, r.activated) {
    val plain = hx(txBytes(tx(Seq(input(box("santa:mtvn:spent", 1000000000L, 1), ext())),
      Seq(candidate(1000000000L, 1)))))
    replaceUnique(plain, PlainTree + "01" + "0000", r.treeHex + "01" + "0000")
  }

  private val NoDegrade = " `ConstraintFailed` is a `BuilderException`, not a `ValidationException`, so the size " +
    "bit does not degrade it: a REJECT."
  private val V5 = " Unsized tree v0 at activated 2: the rule is not version-gated."

  private val TV = "670154"; private val IV = "67024956"; private val UV = "670155"
  private def noneV5(ty: String): String = { val o = if (ty == "04") "28" else "24" + ty; s"b28300${o}040000" }
  private def parseV5(ty: String) = s"950101010193e4dc2407${noneV5(ty)}01d90101${ty}04020402"

  val Rows: Seq[Row] = Seq(
    Row("X1", v6 = true, "950101010193e4dc2407db6a0add0c67015401d901010c67015404020402", accept = false,
      "none-coll-t-map-v3", "`sigmaProp(if (true) true else Global.none[Coll[T]].map((x: Coll[T]) => 1).get == 1)` " +
      "— REJECTED (`ConstraintFailed`, `SameType2`): the explicit argument names `none`'s own `T` and lands twice, so " +
      "the receiver is `Option[Coll[Coll[T]]]`; `map` binds its `T` two ways, the call stays `Option[R]`, and " +
      "`.get == 1` compares an `R` with an `Int`. The fork refuses it too." + NoDegrade),
    Row("X2", v6 = true, "950101010193b2db0c1edc6a04dd010e01000c670154040000dc6a04dd010e0100670154", accept = false,
      "deserializeto-coll-t-reverse-index-v3", "`sigmaProp(if (true) true else deserializeTo[Coll[T]](Coll(0.toByte))" +
      ".reverse(0) == deserializeTo[T](Coll(0.toByte)))` — REJECTED (`ConstraintFailed`, `SameType2`): " +
      "`deserializeTo[Coll[T]]` is typed `Coll[Coll[T]]` (the double substitution), `reverse` binds `IV := Coll[T]`, " +
      "so `reverse(0)` is a `Coll[T]` against a `T`. The fork refuses it too." + NoDegrade),
    Row("X3", v6 = true, "950101010193b2b2db0c1edc6a04dd010e01000c670154040000040000dc6a04dd010e0100670154",
      accept = true, "deserializeto-coll-t-reverse-index-index-v3", "`sigmaProp(if (true) true else " +
      "deserializeTo[Coll[T]](Coll(0.toByte)).reverse(0)(0) == deserializeTo[T](Coll(0.toByte)))` — ACCEPTED, " +
      "round-trips to its own bytes: X2 with one more `(0)`, a `T` against a `T`. CONTROL for X2."),
    Row("X4", v6 = true, "950101010193e4dc2407db6a0add67015401d9010167015404020402", accept = true,
      "none-t-map-v3", "`sigmaProp(if (true) true else Global.none[T].map((x: T) => 1).get == 1)` — ACCEPTED, " +
      "round-trips to its own bytes: `T` stands where `Option.map` declares `T`, they unify, `R := Int`, the call is " +
      "`Option[Int]`. SEPARATING: a table that names `Option.map`'s item otherwise (the fork's `IV`) refuses it."),
    Row("X5", v6 = true, "950101010193e4dc2407db6a0add6702495601d901016702495604020402", accept = false,
      "none-iv-map-v3", "`sigmaProp(if (true) true else Global.none[IV].map((x: IV) => 1).get == 1)` — REJECTED " +
      "(`ConstraintFailed`, `SameType2`): `IV` is not `Option.map`'s `T`, two type variables unify only when their " +
      "names are equal, the call stays `Option[R]`, and `.get == 1` compares an `R` with an `Int`. SEPARATING: a " +
      "table that names `Option.map`'s item `IV` builds it." + NoDegrade),
    Row("X6", v6 = true, "950101010193db0c1edc6a04dd010e01000c670155dc6a04dd010e01000c67024956", accept = true,
      "deserializeto-coll-u-reverse-vs-coll-iv-v3", "`sigmaProp(if (true) true else deserializeTo[Coll[U]]" +
      "(Coll(0.toByte)).reverse == deserializeTo[Coll[IV]](Coll(0.toByte)))` — ACCEPTED, round-trips to its own " +
      "bytes: `U` is not `reverse`'s `IV`, the call stays unspecialised, typed `Coll[IV]`, equal to the right side. " +
      "SEPARATING: a table that names `reverse`'s variable otherwise (the fork's `T`) types the left side `Coll[T]` " +
      "and refuses it."),
    Row("X7", v6 = true, "950101010193db0c1edc6a04dd010e01000c670154dc6a04dd010e01000c670154", accept = true,
      "deserializeto-coll-t-reverse-vs-coll-t-v3", "`sigmaProp(if (true) true else deserializeTo[Coll[T]]" +
      "(Coll(0.toByte)).reverse == deserializeTo[Coll[T]](Coll(0.toByte)))` — ACCEPTED, round-trips to its own " +
      "bytes: both sides `Coll[Coll[T]]` (the double substitution; `reverse` binds `IV := Coll[T]`). SEPARATING for an " +
      "implementation that substitutes the explicit argument once: `Coll[T]`, an unspecialised `Coll[IV]` against " +
      "`Coll[T]`, refused."),
    Row("N1", v6 = false, parseV5(TV), accept = true, "option-t-map-v0", "`sigmaProp(if (true) true else " +
      "Coll[Option[T]]()(0).map((x: T) => 1).get == 1)` — ACCEPTED, round-trips to its own bytes: `T` stands where " +
      "`Option.map` declares `T`, `R := Int`. SEPARATING: a table that names `Option.map`'s item otherwise refuses " +
      "it." + V5),
    Row("N2", v6 = false, parseV5(IV), accept = false, "option-iv-map-v0", "`sigmaProp(if (true) true else " +
      "Coll[Option[IV]]()(0).map((x: IV) => 1).get == 1)` — REJECTED (`ConstraintFailed`, `SameType2`): `IV` is not " +
      "`Option.map`'s `T`, the call stays `Option[R]`. SEPARATING: a table that names the item `IV` accepts it." + V5),
    Row("N3", v6 = false, parseV5(UV), accept = false, "option-u-map-v0", "`sigmaProp(if (true) true else " +
      "Coll[Option[U]]()(0).map((x: U) => 1).get == 1)` — REJECTED, as N2: a name in no table. SEPARATING: an " +
      "implementation that unifies two type variables whatever their names accepts it." + V5),
    Row("N4", v6 = false, parseV5("0c" + TV), accept = true, "option-coll-t-map-v0", "`sigmaProp(if (true) true " +
      "else Coll[Option[Coll[T]]]()(0).map((x: Coll[T]) => 1).get == 1)` — ACCEPTED, round-trips to its own bytes: " +
      "`T := Coll[T]` from the receiver and from the lambda alike. X1 is the same call over `Global.none[Coll[T]]`, " +
      "which the reader substitutes twice, and it is rejected." + V5),
    Row("N5", v6 = false, parseV5("04"), accept = true, "option-int-map-v0", "`sigmaProp(if (true) true else " +
      "Coll[Option[Int]]()(0).map((x: Int) => 1).get == 1)` — ACCEPTED, round-trips to its own bytes. CONTROL for " +
      "N1-N4." + V5))

  private def version(r: Row): Json =
    Json.obj("activated" -> Json.fromInt(r.activated.toInt), "ergoTree" -> Json.fromInt(r.treeVersion.toInt))

  private def check(kind: String, r: Row, name: String, in: String): Unit =
    scala.util.Try(WireCanonicalize.canonicalize(kind, in, r.activated, r.treeVersion)) match {
      case scala.util.Success(out) =>
        require(r.accept, s"$name: must be REFUSED, round-tripped to $out")
        require(out == in, s"$name: the JVM must round-trip an accept vector to itself — in $in, out $out")
      case scala.util.Failure(t) =>
        val causes = Iterator.iterate(t: Throwable)(_.getCause).takeWhile(_ != null).toSeq
        require(!r.accept, s"$name: must ACCEPT, refused: ${causes.map(_.getClass.getName).mkString(" <- ")}")
        require(causes.exists(c => c.getClass.getName == CF && c.getMessage.contains("SameType2")),
          s"$name: refused by the wrong exception (want $CF on SameType2): " +
            causes.map(c => c.getClass.getName + ": " + c.getMessage).mkString(" <- ").take(400))
    }

  private def rowsFor(kind: String, suffix: String, v6: Boolean): Seq[Json] =
    Rows.filter(_.v6 == v6).zipWithIndex.map { case (r, i) =>
      val in = kind match {
        case "ErgoTree"    => r.treeHex
        case "Box"         => boxWithTree(r, s"${r.slug}-$suffix")
        case "Transaction" => txWithTree(r)
      }
      val name = s"method-type-variable-names-${r.slug}-$suffix#$i"
      check(kind, r, name, in)
      Json.obj(Seq(
        "name" -> Json.fromString(name), "kind" -> Json.fromString(kind),
        "source" -> Json.fromString(Source), "description" -> Json.fromString(s"[${r.id}] ${r.what}"),
        "bytes_hex" -> Json.fromString(in)) ++
        (if (r.accept) Nil else Seq("error" -> Json.fromString("errored"))) ++
        Seq("version" -> version(r)): _*)
    }

  /** v6 (activated 3) or v5 (activated 2): the same three ops, one file each under wire/v6 or wire/v5. */
  def extract(v6: Boolean): Map[String, Json] = {
    def envelope(op: String, entries: Seq[Json]): Json = Json.obj(
      "schema"     -> Json.fromString("santa-wire/v1"),
      "op"         -> Json.fromString(op),
      "blessed_by" -> Json.fromString("jvm:sigma-state-6.0.7"),
      "entries"    -> Json.arr(entries: _*))
    Map(
      OpErgoTree    -> envelope(OpErgoTree, rowsFor("ErgoTree", "tree", v6)),
      OpBox         -> envelope(OpBox, rowsFor("Box", "box", v6)),
      OpTransaction -> envelope(OpTransaction, rowsFor("Transaction", "tx", v6)))
  }

  def writeVectors(outDir: java.nio.file.Path): Unit = {
    SpecExtract.writeStaging("AuthoredWireMethodTypeVariableNames.v6", extract(v6 = true), outDir.resolve("v6"))
    SpecExtract.writeStaging("AuthoredWireMethodTypeVariableNames.v5", extract(v6 = false), outDir.resolve("v5"))
  }
}
