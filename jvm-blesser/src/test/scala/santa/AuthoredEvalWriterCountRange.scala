package santa

// Authored eval vectors for sigma-rust's santa-probe-writer-count-range (2026-11-15): two counts sigmastate's writers
// refuse that a script can push out of range. Measured first in santa.spike.WriterCountRangeProbe; the reply is
// prompts/drafts/sigma-rust-writer-count-range-reply.md. Wire-tier companion: AuthoredWireWriterCountRange.
//
// THE RULE, read in 6.0.7 and measured: every count a writer writes is range-checked (`putUByte` refuses a value above
// 255, `putUShort` one above 65,535: `requirement failed: … is out of unsigned byte|short range`).
//  - A COLLECTION OF MORE THAN 65,535 ITEMS at the data writer (CoreDataSerializer.scala:51-52, `putUShort(coll
//    .length)`). No constant holds one (the reader takes the count as an unsigned short), but `Append` builds it, and a
//    script hands it to the writer through `Global.serialize` and through the new values of `substConstants` (at tree
//    version 0 too, at every activated version). 65,535 items are written; 65,536 are refused.
//  - A TYPE VARIABLE NAME WHOSE TEXT IS LONGER THAN 255 BYTES (TypeSerializer.scala:122-126, `putUByte` of the text's
//    UTF-8 length). The reader takes at most 255 bytes, and a malformed byte becomes the three bytes of U+FFFD, so
//    86 bytes of FF read as a text of 258. Wherever such a type is WRITTEN the writer refuses: `Box.bytesWithoutRef` and
//    `Global.serialize(box)` of a box read by `Global.deserializeTo[Box]` (sigmastate keeps the bytes a box was read
//    from, so its value, id, bytes, propositionBytes, creationInfo, registers, tokens and `==` read those and answer),
//    and `substConstants`, which writes every constant of the tree it is given again (a None constant of Option[T], a
//    Box constant whose box's tree holds the name; ErgoTreeSerializer.scala:320-378) and copies the body as read.
//
// THE ROWS: sigma-rust's 129 at activated 2 and 3 (src/test/resources/sigma-rust-writer-count-range/eval.tsv: a bare root expression at a
// tree version and an activated version, with the jars' outcome, value and cost as an independent cross-check), all
// agreeing with the jars. Every expectation is our oracle's. Two changes, both ours:
//  - every `Global.serialize` / `substConstants` row of the long collection is wrapped in `blake2b256` (the 65,535
//    controls answer ~65 KB, written item by item; the digest grades the same bytes). The test checks each digest
//    against the bare expression's value, re-evaluated.
//  - the two Boolean rows of 65,535 items carry a 1,023-item constant whose padding bit sigma-rust set (`FF`); the JVM
//    reads it and writes `7F`, and the vectors carry the JVM's bytes (OUR trees). Every other expression is written
//    back byte-identically.
// A row is WRITER REFUSES when our oracle refuses it in a writer's range check, CONTROL otherwise (the bytes are read,
// or the count fits, or it is refused for another reason whatever the count). Rows at activated 2 go to `v5`, 3 to
// `v6`. The request's 17 rows at activated 1 were deleted with the `v4` slot's unreachable families (2026-11-15):
// activated 1 exists only in mainnet blocks up to 889,855, which a replay without these behaviours validated clean.

import io.circe.Json
import scorex.util.encode.Base16

import sigma.VersionContext
import sigma.ast.{CalcBlake2b256, SByte, SCollection, Value}
import sigma.serialization.{SigmaSerializer, ValueSerializer}

object AuthoredEvalWriterCountRange {
  val Source = "santa:authored-writer-count-range"
  val Stamp = "jvm:sigma-state-6.0.7"
  val IntInput: Json = Json.obj("kind" -> Json.fromString("Int"), "value" -> Json.fromInt(0))
  val RowsFile: java.nio.file.Path =
    java.nio.file.Paths.get("src", "test", "resources", "sigma-rust-writer-count-range", "eval.tsv")
  /** The two rows whose vendored expression sets the padding bit of a packed Boolean constant. */
  val PaddingBitRows: Set[String] = Set("serc|bools|65535", "sizec|bools|65535")

  /** One vendored row: sigma-rust's part, row set and name, the tree version, the activated version, the expression's
    * bytes, and the jars' measurement (outcome, value text with a collection's first 100 items, cost of a value). */
  case class Row(part: String, set: String, name: String, treeVersion: Int, activated: Int, body: String,
                 jarsOutcome: String, jarsValue: String, jarsCost: String) {
    private val f = name.split('|')
    def kind: String = f(0)
    /** A long-collection row the vector wraps in blake2b256. */
    def wrapped: Boolean = kind == "serc" || kind == "substc"
    /** The vector's expression: the JVM's writing of the vendored one, wrapped in blake2b256 where `wrapped`. */
    lazy val expr: String = VersionContext.withVersions(activated.toByte, treeVersion.toByte) {
      val v = ValueSerializer.deserialize(SigmaSerializer.startReader(Base16.decode(body).get))
      val w = SigmaSerializer.startWriter()
      ValueSerializer.serialize(if (wrapped) CalcBlake2b256(v.asInstanceOf[Value[SCollection[SByte.type]]]) else v, w)
      hx(w.toBytes)
    }
    def treeHex: String = tree(expr)
    def bareTreeHex: String = tree(body)
    private def tree(e: String): String =
      if (treeVersion == 0) "00" + e else f"${8 + treeVersion}%02x" + vlq(e.length / 2) + e
    private def slot: String = activated match { case 2 => ""; case 3 => "_v6" }
    def op: String = kind match {
      case "dto"             => "Global.deserializeTo_Box_type_var_name_range"
      case "subst"           => "SubstConstants.type_var_name_range" + slot
      case "serc" | "sizec"  => "Global.serialize_coll_count_range"
      case "substc"          => "SubstConstants.coll_count_range" + slot
    }
    /** The count pushed at the writer: a name's text length, or a collection's item count. */
    def count: Int = kind match {
      case "dto" | "subst" => NameText(f.find(NameText.contains).get)._1
      case _               => f(2).toInt
    }
    /** Over the writer's range: a text past 255 bytes, a collection past 65,535 items. */
    def overRange: Boolean = if (kind == "dto" || kind == "subst") count > 255 else count > 65535
    /** Whether the row's path writes the count: a box written back, a constant written again, a collection serialized
      * (not a box's bytes as read, a body copied as read, or a collection's size). */
    def writes: Boolean = kind match {
      case "dto"   => DtoWrite.contains(f(2))
      case "subst" => f(1).startsWith("const-")
      case "sizec" => false
      case _       => true
    }
    def slug: String = name.replace('|', '-').replaceAll("[^A-Za-z0-9.+_-]", "_")
  }

  /** The names of the type variable in the eval rows: the text's length in bytes, and how it is spelled. */
  val NameText: Map[String, (Int, String)] = Map(
    "ff85"   -> (255, "85 bytes of FF, read as 85 U+FFFD: a text of 255 bytes"),
    "mix256" -> (256, "253 `a` and one FF: a text of 256 bytes"),
    "ff86"   -> (258, "86 bytes of FF: a text of 258 bytes"))

  def rows(): Vector[Row] = {
    val lines = new String(java.nio.file.Files.readAllBytes(RowsFile), "UTF-8").split("\n").toVector
    lines.drop(1).filter(_.trim.nonEmpty).map(_.split("\t", -1)).map(c =>
      Row(c(0), c(1), c(2), c(3).toInt, c(4).toInt, c(5), c(6), c(7), c(8)))
  }

  private def hx(bs: Array[Byte]): String = Base16.encode(bs).toLowerCase
  private def vlq(n: Int): String = { val w = SigmaSerializer.startWriter(); w.putUInt(n.toLong); hx(w.toBytes) }

  /** The oracle's outcome: Right((value, cost)) or Left(the throwable). */
  def outcome(treeHex: String, activated: Int): Either[Throwable, (Json, Long)] =
    EvalCore.evalAppliedOutcome(treeHex, IntInput, activated.toByte)._2

  /** The messages of a failure's cause chain. */
  def causes(t: Throwable): Seq[String] =
    Iterator.iterate(t)(_.getCause).takeWhile(_ != null).map(c => Option(c.getMessage).getOrElse("")).toSeq

  /** The writer's range check, if that is what refused. */
  def writerRefusal(o: Either[Throwable, (Json, Long)]): Option[String] =
    o.left.toOption.flatMap(t => causes(t).find(m => m.contains("out of unsigned byte range") ||
      m.contains("out of unsigned short range")))

  /** sigma-rust's text form of a value: numbers decimal, Coll(a,b), a pair (a,b), <sigmaprop>, <point>, <box>, <tree>. */
  def text(j: Json): String = AuthoredEvalPreActivation.text(j)

  private def clip(m: String, n: Int): String =
    if (m.length <= n) m else m.take(n).reverse.dropWhile(_ != ' ').reverse.trim + "…"
  private def said(o: Either[Throwable, (Json, Long)]): String = o.fold(
    t => s"REFUSED (${t.getClass.getSimpleName}${writerRefusal(o).orElse(causes(t).find(_.nonEmpty))
      .map(m => s": ${clip(m, 150)}").getOrElse("")})",
    { case (v, cost) =>
      val s = text(v); s"${if (s.length > 90) s.take(90) + "…" else s} at cost $cost" })

  private val DtoRead: Map[String, String] = Map(
    "just" -> "the box itself (its value: the bytes it was read from)",
    "value" -> "`.value`", "id" -> "`.id` (the hash of the bytes it was read from)",
    "bytes" -> "`.bytes` (the bytes it was read from)", "propBytes" -> "`.propositionBytes` (the tree as read)",
    "creationInfo" -> "`.creationInfo`", "r4-isDefined" -> "`.R4[…].isDefined`", "tokens-size" -> "`.tokens.size`",
    "eq-self" -> "`==` of two reads of the box")
  val DtoWrite: Map[String, String] = Map(
    "bytesWithoutRef" -> "`.bytesWithoutRef`, which WRITES the box (its tree from its structure)",
    "serialize" -> "`Global.serialize(box)`, which WRITES the box (its tree from its structure)")
  private val SubstWhere: Map[String, String] = Map(
    "const-none" -> "a None constant of `Option[<the name>]` (the tree segregated, v3)",
    "const-box" -> "the tree of a Box constant's box (the tree segregated, v3; the box's tree unsized, v0)",
    "body-sized" -> "the body (the tree sized, v3)", "body-unsized" -> "the body (the tree unsized, v0)",
    "body-seg" -> "the body (the tree segregated, v3)")
  private val CollOf: Map[String, String] = Map("bytes" -> "Coll[Byte]", "bools" -> "Coll[Boolean]", "ints" -> "Coll[Int]")

  /** What the row does, and what the label rests on. */
  private def what(r: Row): String = {
    val f = r.name.split('|')
    r.kind match {
      case "dto" =>
        val (n, spelled) = NameText(f(3))
        val tree = if (f(1) == "sized") "sized, v3" else "unsized, v0"
        val m = DtoRead.getOrElse(f(2), DtoWrite(f(2)))
        s"`Global.deserializeTo[Box]` of a box whose tree ($tree) names a type variable with $spelled, then $m"
      case "subst" =>
        val (_, spelled) = NameText(f(2))
        s"`substConstants` over the bytes of a tree in which ${SubstWhere(f(1))} names a type variable with " +
          s"$spelled: it writes every constant again and copies the body as read"
      case "serc" => s"`blake2b256(Global.serialize(c))`, c a ${CollOf(f(1))} of ${r.count} items built by Append (a " +
          "constant of 1,024 doubled five times, then appended)"
      case "sizec" => s"`c.size`, c a ${CollOf(f(1))} of ${r.count} items built by Append (a constant of 1,024 doubled " +
          "five times, then appended): the collection exists"
      case "substc" => s"`blake2b256(substConstants(tree, Coll(0), Coll(c)))`, c a Coll[Byte] of ${r.count} items built " +
          "by Append: the new value goes through the data writer"
    }
  }

  def label(r: Row): String = {
    val o = outcome(r.treeHex, r.activated)
    val limit = if (r.kind == "dto" || r.kind == "subst") "255 (`putUByte`)" else "65,535 (`putUShort`)"
    writerRefusal(o) match {
      case Some(_) => s"WRITER REFUSES: ${r.count} is past $limit; a port that narrows the count, or writes it modulo " +
          "its width, answers here."
      case None if o.isLeft => "CONTROL: refused before any count is written, whatever the count."
      case None if !r.writes => r.kind match {
        case "sizec" => "CONTROL: the collection exists, and nothing writes it."
        case "dto"   => "CONTROL: nothing writes the name: the box's bytes as read are kept."
        case _       => "CONTROL: nothing writes the name: the body is copied as read."
      }
      case None if !r.overRange => s"CONTROL: ${r.count} is within $limit, so the writer writes it."
      case None => sys.error(s"${r.name}: a count past the range written without a refusal")
    }
  }

  def script(r: Row): String =
    s"[${r.name}] ${what(r)} — ${said(outcome(r.treeHex, r.activated))}. ${label(r)}  " +
      s"// tree v${r.treeVersion}, activated ${r.activated}"

  def extract(): Map[String, Json] =
    rows().groupBy(_.op).map { case (op, rs) =>
      val entries = rs.zipWithIndex.map { case (r, i) =>
        val name = s"${r.slug}-a${r.activated}#$i"
        val a = r.activated.toByte
        if (outcome(r.treeHex, r.activated).isRight)
          SpecExtract.authoredEntryV(op, script(r), r.treeHex, name, IntInput, a, ergoTree = r.treeVersion)
        else SpecExtract.authoredRejectEntryV(op, script(r), r.treeHex, name, IntInput, a, ergoTree = r.treeVersion)
      }
      op -> SpecExtract.authoredEnvelope(op, entries, Source).mapObject(_.add("blessed_by", Json.fromString(Stamp)))
    }

  def writeVectors(outDir: java.nio.file.Path): Unit =
    SpecExtract.writeStaging("AuthoredEvalWriterCountRange", extract(), outDir)
}
