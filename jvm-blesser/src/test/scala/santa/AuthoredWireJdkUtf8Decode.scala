package santa

// Authored wire vectors: bytes become TEXT as the JDK decodes them, and are written back as that text. Request:
// `~/projects/sigma-rust/prompts/santa-probe-jvm-string.md` (fork PR 93, its `rt`, `tv` and `tx` sets with their
// controls); measured by `santa.spike.StringDecodeProbe` (local). Eval-tier companion: AuthoredEvalJdkUtf8Decode.
//
// THE RULE: `new String(bytes, UTF_8)` for String DATA (CoreDataSerializer.scala:109-113) and for a type variable's
// NAME (TypeSerializer.scala:204), and the TEXT is what is kept and written back (UTF-8 of the text). The JDK takes
// any continuation byte after `ED` (an encoded surrogate is ONE U+FFFD) and replaces an overlong form or a code
// point above U+10FFFF one U+FFFD PER BYTE. So a non-canonical input round-trips to OTHER bytes: those entries carry
// `expected_bytes_hex` (a non-identity round-trip, runner-contract-wire §1), and the runner is graded on writing the
// JVM's bytes. Decoded names also decide a parse: `==` checks its operands' types when the node is built
// (`SameType2`, plain equality of the types), and two type variables are equal when their decoded names are, so
// `Coll[⟨E0 80 80⟩]() == Coll[⟨FF FF FF⟩]()` parses (three U+FFFD a side) and `… == Coll[⟨FF⟩]()` is REJECTED
// (`ConstraintFailed`, a BuilderException: the size bit does not degrade it).
//
// A transaction's id is computed over the bytes the JVM writes, so a transaction holding such a String (an output
// register, an output tree, a context extension) has the id of its well-formed twin: a port that keeps the bytes
// computes another id, a wrong block. The entries' descriptions state the JVM's id.
//
// Ops: `Constant` and `Transaction` in wire/v5 (activated 2; they run on every runner, the v5-only Fleet included),
// `ErgoTree` in wire/v5 (unsized tree v0) and wire/v6 (sized tree v3). Every tree is SigmaProp-rooted (a non-SigmaProp
// root degrades on rule 1001 and would echo instead of re-writing).

import io.circe.Json
import scorex.util.encode.Base16
import sigma.VersionContext
import sigma.serialization.SigmaSerializer

object AuthoredWireJdkUtf8Decode {
  val OpConstant    = "Constant.jdk_utf8_decode"
  val OpErgoTree    = "ErgoTree.jdk_utf8_decode"
  val OpTransaction = "Transaction.jdk_utf8_decode"
  val Source        = "santa:authored-jdk-utf8-decode"
  private val CF = "sigma.exceptions.ConstraintFailed"

  private def hx(bs: Array[Byte]): String = Base16.encode(bs).toLowerCase
  private def vlq(n: Int): String = { val w = SigmaSerializer.startWriter(); w.putUInt(n.toLong); hx(w.toBytes) }

  /** One row. `in` is the object's bytes (for ErgoTree: the root expression, wrapped per version). */
  case class Row(kind: String, id: String, v6: Boolean, in: String, what: String) {
    def activated: Byte = if (v6) 3 else 2
    def treeVersion: Byte = if (v6) 3 else 0
    def bytes: String = kind match {
      case "ErgoTree" => if (v6) "0b" + vlq(in.length / 2) + in else "00" + in
      case _          => in
    }
  }

  val ConstantRows: Seq[Row] = Seq(
    Row("Constant", "str-sur", v6 = false, "6603eda080", "The String ⟨ED A0 80⟩, an encoded surrogate: ONE U+FFFD, " +
      "written `66 03 EF BF BD`. SEPARATING: a port that keeps the bytes echoes; a decoder that replaces per maximal " +
      "subpart writes three U+FFFD (`66 09 …`)."),
    Row("Constant", "str-sur-ascii", v6 = false, "6603eda041", "The String ⟨ED A0 41⟩: `ED A0` is one U+FFFD (the JDK " +
      "takes the continuation byte after `ED`), then `A`: written `66 04 EF BF BD 41`. SEPARATING, as str-sur."),
    Row("Constant", "str-overlong3", v6 = false, "6603e08080", "The String ⟨E0 80 80⟩, an overlong form: one U+FFFD " +
      "PER BYTE, written `66 09 EF BF BD × 3`. SEPARATING: a decoder that replaces the overlong sequence once writes " +
      "`66 03 EF BF BD`."),
    Row("Constant", "str-above", v6 = false, "6604f4908080", "The String ⟨F4 90 80 80⟩, a code point above U+10FFFF: " +
      "one U+FFFD per byte, written `66 0C EF BF BD × 4`. SEPARATING, as str-overlong3."),
    Row("Constant", "str-valid", v6 = false, "6603e282ac", "The String ⟨E2 82 AC⟩ (€), well-formed: written back as " +
      "read. CONTROL."))

  private def size0(id: String, name: String, nameHex: String, what: String): Seq[Row] = Seq(false, true).map(v6 =>
    Row("ErgoTree", s"size0-$id", v6, s"d193b18300$nameHex" + "0400",
      s"`sigmaProp(Coll[$name]().size == 0)`, a type variable named $name. $what"))
  private def eqRow(id: String, l: String, r: String, body: String, what: String): Seq[Row] = Seq(false, true).map(v6 =>
    Row("ErgoTree", s"eq-$id", v6, s"d1$body", s"`sigmaProp(Coll[$l]() == Coll[$r]())`. $what"))

  val ErgoTreeRows: Seq[Row] =
    size0("sur", "⟨ED A0 80⟩", "6703eda080", "One U+FFFD: the name is written `EF BF BD`. SEPARATING, as str-sur.") ++
    size0("overlong3", "⟨E0 80 80⟩", "6703e08080", "One U+FFFD per byte: the name is written as 9 bytes (`67 09 …`). " +
      "SEPARATING, as str-overlong3.") ++
    size0("overlong4", "⟨F0 80 80 80⟩", "6704f0808080", "One U+FFFD per byte: 12 bytes. SEPARATING, as str-overlong3.") ++
    size0("above", "⟨F4 90 80 80⟩", "6704f4908080", "One U+FFFD per byte: 12 bytes. SEPARATING, as str-above.") ++
    size0("ff", "⟨FF⟩", "6701ff", "`FF` is never valid UTF-8: one U+FFFD, written `EF BF BD`.") ++
    size0("valid", "⟨E2 82 AC⟩", "6703e282ac", "Well-formed: written back as read. CONTROL.") ++
    eqRow("ctl", "T", "T", "9383006701548300670154", "Both names `T`: built, written back as read. CONTROL.") ++
    eqRow("ctl-differ", "T", "U", "9383006701548300670155", "`T` and `U` differ: REJECTED (`ConstraintFailed`, " +
      "`SameType2`). CONTROL: what a name mismatch does.") ++
    eqRow("overlong-vs-3ff", "⟨E0 80 80⟩", "⟨FF FF FF⟩", "9383006703e0808083006703ffffff", "Both decode to three " +
      "U+FFFD: the types are EQUAL, the node is built, and both names are written `EF BF BD × 3`. SEPARATING: a port " +
      "that compares the bytes, or replaces the overlong once, refuses the tree.") ++
    eqRow("overlong-vs-1ff", "⟨E0 80 80⟩", "⟨FF⟩", "9383006703e0808083006701ff", "Three U+FFFD against one: REJECTED " +
      "(`ConstraintFailed`). SEPARATING: a decoder that replaces the overlong once builds it.") ++
    eqRow("above-vs-4ff", "⟨F4 90 80 80⟩", "⟨FF FF FF FF⟩", "9383006704f490808083006704ffffffff", "Four U+FFFD a " +
      "side: built, both written `EF BF BD × 4`. SEPARATING, as overlong-vs-3ff.") ++
    eqRow("above-vs-1ff", "⟨F4 90 80 80⟩", "⟨FF⟩", "9383006704f490808083006701ff", "Four against one: REJECTED. " +
      "SEPARATING, as overlong-vs-1ff.") ++
    eqRow("sur-vs-1ff", "⟨ED A0 80⟩", "⟨FF⟩", "9383006703eda08083006701ff", "One U+FFFD a side: built, both written " +
      "`EF BF BD`. SEPARATING: a decoder that replaces the surrogate per maximal subpart refuses it.") ++
    eqRow("sur-vs-3ff", "⟨ED A0 80⟩", "⟨FF FF FF⟩", "9383006703eda08083006703ffffff", "One against three: REJECTED. " +
      "SEPARATING: a three-replacement decoder builds it.")

  val TxRows: Seq[Row] = Seq(
    Row("Transaction", "tx-ctl-r4-repl", v6 = false, "01a05d90c50251aea28100ccaa1da38004fb5a04154bfa300a600f3c10d130816a00000000018094ebdc030008d30100016603efbfbd",
      "an output whose R4 is the String ⟨EF BF BD⟩. CONTROL: well-formed text, written back as read."),
    Row("Transaction", "tx-r4-sur", v6 = false, "01a05d90c50251aea28100ccaa1da38004fb5a04154bfa300a600f3c10d130816a00000000018094ebdc030008d30100016603eda080",
      "an output whose R4 is the String ⟨ED A0 80⟩. The JVM reads it as ONE U+FFFD and writes `EF BF BD`, so the transaction it writes, and the id it computes over those bytes, are tx-ctl-r4-repl's. SEPARATING: a port that keeps the bytes, or decodes three replacements, writes other bytes and computes another id: a wrong transaction id is a wrong block."),
    Row("Transaction", "tx-ctl-ext-repl", v6 = false, "01a05d90c50251aea28100ccaa1da38004fb5a04154bfa300a600f3c10d130816a0001006603efbfbd0000018094ebdc030008d3010000",
      "an input whose context extension holds var 0 = the String ⟨EF BF BD⟩. CONTROL."),
    Row("Transaction", "tx-ext-sur", v6 = false, "01a05d90c50251aea28100ccaa1da38004fb5a04154bfa300a600f3c10d130816a0001006603eda0800000018094ebdc030008d3010000",
      "an input whose context extension holds var 0 = the String ⟨ED A0 80⟩, written back as `EF BF BD`: the bytes and id of tx-ctl-ext-repl. SEPARATING, as tx-r4-sur."),
    Row("Transaction", "tx-ctl-tree-repl", v6 = false, "01a05d90c50251aea28100ccaa1da38004fb5a04154bfa300a600f3c10d130816a00000000018094ebdc0300d1930c660103efbfbd0c660103efbfbd010000",
      "an output whose (unsized v0) tree is `sigmaProp(Coll(⟨EF BF BD⟩) == Coll(⟨EF BF BD⟩))`. CONTROL."),
    Row("Transaction", "tx-tree-sur", v6 = false, "01a05d90c50251aea28100ccaa1da38004fb5a04154bfa300a600f3c10d130816a00000000018094ebdc0300d1930c660103eda0800c660103efbfbd010000",
      "an output whose (unsized v0) tree holds the String ⟨ED A0 80⟩ in a constant: the tree is written with `EF BF BD`, so the bytes and id are tx-ctl-tree-repl's. SEPARATING, as tx-r4-sur."),
    Row("Transaction", "tx-ctl-tree-tv", v6 = false, "01a05d90c50251aea28100ccaa1da38004fb5a04154bfa300a600f3c10d130816a00000000018094ebdc0300d193b183006701540400010000",
      "an output whose tree is `sigmaProp(Coll[T]().size == 0)`. CONTROL: a well-formed type-variable name."),
    Row("Transaction", "tx-tree-tv-overlong", v6 = false, "01a05d90c50251aea28100ccaa1da38004fb5a04154bfa300a600f3c10d130816a00000000018094ebdc0300d193b183006703e080800400010000",
      "an output whose (unsized v0) tree names a type variable ⟨E0 80 80⟩: an overlong form, one U+FFFD per byte, so the name is written as `EF BF BD × 3` (9 bytes). SEPARATING: a port that keeps the name, or replaces the overlong once, writes other bytes."),
    Row("Transaction", "tx-sizedtree-tv-overlong", v6 = false, "01a05d90c50251aea28100ccaa1da38004fb5a04154bfa300a600f3c10d130816a00000000018094ebdc03080cd193b183006703e080800400010000",
      "the same tree SIZED (header 08): the JVM re-writes it and its size grows from 12 to 18. SEPARATING, as tx-tree-tv-overlong, and for a port that echoes a sized tree's bytes.")
  )

  /** The JVM's id for a transaction row, over the bytes it writes. */
  def txId(r: Row): String = VersionContext.withVersions(r.activated, r.treeVersion) {
    org.ergoplatform.ErgoLikeTransactionSerializer.fromBytes(Base16.decode(r.bytes).get).id
  }

  private def version(r: Row): Json =
    Json.obj("activated" -> Json.fromInt(r.activated.toInt), "ergoTree" -> Json.fromInt(r.treeVersion.toInt))

  /** The JVM's outcome: Right(None) echo, Right(Some(bytes)) a re-write, Left(()) a ConstraintFailed refusal. */
  def outcome(r: Row): Either[Unit, Option[String]] =
    scala.util.Try(WireCanonicalize.canonicalize(r.kind, r.bytes, r.activated, r.treeVersion)) match {
      case scala.util.Success(out) => Right(if (out == r.bytes) None else Some(out))
      case scala.util.Failure(t) =>
        val causes = Iterator.iterate(t: Throwable)(_.getCause).takeWhile(_ != null).toSeq
        require(causes.exists(c => c.getClass.getName == CF && c.getMessage.contains("SameType2")),
          s"${r.kind} ${r.id}: refused by the wrong exception: " +
            causes.map(c => c.getClass.getName + ": " + c.getMessage).mkString(" <- ").take(400))
        Left(())
    }

  private def entry(r: Row, i: Int): Json = {
    val o = outcome(r)
    val idNote = if (r.kind == "Transaction") s" The JVM's id: ${txId(r)}." else ""
    val v = if (r.v6) "v3" else "v0"
    Json.obj(Seq(
      "name" -> Json.fromString(s"jdk-utf8-decode-${r.id}-$v#$i"), "kind" -> Json.fromString(r.kind),
      "source" -> Json.fromString(Source), "description" -> Json.fromString(s"[${r.id}] ${r.what}$idNote"),
      "bytes_hex" -> Json.fromString(r.bytes)) ++
      (o match {
        case Right(Some(out)) => Seq("expected_bytes_hex" -> Json.fromString(out))
        case Right(None)      => Nil
        case Left(())         => Seq("error" -> Json.fromString("errored"))
      }) ++ Seq("version" -> version(r)): _*)
  }

  val Rows: Seq[Row] = ConstantRows ++ ErgoTreeRows ++ TxRows

  def extract(v6: Boolean): Map[String, Json] = {
    def envelope(op: String, kind: String): Option[(String, Json)] = {
      val rs = Rows.filter(r => r.kind == kind && r.v6 == v6)
      if (rs.isEmpty) None else Some(op -> Json.obj(
        "schema"     -> Json.fromString("santa-wire/v1"),
        "op"         -> Json.fromString(op),
        "blessed_by" -> Json.fromString("jvm:sigma-state-6.0.7"),
        "entries"    -> Json.arr(rs.zipWithIndex.map { case (r, i) => entry(r, i) }: _*)))
    }
    Seq(envelope(OpConstant, "Constant"), envelope(OpErgoTree, "ErgoTree"), envelope(OpTransaction, "Transaction"))
      .flatten.toMap
  }

  def writeVectors(outDir: java.nio.file.Path): Unit = {
    SpecExtract.writeStaging("AuthoredWireJdkUtf8Decode.v6", extract(v6 = true), outDir.resolve("v6"))
    SpecExtract.writeStaging("AuthoredWireJdkUtf8Decode.v5", extract(v6 = false), outDir.resolve("v5"))
  }
}
