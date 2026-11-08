package santa

// Authored wire vectors — the thirteen parse-but-never-evaluate node kinds, as a BOX and as a
// TRANSACTION OUTPUT, in both tree forms.
//
// The eval half is `AuthoredUnevaluatedNodeKinds` (every one of the thirteen errors at eval). This
// family is the other half of the same question, and the half that separates: an implementation
// with no READER for a node kind refuses the carrier, and
//   v0 UNSIZED   there is no degrade path, so the box and the transaction are REFUSED — an
//                over-reject of a box the JVM accepts, reachable since v6 activated
//   v3 SIZED     a missing reader raises rule 1002 (`NotImplementedOpCode`), a ValidationException,
//                which the size bit turns into a degrade: the carrier round-trips to its own bytes
//                and the entry is green either way
// So the v0 rows are the live arm and the v3 rows are regression guards that prove the degrade.
// (The sigma-rust fork has no reader for the three shifts, the three ModQ ops, SigmaPropIsProven
// or TaggedVariable — its own reading, 2026-11-08.)
//
// Four reject arms pin the edges, all measured:
//   create_avl_tree v0   rule 1009 refuses the `None: Option[Int]` data value (type code 36) and
//                        an unsized tree cannot degrade: REJECTED. The JVM will not write this
//                        tree at v0 either, so the bytes are composed — see UnevaluatedNodeTrees
//   old layout v0 + v3   the fork's three-values-then-`00` form: `00` is read as a type prefix and
//                        `TypeSerializer` has no type code 0 (`:138`). Rejected at BOTH versions —
//                        an `InvalidTypePrefix` is not a ValidationException, so the size bit does
//                        not degrade it
//   bool operand v0 + v3 `BitShiftLeft(true, 2)`: `BitOp`'s numeric `require` throws an
//                        IllegalArgumentException, and `deserializeErgoTree` rethrows ANY IAE as
//                        `SerializerException("Tree version (N) is above activated script version
//                        (M)")` (`ErgoTreeSerializer.scala:191-193`) — a message hard-coded for a
//                        different hypothesis. The real reason is only in the CAUSE. Rejected at
//                        both versions: the rethrow happens inside the inner try, before the
//                        ValidationException catch that degrades
//   spip filter v0       `SigmaPropIsProven(Filter(1, (x: Int) => true))`: the build check reads
//                        the input's type and `Filter.tpe` casts SInt to SCollection
//
// extract() re-derives every verdict through `WireCanonicalize` — the path rudolph grades with —
// and each reject asserts the REASON, not just the refusal.

import io.circe.Json
import scorex.util.encode.Base16
import sigma.VersionContext
import org.ergoplatform.ErgoBox

import RentFixtures._
import UnevaluatedNodeTrees.{Activated, nodes, oldLayoutBody, boolOperandBody, spipFilterTree}

object AuthoredWireUnevaluatedNodeKinds {
  val OpBox         = "Box.unevaluated_node_kinds"
  val OpTransaction = "Transaction.unevaluated_node_kinds"
  val Source        = "santa:authored-unevaluated-node-kinds"

  private def hx(bs: Array[Byte]): String = Base16.encode(bs)
  private def ctx[A](f: => A): A = VersionContext.withVersions(Activated, Activated)(f)

  /** `sigmaProp(true)` — the carrier's own tree, spliced over. A splice, not a build: two of these
    * trees cannot be deserialized into an `ErgoTree` at all. */
  private val PlainTreeHex = "0008d3"

  private def replaceUnique(in: String, from: String, to: String): String = {
    val i = in.indexOf(from)
    require(i >= 0 && in.indexOf(from, i + 1) < 0, s"marker $from must occur exactly once")
    in.take(i) + to + in.drop(i + from.length)
  }

  private def boxWithTree(slug: String, treeHex: String): String = ctx {
    replaceUnique(hx(ErgoBox.sigmaSerializer.toBytes(box(s"santa:uen:$slug", 1000000L, 1))),
      PlainTreeHex, treeHex)
  }
  private def txWithTree(treeHex: String): String = ctx {
    val plain = hx(txBytes(tx(Seq(input(box("santa:uen:spent", 1000000000L, 1), ext())),
      Seq(candidate(1000000L, 1)))))
    replaceUnique(plain, PlainTreeHex + "01" + "0000", treeHex + "01" + "0000")
  }
  private def carrier(kind: String, slug: String, treeHex: String): String = kind match {
    case "Box"         => boxWithTree(slug, treeHex)
    case "Transaction" => txWithTree(treeHex)
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
      case scala.util.Success(out) => sys.error(s"$name: must REJECT at parse, round-tripped to $out")
      case scala.util.Failure(t) =>
        val causes = Iterator.iterate(t: Throwable)(_.getCause).takeWhile(_ != null)
          .map(c => s"${c.getClass.getName}: ${c.getMessage}").mkString(" <- ")
        reasonMust.foreach(frag => require(causes.contains(frag),
          s"$name: rejected for the wrong reason (want '$frag'): $causes"))
    }
    entry(kind, name, description, in, treeVersion.toInt, "error" -> Json.fromString("errored"))
  }

  private val V0: Byte = 0

  private def rows(kind: String, suffix: String): Seq[Json] = {
    var i = -1
    def next(): Int = { i += 1; i }

    val v0Accepts = (nodes.filter(_.v0Representable) ++ UnevaluatedNodeTrees.extraEncodings).map { n =>
      accept(kind, s"unevaluated-${n.slug}-v0-$suffix#${next()}",
        s"${n.what} — at tree version 0, UNSIZED. The JVM parses the node and the carrier " +
        "round-trips to its own bytes; evaluating it throws (the eval family). An implementation " +
        "with no reader for this node kind has NO degrade path here and refuses the carrier: the " +
        "live arm of this family, an over-reject of a box the JVM accepts.",
        carrier(kind, n.slug, UnevaluatedNodeTrees.v0(n.body)), V0)
    }
    val v3Accepts = nodes.map { n =>
      accept(kind, s"unevaluated-${n.slug}-v3-$suffix#${next()}",
        s"${n.what} — at tree version 3, SIZE-FLAGGED. The same parse, but an implementation " +
        "without the reader raises rule 1002 (`NotImplementedOpCode`), a ValidationException the " +
        "size bit turns into a degrade that re-serializes verbatim — so this row is green either " +
        "way and the v0 twin is what separates. It is the regression guard that the degrade path " +
        "stays open.",
        carrier(kind, n.slug, UnevaluatedNodeTrees.v3(n.body)), Activated)
    }
    val createAvl = nodes.find(_.slug == "create_avl_tree_four_value").get
    val rejects = Seq(
      reject(kind, s"unevaluated-create-avl-tree-v0-reject-$suffix#${next()}",
        "The four-value CreateAvlTree layout at tree version 0, UNSIZED. Its fourth field is a " +
        "`None: Option[Int]` CONSTANT, and rule 1009 (`CheckSerializableTypeCode`) refuses that " +
        "data value at v0 — type code 36. With no size bit there is nothing to degrade behind, so " +
        "the carrier is REJECTED. The JVM will not WRITE this tree at v0 either (its serializer " +
        "re-enters the version context at the tree's own version), so the bytes are composed; the " +
        "v3 twin above is the accepted form.",
        carrier(kind, "create-avl-v0", UnevaluatedNodeTrees.v0(createAvl.body)), V0,
        Seq("1009", "code 36")),
      reject(kind, s"unevaluated-old-layout-v0-reject-$suffix#${next()}",
        "CreateAvlTree in the fork's OLD layout — three values and then a bare `00` where the " +
        "fourth value belongs. `00` is read as a type prefix and `TypeSerializer` has no type " +
        "code 0 (`:138`): REJECTED. This is the arm that tells the two layouts apart.",
        carrier(kind, "old-layout-v0", UnevaluatedNodeTrees.v0(oldLayoutBody)), V0,
        Seq("type prefix 0")),
      reject(kind, s"unevaluated-old-layout-v3-reject-$suffix#${next()}",
        "The same old layout, SIZE-FLAGGED at v3 — still REJECTED. An `InvalidTypePrefix` is not " +
        "a ValidationException, so the size bit does not degrade it: the pair pins that the " +
        "layout error is fatal at every version, unlike a missing opcode.",
        carrier(kind, "old-layout-v3", UnevaluatedNodeTrees.v3(oldLayoutBody)), Activated,
        Seq("type prefix 0")),
      reject(kind, s"unevaluated-bool-operand-v0-reject-$suffix#${next()}",
        "`BitShiftLeft(true, 2)` at v0: `BitOp`'s `require(left.tpe.isNumTypeOrNoType && ...)` " +
        "throws an IllegalArgumentException at BUILD, inside the parse. Note what the JVM then " +
        "SAYS: `deserializeErgoTree` rethrows any IAE as `SerializerException(\"Tree version (0) " +
        "is above activated script version (3)\")` (`ErgoTreeSerializer.scala:191-193`) — a " +
        "message hard-coded for an unrelated hypothesis. The real reason, `invalid types " +
        "left:SBoolean, right:SInt`, survives only as the CAUSE. Match on classes, not messages.",
        carrier(kind, "bool-operand-v0", UnevaluatedNodeTrees.v0(boolOperandBody)), V0,
        Seq("invalid types left:SBoolean")),
      reject(kind, s"unevaluated-bool-operand-v3-reject-$suffix#${next()}",
        "The same Boolean operand SIZE-FLAGGED at v3 — still REJECTED, and this is the half worth " +
        "having: the IAE is rethrown inside the inner try, BEFORE the catch that turns a " +
        "ValidationException into a degrade, so the size bit cannot save a failed build check.",
        carrier(kind, "bool-operand-v3", UnevaluatedNodeTrees.v3(boolOperandBody)), Activated,
        Seq("invalid types left:SBoolean")),
      reject(kind, s"unevaluated-spip-filter-v0-reject-$suffix#${next()}",
        "`sigmaProp(SigmaPropIsProven(Filter(1, (x: Int) => true)))`: SigmaPropIsProven is the one " +
        "of the thirteen whose build check reads a child's TYPE (`val opType`), and `Filter.tpe` " +
        "casts SInt to SCollection — a ClassCastException, not a ValidationException, so no " +
        "degrade. The companion of the PropertyCall receiver-type family: the same cast, reached " +
        "through a node that never evaluates.",
        carrier(kind, "spip-filter-v0", spipFilterTree), V0,
        Seq("ClassCastException", "SCollection")))
    v0Accepts ++ v3Accepts ++ rejects
  }

  def extract(): Map[String, Json] = {
    def envelope(op: String, entries: Seq[Json]): Json = Json.obj(
      "schema"     -> Json.fromString("santa-wire/v1"),
      "op"         -> Json.fromString(op),
      "blessed_by" -> Json.fromString("jvm:sigma-state-6.0.7"),
      "entries"    -> Json.arr(entries: _*))
    Map(
      OpBox         -> envelope(OpBox, rows("Box", "box")),
      OpTransaction -> envelope(OpTransaction, rows("Transaction", "tx")))
  }

  def writeVectors(outDir: java.nio.file.Path): Unit =
    SpecExtract.writeStaging("AuthoredWireUnevaluatedNodeKinds", extract(), outDir)
}
