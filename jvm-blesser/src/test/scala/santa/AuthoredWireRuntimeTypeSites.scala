package santa

// Authored wire vectors: collection DATA whose item type has no runtime type is REFUSED by the reader, and the size
// bit does not degrade it. Request: `~/projects/sigma-rust/prompts/santa-probe-runtime-type.md` (fork PR 92, its whole
// trees with their controls, `trees.txt` / `trees2.txt`); measured by `santa.spike.RuntimeTypeProbe` (local).
// Eval-tier companion: AuthoredEvalRuntimeTypeSites.
//
// THE RULE: the data reader resolves a collection's item type with `Evaluation.stypeToRType` BEFORE it reads the
// items, and that throws `RuntimeException: Don't know how to convert SType … to RType` for a type variable, a
// function type of other than one argument, and any type containing one. A RuntimeException is not a
// ValidationException, so a sized tree is REJECTED, not degraded: "a green wire entry on a sized tree does not prove a
// parse" is why the tree rows are here. What has a runtime type parses: `Coll[(Int, Int, T)]()` (a tuple of more than
// two items is read as `Coll[Any]` and its items' types are not resolved), an `Option[T]` `None` constant (no data to
// read), `Coll[(Int) => Int]()`.
//
// ErgoTree rows are `sigmaProp(c.size == 0)` around the constant `c` (inline, or segregated: `seg-`), at tree v0
// (sized and unsized; wire/v5, activated 2) and sized v3 (wire/v6, activated 3). Box rows carry the data in R4.

import io.circe.Json

object AuthoredWireRuntimeTypeSites {
  val OpErgoTree = "ErgoTree.stypeToRType_sites"
  val OpBox      = "Box.stypeToRType_sites"
  val Source     = "santa:authored-runtime-type-sites"
  private val NoRType = "to RType"

  /** One row: `bytes` the object; `v6` picks the version; `accept` whether the JVM reads it. */
  case class Row(kind: String, id: String, v6: Boolean, bytes: String, accept: Boolean, what: String) {
    def activated: Byte = if (v6) 3 else 2
    def treeVersion: Byte = if (v6) 3 else 0
  }

  private val G = "0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798"
  private def box(r4: String) = "c0843d" + "0008cd" + G + "00" + "00" + "01" + r4 + "00" * 32 + "00"

  private val Reject = "REJECTED: the reader resolves the item type before reading and finds no runtime type; a " +
    "RuntimeException, not a ValidationException, so nothing degrades. SEPARATING: an implementation that reads such " +
    "data accepts it."
  private def tree(id: String, v6: Boolean, hex: String, c: String, accept: Boolean, extra: String = ""): Row =
    Row("ErgoTree", id, v6, hex, accept, s"`sigmaProp($c.size == 0)`${if (id.startsWith("seg-")) ", the constant " +
      "segregated" else ""}. " + (if (accept) s"ACCEPTED, round-trips to its own bytes. $extra" else Reject))

  val Rows: Seq[Row] = Seq(
    // v5: tree v0, sized (08) and unsized (00)
    tree("CollInt-empty-sized", v6 = false, "0807d193b110000400", "Coll[Int]()", accept = true, "CONTROL."),
    tree("CollInt-empty-unsized", v6 = false, "00d193b110000400", "Coll[Int]()", accept = true, "CONTROL."),
    tree("CollT-empty-sized", v6 = false, "080ad193b10c670154000400", "Coll[T]()", accept = false),
    tree("CollT-empty-unsized", v6 = false, "00d193b10c670154000400", "Coll[T]()", accept = false),
    tree("CollCollT-1-empty-sized", v6 = false, "080cd193b10c0c67015401000400", "Coll[Coll[T]](Coll())", accept = false),
    tree("CollCollT-1-empty-unsized", v6 = false, "00d193b10c0c67015401000400", "Coll[Coll[T]](Coll())", accept = false),
    tree("CollOptT-empty-sized", v6 = false, "080bd193b10c24670154000400", "Coll[Option[T]]()", accept = false),
    tree("CollOptT-empty-unsized", v6 = false, "00d193b10c24670154000400", "Coll[Option[T]]()", accept = false),
    tree("CollOptT-1-sized", v6 = false, "080cd193b10c2467015401000400", "Coll[Option[T]](None)", accept = false),
    tree("CollTripleT-empty-sized", v6 = false, "080dd193b10c480404670154000400", "Coll[(Int, Int, T)]()",
      accept = true, "A tuple of three is read as `Coll[Any]`: its items' types are not resolved. SEPARATING: an " +
      "implementation that refuses every collection whose item type mentions a variable rejects it."),
    tree("CollTripleT-empty-unsized", v6 = false, "00d193b10c480404670154000400", "Coll[(Int, Int, T)]()",
      accept = true, "SEPARATING, as CollTripleT-empty-sized."),
    tree("seg-CollT-empty-sized", v6 = false, "180d010c67015400d193b173000400", "Coll[T]()", accept = false),
    // v6: sized tree v3
    tree("CollInt-empty", v6 = true, "0b07d193b110000400", "Coll[Int]()", accept = true, "CONTROL."),
    tree("CollT-empty", v6 = true, "0b0ad193b10c670154000400", "Coll[T]()", accept = false),
    tree("CollCollT-1-empty", v6 = true, "0b0cd193b10c0c67015401000400", "Coll[Coll[T]](Coll())", accept = false),
    tree("CollOptT-empty", v6 = true, "0b0bd193b10c24670154000400", "Coll[Option[T]]()", accept = false),
    tree("CollOptT-1", v6 = true, "0b0cd193b10c2467015401000400", "Coll[Option[T]](None)", accept = false),
    tree("CollTripleT-empty", v6 = true, "0b0dd193b10c480404670154000400", "Coll[(Int, Int, T)]()", accept = true,
      "SEPARATING, as the v0 rows."),
    tree("CollF2-empty", v6 = true, "0b0dd193b10c700204040400000400", "Coll[(Int, Int) => Int]()", accept = false),
    tree("CollF2-1", v6 = true, "0b0ed193b10c70020404040001000400", "Coll[(Int, Int) => Int](…)", accept = false),
    tree("CollF1-empty", v6 = true, "0b0cd193b10c7001040400000400", "Coll[(Int) => Int]()", accept = true,
      "CONTROL for CollF2: a one-argument function type has a runtime type."),
    tree("seg-CollT-empty", v6 = true, "1b0d010c67015400d193b173000400", "Coll[T]()", accept = false),
    tree("seg-CollInt-empty", v6 = true, "1b0a011000d193b173000400", "Coll[Int]()", accept = true, "CONTROL."),
    tree("seg-CollTripleT-empty", v6 = true, "1b10010c48040467015400d193b173000400", "Coll[(Int, Int, T)]()",
      accept = true, "SEPARATING, as CollTripleT-empty."),
    tree("seg-CollF2-1", v6 = true, "1b11010c7002040404000100d193b173000400", "Coll[(Int, Int) => Int](…)",
      accept = false),
    Row("ErgoTree", "OptT-none", v6 = true, "0b07d1e62467015400", accept = true, "`sigmaProp(None: Option[T])." +
      "isDefined)`, a `None` constant of `Option[T]` — ACCEPTED, round-trips: there is no data to read, so nothing " +
      "resolves `T`. SEPARATING: an implementation that refuses any constant whose type mentions a variable rejects it."),
    // Box: R4 = Coll[τ]()
    Row("Box", "r4-CollInt", v6 = false, box("1000"), accept = true, "A box whose R4 is `Coll[Int]()`. ACCEPTED. CONTROL."),
    Row("Box", "r4-CollT", v6 = false, box("0c67015400"), accept = false, s"A box whose R4 is `Coll[T]()`. $Reject"),
    Row("Box", "r4-CollCollT", v6 = false, box("0c0c67015400"), accept = false,
      s"A box whose R4 is `Coll[Coll[T]]()`. $Reject"),
    Row("Box", "r4-CollOptT", v6 = false, box("0c2467015400"), accept = false,
      s"A box whose R4 is `Coll[Option[T]]()`. $Reject"),
    Row("Box", "r4-CollPairTInt", v6 = false, box("0c3c6701540400"), accept = false,
      s"A box whose R4 is `Coll[(T, Int)]()`. $Reject"),
    Row("Box", "r4-CollTripleT", v6 = false, box("0c48040467015400"), accept = true, "A box whose R4 is " +
      "`Coll[(Int, Int, T)]()`. ACCEPTED: a tuple of three is read as `Coll[Any]`. SEPARATING, as the tree rows."),
    Row("Box", "r4-CollF1", v6 = true, box("0c700104040000"), accept = true, "A box whose R4 is `Coll[(Int) => Int]()` " +
      "(a v6 register type). ACCEPTED. CONTROL for r4-CollF2."),
    Row("Box", "r4-CollF2", v6 = true, box("0c70020404040000"), accept = false,
      s"A box whose R4 is `Coll[(Int, Int) => Int]()`. $Reject"))

  def check(r: Row): Unit =
    scala.util.Try(WireCanonicalize.canonicalize(r.kind, r.bytes, r.activated, r.treeVersion)) match {
      case scala.util.Success(out) =>
        require(r.accept, s"${r.kind} ${r.id}: must be REFUSED, round-tripped to $out")
        require(out == r.bytes, s"${r.kind} ${r.id}: must round-trip to itself, in ${r.bytes}, out $out")
      case scala.util.Failure(t) =>
        val causes = Iterator.iterate(t: Throwable)(_.getCause).takeWhile(_ != null).toSeq
        require(!r.accept, s"${r.kind} ${r.id}: must ACCEPT, refused: ${causes.map(_.getMessage).mkString(" <- ")}")
        require(causes.exists(c => c.isInstanceOf[RuntimeException] && Option(c.getMessage).exists(_.contains(NoRType))),
          s"${r.kind} ${r.id}: refused for another reason: " +
            causes.map(c => c.getClass.getName + ": " + c.getMessage).mkString(" <- ").take(400))
    }

  def extract(v6: Boolean): Map[String, Json] =
    Seq(OpErgoTree -> "ErgoTree", OpBox -> "Box").map { case (op, kind) =>
      val es = Rows.filter(r => r.kind == kind && r.v6 == v6).zipWithIndex.map { case (r, i) =>
        check(r)
        Json.obj(Seq(
          "name" -> Json.fromString(s"stypetortype-${r.id.toLowerCase}-${if (v6) "v3" else "v0"}#$i"),
          "kind" -> Json.fromString(kind), "source" -> Json.fromString(Source),
          "description" -> Json.fromString(s"[${r.id}] ${r.what}"), "bytes_hex" -> Json.fromString(r.bytes)) ++
          (if (r.accept) Nil else Seq("error" -> Json.fromString("errored"))) ++
          Seq("version" -> Json.obj("activated" -> Json.fromInt(r.activated.toInt),
            "ergoTree" -> Json.fromInt(r.treeVersion.toInt))): _*)
      }
      op -> Json.obj("schema" -> Json.fromString("santa-wire/v1"), "op" -> Json.fromString(op),
        "blessed_by" -> Json.fromString("jvm:sigma-state-6.0.7"), "entries" -> Json.arr(es: _*))
    }.toMap

  def writeVectors(outDir: java.nio.file.Path): Unit = {
    SpecExtract.writeStaging("AuthoredWireRuntimeTypeSites.v6", extract(v6 = true), outDir.resolve("v6"))
    SpecExtract.writeStaging("AuthoredWireRuntimeTypeSites.v5", extract(v6 = false), outDir.resolve("v5"))
  }
}
