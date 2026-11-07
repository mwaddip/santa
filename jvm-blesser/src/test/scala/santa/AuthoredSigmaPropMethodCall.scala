package santa

// ─────────────────────────────────────────────────────────────────────────────
// Authored vectors for sigma-rust's `santa-probe-sigmaprop-methods` (2026-11-07):
// `SSigmaPropMethods` ids 1 (`propBytes`) and 2 (`isProven`) written as PropertyCalls — they
// PARSE on the JVM and then throw at eval. sigma-rust has no SigmaProp method container at all,
// so it refuses both at parse: the same shape as `Coll` ids 1-10.
//
// THE RULE, confirmed exhaustively. `MethodCall.eval` dispatches on the method's COST KIND
// (`values.scala:1364-1390`): a `FixedCost` reaches `invokeFixed` — a `userDefinedInvoke` handler
// if there is one, otherwise a reflective call of the receiver's own method of that name — and any
// other cost kind goes to `genericMethod.evalMethod`, which looks up `<name>_eval` and throws
// `RuntimeException("Cannot find eval method def ...")` when it is absent (`SMethod.scala:119-159`).
// So a method evaluates through a MethodCall only if its cost kind is a FixedCost or it has an
// `_eval` handler. Enumerating every `MethodsContainer` at runtime at 6.0.7: **199 methods, of
// which 38 are neither** —
//
//   30  the numeric conversions `toByte`..`toBigInt` (ids 1-5) on each of SByte, SShort, SInt,
//       SLong, SBigInt and SUnsignedBigInt — cost kind `null`. Unreachable from the compiler,
//       which emits Upcast/Downcast nodes instead of MethodCalls.
//    6  SCollection exists 4, fold 5, forall 6, slice 7, filter 8, append 9 — PerItemCost, no
//       handler. Pinned by `Coll.methodcall_v5_ids`.
//    2  the two here: `propBytes` (PerItemCost) and `isProven` (cost kind `null`).
//
// `isProven`'s cost kind is a plain `null`, which still takes the `case _` branch — a type pattern
// does not match null — so it reaches the same missing-handler throw rather than an NPE.
//
// THE CONTROL IS THE POINT. `propBytes` also has a dedicated node, `SigmaPropBytes` (opcode 0xd0),
// and that one EVALUATES: SigmaProp `d3` at cost 115. So the two reject entries are not about
// `propBytes` being unimplemented — the same property, reached through its own opcode, works. It
// is the MethodCall encoding of it that the JVM parses and cannot evaluate.
// ─────────────────────────────────────────────────────────────────────────────

import io.circe.Json
import scorex.util.encode.Base16
import sigma.VersionContext
import sigma.ast.ErgoTree.{HeaderType, ZeroHeader}
import sigma.ast._

object AuthoredSigmaPropMethodCall {

  val V3: Byte = VersionContext.V6SoftForkVersion
  val V0: Byte = 0
  val OpEval = "SigmaProp.methodcall_ids"
  val OpWire = "ErgoTree.sigmaprop_methodcall_ids"
  val Source = "santa:authored-sigmaprop-methodcall"

  private val input = Json.obj("kind" -> Json.fromString("Int"), "value" -> Json.fromInt(0))

  private def any(v: Value[_ <: SType]): Value[SType] = v.asInstanceOf[Value[SType]]
  private def sp: Value[SSigmaProp.type] = BoolToSigmaProp(TrueLeaf)
  private def spm(name: String): SMethod = SSigmaPropMethods.getMethodByName(name)

  private def sizeGtZero(coll: Value[SType]): Value[SType] =
    any(BoolToSigmaProp(GT(SizeOf(coll.asInstanceOf[Value[SCollection[SType]]]), IntConstant(0))))

  /** `sigmaProp(sigmaProp(true).propBytes.size > 0)`, propBytes as a PropertyCall (db 08 01). */
  def callPropBytes: Value[SType] =
    sizeGtZero(any(MethodCall(any(sp), spm("propBytes"), IndexedSeq.empty, Map.empty)))

  /** `sigmaProp(sigmaProp(true).isProven)` (db 08 02). */
  def callIsProven: Value[SType] =
    any(BoolToSigmaProp(MethodCall(any(sp), spm("isProven"), IndexedSeq.empty, Map.empty)
      .asInstanceOf[Value[SBoolean.type]]))

  /** The same property through its DEDICATED node (0xd0), which does evaluate. */
  def callPropBytesNode: Value[SType] = sizeGtZero(any(SigmaPropBytes(sp)))

  def treeHex(root: Value[SType], etv: Byte, sized: Boolean): String =
    VersionContext.withVersions(V3, etv) {
      val base = ErgoTree.headerWithVersion(ZeroHeader, etv)
      val header: HeaderType = if (sized) ErgoTree.setSizeBit(base) else base
      val tree = new ErgoTree(header, IndexedSeq.empty,
        Right(root.asInstanceOf[sigma.ast.syntax.SigmaPropValue]))
      Base16.encode(sigma.serialization.ErgoTreeSerializer.DefaultSerializer.serializeErgoTree(tree))
    }

  /** SSigmaPropMethods is a v5 container, but getMethodByName wants a live context anyway. */
  private def inV6[A](f: => A): A = VersionContext.withVersions(V3, V3)(f)

  private val NoHandler = "PARSES and then throws at eval: `%s` has %s and no `%s_eval` handler, " +
    "so MethodCall.eval takes the non-FixedCost branch and genericMethod.evalMethod raises " +
    "RuntimeException(\"Cannot find eval method\") — values.scala:1364-1390, SMethod.scala:138-159. " +
    "sigma-rust has no SigmaProp method container at all and refuses this at PARSE instead, which " +
    "shows up on the wire arm."

  // ── eval arm ────────────────────────────────────────────────────────────────

  def extractEval(): Map[String, Json] = {
    val entries = Seq(
      SpecExtract.authoredRejectEntryV(OpEval,
        "{ sigmaProp(sigmaProp(true).propBytes.size > 0) }  // JVM: " +
          NoHandler.format("propBytes", "a PerItemCost cost kind", "propBytes"),
        treeHex(inV6(callPropBytes), V3, sized = true), "propbytes-propertycall-errored#0", input, V3, 3),
      SpecExtract.authoredRejectEntryV(OpEval,
        "{ sigmaProp(sigmaProp(true).isProven) }  // JVM: " +
          NoHandler.format("isProven", "a cost kind of plain `null`", "isProven") +
          " The null cost kind still takes the `case _` branch — a type pattern does not match " +
          "null — so this is the same missing-handler throw and not an NPE.",
        treeHex(inV6(callIsProven), V3, sized = true), "isproven-propertycall-errored#1", input, V3, 3),
      SpecExtract.authoredEntryV(OpEval,
        "{ sigmaProp(sigmaProp(true).propBytes.size > 0) with propBytes as the DEDICATED node }  " +
          "// JVM: the CONTROL — the same property reached through opcode 0xd0 (SigmaPropBytes) " +
          "EVALUATES, to SigmaProp d3. So #0 is not about propBytes being unimplemented: it is the " +
          "MethodCall encoding of it that cannot evaluate. A red here would mean the implementation " +
          "lacks the node too, and #0/#1 would stop meaning anything.",
        treeHex(inV6(callPropBytesNode), V3, sized = true), "propbytes-dedicated-node-accept#2", input, V3, 3))
    Map(OpEval -> SpecExtract.authoredEnvelope(OpEval, entries, Source)
      .mapObject(_.add("blessed_by", Json.fromString("jvm:sigma-state-6.0.7"))))
  }

  // ── wire arm ────────────────────────────────────────────────────────────────

  private def wireEntry(name: String, description: String, in: String, etv: Byte): Json = {
    val out = WireCanonicalize.canonicalize("ErgoTree", in, V3, etv)
    require(out == in, s"$name: an identity round-trip is the whole claim — in $in, out $out")
    Json.obj(
      "name"        -> Json.fromString(name),
      "kind"        -> Json.fromString("ErgoTree"),
      "source"      -> Json.fromString(Source),
      "description" -> Json.fromString(description),
      "bytes_hex"   -> Json.fromString(in),
      "version"     -> Json.obj("activated" -> Json.fromInt(V3.toInt),
                                "ergoTree"  -> Json.fromInt(etv.toInt)))
  }

  private val Sized = "Size-flagged (forced above tree version 0, ErgoTree.scala:104): an " +
    "implementation that has no SigmaProp method container DEGRADES this tree and echoes these " +
    "bytes, which a byte round-trip cannot tell from a real parse — the v0 unsized twin is what " +
    "separates them."
  private val Unsized = "UNSIZED, at tree version 0 because that is the only version where a tree " +
    "may omit the size flag. With no size flag a parse failure cannot degrade, so an implementation " +
    "without the SigmaProp container REJECTS outright and goes red here. This is the entry that " +
    "actually catches the missing method id."

  def extractWire(): Map[String, Json] = {
    val calls = Seq(("propBytes", 1, callPropBytes), ("isProven", 2, callIsProven))
    val base = (n: String, id: Int) =>
      s"SSigmaProp method id $id, `$n`, as a PropertyCall (db 08 0$id). The JVM resolves it, parses " +
      "the tree and re-encodes it byte-identically; it then has no `${n}_eval` handler and throws at " +
      "EVAL, which the eval arm pins separately."
    val sized = calls.zipWithIndex.map { case ((n, id, call), i) =>
      wireEntry(s"$n-v3-sized#$i", s"${base(n, id)} $Sized", treeHex(inV6(call), V3, sized = true), V3)
    }
    val unsized = calls.zipWithIndex.map { case ((n, id, call), i) =>
      wireEntry(s"$n-v0-unsized#${i + calls.length}", s"${base(n, id)} $Unsized",
        treeHex(inV6(call), V0, sized = false), V0)
    }
    val control = Seq(wireEntry(s"propBytes-v0-sized#${2 * calls.length}",
      "id 1 at tree version 0, SIZE-FLAGGED — the control separating the two dimensions. It shares " +
      "the v0 header family with the unsized entries and the size flag with the v3 ones, so a red " +
      "here with `propBytes-v3-sized#0` green would mean the VERSION matters to method resolution, " +
      "while a red on the unsized entries alone means only the SIZE FLAG does. " + Sized,
      treeHex(inV6(callPropBytes), V0, sized = true), V0))
    Map(OpWire -> Json.obj(
      "schema"     -> Json.fromString("santa-wire/v1"),
      "op"         -> Json.fromString(OpWire),
      "blessed_by" -> Json.fromString("jvm:sigma-state-6.0.7"),
      "entries"    -> Json.arr((sized ++ unsized ++ control): _*)))
  }

  def writeVectors(evalDir: java.nio.file.Path, wireDir: java.nio.file.Path): Unit = {
    SpecExtract.writeStaging("AuthoredSigmaPropMethodCall-eval", extractEval(), evalDir)
    SpecExtract.writeStaging("AuthoredSigmaPropMethodCall-wire", extractWire(), wireDir)
  }
}
