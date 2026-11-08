package santa

// Authored wire vectors — a MethodCall / PropertyCall with an UNKNOWN type id, where something
// AFTER it fails. Request: `prompts/santa-probe-methodcall-type-id-order.md` (sigma-rust session,
// 2026-11-08; found by their review, pre-existing, not introduced by their degrade-gate flip).
//
// READ ORDER is the whole question, and it decides degrade-versus-reject.
// `MethodCallSerializer.parse` (`:47-56`) reads `typeId` as a RAW BYTE, then `methodId`, then `obj`,
// then `args`, then from v3 `assert(args.nonEmpty)` — and only THEN `SMethod.fromIds`, where an
// unknown type id raises rule 1010 (`CheckTypeWithMethods`), a ValidationException.
// `PropertyCallSerializer.parse` (`:30-34`) is the same without the args.
//
// So an unknown type id is NOT a parse-time refusal on the JVM: it rides as a byte until `fromIds`,
// and whatever fails in between wins. The degrade gate takes a ValidationException only
// (`ErgoTreeSerializer.scala:197`), so:
//
//   nothing fails before `fromIds`  -> rule 1010 -> a size-flagged tree DEGRADES, bytes echoed
//   something fails before it       -> that failure's class -> REJECTED, at any version
//
// An implementation that validates the type id the moment it reads the byte raises its own
// ValidationException-class error FIRST and degrades every one of these. Rows 3 and 5 are the pair
// that isolates exactly that: the SAME body, differing only in the header's version byte, where
// v3's args assert fires before `fromIds` and v1's does not.
//
// THREE failure classes here, none of them a ValidationException, and the first two carry no useful
// message at all:
//   `BufferUnderflowException`  the input ends at the method id (rows 1-2)
//   `AssertionError`            v3's `assert(args.nonEmpty)` — message "assertion failed" (row 3)
//   `SerializerException`       no data serializer for `SAny` (row 4), and separately the
//                               no-size-bit wrapper (row 6)
//
// NOT VECTORED, deliberately. The probe offered a known-type-id twin (`0x63` = SBox) as a reject
// control, reporting that sigma-rust rejects it. On the JVM it does not reject: at v1 it DEGRADES
// on rule 1001 (`CheckDeserializedScriptIsSigmaProp` — the root is a MethodCall, not a SigmaProp),
// an unrelated reason, so it is not a control for this question at all. On the LENIENT path it
// parses outright and round-trips NON-IDENTICALLY (`0905dc63017f00` -> `0905db63010101`: a zero-arg
// MethodCall is written back by the PropertyCall serializer, and `TrueLeaf` by the Boolean-constant
// one). Both of those canonicalizations are already pinned —
// `Box.tree_parse_acceptance`'s `box-v0-methodcall-no-args-propertycall-accept` and
// `box-c3-trueleaf-opcode-accept` — so there is nothing to add, only something to say in the reply.
//
// extract() re-derives every verdict through `WireCanonicalize` and asserts each reject's exception
// CLASS out of the cause chain.

import io.circe.Json
import scorex.util.encode.Base16
import sigma.VersionContext
import org.ergoplatform.ErgoBox

import RentFixtures._

object AuthoredWireMethodCallTypeIdOrder {
  val OpErgoTree    = "ErgoTree.methodcall_type_id_order"
  val OpBox         = "Box.methodcall_type_id_order"
  val OpTransaction = "Transaction.methodcall_type_id_order"
  val Source        = "santa:authored-methodcall-type-id-order"
  private val Activated: Byte = 3

  /** The body rows 3, 5 and 6 share: MethodCall, type id `ff`, method 1, obj `TrueLeaf`, no args. */
  val SharedBody = "dcff017f00"

  private def hx(bs: Array[Byte]): String = Base16.encode(bs)
  private def ctx[A](f: => A): A = VersionContext.withVersions(Activated, Activated)(f)
  private val PlainTree = "0008d3"

  private def replaceUnique(in: String, from: String, to: String): String = {
    val i = in.indexOf(from)
    require(i >= 0 && in.indexOf(from, i + 1) < 0, s"marker $from must occur exactly once")
    in.take(i) + to + in.drop(i + from.length)
  }
  private def boxWithTree(slug: String, treeHex: String): String = ctx {
    replaceUnique(hx(ErgoBox.sigmaSerializer.toBytes(box(s"santa:mctio:$slug", 1000000L, 1))),
      PlainTree, treeHex)
  }
  private def txWithTree(treeHex: String): String = ctx {
    val plain = hx(txBytes(tx(Seq(input(box("santa:mctio:spent", 1000000000L, 1), ext())),
      Seq(candidate(1000000000L, 1)))))
    replaceUnique(plain, PlainTree + "01" + "0000", treeHex + "01" + "0000")
  }

  private def version(treeVersion: Int): Json =
    Json.obj("activated" -> Json.fromInt(Activated.toInt), "ergoTree" -> Json.fromInt(treeVersion))

  private def entry(kind: String, name: String, description: String, in: String, treeVersion: Int,
                    extra: (String, Json)*): Json =
    Json.obj(Seq(
      "name" -> Json.fromString(name), "kind" -> Json.fromString(kind),
      "source" -> Json.fromString(Source), "description" -> Json.fromString(description),
      "bytes_hex" -> Json.fromString(in)) ++ extra ++ Seq("version" -> version(treeVersion)): _*)

  private def canonical(kind: String, in: String, treeVersion: Byte): String =
    WireCanonicalize.canonicalize(kind, in, Activated, treeVersion)

  private def accept(kind: String, name: String, description: String, in: String,
                     treeVersion: Byte): Json = {
    val out = canonical(kind, in, treeVersion)
    require(out == in, s"$name: the JVM must round-trip an accept vector to itself — in $in, out $out")
    entry(kind, name, description, in, treeVersion.toInt)
  }

  private def reject(kind: String, name: String, description: String, in: String,
                     treeVersion: Byte, wantClass: String): Json = {
    scala.util.Try(canonical(kind, in, treeVersion)) match {
      case scala.util.Success(out) => sys.error(s"$name: must be REFUSED, round-tripped to $out")
      case scala.util.Failure(t) =>
        val classes = Iterator.iterate(t: Throwable)(_.getCause).takeWhile(_ != null)
          .map(_.getClass.getName).mkString(" <- ")
        require(classes.contains(wantClass),
          s"$name: refused by the wrong exception (want $wantClass): $classes")
    }
    entry(kind, name, description, in, treeVersion.toInt, "error" -> Json.fromString("errored"))
  }

  /** One case: `tv` is the tree version its header declares, `carrier` whether the tree is complete
    * enough to sit inside a Box or a Transaction (the truncated rows are not). */
  private case class Row(slug: String, treeHex: String, tv: Byte, wantClass: Option[String],
                         carrier: Boolean, what: String)

  private val BUE = "BufferUnderflowException"

  private val rows: Seq[Row] = Seq(
    Row("ends-at-method-id-methodcall-v1", "0902dcff", 1.toByte, Some(BUE), false,
      "A `MethodCall` (`dc`) with type id `0xff`, where the input ENDS at the method id. The JVM " +
      "has not reached `SMethod.fromIds` — it has not even finished the header fields — so its " +
      "failure is a raw `BufferUnderflowException`, not rule 1010, and the size bit cannot degrade " +
      "it. REJECTED. **SEPARATING ROW:** an implementation that validates the type id at the byte " +
      "raises a ValidationException-class error instead and degrades, accepting a tree the JVM " +
      "refuses."),
    Row("ends-at-method-id-propertycall-v1", "0902dbff", 1.toByte, Some(BUE), false,
      "The same as a `PropertyCall` (`db`), whose parse is the MethodCall one minus the args. " +
      "REJECTED identically — the pair shows the read order is a property of both serializers and " +
      "not of the args handling. **SEPARATING ROW.**"),
    Row("v3-args-assert", "0b" + "05" + SharedBody, Activated, Some("AssertionError"), true,
      "Type id `0xff`, method 1, obj `TrueLeaf`, and NO arguments, size-flagged at v3. From v3 " +
      "`MethodCallSerializer.parse` runs `assert(args.nonEmpty)` BEFORE `SMethod.fromIds`, so the " +
      "JVM fails with an `AssertionError` (message: \"assertion failed\") and REJECTS. **THE " +
      "SEPARATING ROW, and the half of the pair that matters:** its v1 twin below has the same " +
      "body and DEGRADES. Nothing but the header's version byte differs, so a red here against a " +
      "green there is read order and nothing else."),
    Row("sany-obj-v1", "09" + "05" + "dcff016100", 1.toByte, Some("SerializerException"), true,
      "Type id `0xff`, method 1, and an obj that is a CONSTANT of type `SAny` (`61`). Reading that " +
      "constant's data fails first — `SerializerException: Not defined DataSerializer for type " +
      "SAny` — so again the JVM never reaches `fromIds`, and again REJECTS. **SEPARATING ROW**, " +
      "and a third distinct failure class."),
    Row("v1-degrades", "09" + "05" + SharedBody, 1.toByte, None, true,
      "THE CONTROL, and the row that makes the family mean something. The same body as the v3 row " +
      "above, size-flagged at v1, where there is no args assert — so NOTHING fails before " +
      "`SMethod.fromIds`, the unknown type id raises rule 1010 " +
      "(`CheckTypeWithMethods`, a ValidationException), and the size bit DEGRADES it: the tree is " +
      "kept as `UnparsedErgoTree` and the carrier round-trips to its own bytes. Both sides agree " +
      "here, which is the point — it is what shows a red on its v3 twin is about WHEN the type id " +
      "is checked, not about whether `0xff` is rejected at all."),
    Row("v1-unsized", "00" + SharedBody, 0.toByte, Some("SerializerException"), true,
      "The same body UNSIZED. Rule 1010 fires exactly as in the control, but with no size bit " +
      "there is nothing to degrade behind, so `deserializeErgoTree` wraps it: " +
      "`SerializerException: Cannot handle ValidationException, ErgoTree serialized without size " +
      "bit.` REJECTED. The third member of the degrade trio — it pins that the control's green is " +
      "the SIZE BIT's doing and not rule 1010 being harmless."))

  private def rowsFor(kind: String, suffix: String): Seq[Json] = {
    var i = -1
    def next(): Int = { i += 1; i }
    rows.filter(r => kind == "ErgoTree" || r.carrier).map { r =>
      val carrier = kind match {
        case "ErgoTree"    => r.treeHex
        case "Box"         => boxWithTree(s"${r.slug}-$suffix", r.treeHex)
        case "Transaction" => txWithTree(r.treeHex)
      }
      val name = s"methodcall-type-id-order-${r.slug}-$suffix#${next()}"
      r.wantClass match {
        case Some(cls) => reject(kind, name, r.what, carrier, r.tv, cls)
        case None      => accept(kind, name, r.what, carrier, r.tv)
      }
    }
  }

  def extract(): Map[String, Json] = {
    def envelope(op: String, entries: Seq[Json]): Json = Json.obj(
      "schema"     -> Json.fromString("santa-wire/v1"),
      "op"         -> Json.fromString(op),
      "blessed_by" -> Json.fromString("jvm:sigma-state-6.0.7"),
      "entries"    -> Json.arr(entries: _*))
    Map(
      OpErgoTree    -> envelope(OpErgoTree, rowsFor("ErgoTree", "tree")),
      OpBox         -> envelope(OpBox, rowsFor("Box", "box")),
      OpTransaction -> envelope(OpTransaction, rowsFor("Transaction", "tx")))
  }

  def writeVectors(outDir: java.nio.file.Path): Unit =
    SpecExtract.writeStaging("AuthoredWireMethodCallTypeIdOrder", extract(), outDir)
}
