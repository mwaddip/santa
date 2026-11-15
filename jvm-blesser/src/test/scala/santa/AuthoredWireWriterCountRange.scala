package santa

// Authored wire vectors: a type variable name whose TEXT is longer than 255 bytes is read, and refused when written
// back. Request: `~/projects/sigma-rust/prompts/santa-probe-writer-count-range.md` (its `written` and `tx` sets);
// measured by `santa.spike.WriterCountRangeProbe` (local). Eval-tier companion: AuthoredEvalWriterCountRange.
//
// THE RULE: the reader takes a name of at most 255 bytes and keeps its TEXT (`new String(bytes, UTF_8)`,
// TypeSerializer.scala:204), so a malformed byte becomes the three bytes of U+FFFD; the writer writes the text's UTF-8
// length with `putUByte` (TypeSerializer.scala:122-126), which refuses a value above 255 (`requirement failed: N is
// out of unsigned byte range`). So 86 bytes of FF are read (a text of 258 bytes) and refused when written. A name
// whose text is 255 bytes is written back; when it held FF, as its text (non-identity: `expected_bytes_hex`).
//
// ErgoTree: sigma-rust's 23 type positions (a collection node's item type, bare and under Coll, Option, a pair, a
// function type's domain and type parameter; getVar; ExtractRegisterAs; a lambda's argument; a FunDef's type argument;
// executeFromVar's type; a method call's type argument, bare and under Coll; a None constant of Option[T]) times six
// names, each expression wrapped `sigmaProp((e, true)._2)` (SigmaProp-rooted, so the tree is re-written and not
// degraded on rule 1001), in an unsized v0 tree (wire/v5, activated 2) or a sized v3 tree (wire/v6, activated 3).
// Transaction (wire/v6, activated 3): the name in an output's tree (unsized v0; sized v0; sized v3), in a Box constant
// in a register, in a Box in a collection in a register, in a Box in a context extension. sigmastate's
// ErgoLikeTransaction reads every one (its id is lazy) and refuses to write the long names; ergo's ErgoTransaction
// computes its id at the read, so the node refuses them there (measured on ergo-core 6.0.7 in the probe). Either way
// the JVM has no bytes for them: a reject entry (runner-contract-wire §3). The controls' descriptions carry the id.

import io.circe.Json
import scorex.util.encode.Base16
import sigma.VersionContext
import sigma.serialization.SigmaSerializer

object AuthoredWireWriterCountRange {
  val OpErgoTree    = "ErgoTree.type_var_name_write_range"
  val OpTransaction = "Transaction.type_var_name_write_range"
  val Source        = "santa:authored-writer-count-range"
  private val Dir = java.nio.file.Paths.get("src", "test", "resources", "sigma-rust-writer-count-range")
  val WrittenFile: java.nio.file.Path = Dir.resolve("written.tsv")
  val TxFile: java.nio.file.Path = Dir.resolve("tx.tsv")

  private def hx(bs: Array[Byte]): String = Base16.encode(bs).toLowerCase
  private def vlq(n: Int): String = { val w = SigmaSerializer.startWriter(); w.putUInt(n.toLong); hx(w.toBytes) }

  /** The six names: the text's length in bytes, and how it is spelled. */
  val Names: Map[String, (Int, String)] = Map(
    "a255"   -> (255, "255 `a` (a text of 255 bytes)"),
    "ff85"   -> (255, "85 bytes of FF, read as 85 U+FFFD (a text of 255 bytes)"),
    "mix255" -> (255, "252 `a` and one FF (a text of 255 bytes)"),
    "mix256" -> (256, "253 `a` and one FF (a text of 256 bytes)"),
    "ff86"   -> (258, "86 bytes of FF (a text of 258 bytes)"),
    "ff255"  -> (765, "255 bytes of FF (a text of 765 bytes)"))

  private val Positions: Map[String, String] = Map(
    "cc-elem" -> "a collection node `Coll[<name>]()` (its item type)",
    "cc-coll" -> "a collection node whose item type is `Coll[<name>]`",
    "cc-opt" -> "a collection node whose item type is `Option[<name>]`",
    "cc-pair" -> "a collection node whose item type is a pair holding the name",
    "cc-func-param" -> "a collection node whose item type is a function type with the name as its type parameter",
    "cc-func-dom" -> "a collection node whose item type is a function type with the name as its domain",
    "getvar" -> "`getVar[<name>](1)`",
    "regas" -> "a register read `R4[<name>]` (ExtractRegisterAs)",
    "funcvalue" -> "a lambda whose argument is of the name's type",
    "fundef" -> "a `FunDef` whose type argument is the name",
    "dctx" -> "`executeFromVar[<name>]` (DeserializeContext)",
    "mc-typearg" -> "a method call whose explicit type argument is the name",
    "mc-typearg-coll" -> "a method call whose explicit type argument is `Coll[<name>]`",
    "const-none" -> "a None constant of `Option[<name>]`")

  private val TxWhere: Map[String, String] = Map(
    "tree-unsized" -> "an output whose tree (unsized, v0) is `sigmaProp(Coll[<name>]().size == 0)`",
    "tree-sized-v0" -> "an output whose tree (sized, v0) is `sigmaProp(Coll[<name>]().size == 0)`",
    "tree-sized-v3" -> "an output whose tree (sized, v3) is `sigmaProp(Coll[<name>]().size == 0)`",
    "r4-box" -> "an output whose R4 is a Box constant, that box's tree naming the type variable",
    "r4-collbox" -> "an output whose R4 is a Coll[Box], its box's tree naming the type variable",
    "ext-box" -> "an input whose context extension holds a Box, that box's tree naming the type variable")

  /** One row. `in`: for ErgoTree the root expression (wrapped per version), for Transaction its bytes. `jars` is
    * sigma-rust's measurement on the 6.0.7 jars: `WRITES <bytes>` / `WRITE-THROWS`, or `ID <id>` / `ID-THROWS`. */
  case class Row(kind: String, name: String, treeVersion: Int, in: String, jars: String) {
    private val f = name.split('|')
    def where: String = f(1)
    def nameToken: String = f(2)
    def text: Int = Names(nameToken)._1
    def v6: Boolean = kind == "Transaction" || treeVersion == 3
    def activated: Byte = if (v6) 3 else 2
    def ergoTree: Byte = if (kind == "Transaction") 3 else treeVersion.toByte
    def bytes: String = kind match {
      case "ErgoTree" => tree(Row.wrap(in))
      case _          => in
    }
    def tree(e: String): String = if (treeVersion == 0) "00" + e else f"${8 + treeVersion}%02x" + vlq(e.length / 2) + e
    def slug: String = name.stripPrefix("rt|").stripPrefix("tx|").replace('|', '-')
  }
  object Row {
    /** `sigmaProp((e, true)._2)`: BoolToSigmaProp(SelectField(Tuple(e, true), 2)). */
    def wrap(e: String): String = "d18c8602" + e + "0101" + "02"
  }

  private def tsv(p: java.nio.file.Path): Vector[Array[String]] =
    new String(java.nio.file.Files.readAllBytes(p), "UTF-8").split("\n").toVector.drop(1).filter(_.trim.nonEmpty)
      .map(_.split("\t", -1))

  def writtenRows(): Vector[Row] = tsv(WrittenFile).map(c => Row("ErgoTree", c(1), c(2).toInt, c(4), c(5)))
  def txRows(): Vector[Row] = tsv(TxFile).map(c => Row("Transaction", c(1), 0, c(2), c(3)))
  def rows(): Vector[Row] = writtenRows() ++ txRows()

  /** The messages of a failure's cause chain. */
  def causes(t: Throwable): Seq[String] =
    Iterator.iterate(t)(_.getCause).takeWhile(_ != null).map(c => s"${c.getClass.getName}: ${c.getMessage}").toSeq

  /** The JVM's outcome: Right(None) echo, Right(Some(bytes)) a re-write, Left(()) the writer's range check. */
  def outcome(r: Row): Either[Unit, Option[String]] =
    scala.util.Try(WireCanonicalize.canonicalize(r.kind, r.bytes, r.activated, r.ergoTree)) match {
      case scala.util.Success(out) => Right(if (out == r.bytes) None else Some(out))
      case scala.util.Failure(t) =>
        require(causes(t).exists(c => c.startsWith("java.lang.IllegalArgumentException") &&
          c.contains(s"requirement failed: ${r.text} is out of unsigned byte range")),
          s"${r.kind} ${r.name}: refused by the wrong exception: " + causes(t).mkString(" <- ").take(400))
        Left(())
    }

  /** The JVM's id for a transaction it writes, over the bytes it writes. */
  def txId(r: Row): String = VersionContext.withVersions(r.activated, r.ergoTree) {
    org.ergoplatform.ErgoLikeTransactionSerializer.fromBytes(Base16.decode(r.bytes).get).id
  }

  private def what(r: Row): String = r.kind match {
    case "ErgoTree" =>
      val tree = if (r.treeVersion == 0) "unsized, v0" else "sized, v3"
      s"`sigmaProp((e, true)._2)` in a tree ($tree), e ${Positions(r.where)}, the name ${Names(r.nameToken)._2}."
    case _ => s"A transaction with ${TxWhere(r.where)}, the name ${Names(r.nameToken)._2}."
  }

  private def verdict(r: Row): String = outcome(r) match {
    case Left(()) =>
      val read = if (r.kind == "Transaction") " (sigmastate's ErgoLikeTransaction reads it, its id lazy; ergo's " +
        "ErgoTransaction computes the id at the read and refuses there)" else ""
      s"Read$read, and REFUSED when written back: ${r.text} is past `putUByte` (`requirement failed: ${r.text} is " +
        "out of unsigned byte range`). WRITER REFUSES: a port that writes the length modulo 256 writes bytes, the " +
        "over-accept."
    case Right(out) =>
      val id = if (r.kind == "Transaction") s" The JVM's id: ${txId(r)}." else ""
      val how = if (out.isEmpty) "Written back as read." else "Written back with the name's text (each FF as `EF BF " +
        "BD`): `expected_bytes_hex`."
      s"$how CONTROL: a text of ${r.text} bytes fits `putUByte`.$id"
  }

  private def entry(r: Row, i: Int): Json = Json.obj(Seq(
    "name" -> Json.fromString(s"type-var-name-write-range-${r.slug}#$i"), "kind" -> Json.fromString(r.kind),
    "source" -> Json.fromString(Source), "description" -> Json.fromString(s"[${r.name}] ${what(r)} ${verdict(r)}"),
    "bytes_hex" -> Json.fromString(r.bytes)) ++
    (outcome(r) match {
      case Right(Some(out)) => Seq("expected_bytes_hex" -> Json.fromString(out))
      case Right(None)      => Nil
      case Left(())         => Seq("error" -> Json.fromString("errored"))
    }) ++ Seq("version" -> Json.obj("activated" -> Json.fromInt(r.activated.toInt),
      "ergoTree" -> Json.fromInt(r.ergoTree.toInt))): _*)

  def extract(v6: Boolean): Map[String, Json] = {
    def envelope(op: String, kind: String): Option[(String, Json)] = {
      val rs = rows().filter(r => r.kind == kind && r.v6 == v6)
      if (rs.isEmpty) None else Some(op -> Json.obj(
        "schema"     -> Json.fromString("santa-wire/v1"),
        "op"         -> Json.fromString(op),
        "blessed_by" -> Json.fromString("jvm:sigma-state-6.0.7"),
        "entries"    -> Json.arr(rs.zipWithIndex.map { case (r, i) => entry(r, i) }: _*)))
    }
    Seq(envelope(OpErgoTree, "ErgoTree"), envelope(OpTransaction, "Transaction")).flatten.toMap
  }

  def writeVectors(outDir: java.nio.file.Path): Unit = {
    SpecExtract.writeStaging("AuthoredWireWriterCountRange.v6", extract(v6 = true), outDir.resolve("v6"))
    SpecExtract.writeStaging("AuthoredWireWriterCountRange.v5", extract(v6 = false), outDir.resolve("v5"))
  }
}
