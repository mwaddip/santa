package santa

// Authored wire vectors — a size-flagged ErgoTree whose declared size disagrees with the bytes its body takes.
//
// sigmastate v6.0.6 `ErgoTreeSerializer.deserializeErgoTree` (:141-215) reads the declared size
// (`deserializeHeaderAndSize`, :217-237) but uses it only in the ValidationException degrade branch (:197-208).
// When the body parses, the tree is exactly the bytes the parse consumed (:176-179), and the box parse carries
// on right after them: the declared size is never compared with anything. Re-serializing a parsed tree writes the
// recomputed size (`serializeErgoTree`, :114-122), so the JVM answers a mismatched size with the canonical bytes.
//
// Each op holds a control (declared = actual, identity round-trip) and the same object with the declared size
// one larger and one smaller (non-identity round-trips whose expected bytes are the control's). An impl that
// consumes exactly the declared size reads the next field as tree padding (over) or runs out of body (under).
//
// extract() re-derives every blessing through WireCanonicalize, the path rudolph grades with, under the node's v6
// parse context (3, 3), and fails loud unless the JVM re-serializes each mismatch to the control's bytes from a
// parsed, not degraded, tree.

import io.circe.Json
import scorex.util.encode.Base16
import sigma.VersionContext
import sigma.ast.{ErgoTree, SigmaPropConstant}
import sigma.data.TrivialProp
import sigma.serialization.SigmaSerializer
import org.ergoplatform.{ErgoBox, ErgoLikeTransaction}

import RentFixtures._

object AuthoredWireSizedTreeDeclaredSize {
  val V3: Byte = VersionContext.V6SoftForkVersion // activated AND ergoTree: the node's v6 parse context
  val OpBox         = "Box.sized_tree_declared_size"
  val OpTransaction = "Transaction.sized_tree_declared_size"
  val Source        = "santa:authored-sized-tree-declared-size"

  /** v1 size-flagged tree `sigmaProp(true)`: header 0x09, size 2, body 08 d3. */
  private val tree: ErgoTree = VersionContext.withVersions(V3, V3) {
    ErgoTree(ErgoTree.headerWithVersion(ErgoTree.ZeroHeader, 1), IndexedSeq(), SigmaPropConstant(TrivialProp.TrueProp))
  }
  private def treeWithSize(declared: Int): Array[Byte] = Array(0x09, declared, 0x08, 0xd3).map(_.toByte)

  private def boxBytes: Array[Byte] = VersionContext.withVersions(V3, V3) {
    ErgoBox.sigmaSerializer.toBytes(box("santa:sts:box", 1000000L, 1, tree = tree))
  }
  private def txBytesSized: Array[Byte] = VersionContext.withVersions(V3, V3) {
    val spent = box("santa:sts:spent", 1000000000L, 1)
    txBytes(RentFixtures.tx(Seq(input(spent, ext())), Seq(candidate(spent.value, 1, tree = tree))))
  }

  /** The tree the JVM parsed out of `in`: it must be a parsed tree, not a soft-fork degrade. */
  private def parsedTreeIsRight(kind: String, in: String): Boolean = VersionContext.withVersions(V3, V3) {
    val r = SigmaSerializer.startReader(Base16.decode(in).get)
    kind match {
      case "Box"         => ErgoBox.sigmaSerializer.parse(r).ergoTree.root.isRight
      case "Transaction" => ErgoLikeTransaction.serializer.parse(r).outputCandidates(0).ergoTree.root.isRight
    }
  }

  private def version: Json =
    Json.obj("activated" -> Json.fromInt(V3.toInt), "ergoTree" -> Json.fromInt(V3.toInt))

  private def entries(kind: String, control: Array[Byte]): Seq[Json] = {
    require(Base16.encode(control).contains(Base16.encode(treeWithSize(2))),
      s"$kind control must carry tree 090208d3: ${Base16.encode(control)}")
    val controlHex = Base16.encode(control)
    require(WireCanonicalize.canonicalize(kind, controlHex, V3, V3) == controlHex,
      s"$kind control must round-trip to itself")
    def entry(name: String, description: String, in: String, expected: Option[String]): Json =
      Json.obj(Seq(
        "name" -> Json.fromString(name), "kind" -> Json.fromString(kind),
        "source" -> Json.fromString(Source), "description" -> Json.fromString(description),
        "bytes_hex" -> Json.fromString(in)) ++
        expected.map(x => "expected_bytes_hex" -> Json.fromString(x)).toSeq ++
        Seq("version" -> version): _*)
    def mismatch(i: Int, label: String, declared: Int, description: String): Json = {
      val in = Base16.encode(spliceUnique(control, treeWithSize(2), treeWithSize(declared)))
      val out = WireCanonicalize.canonicalize(kind, in, V3, V3)
      require(out == controlHex, s"$kind $label: the JVM must re-serialize to the control's bytes — got $out")
      require(parsedTreeIsRight(kind, in), s"$kind $label: the tree must parse, not degrade")
      entry(s"${kind.toLowerCase}-sized-tree-declared-$label#$i", description, in, Some(out))
    }
    val subject = if (kind == "Box") "An ErgoBox" else "A transaction whose only output"
    Seq(
      entry(s"${kind.toLowerCase}-sized-tree-control#0",
        s"$subject has the size-flagged v1 tree 09 02 08 d3 (sigmaProp(true)); the declared size 2 is the body's. " +
        "Round-trip identity: the control for the two mismatches.", controlHex, None),
      mismatch(1, "over", 3,
        s"$subject has the tree declared as 3 bytes while its body 08 d3 takes 2. The JVM ignores the declared size " +
        "when the body parses (ErgoTreeSerializer.scala:141-215; the size is read only for the degrade branch), reads " +
        "the next byte as the creation height as usual, and re-serializes with the recomputed size 2. An impl that " +
        "consumes exactly the declared size swallows the creation-height byte and misreads every later field."),
      mismatch(2, "under", 1,
        s"$subject has the tree declared as 1 byte while its body 08 d3 takes 2. The JVM parses the whole body anyway " +
        "and re-serializes with the recomputed size 2. An impl that consumes exactly the declared size runs out of " +
        "body bytes: it rejects the object, or degrades the tree and misreads the rest."))
  }

  def extract(): Map[String, Json] = {
    def envelope(op: String, es: Seq[Json]): Json = Json.obj(
      "schema"     -> Json.fromString("santa-wire/v1"),
      "op"         -> Json.fromString(op),
      "blessed_by" -> Json.fromString("jvm:sigma-state-6.0.6"),
      "entries"    -> Json.arr(es: _*))
    Map(
      OpBox         -> envelope(OpBox, entries("Box", boxBytes)),
      OpTransaction -> envelope(OpTransaction, entries("Transaction", txBytesSized)))
  }

  def writeVectors(outDir: java.nio.file.Path): Unit =
    SpecExtract.writeStaging("AuthoredWireSizedTreeDeclaredSize", extract(), outDir)
}
