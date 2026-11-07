package santa

// Authored wire vectors — the explicit type arguments a MethodCall / PropertyCall carries.
//
// Six methods in sigmastate 6.0.7 declare `explicitTypeArgs` (enumerated at runtime from every
// MethodsContainer, not grepped): SBox.getReg (19), SContext.getVarFromInput (12),
// SGlobal.deserializeTo (4), SGlobal.fromBigEndianBytes (5), SGlobal.some (9), SGlobal.none (10).
//
// `MethodCallSerializer` / `PropertyCallSerializer` write ONE type per explicit type argument
// after the object (PropertyCall) or after the arguments (MethodCall), and read exactly that many
// back (`MethodCallSerializer.scala:29,59-67`, `PropertyCallSerializer.scala:24,37-43`). The
// trailing type byte is therefore part of the tree's wire form and of its size.
//
// sigma-rust has no field for explicit types on either node: it neither reads nor writes them
// (reported by the sigma-rust session, 2026-11-07, fork master 65c94a03). The divergence shows
// up differently per kind, which is why all three are vectored:
//   ErgoTree     the tree alone round-trips NON-identically — the trailing type byte is dropped
//                and the declared size shrinks with it
//   Box          the unread type byte is taken as the next field (creation height), every later
//                field shifts, and the parse runs off the end
//   Transaction  the same, inside an output
//
// By the source a v3 output box using any of these methods is valid on the JVM and refused by
// such an implementation: an over-reject in block validation, reachable since v6 activated.
//
// extract() re-derives every verdict through WireCanonicalize — the path rudolph grades with —
// and fails loud if the JVM does not round-trip an entry to itself.

import io.circe.Json
import scorex.util.encode.Base16
import sigma.VersionContext
import sigma.ast._
import sigma.ast.syntax.SigmaPropValue
import org.ergoplatform.{ErgoBox, ErgoBoxCandidate}

import RentFixtures._

object AuthoredWireExplicitTypeArgs {
  val V3: Byte = VersionContext.V6SoftForkVersion // activated AND ergoTree: the node's v6 context
  val OpErgoTree    = "ErgoTree.explicit_type_args"
  val OpBox         = "Box.explicit_type_args"
  val OpTransaction = "Transaction.explicit_type_args"
  val Source        = "santa:authored-explicit-type-args"

  private val T = STypeVar("T")

  /** The six methods, each as a SigmaProp-rooted tree. Built inside a (3,3) version context:
    * the v6 method tables are version-gated, so the lookups throw outside it. */
  private lazy val trees: Seq[(String, String, String)] =
    VersionContext.withVersions(V3, V3) {
      val someM   = SGlobalMethods.getMethodByName("some")
      val noneM   = SGlobalMethods.getMethodByName("none")
      val deserM  = SGlobalMethods.getMethodByName("deserializeTo")
      val fromBeM = SGlobalMethods.getMethodByName("fromBigEndianBytes")
      val getRegM = SBoxMethods.getMethodByName("getReg")
      val getVfiM = SContextMethods.getMethodByName("getVarFromInput")
      val subst   = Map(T -> (SInt: SType))

      /** MethodCall whose result is Option[Int]; `.isDefined` closes it to a SigmaProp root. */
      def optIsDefined(obj: Value[_ <: SType], m: SMethod, args: IndexedSeq[Value[SType]]): SigmaPropValue =
        BoolToSigmaProp(OptionIsDefined(
          MethodCall.typed[Value[SOption[SInt.type]]](obj, m, args, subst)))

      /** MethodCall whose result is Int; an equality closes it to a SigmaProp root. */
      def intEq(obj: Value[_ <: SType], m: SMethod, args: IndexedSeq[Value[SType]]): SigmaPropValue =
        BoolToSigmaProp(EQ(
          MethodCall.typed[Value[SInt.type]](obj, m, args, subst), IntConstant(7)))

      val bytesArg: Value[SType] = ByteArrayConstant(Array[Byte](1, 2, 3, 4)).asInstanceOf[Value[SType]]

      Seq(
        ("none",
         "sigmaProp(Global.none[Int].isDefined) — PropertyCall (no arguments): the type byte " +
         "follows the object directly (PropertyCallSerializer.scala:24).",
         optIsDefined(Global, noneM, IndexedSeq.empty)),
        ("some",
         "sigmaProp(Global.some[Int](5).isDefined) — MethodCall with one argument: the type byte " +
         "follows the arguments (MethodCallSerializer.scala:29).",
         optIsDefined(Global, someM, IndexedSeq(IntConstant(5).asInstanceOf[Value[SType]]))),
        ("deserializeTo",
         "sigmaProp(Global.deserializeTo[Int](Coll(1,2,3,4)) == 7) — MethodCall over Coll[Byte]. " +
         "Only the parse is graded here; the call need not evaluate.",
         intEq(Global, deserM, IndexedSeq(bytesArg))),
        ("fromBigEndianBytes",
         "sigmaProp(Global.fromBigEndianBytes[Int](Coll(1,2,3,4)) == 7) — MethodCall over Coll[Byte].",
         intEq(Global, fromBeM, IndexedSeq(bytesArg))),
        ("getReg",
         "sigmaProp(SELF.getReg[Int](4).isDefined) — MethodCall on a Box object, not Global: the " +
         "explicit type argument is a property of the method, not of its owner.",
         optIsDefined(Self, getRegM, IndexedSeq(IntConstant(4).asInstanceOf[Value[SType]]))),
        ("getVarFromInput",
         "sigmaProp(CONTEXT.getVarFromInput[Int](0, 1).isDefined) — MethodCall on CONTEXT with TWO " +
         "arguments: the type byte still follows them both.",
         optIsDefined(Context, getVfiM,
           IndexedSeq(ShortConstant(0).asInstanceOf[Value[SType]], ByteConstant(1).asInstanceOf[Value[SType]])))
      ).map { case (name, desc, prop) =>
        val header = ErgoTree.setSizeBit(
          ErgoTree.setConstantSegregation(ErgoTree.headerWithVersion(ErgoTree.ZeroHeader, V3)))
        (name, desc, Base16.encode(ErgoTree.fromProposition(header, prop).bytes))
      }
    }

  private def ergoTreeOf(treeHex: String): ErgoTree = VersionContext.withVersions(V3, V3) {
    sigma.serialization.ErgoTreeSerializer.DefaultSerializer
      .deserializeErgoTree(Base16.decode(treeHex).get)
  }

  /** The tree inside a full ErgoBox (the wire `Box` kind: candidate fields + tx id + index). */
  private def boxHexOf(name: String, treeHex: String): String = VersionContext.withVersions(V3, V3) {
    hex(ErgoBox.sigmaSerializer.toBytes(box(s"santa:eta:$name", 1000000L, 1, ergoTreeOf(treeHex))))
  }

  /** The tree as a transaction's only output, spending one sigmaProp(true) box. */
  private def txHexOf(treeHex: String): String = VersionContext.withVersions(V3, V3) {
    val spent = box("santa:eta:spent", 1000000000L, 1)
    val out = new ErgoBoxCandidate(1000000L, ergoTreeOf(treeHex), 1)
    hex(txBytes(tx(Seq(input(spent, ext())), Seq(out))))
  }

  private def version: Json =
    Json.obj("activated" -> Json.fromInt(V3.toInt), "ergoTree" -> Json.fromInt(V3.toInt))

  private def canonical(kind: String, in: String): String =
    WireCanonicalize.canonicalize(kind, in, V3, V3)

  /** An accept entry: the JVM must round-trip the bytes to themselves. */
  private def accept(kind: String, name: String, description: String, in: String): Json = {
    val out = canonical(kind, in)
    require(out == in, s"$name: the JVM must round-trip an accept vector to itself — in $in, out $out")
    Json.obj(
      "name" -> Json.fromString(name), "kind" -> Json.fromString(kind),
      "source" -> Json.fromString(Source), "description" -> Json.fromString(description),
      "bytes_hex" -> Json.fromString(in), "version" -> version)
  }

  def extract(): Map[String, Json] = {
    val treeEntries = trees.zipWithIndex.map { case ((name, desc, treeHex), i) =>
      accept("ErgoTree", s"explicit-type-args-$name-tree#$i",
        s"$desc An implementation that does not read the trailing type re-serializes the tree " +
        "without it, one byte shorter, and the declared size shrinks: a NON-identity round-trip " +
        "and a different script hash.",
        treeHex)
    }
    val boxEntries = trees.zipWithIndex.map { case ((name, desc, treeHex), i) =>
      accept("Box", s"explicit-type-args-$name-box#$i",
        s"$desc The same tree inside a box. An implementation that leaves the type byte unread " +
        "takes it as the creation height and every later field shifts by one: the box does not " +
        "parse at all — an over-reject on a box the JVM accepts.",
        boxHexOf(name, treeHex))
    }
    val txEntries = trees.zipWithIndex.map { case ((name, desc, treeHex), i) =>
      accept("Transaction", s"explicit-type-args-$name-tx#$i",
        s"$desc The same tree as a transaction's only output. The unread type byte shifts the " +
        "rest of the output and the transaction does not parse: an over-reject in block " +
        "validation, reachable since v6 activated.",
        txHexOf(treeHex))
    }

    def envelope(op: String, entries: Seq[Json]): Json = Json.obj(
      "schema"     -> Json.fromString("santa-wire/v1"),
      "op"         -> Json.fromString(op),
      "blessed_by" -> Json.fromString("jvm:sigma-state-6.0.7"),
      "entries"    -> Json.arr(entries: _*))
    Map(
      OpErgoTree    -> envelope(OpErgoTree, treeEntries),
      OpBox         -> envelope(OpBox, boxEntries),
      OpTransaction -> envelope(OpTransaction, txEntries))
  }

  def writeVectors(outDir: java.nio.file.Path): Unit =
    SpecExtract.writeStaging("AuthoredWireExplicitTypeArgs", extract(), outDir)
}
