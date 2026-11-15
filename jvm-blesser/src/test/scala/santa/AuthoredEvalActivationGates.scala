package santa

// Authored eval vectors for sigma-rust's santa-probe-activation-gates (2026-11-15). Measured first in
// santa.spike.ActivationGatesProbe; the reply is prompts/drafts/sigma-rust-activation-gates-reply.md.
//
// THE RULE, read in 6.0.7 and measured: two 4.x behaviours sit behind `VersionContext.isJitActivated`, which is
// `activatedVersion >= 2` (VersionContext.scala:25). It reads the ACTIVATED version, never the tree's:
//  - `xorOf` (CSigmaDslBuilder.scala:116-134): from the activation on, the exclusive or of the items (false for
//    none, the item for one); below it, `distinct.length == 2` ("holds both a true and a false"). The cost is the
//    same either way (`addSeqCost(XorOf.costKind, n)` around the call): Constant 5 + PerItemCost(20, 5, 32) = 30
//    for up to 32 items. So the separation is the VALUE.
//  - `BoolToSigmaProp.eval` (trees.scala:36-50): from the activation on, the operand is cast to `Boolean`, and a
//    `SigmaProp` operand throws `ClassCastException`; below it, a `SigmaProp` passes through unchanged.
// So a tree of version 0 or 1 evaluated at activated 2 or 3 takes the 5.x reading. An implementation that keys
// the gate on the TREE version ("old scripts keep old semantics") keeps the 4.x reading there: it returns another
// value on the SEPARATING xorOf rows and accepts the SigmaProp operands the JVM refuses.
//
// THE FAMILIES: `XorOf.activated_version_gate` (v5, activated 2) and `_v6` (activated 3), 9 inputs each at tree
// v0 then tree v1; `BoolToSigmaProp.activated_version_gate` (v5) and `_v6`, 6 operands each at tree v0 then v1.
// Every row of the request, on its own tree bytes (header 00 unsized, or 09 + size; constants inline). The xorOf
// inputs on which the two readings agree, `sigmaProp(true)`, and the operands both readings refuse (an Int; an
// out-of-bounds `ByIndex` on an empty `Coll[SigmaProp]`) are the CONTROLS beside the separating rows. Closed
// trees in the contract §2 context; var 1 = Int 0 is never read.
//
// BELOW THE ACTIVATION, the `v4` slot (activated 0 or 1): `BoolToSigmaProp.activated_version_gate_v4`, the same rows
// at (activated 0, tree 0), (1, 0) and (1, 1), where a SigmaProp operand passes through (cost 20 / 30 / 35). Mainnet
// history needs that reading: 12 trees created below the activation hold BoolToSigmaProp over a SigmaProp (one of
// them spent in 5fe235558... at height 680,692). xorOf's 4.x reading has no `v4` file: no code that ran at activated
// 0/1 on mainnet holds an XorOf node (no tree created at or below 889,855, and no code injected into a spend there;
// scanned 2026-11-15), so nothing can reach it. (Tree v1 at activated 0 is left out: the node refuses a tree above
// its activated version in its verifier, which the transaction tier grades; no eval entry carries that pair,
// runner-contract §2.)

import io.circe.Json
import scorex.util.encode.Base16

import sigma.{Colls, VersionContext}
import sigma.ast._
import sigma.crypto.CryptoConstants
import sigma.data.{CSigmaProp, ProveDlog}
import sigma.serialization.{SigmaSerializer, ValueSerializer}

object AuthoredEvalActivationGates {
  val V5: Byte = VersionContext.JitActivationVersion // 2
  val V6: Byte = VersionContext.V6SoftForkVersion    // 3
  val Source = "santa:authored-activation-gates"
  val Stamp = "jvm:sigma-state-6.0.7"
  val XorV5 = "XorOf.activated_version_gate"
  val XorV6 = "XorOf.activated_version_gate_v6"
  val B2spV5 = "BoolToSigmaProp.activated_version_gate"
  val B2spV6 = "BoolToSigmaProp.activated_version_gate_v6"
  /** The activated version per file. Every file holds tree v0 then tree v1. */
  val Activated: Map[String, Byte] = Map(XorV5 -> V5, B2spV5 -> V5, XorV6 -> V6, B2spV6 -> V6)
  val TreeVersions: Seq[Int] = Seq(0, 1)
  val B2spV4 = "BoolToSigmaProp.activated_version_gate_v4"
  /** The v4 file's (activated, tree version) pairs, in entry order: tree v0 at activated 0 and 1, then the v1 tree at
    * activated 1 (never above its activated version). */
  val V4Combos: Seq[(Byte, Int)] = Seq((0.toByte, 0), (1.toByte, 0), (1.toByte, 1))

  val IntInput: Json = Json.obj("kind" -> Json.fromString("Int"), "value" -> Json.fromInt(0))

  private def hx(bs: Array[Byte]): String = Base16.encode(bs).toLowerCase
  private def vlq(n: Int): String = { val w = SigmaSerializer.startWriter(); w.putUInt(n.toLong); hx(w.toBytes) }
  private def any(v: Value[_ <: SType]): Value[SType] = v.asInstanceOf[Value[SType]]
  private def bool(v: Value[SType]) = v.asInstanceOf[Value[SBoolean.type]]

  def body(root: Value[SType], a: Byte, t: Int): String = VersionContext.withVersions(a, t.toByte) {
    val w = SigmaSerializer.startWriter(); ValueSerializer.serialize(root, w); hx(w.toBytes)
  }
  /** Tree version 0: header 00, unsized. Tree version 1: header 09 (size bit), the size, the body. */
  def tree(root: Value[SType], a: Byte, t: Int): String = {
    val bd = body(root, a, t)
    t match {
      case 0 => "00" + bd
      case 1 => "09" + vlq(bd.length / 2) + bd
    }
  }

  /** The exclusive or (the reading from the activation on), and the 4.x reading below it. */
  def xorReading(bs: Seq[Boolean]): Boolean = bs.foldLeft(false)(_ ^ _)
  def v4Reading(bs: Seq[Boolean]): Boolean = bs.distinct.length == 2

  /** One xorOf row: an id, the items. SEPARATING when the two readings differ. */
  case class XorRow(id: String, items: Seq[Boolean]) {
    def separating: Boolean = xorReading(items) != v4Reading(items)
    def root: Value[SType] = any(XorOf(any(Constant[SCollection[SBoolean.type]](
      Colls.fromArray(items.toArray), SCollection(SBoolean))).asInstanceOf[Value[SCollection[SBoolean.type]]]))
    private def lit = items.map(_.toString).mkString("Coll(", ", ", ")")
    def script: String = {
      val x = xorReading(items); val v4 = v4Reading(items)
      val why = if (separating)
        s"SEPARATING: the 4.x reading (`distinct.length == 2`, below activated 2) gives $v4; an implementation " +
          "that gates on the TREE version keeps it here."
      else s"CONTROL: the 4.x reading gives $x too."
      s"`xorOf($lit)`, the Coll[Boolean] a constant — $x at cost 30, the exclusive or: the gate reads the " +
        s"ACTIVATED version, whatever the tree's. $why"
    }
    def slug: String = s"xorof-$id-${if (separating) "separating" else "control"}"
  }

  val XorRows: Seq[XorRow] = Seq(
    XorRow("tft", Seq(true, false, true)), XorRow("tt", Seq(true, true)), XorRow("tf", Seq(true, false)),
    XorRow("ttf", Seq(true, true, false)), XorRow("ttt", Seq(true, true, true)), XorRow("ff", Seq(false, false)),
    XorRow("t", Seq(true)), XorRow("f", Seq(false)), XorRow("empty", Seq()))

  private val G = CryptoConstants.dlogGroup.generator
  private val SpConst: Value[SType] = any(SigmaPropConstant(CSigmaProp(ProveDlog(G))))

  /** One BoolToSigmaProp row: an id, its operand, whether the JVM accepts from the activation on, whether that
    * separates, its script below the activation, and whether the JVM accepts there. */
  case class B2spRow(id: String, script: String, operand: Value[SType], accepts: Boolean, separating: Boolean,
                     below: String, acceptsBelow: Boolean) {
    def root: Value[SType] = any(BoolToSigmaProp(bool(operand)))
    def slug: String = s"booltosigmaprop-$id-${if (accepts) "accept" else "reject"}"
    def slugBelow: String = s"booltosigmaprop-$id-${if (acceptsBelow) "accept" else "reject"}"
  }

  private val sepB2sp = "SEPARATING: from activated 2 on the operand is cast to Boolean and a SigmaProp throws " +
    "(ClassCastException); below it a SigmaProp passes through, so an implementation that gates on the TREE version " +
    "ACCEPTS here."
  private val passB2sp = "the SigmaProp PASSES THROUGH: below the JIT activation `BoolToSigmaProp.eval` keeps the 4.x " +
    "branch for historical scripts. SEPARATING: from activated 2 on it throws, so an implementation that casts at " +
    "every activated version refuses here."
  val B2spRows: Seq[B2spRow] = Seq(
    B2spRow("true", "`sigmaProp(true)` — `TrivialProp.TrueProp` (d3) at cost 20 (Constant 5 + BoolToSigmaProp 15). " +
      "CONTROL: both readings accept.", any(TrueLeaf), accepts = true, separating = false,
      "`sigmaProp(true)` below the JIT activation — TrueProp (d3) at cost 20. CONTROL: both " +
        "readings accept.", acceptsBelow = true),
    B2spRow("sp-const", "`sigmaProp(<SigmaProp constant proveDlog(G)>)`, BoolToSigmaProp over a SigmaProp " +
      s"constant (`d1 08cd…`) — REFUSED. $sepB2sp", SpConst, accepts = false, separating = true,
      "`sigmaProp(<SigmaProp constant proveDlog(G)>)` below the JIT activation — " +
        s"`proveDlog(G)` at cost 20 (Constant 5 + BoolToSigmaProp 15): $passB2sp", acceptsBelow = true),
    B2spRow("provedlog", "`sigmaProp(proveDlog(G))`, BoolToSigmaProp over a CreateProveDlog node (`d1 cd07…`) — " +
      s"REFUSED. $sepB2sp", any(CreateProveDlog(GroupElementConstant(G))), accepts = false, separating = true,
      "`sigmaProp(proveDlog(G))` below the JIT activation — `proveDlog(G)` at cost 30 " +
        s"(GroupElement constant 5 + CreateProveDlog 10 + BoolToSigmaProp 15): $passB2sp", acceptsBelow = true),
    B2spRow("nested", "`sigmaProp(sigmaProp(<SigmaProp constant proveDlog(G)>))` (`d1 d1 08cd…`) — REFUSED: the " +
      s"inner node already throws. $sepB2sp", any(BoolToSigmaProp(bool(SpConst))), accepts = false, separating = true,
      "`sigmaProp(sigmaProp(<SigmaProp constant proveDlog(G)>))` below " +
        "the JIT activation — `proveDlog(G)` at cost 35, through both nodes (Constant 5 + 15 + 15): " + passB2sp,
      acceptsBelow = true),
    B2spRow("int", "`sigmaProp(<Int 1>)` (`d1 0402`) — REFUSED (ClassCastException Integer → Boolean). CONTROL: " +
      "both readings refuse.", any(IntConstant(1)), accepts = false, separating = false,
      "`sigmaProp(<Int 1>)` below the JIT activation — REFUSED (ClassCastException Integer → " +
        "Boolean: an operand that is no SigmaProp is cast on both branches). CONTROL: both readings refuse.",
      acceptsBelow = false),
    B2spRow("sp-throws", "`sigmaProp(Coll[SigmaProp]()(0))` (`d1 b2 830008 0400 00`) — REFUSED " +
      "(ArrayIndexOutOfBounds before BoolToSigmaProp is reached). CONTROL: both readings refuse.",
      any(ByIndex(ConcreteCollection(IndexedSeq[Value[SSigmaProp.type]](), SSigmaProp), IntConstant(0), None)),
      accepts = false, separating = false,
      "`sigmaProp(Coll[SigmaProp]()(0))` below the JIT activation — REFUSED " +
        "(ArrayIndexOutOfBounds before BoolToSigmaProp is reached). CONTROL: both readings refuse.",
      acceptsBelow = false))

  /** One entry to author: id, root, script, whether the JVM accepts, the name's slug. */
  private type Row = (String, Value[SType], String, Boolean, String)

  private def fileOf(op: String, combos: Seq[(Byte, Int)], rowsAt: (Byte, Int) => Seq[Row],
                     name: (String, Byte, Int, Int) => String): Json = {
    val entries = combos.flatMap { case (a, t) => rowsAt(a, t).map(r => (a, t, r)) }.zipWithIndex.map {
      case ((a, t, (id, root, script, accepts, slug)), i) =>
        val sc = s"[$id] $script  // tree v$t, activated $a"
        val hex = tree(root, a, t)
        if (accepts) SpecExtract.authoredEntryV(op, sc, hex, name(slug, a, t, i), IntInput, a, ergoTree = t)
        else SpecExtract.authoredRejectEntryV(op, sc, hex, name(slug, a, t, i), IntInput, a, ergoTree = t)
    }
    SpecExtract.authoredEnvelope(op, entries, Source).mapObject(_.add("blessed_by", Json.fromString(Stamp)))
  }
  /** v5/v6: the file's activated version at tree v0 then v1; a row reads the same at both. */
  private def fileAtActivation(op: String, rows: Seq[Row]): Json =
    fileOf(op, TreeVersions.map(t => (Activated(op), t)), (_, _) => rows, (slug, _, t, i) => s"$slug-t$t#$i")
  /** v4: every pair of V4Combos; a row reads the same at each. */
  private def fileBelowActivation(op: String, rows: Seq[Row]): Json =
    fileOf(op, V4Combos, (_, _) => rows, (slug, a, t, i) => s"$slug-a$a-t$t#$i")

  def extract(): Map[String, Json] = {
    val xor = XorRows.map(r => (r.id, r.root, r.script, true, r.slug))
    val b2sp = B2spRows.map(r => (r.id, r.root, r.script, r.accepts, r.slug))
    val b2spBelow = B2spRows.map(r => (r.id, r.root, r.below, r.acceptsBelow, r.slugBelow))
    Map(XorV5 -> fileAtActivation(XorV5, xor), XorV6 -> fileAtActivation(XorV6, xor),
      B2spV5 -> fileAtActivation(B2spV5, b2sp), B2spV6 -> fileAtActivation(B2spV6, b2sp),
      B2spV4 -> fileBelowActivation(B2spV4, b2spBelow))
  }

  def writeVectors(outDir: java.nio.file.Path): Unit =
    SpecExtract.writeStaging("AuthoredEvalActivationGates", extract(), outDir)
}
