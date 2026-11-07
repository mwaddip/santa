package santa

// Authored wire vectors — rule 1020 `CheckZeroWidthCollection`, new in sigma-state 6.0.7.
//
// `CoreDataSerializer.deserializeColl` calls `CheckZeroWidthCollection(tpeElem)` when the element
// type is zero-width: `SUnit`, a collection of zero-width elements, or a tuple whose items are ALL
// zero-width (recursive). Zero-width elements consume no input bytes, so a declared length is not
// bounded by `CheckPositionLimit`. The rule is listed in both `ruleSpecsV5` and `ruleSpecsV6`, and
// measurement found no v5/v6 difference, so these entries are blessed once at the node's v6 context.
//
// The rule raises a ValidationException, so WHERE the value sits decides what happens:
//   size-flagged tree   the tree DEGRADES to UnparsedErgoTree and the carrier round-trips to its
//                       own bytes — a soft fork, like CheckValidOpCode
//   unsized tree        no degrade path: "Cannot handle ValidationException, ErgoTree serialized
//                       without size bit" — the carrier is REJECTED
//   register / context  parsed by CoreDataSerializer outside deserializeErgoTree's try, so the
//     extension value   exception escapes and the carrier is REJECTED, with no degrade
//
// Two negative controls must keep parsing: `Coll[(Unit, Int)]` (a tuple is zero-width only when
// EVERY item is) and a bare `SUnit` constant (the rule covers collection ELEMENTS, not SUnit).
//
// The JVM refuses to SERIALIZE a zero-width collection (`CoreDataSerializer.serialize` throws for
// the same shape in 6.0.7), so these constants cannot come from a round trip: the type bytes are
// produced by `TypeSerializer` (which has no such guard) and the data bytes are authored here.

import io.circe.Json
import scorex.util.encode.Base16
import sigma.VersionContext
import sigma.ast._
import sigma.serialization.{SigmaSerializer, TypeSerializer}
import org.ergoplatform.ErgoBox

import RentFixtures._

object AuthoredWireZeroWidthCollection {
  val V3: Byte = VersionContext.V6SoftForkVersion
  val OpBox         = "Box.zero_width_collection"
  val OpTransaction = "Transaction.zero_width_collection"
  val Source        = "santa:authored-zero-width-collection"

  private def b(h: String): Array[Byte] = Base16.decode(h).get
  private def vlq(n: Int): String = hex(vlqU32(n.toLong))

  /** Type bytes straight from the JVM's TypeSerializer — the data side is what refuses. */
  private def typeHex(tpe: SType): String = VersionContext.withVersions(V3, V3) {
    val w = SigmaSerializer.startWriter()
    new TypeSerializer().serialize(tpe, w)
    hex(w.toBytes)
  }

  /** One authored constant: its type bytes, its data bytes, and what it is. */
  private case class Const(name: String, what: String, typeB: String, dataB: String) {
    def bytes: String = typeB + dataB
  }

  private lazy val ZeroWidth: Seq[Const] = Seq(
    Const("coll-unit-3", "Coll[Unit] of declared length 3",
      typeHex(SCollection(SUnit)), vlq(3)),
    Const("coll-unit-0", "Coll[Unit]() — declared length 0, so no element is read at all",
      typeHex(SCollection(SUnit)), vlq(0)),
    Const("coll-coll-unit", "Coll[Coll[Unit]] — one outer element whose inner length is 2",
      typeHex(SCollection(SCollection(SUnit))), vlq(1) + vlq(2)),
    Const("coll-tuple-unit-unit", "Coll[(Unit, Unit)] — one element, a tuple of two zero-width items",
      typeHex(SCollection(STuple(SUnit, SUnit))), vlq(1)))

  private lazy val Controls: Seq[Const] = Seq(
    Const("coll-tuple-unit-int", "Coll[(Unit, Int)] — one element (Unit, Int 5); the tuple is NOT " +
      "wholly zero-width, so the rule does not fire",
      typeHex(SCollection(STuple(SUnit, SInt))), vlq(1) + "0a"),
    Const("bare-unit", "a bare SUnit constant — not a collection element, so the rule does not reach it",
      typeHex(SUnit), ""))

  // ── trees ────────────────────────────────────────────────────────────────────────────────────
  /** A v0 segregated tree whose constant 0 is `c`, unused by the body `placeholder(1)`.
    * `sized` sets the size bit, which is what turns a ValidationException into a degrade — the
    * sized and unsized headers differ in exactly that bit (0x18 vs 0x10).
    *
    * The trees are v0 because the pair has to be representable at ONE version: from version 1 up,
    * rule 1012 ("For version greater then 0, size bit should be set") refuses an unsized tree
    * before rule 1020 is ever reached, so a v3 unsized twin would reject for the wrong reason.
    * The entries are still read under the node's v6 parse context (activated 3), where 1020
    * applies — it is in both ruleSpecsV5 and ruleSpecsV6 and measurement found no version split. */
  private def treeCarrying(c: Const, sized: Boolean): String = {
    val payload = "02" + c.bytes + "08d3" + "7301" // 2 constants, then placeholder 1
    if (sized) "18" + vlq(payload.length / 2) + payload else "10" + payload
  }

  // ── carriers ─────────────────────────────────────────────────────────────────────────────────
  /** A marker constant that DOES serialize, so a real carrier can be built and then spliced. */
  private val MarkerHex = "0e08" + "a1b2c3d4e5f60718" // Coll[Byte] of 8 distinctive bytes

  private def replaceUnique(in: String, from: String, to: String): String = {
    val i = in.indexOf(from)
    require(i >= 0 && in.indexOf(from, i + 1) < 0, s"marker $from must occur exactly once in $in")
    in.take(i) + to + in.drop(i + from.length)
  }

  private def markerBox: ErgoBox = VersionContext.withVersions(V3, V3) {
    box("santa:zwc:box", 1000000L, 1, regs = Map(ErgoBox.R4 ->
      ByteArrayConstant(b("a1b2c3d4e5f60718")).asInstanceOf[Constant[SType]]))
  }

  /** A full ErgoBox whose TREE is `treeHex` (the box's own register stays a plain marker-free box). */
  private def boxWithTree(treeHex: String): String = VersionContext.withVersions(V3, V3) {
    val plain = hex(ErgoBox.sigmaSerializer.toBytes(box("santa:zwc:tree", 1000000L, 1)))
    replaceUnique(plain, "0008d3", treeHex)
  }

  /** A full ErgoBox whose REGISTER R4 is the authored constant. */
  private def boxWithRegister(c: Const): String =
    replaceUnique(hex(ErgoBox.sigmaSerializer.toBytes(markerBox)), MarkerHex, c.bytes)

  /** A transaction whose only output carries `treeHex`. */
  private def txWithTree(treeHex: String): String = VersionContext.withVersions(V3, V3) {
    val spent = box("santa:zwc:spent", 1000000000L, 1)
    val plain = hex(txBytes(tx(Seq(input(spent, ext())), Seq(candidate(1000000L, 1)))))
    // the output's tree is the only 0008d3 after the input section
    replaceUnique(plain, "0008d3" + "01" + "0000", treeHex + "01" + "0000")
  }

  /** A transaction whose input 0 carries the authored constant as context-extension var 1. */
  private def txWithExtension(c: Const): String = VersionContext.withVersions(V3, V3) {
    val spent = box("santa:zwc:spent", 1000000000L, 1)
    val marker = ByteArrayConstant(b("a1b2c3d4e5f60718")).asInstanceOf[Constant[SType]]
    val plain = hex(txBytes(tx(Seq(input(spent, ext(1.toByte -> marker))), Seq(candidate(1000000L, 1)))))
    replaceUnique(plain, MarkerHex, c.bytes)
  }

  // ── entries ──────────────────────────────────────────────────────────────────────────────────
  private def version: Json =
    Json.obj("activated" -> Json.fromInt(V3.toInt), "ergoTree" -> Json.fromInt(V3.toInt))

  private def canonical(kind: String, in: String): String =
    WireCanonicalize.canonicalize(kind, in, V3, V3)

  private def entry(kind: String, name: String, description: String, in: String,
                    extra: (String, Json)*): Json =
    Json.obj(Seq(
      "name" -> Json.fromString(name), "kind" -> Json.fromString(kind),
      "source" -> Json.fromString(Source), "description" -> Json.fromString(description),
      "bytes_hex" -> Json.fromString(in)) ++ extra ++ Seq("version" -> version): _*)

  /** The JVM must round-trip these bytes to themselves (a degrade still re-serializes verbatim). */
  private def accept(kind: String, name: String, description: String, in: String): Json = {
    val out = canonical(kind, in)
    require(out == in, s"$name: the JVM must round-trip an accept vector to itself — in $in, out $out")
    entry(kind, name, description, in)
  }

  /** The JVM must throw at parse, and mention rule 1020. */
  private def reject(kind: String, name: String, description: String, in: String): Json = {
    scala.util.Try(canonical(kind, in)) match {
      case scala.util.Success(out) => sys.error(s"$name: must REJECT at parse, round-tripped to $out")
      case scala.util.Failure(t) =>
        val causes = Iterator.iterate(t: Throwable)(_.getCause).takeWhile(_ != null)
          .map(c => s"${c.getClass.getName}: ${c.getMessage}").toList
        require(causes.exists(_.contains("1020")) || causes.exists(_.contains("zero-width")),
          s"$name: rejected for the wrong reason: ${causes.mkString(" <- ")}")
    }
    entry(kind, name, description, in, "error" -> Json.fromString("errored"))
  }

  /** `valueSlug` names the entry, `valueWhat` reads in its description — they are not the same
    * string: an entry name is a slug other sessions copy verbatim. */
  private def rows(kind: String, carryTree: String => String,
                   carryValue: Const => String, valueSlug: String, valueWhat: String): Seq[Json] = {
    val degrades = ZeroWidth.zipWithIndex.map { case (c, i) =>
      accept(kind, s"zero-width-sized-${c.name}#$i",
        s"A size-flagged tree whose segregated constant 0 is ${c.what}. Rule 1020 raises a " +
        "ValidationException, the size bit turns it into a degrade, and the tree becomes " +
        "UnparsedErgoTree: the carrier parses and re-serializes to its own bytes. An " +
        "implementation without the rule parses the constant instead and round-trips the same " +
        "bytes — so this entry is green either way, and the UNSIZED twin is what separates them.",
        carryTree(treeCarrying(c, sized = true)))
    }
    val unsized = reject(kind, s"zero-width-unsized-coll-unit-3#${ZeroWidth.size}",
      s"The same ${ZeroWidth.head.what} in an UNSIZED tree (no size bit). There is no degrade " +
      "path, so the ValidationException surfaces as a SerializerException and the carrier is " +
      "REJECTED. An implementation that accepts a zero-width collection parses it and " +
      "round-trips: the over-accept.",
      carryTree(treeCarrying(ZeroWidth.head, sized = false)))
    val controls = Controls.zipWithIndex.map { case (c, i) =>
      accept(kind, s"zero-width-control-${c.name}#${ZeroWidth.size + 1 + i}",
        s"Negative control: a size-flagged tree whose constant 0 is ${c.what}. The tree PARSES — " +
        "it does not degrade — so an implementation that over-applies the rule rejects this and " +
        "the family catches it.",
        carryTree(treeCarrying(c, sized = true)))
    }
    val valueRow = reject(kind, s"zero-width-$valueSlug-coll-unit-3#${ZeroWidth.size + 3}",
      s"${ZeroWidth.head.what} as $valueWhat, not inside a tree. It is read by " +
      "CoreDataSerializer outside deserializeErgoTree's try, so the ValidationException escapes " +
      "and the carrier is REJECTED with no degrade. An implementation without the rule accepts it.",
      carryValue(ZeroWidth.head))
    degrades ++ Seq(unsized) ++ controls ++ Seq(valueRow)
  }

  def extract(): Map[String, Json] = {
    def envelope(op: String, entries: Seq[Json]): Json = Json.obj(
      "schema"     -> Json.fromString("santa-wire/v1"),
      "op"         -> Json.fromString(op),
      "blessed_by" -> Json.fromString("jvm:sigma-state-6.0.7"),
      "entries"    -> Json.arr(entries: _*))
    Map(
      OpBox         -> envelope(OpBox,
        rows("Box", boxWithTree, boxWithRegister, "register", "a box register R4")),
      OpTransaction -> envelope(OpTransaction,
        rows("Transaction", txWithTree, txWithExtension, "extension", "a context-extension value")))
  }

  def writeVectors(outDir: java.nio.file.Path): Unit =
    SpecExtract.writeStaging("AuthoredWireZeroWidthCollection", extract(), outDir)
}
