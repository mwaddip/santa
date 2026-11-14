package santa

// Authored eval vectors for sigma-rust's santa-probe-jvm-string (fork PR 93, from its vector backlog, 2026-11-14).
// Measured first in santa.spike.StringDecodeProbe (local): every row of its eval set and its reviewer's set as read,
// value and cost. Wire-tier companion: AuthoredWireJdkUtf8Decode (constants, type-variable names, transactions).
//
// THE RULE, read in 6.0.7: sigmastate turns bytes into text with `new String(bytes, UTF_8)` — `String` DATA in the
// data reader (CoreDataSerializer.scala:109-113) — and keeps the TEXT: it compares it, charges a String comparison by
// its UTF-16 length, and writes it back as UTF-8 of the text (CoreDataSerializer.scala:29-32). For malformed UTF-8
// the JDK replaces by maximal subpart with ONE exception: after `ED` it takes any continuation byte, so an encoded
// surrogate `ED A0 80` is ONE U+FFFD (not three); an overlong form (`E0 80..9F`) or a code point above U+10FFFF
// becomes one U+FFFD PER BYTE. A port that uses its own language's lossy decoder, or keeps the bytes, answers these
// comparisons the other way, writes other bytes, and charges another cost.
//
// THE CARRIERS. A String is shown by its UTF-8 bytes, `⟨ED A0 80⟩`; `⟨EF BF BD⟩` is a well-formed U+FFFD. Two bare
// String operands never reach the comparer (`EQ`'s operand check throws `Unknown type SString`, EQ.comparer_dispatch
// S0), so the comparisons hold their strings in a `Coll[String]`, a pair or an option constant. The comparer
// charges a String `MatchType` and, when the UTF-16 lengths agree, `EQ_COA_Short` over the length (one chunk per 96
// units): unequal lengths cost 17 less.
//
// TWO FAMILIES:
//  * String.jdk_utf8_decode_v6 (activated 3, tree v3): 14 comparison rows + the reviewer's 20 (deserializeTo,
//    serialize, substConstants, an Option[String] constant, and the writer's charge per string).
//  * String.jdk_utf8_decode (activated 2, tree v0): the 14 comparison rows and the 3 substConstants rows, which use
//    no v6 method: the rule is not version-gated (the same values and costs as at tree v3).
// Left out: their bare-String row (EQ.comparer_dispatch S0 holds it) and the v0 Option row (an Option constant is
// v6-only, SOption.pre_v3_data_constant).

import io.circe.Json
import scorex.util.encode.Base16

import sigma.serialization.SigmaSerializer

object AuthoredEvalJdkUtf8Decode {
  val V3: Byte = 3
  val V2: Byte = 2
  val Source = "santa:authored-jdk-utf8-decode"
  val Stamp = "jvm:sigma-state-6.0.7"
  val Op6 = "String.jdk_utf8_decode_v6"
  val Op5 = "String.jdk_utf8_decode"
  val Ops: Seq[String] = Seq(Op6, Op5)

  val IntInput: Json = Json.obj("kind" -> Json.fromString("Int"), "value" -> Json.fromInt(0))

  private def hx(bs: Array[Byte]): String = Base16.encode(bs).toLowerCase
  private def vlq(n: Int): String = { val w = SigmaSerializer.startWriter(); w.putUInt(n.toLong); hx(w.toBytes) }

  /** One row: sigma-rust's expression `body`, verbatim; `expr` the expression in words, `why` the row's role. */
  case class Row(op: String, id: String, body: String, expr: String, why: String) {
    def v6: Boolean = op == Op6
    def treeHex: String = if (v6) "0b" + vlq(body.length / 2) + body else "00" + body
    def activated: Byte = if (v6) V3 else V2
    def ergoTree: Int = if (v6) 3 else 0
  }

  val Rows6: Seq[Row] = Seq(
    Row(Op6, "ctl-true", "930c660103efbfbd0c660103efbfbd",
      "`Coll(⟨EF BF BD⟩) == Coll(⟨EF BF BD⟩)`, both written `EF BF BD`",
      "CONTROL: well-formed text on both sides."),
    Row(Op6, "ctl-false", "930c660101410c66010142",
      "`Coll(\"A\") == Coll(\"B\")`",
      "CONTROL: equal lengths, unequal text: the comparison is charged and answers false."),
    Row(Op6, "sur-vs-1repl", "930c660103eda0800c660103efbfbd",
      "`Coll(⟨ED A0 80⟩) == Coll(⟨EF BF BD⟩)`",
      "SEPARATING: the JDK takes ANY continuation byte after `ED`, so an encoded surrogate is ONE U+FFFD; a decoder that replaces it per maximal subpart (three U+FFFD), or that keeps the bytes, answers the other way."),
    Row(Op6, "sur-vs-3repl", "930c660103eda0800c660109efbfbdefbfbdefbfbd",
      "`Coll(⟨ED A0 80⟩) == Coll(⟨EF BF BD × 3⟩)`",
      "The lengths differ (1 against 3 UTF-16 units), so no character comparison is charged. SEPARATING: the JDK takes ANY continuation byte after `ED`, so an encoded surrogate is ONE U+FFFD; a decoder that replaces it per maximal subpart (three U+FFFD), or that keeps the bytes, answers the other way."),
    Row(Op6, "sur-same", "930c660103eda0800c660103eda080",
      "`Coll(⟨ED A0 80⟩) == Coll(⟨ED A0 80⟩)`",
      "CONTROL: the same bytes on both sides are equal under any decoder."),
    Row(Op6, "sur40-same", "930c660178eda080eda080eda080eda080eda080eda080eda080eda080eda080eda080eda080eda080eda080eda080eda080eda080eda080eda080eda080eda080eda080eda080eda080eda080eda080eda080eda080eda080eda080eda080eda080eda080eda080eda080eda080eda080eda080eda080eda080eda0800c660178eda080eda080eda080eda080eda080eda080eda080eda080eda080eda080eda080eda080eda080eda080eda080eda080eda080eda080eda080eda080eda080eda080eda080eda080eda080eda080eda080eda080eda080eda080eda080eda080eda080eda080eda080eda080eda080eda080eda080eda080",
      "`Coll(40 × ⟨ED A0 80⟩) == Coll(40 × ⟨ED A0 80⟩)`, 120 bytes a side",
      "Decoded, each side is 40 UTF-16 units: one 96-unit chunk of the String comparison, the cost of a one-character string. SEPARATING on cost: a port that counts the 120 bytes, or 120 replacement characters, charges two chunks (43, as ctl-repl120-same)."),
    Row(Op6, "ctl-repl40-same", "930c660178efbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbd0c660178efbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbd",
      "`Coll(40 × ⟨EF BF BD⟩) == Coll(40 × ⟨EF BF BD⟩)`, 120 bytes of `EF BF BD` a side",
      "CONTROL for sur40-same: well-formed, 40 UTF-16 units, the same cost."),
    Row(Op6, "ctl-repl120-same", "930c6601e802efbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbd0c6601e802efbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbd",
      "`Coll(120 × ⟨EF BF BD⟩) == Coll(120 × ⟨EF BF BD⟩)`",
      "CONTROL: what 120 UTF-16 units cost, two chunks."),
    Row(Op6, "overlong3-vs-3repl", "930c660103e080800c660109efbfbdefbfbdefbfbd",
      "`Coll(⟨E0 80 80⟩) == Coll(⟨EF BF BD × 3⟩)`",
      "An overlong form (`E0 80..9F`) becomes one U+FFFD PER BYTE. SEPARATING: a decoder that replaces the overlong sequence once answers false."),
    Row(Op6, "overlong3-vs-1repl", "930c660103e080800c660103efbfbd",
      "`Coll(⟨E0 80 80⟩) == Coll(⟨EF BF BD⟩)`",
      "The lengths differ (3 against 1). SEPARATING: a decoder that replaces the overlong sequence once answers true."),
    Row(Op6, "neq-sur-vs-1repl", "940c660103eda0800c660103efbfbd",
      "`Coll(⟨ED A0 80⟩) != Coll(⟨EF BF BD⟩)`",
      "`NEQ` calls the same comparer. SEPARATING, as sur-vs-1repl."),
    Row(Op6, "pair-sur-vs-1repl", "933c660403eda080023c660403efbfbd02",
      "`(⟨ED A0 80⟩, 1) == (⟨EF BF BD⟩, 1)`, two `(String, Int)` constants",
      "The same decoding inside a pair. SEPARATING, as sur-vs-1repl."),
    Row(Op6, "pair-sur-vs-3repl", "933c660403eda080023c660409efbfbdefbfbdefbfbd02",
      "`(⟨ED A0 80⟩, 1) == (⟨EF BF BD × 3⟩, 1)`",
      "The lengths differ; the String case bails before charging the comparison. SEPARATING, as sur-vs-3repl."),
    Row(Op6, "pair-ctl", "933c660403efbfbd023c660403efbfbd02",
      "`(⟨EF BF BD⟩, 1) == (⟨EF BF BD⟩, 1)`",
      "CONTROL for the pair rows."),
    Row(Op6, "dto-sur-vs-1repl", "93dc6a04dd010e050103eda0800c660c660103efbfbd",
      "`deserializeTo[Coll[String]](Coll[Byte](01 03 ED A0 80)) == Coll(⟨EF BF BD⟩)`",
      "The data reader decodes a deserialized value the same way. SEPARATING, as sur-vs-1repl."),
    Row(Op6, "dto-sur-vs-3repl", "93dc6a04dd010e050103eda0800c660c660109efbfbdefbfbdefbfbd",
      "`deserializeTo[Coll[String]](Coll[Byte](01 03 ED A0 80)) == Coll(⟨EF BF BD × 3⟩)`",
      "SEPARATING, as sur-vs-3repl."),
    Row(Op6, "dto-ctl-repl", "93dc6a04dd010e050103efbfbd0c660c660103efbfbd",
      "`deserializeTo[Coll[String]](Coll[Byte](01 03 EF BF BD)) == Coll(⟨EF BF BD⟩)`",
      "CONTROL for the deserializeTo rows."),
    Row(Op6, "ser-sur-vs-1repl", "93dc6a03dd010c660103eda0800e050103efbfbd",
      "`serialize(Coll(⟨ED A0 80⟩)) == Coll[Byte](01 03 EF BF BD)`",
      "The writer writes the DECODED text (CoreDataSerializer.scala:29-32). SEPARATING: a port that keeps the bytes writes `ED A0 80`."),
    Row(Op6, "ser-sur-vs-3repl", "93dc6a03dd010c660103eda0800e0b0109efbfbdefbfbdefbfbd",
      "`serialize(Coll(⟨ED A0 80⟩)) == Coll[Byte](01 09 EF BF BD × 3)`",
      "SEPARATING: a three-replacement decoder writes these bytes."),
    Row(Op6, "ser-ctl-repl", "93dc6a03dd010c660103efbfbd0e050103efbfbd",
      "`serialize(Coll(⟨EF BF BD⟩)) == Coll[Byte](01 03 EF BF BD)`",
      "CONTROL for the serialize rows."),
    Row(Op6, "subst-sur-vs-1repl", "93740e0910016603eda0807300100010000e0910016603efbfbd7300",
      "`substConstants(tree with String constant ⟨ED A0 80⟩, Coll[Int](), Coll[Int]()) == the same tree with `EF BF BD``",
      "`substConstants` re-writes the tree, and the constant goes out as decoded text. SEPARATING: a port that keeps the bytes returns the input."),
    Row(Op6, "subst-sur-vs-3repl", "93740e0910016603eda0807300100010000e0f10016609efbfbdefbfbdefbfbd7300",
      "`substConstants(tree with String constant ⟨ED A0 80⟩, …) == the tree with `EF BF BD × 3``",
      "SEPARATING, as sur-vs-3repl."),
    Row(Op6, "subst-ctl-repl", "93740e0910016603efbfbd7300100010000e0910016603efbfbd7300",
      "`substConstants(tree with String constant ⟨EF BF BD⟩, …) == the same tree`",
      "CONTROL for the substConstants rows."),
    Row(Op6, "ser-bare-sur-vs-1repl", "93dc6a03dd016603eda0800e0403efbfbd",
      "`serialize(⟨ED A0 80⟩) == Coll[Byte](03 EF BF BD)`, a bare String argument",
      "`Global.serialize` takes a bare String (no operand check), and writes the decoded text. SEPARATING, as ser-sur-vs-1repl."),
    Row(Op6, "ser-bare-sur-vs-3repl", "93dc6a03dd016603eda0800e0a09efbfbdefbfbdefbfbd",
      "`serialize(⟨ED A0 80⟩) == Coll[Byte](09 EF BF BD × 3)`",
      "SEPARATING, as ser-sur-vs-3repl."),
    Row(Op6, "ser-bare-ctl", "93dc6a03dd016603efbfbd0e0403efbfbd",
      "`serialize(⟨EF BF BD⟩) == Coll[Byte](03 EF BF BD)`",
      "CONTROL for the bare serialize rows."),
    Row(Op6, "ser-dto-string-sur", "93dc6a03dd01dc6a04dd010e0403eda080660e0403efbfbd",
      "`serialize(deserializeTo[String](Coll[Byte](03 ED A0 80))) == Coll[Byte](03 EF BF BD)`",
      "Read and written back: the decoded text. SEPARATING, as ser-sur-vs-1repl."),
    Row(Op6, "ser-dto-string-ctl", "93dc6a03dd01dc6a04dd010e0403efbfbd660e0403efbfbd",
      "`serialize(deserializeTo[String](Coll[Byte](03 EF BF BD))) == Coll[Byte](03 EF BF BD)`",
      "CONTROL for ser-dto-string-sur."),
    Row(Op6, "ser-1item-ctl", "93dc6a03dd010c660101410e03010141",
      "`serialize(Coll(\"A\")) == Coll[Byte](01 01 41)`",
      "THE WRITER'S CHARGE: with ser-empty-coll (50) and ser-2items-ctl (58), each string adds 4. SEPARATING on cost for a writer that also charges the length prefix."),
    Row(Op6, "ser-2items-ctl", "93dc6a03dd010c6602014101420e050201410142",
      "`serialize(Coll(\"A\", \"B\")) == Coll[Byte](02 01 41 01 42)`",
      "THE WRITER'S CHARGE, as ser-1item-ctl."),
    Row(Op6, "ser-empty-coll", "93dc6a03dd010c66000e0100",
      "`serialize(Coll[String]()) == Coll[Byte](00)`",
      "THE WRITER'S CHARGE: the base, no string."),
    Row(Op6, "opt-sur-vs-1repl", "9324660103eda08024660103efbfbd",
      "`Some(⟨ED A0 80⟩) == Some(⟨EF BF BD⟩)`, two `Option[String]` constants",
      "The same decoding inside an option (a v6 constant). SEPARATING, as sur-vs-1repl."),
    Row(Op6, "opt-sur-vs-3repl", "9324660103eda08024660109efbfbdefbfbdefbfbd",
      "`Some(⟨ED A0 80⟩) == Some(⟨EF BF BD × 3⟩)`",
      "SEPARATING, as sur-vs-3repl."),
    Row(Op6, "opt-ctl", "9324660103efbfbd24660103efbfbd",
      "`Some(⟨EF BF BD⟩) == Some(⟨EF BF BD⟩)`",
      "CONTROL for the option rows."))

  val Rows5: Seq[Row] = Seq(
    Row(Op5, "ctl-true", "930c660103efbfbd0c660103efbfbd",
      "`Coll(⟨EF BF BD⟩) == Coll(⟨EF BF BD⟩)`, both written `EF BF BD`",
      "CONTROL: well-formed text on both sides."),
    Row(Op5, "ctl-false", "930c660101410c66010142",
      "`Coll(\"A\") == Coll(\"B\")`",
      "CONTROL: equal lengths, unequal text: the comparison is charged and answers false."),
    Row(Op5, "sur-vs-1repl", "930c660103eda0800c660103efbfbd",
      "`Coll(⟨ED A0 80⟩) == Coll(⟨EF BF BD⟩)`",
      "SEPARATING: the JDK takes ANY continuation byte after `ED`, so an encoded surrogate is ONE U+FFFD; a decoder that replaces it per maximal subpart (three U+FFFD), or that keeps the bytes, answers the other way."),
    Row(Op5, "sur-vs-3repl", "930c660103eda0800c660109efbfbdefbfbdefbfbd",
      "`Coll(⟨ED A0 80⟩) == Coll(⟨EF BF BD × 3⟩)`",
      "The lengths differ (1 against 3 UTF-16 units), so no character comparison is charged. SEPARATING: the JDK takes ANY continuation byte after `ED`, so an encoded surrogate is ONE U+FFFD; a decoder that replaces it per maximal subpart (three U+FFFD), or that keeps the bytes, answers the other way."),
    Row(Op5, "sur-same", "930c660103eda0800c660103eda080",
      "`Coll(⟨ED A0 80⟩) == Coll(⟨ED A0 80⟩)`",
      "CONTROL: the same bytes on both sides are equal under any decoder."),
    Row(Op5, "sur40-same", "930c660178eda080eda080eda080eda080eda080eda080eda080eda080eda080eda080eda080eda080eda080eda080eda080eda080eda080eda080eda080eda080eda080eda080eda080eda080eda080eda080eda080eda080eda080eda080eda080eda080eda080eda080eda080eda080eda080eda080eda080eda0800c660178eda080eda080eda080eda080eda080eda080eda080eda080eda080eda080eda080eda080eda080eda080eda080eda080eda080eda080eda080eda080eda080eda080eda080eda080eda080eda080eda080eda080eda080eda080eda080eda080eda080eda080eda080eda080eda080eda080eda080eda080",
      "`Coll(40 × ⟨ED A0 80⟩) == Coll(40 × ⟨ED A0 80⟩)`, 120 bytes a side",
      "Decoded, each side is 40 UTF-16 units: one 96-unit chunk of the String comparison, the cost of a one-character string. SEPARATING on cost: a port that counts the 120 bytes, or 120 replacement characters, charges two chunks (43, as ctl-repl120-same)."),
    Row(Op5, "ctl-repl40-same", "930c660178efbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbd0c660178efbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbd",
      "`Coll(40 × ⟨EF BF BD⟩) == Coll(40 × ⟨EF BF BD⟩)`, 120 bytes of `EF BF BD` a side",
      "CONTROL for sur40-same: well-formed, 40 UTF-16 units, the same cost."),
    Row(Op5, "ctl-repl120-same", "930c6601e802efbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbd0c6601e802efbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbd",
      "`Coll(120 × ⟨EF BF BD⟩) == Coll(120 × ⟨EF BF BD⟩)`",
      "CONTROL: what 120 UTF-16 units cost, two chunks."),
    Row(Op5, "overlong3-vs-3repl", "930c660103e080800c660109efbfbdefbfbdefbfbd",
      "`Coll(⟨E0 80 80⟩) == Coll(⟨EF BF BD × 3⟩)`",
      "An overlong form (`E0 80..9F`) becomes one U+FFFD PER BYTE. SEPARATING: a decoder that replaces the overlong sequence once answers false."),
    Row(Op5, "overlong3-vs-1repl", "930c660103e080800c660103efbfbd",
      "`Coll(⟨E0 80 80⟩) == Coll(⟨EF BF BD⟩)`",
      "The lengths differ (3 against 1). SEPARATING: a decoder that replaces the overlong sequence once answers true."),
    Row(Op5, "neq-sur-vs-1repl", "940c660103eda0800c660103efbfbd",
      "`Coll(⟨ED A0 80⟩) != Coll(⟨EF BF BD⟩)`",
      "`NEQ` calls the same comparer. SEPARATING, as sur-vs-1repl."),
    Row(Op5, "pair-sur-vs-1repl", "933c660403eda080023c660403efbfbd02",
      "`(⟨ED A0 80⟩, 1) == (⟨EF BF BD⟩, 1)`, two `(String, Int)` constants",
      "The same decoding inside a pair. SEPARATING, as sur-vs-1repl."),
    Row(Op5, "pair-sur-vs-3repl", "933c660403eda080023c660409efbfbdefbfbdefbfbd02",
      "`(⟨ED A0 80⟩, 1) == (⟨EF BF BD × 3⟩, 1)`",
      "The lengths differ; the String case bails before charging the comparison. SEPARATING, as sur-vs-3repl."),
    Row(Op5, "pair-ctl", "933c660403efbfbd023c660403efbfbd02",
      "`(⟨EF BF BD⟩, 1) == (⟨EF BF BD⟩, 1)`",
      "CONTROL for the pair rows."),
    Row(Op5, "subst-sur-vs-1repl", "93740e0910016603eda0807300100010000e0910016603efbfbd7300",
      "`substConstants(tree with String constant ⟨ED A0 80⟩, Coll[Int](), Coll[Int]()) == the same tree with `EF BF BD``",
      "`substConstants` re-writes the tree, and the constant goes out as decoded text. SEPARATING: a port that keeps the bytes returns the input."),
    Row(Op5, "subst-sur-vs-3repl", "93740e0910016603eda0807300100010000e0f10016609efbfbdefbfbdefbfbd7300",
      "`substConstants(tree with String constant ⟨ED A0 80⟩, …) == the tree with `EF BF BD × 3``",
      "SEPARATING, as sur-vs-3repl."),
    Row(Op5, "subst-ctl-repl", "93740e0910016603efbfbd7300100010000e0910016603efbfbd7300",
      "`substConstants(tree with String constant ⟨EF BF BD⟩, …) == the same tree`",
      "CONTROL for the substConstants rows."))

  val Rows: Seq[Row] = Rows6 ++ Rows5

  /** Every row returns (a Boolean); the value and cost are blessed, and stated in the script. */
  def extract(): Map[String, Json] =
    Ops.map { op =>
      val entries = Rows.filter(_.op == op).zipWithIndex.map { case (r, i) =>
        val (_, outcome) = EvalCore.evalApplied(r.treeHex, IntInput, activated = r.activated)
        val said = outcome.fold(e => sys.error(s"$op ${r.id}: every row returns, threw $e"),
          { case (v, c) => s"→ ${v.hcursor.get[Boolean]("value").toOption.getOrElse(sys.error(s"${r.id}: not a Boolean"))} at cost $c" })
        SpecExtract.authoredEntryV(op, s"[${r.id}] ${r.expr} $said. ${r.why}", r.treeHex, s"${r.id}#$i", IntInput,
          r.activated, ergoTree = r.ergoTree)
      }
      op -> SpecExtract.authoredEnvelope(op, entries, Source).mapObject(_.add("blessed_by", Json.fromString(Stamp)))
    }.toMap

  def writeVectors(outDir: java.nio.file.Path): Unit =
    SpecExtract.writeStaging("AuthoredEvalJdkUtf8Decode", extract(), outDir)
}
