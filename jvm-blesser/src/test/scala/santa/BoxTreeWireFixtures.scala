package santa

// Shared by the box-tree wire blessers (AuthoredWireBoxTreeParse, AuthoredWireSizedTreeRequests): a crafted
// candidate put in a bare box or a transaction, and entries whose blessing is re-derived through WireCanonicalize
// under the node's v6 parse context (3, 3). Every candidate has value 1000000 (3 VLQ bytes), so its tree window ends 3
// bytes after its box window (candidate offsets 4099 and 4096).

import scala.util.{Failure, Success, Try}

import io.circe.Json
import scorex.util.encode.Base16
import sigma.VersionContext
import sigma.ast.{ErgoTree, UnparsedErgoTree}
import sigma.serialization.SigmaSerializer
import org.ergoplatform.{ErgoBox, ErgoBoxCandidate, ErgoLikeTransaction}

import RentFixtures._

trait BoxTreeWireFixtures {
  val V3: Byte = VersionContext.V6SoftForkVersion // activated AND ergoTree: the node's v6 parse context
  /** The entries' `source`. */
  def Source: String

  protected def b(hex: String): Array[Byte] = Base16.decode(hex).get
  protected def vlq(n: Int): Array[Byte] = vlqU32(n.toLong)
  protected def zeros(n: Int): Array[Byte] = Array.fill(n)(0.toByte)

  protected val Value  = vlq(1000000).clone() // c0843d, 3 bytes
  protected val Fields = b("010000")          // creation height 1, no tokens, no registers
  protected val MaxSize = 4096
  require(Value.length == 3)

  private val spent = box("santa:btp:spent", 1000000000L, 1)
  private val placeholder = candidate(1000000L, 1)                 // c0843d 0008d3 01 00 00
  protected val placeholderBytes = b(hex(Value) + "0008d3" + "010000")

  protected def replaceUnique(bytes: Array[Byte], from: Array[Byte], to: Array[Byte]): Array[Byte] = {
    val at = bytes.indexOfSlice(from)
    require(at >= 0 && bytes.indexOfSlice(from, at + 1) < 0, s"${hex(from)} must occur exactly once in ${hex(bytes)}")
    bytes.take(at) ++ to ++ bytes.drop(at + from.length)
  }
  /** A bare box: `cand`, then the placeholder box's tx id and index. */
  protected def boxWith(cand: Array[Byte]): Array[Byte] = VersionContext.withVersions(V3, V3) {
    val plain = ErgoBox.sigmaSerializer.toBytes(box("santa:btp:box", 1000000L, 1))
    require(plain.startsWith(placeholderBytes), s"unexpected box layout: ${hex(plain)}")
    cand ++ plain.drop(placeholderBytes.length)
  }
  /** A tx whose outputs are `cand` followed by `after`. */
  protected def txWith(cand: Array[Byte], after: Seq[ErgoBoxCandidate] = Nil): Array[Byte] =
    VersionContext.withVersions(V3, V3) {
      replaceUnique(txBytes(RentFixtures.tx(Seq(input(spent, ext())), placeholder +: after)), placeholderBytes, cand)
    }

  /** A size-flagged tree: `header`, the content's size, the content. */
  protected def sizedTree(header: Int, content: Array[Byte]): Array[Byte] =
    Array(header.toByte) ++ vlq(content.length) ++ content

  protected def version: Json =
    Json.obj("activated" -> Json.fromInt(V3.toInt), "ergoTree" -> Json.fromInt(V3.toInt))
  protected def causes(t: Throwable): List[String] =
    Iterator.iterate(t)(_.getCause).takeWhile(_ != null).map(c => s"${c.getClass.getName}: ${c.getMessage}").toList
  protected def canonical(kind: String, in: String): String = WireCanonicalize.canonicalize(kind, in, V3, V3)

  /** The crafted candidate's tree as the JVM parsed it (a Box's tree, or a Transaction's output 0). */
  protected def parsedTree(kind: String, bytes: Array[Byte]): ErgoTree = VersionContext.withVersions(V3, V3) {
    val r = SigmaSerializer.startReader(bytes)
    kind match {
      case "Box"         => ErgoBox.sigmaSerializer.parse(r).ergoTree
      case "Transaction" => ErgoLikeTransaction.serializer.parse(r).outputCandidates(0).ergoTree
    }
  }
  protected def degradedBy(t: ErgoTree): Option[Int] = t.root match {
    case Left(UnparsedErgoTree(_, error)) => Some(error.rule.id.toInt)
    case Right(_)                         => None
  }

  protected def entry(name: String, kind: String, description: String, in: String, extra: (String, Json)*): Json =
    Json.obj(Seq(
      "name" -> Json.fromString(name), "kind" -> Json.fromString(kind),
      "source" -> Json.fromString(Source), "description" -> Json.fromString(description),
      "bytes_hex" -> Json.fromString(in)) ++ extra ++ Seq("version" -> version): _*)

  /** An accept entry: identity round-trip, and the tree parsed (`degrade = None`) or degraded by that rule. */
  protected def accept(name: String, kind: String, description: String, bytes: Array[Byte], degrade: Option[Int]): Json = {
    val in = hex(bytes)
    val out = canonical(kind, in)
    require(out == in, s"$name: the JVM must round-trip an accept vector to itself — in ${in.take(80)}…, out ${out.take(80)}…")
    val got = degradedBy(parsedTree(kind, bytes))
    require(got == degrade, s"$name: tree degrade rule must be $degrade, got $got")
    entry(name, kind, description, in)
  }

  /** A non-identity accept: the tree parses and the JVM writes the object back as `rewritten` (expected_bytes_hex). */
  protected def acceptRewritten(name: String, kind: String, description: String, bytes: Array[Byte],
                                rewritten: Array[Byte]): Json = {
    val (in, want) = (hex(bytes), hex(rewritten))
    require(want != in, s"$name: a non-identity accept must change the bytes")
    val out = canonical(kind, in)
    require(out == want, s"$name: the JVM must re-serialize to ${want.take(80)}…, got ${out.take(80)}…")
    require(degradedBy(parsedTree(kind, bytes)).isEmpty, s"$name: the tree must parse")
    entry(name, kind, description, in, "expected_bytes_hex" -> Json.fromString(want))
  }

  /** A reject entry: the JVM must throw, with every `mention` in the cause chain and no `forbid`. */
  protected def reject(name: String, kind: String, description: String, bytes: Array[Byte],
                       mention: Seq[String], forbid: Seq[String] = Nil): Json = {
    val in = hex(bytes)
    Try(canonical(kind, in)) match {
      case Success(out) => sys.error(s"$name: the JVM must REJECT, but it round-tripped to ${out.take(80)}…")
      case Failure(t) =>
        val chain = causes(t)
        mention.foreach(m => require(chain.exists(_.contains(m)), s"$name: want '$m' in ${chain.mkString(" <- ")}"))
        forbid.foreach(f => require(!chain.exists(_.contains(f)), s"$name: must not fail with '$f': ${chain.mkString(" <- ")}"))
    }
    entry(name, kind, description, in, "error" -> Json.fromString("errored"))
  }

  protected def envelope(op: String, es: Seq[Json]): Json = Json.obj(
    "schema"     -> Json.fromString("santa-wire/v1"),
    "op"         -> Json.fromString(op),
    "blessed_by" -> Json.fromString("jvm:sigma-state-6.0.6"),
    "entries"    -> Json.arr(es: _*))
}
