package santa

// AuthoredTxKeptTreeBytes — the INPUT side of a parsed tree's kept bytes.
// Request: ~/projects/sigma-rust/prompts/santa-probe-kept-tree-bytes.md (2026-11-11, queue item 4),
// measured by `santa.spike.KeptTreeBytesProbe`. The OUTPUT side is already pinned by
// `sized-tree-output-bytes` and `rent-recreation-noncanonical-script-reject`.
//
// THE TWO READINGS (sigma-state 6.0.7):
// - `ErgoTree._bytes` (`ErgoTree.scala:123-130`) is the PARSE'S OWN SPAN, set at
//   `ErgoTreeSerializer.scala:178-185` from `r.position - startPos`. `propositionBytes`, R1, the
//   deserialize-substitution cost and `template` all read IT.
// - `ErgoTreeSerializer.serializeErgoTree` (`:105-121`) RE-WRITES from the AST. The box writer, the
//   box id, JSON and addresses read THAT. (`:112` is the exception: an `UnparsedErgoTree` hands back
//   its kept bytes, so for a degraded tree the two readings agree.)
// So a box READ FROM BYTES whose tree is not in the writer's own form reads one way and writes
// another, and an implementation that re-writes on parse answers every row here with the other one.
//
// WHY THE SPENT BOX'S OWN SCRIPT CAN TEST THIS AT ALL: the script cannot hold its own bytes as a
// constant (it would have to contain its own hash), so each row compares a WINDOW that the
// encoding moves — the header-and-count slice, or the LAST byte — against a constant. The constant
// sits inside the script, so its value changes the script's length: every row below is solved to a
// FIXED POINT (`stabilise`), and the solution is asserted stable.
//
// THE CONTROL THAT MAKES THE ROWS EVIDENCE: for each accept row, the SAME script spent over the
// CANONICAL encoding of itself must be INVALID — that is what shows the comparison reads the tree's
// own encoding rather than a constant that happens to match.

import io.circe.Json
import scorex.util.encode.Base16

import sigma.VersionContext
import sigma.ast.ErgoTree.{HeaderType, ZeroHeader}
import sigma.ast._
import sigma.ast.syntax.SigmaPropValue
import sigma.serialization.ErgoTreeSerializer.DefaultSerializer
import org.ergoplatform.ErgoBox
import santa.runner.TxEngine
import sigma.serialization.OpCodes

import RentFixtures._

object AuthoredTxKeptTreeBytes {
  val InputPath = "transaction/v6/authored/kept-tree-bytes-input.json"
  val SubstCostPath = "transaction/v6/authored/kept-tree-bytes-substitution-cost.json"

  private val A: Byte = VersionContext.V6SoftForkVersion // 3
  private val TV: Byte = 0
  private val V = 1000000000L
  private val H = AuthoredTxStorageRent.H

  private def bs(h: String): Array[Byte] = Base16.decode(h).get
  private def hx(b: Array[Byte]): String = Base16.encode(b).toLowerCase

  private def ser(t: ErgoTree): String = VersionContext.withVersions(A, A)(hx(DefaultSerializer.serializeErgoTree(t)))

  /** A box whose tree is `treeHex`, PARSED from bytes — so its tree keeps that span, and its id is
    * over those bytes. (A box built from a candidate has no parsed bytes and would keep the
    * writer's form, which is the whole distinction this file is about.) */
  private def boxWithTreeHex(label: String, treeHex: String): ErgoBox = VersionContext.withVersions(A, A) {
    val plain = hx(ErgoBox.sigmaSerializer.toBytes(box(label, V, 1)))
    val at = hx(vlqU32(V)).length
    require(plain.substring(at).startsWith("0008d3"), s"unexpected box layout $plain")
    ErgoBox.sigmaSerializer.fromBytes(bs(plain.take(at) + treeHex + plain.drop(at + 6)))
  }

  // ── the encodings, as surgery on the writer's own output ───────────────────────────────────
  /** `10 <count> …` -> `10 <count|0x80> 00 …`: the same count, in an overlong two-byte VLQ. */
  private def overlongCount(canonical: String): String = {
    val c = Integer.parseInt(canonical.substring(2, 4), 16)
    require(c < 0x80, s"constants count $c is already multi-byte in $canonical")
    "10" + f"${c | 0x80}%02x" + "00" + canonical.substring(4)
  }

  /** `18 <size> …` -> `18 <size+delta> …`: a size-flagged tree whose declared size is wrong. */
  private def declaredSize(canonical: String, delta: Int): String = {
    require(canonical.startsWith("18"), s"not size-flagged: $canonical")
    val n = Integer.parseInt(canonical.substring(2, 4), 16)
    require(n + delta > 0 && n + delta < 0x80, s"size $n + $delta leaves the one-byte VLQ range")
    "18" + f"${n + delta}%02x" + canonical.substring(4)
  }

  /** `18 <size> …` -> `18 <size in a FIVE-byte VLQ> …`: the same declared size, four bytes longer.
    * The declared size stays CORRECT, so nothing about the parse changes except the span kept. */
  private def fiveByteVlqSize(canonical: String): String = {
    require(canonical.startsWith("18"), s"not size-flagged: $canonical")
    val n = Integer.parseInt(canonical.substring(2, 4), 16)
    require(n < 0x80, s"size $n is already multi-byte")
    "18" + f"${n | 0x80}%02x" + "808080" + "00" + canonical.substring(4)
  }

  /** Replace the trailing two-byte Boolean constant with the one-byte legacy opcode the writer
    * never emits: `0101` -> `7f` (TrueLeaf), `0100` -> `80` (FalseLeaf). The kept span is one byte
    * SHORTER than the writer's form, which is the only direction where a re-writing implementation
    * reads MORE bytes than the JVM kept. */
  private def trailingShortForm(canonical: String, from: String, to: String): String = {
    require(canonical.endsWith(from), s"does not end with $from: $canonical")
    canonical.dropRight(from.length) + to
  }

  // ── the scripts ────────────────────────────────────────────────────────────────────────────
  private def treeOf(root: SigmaPropValue, size: Boolean, segregate: Boolean = true): ErgoTree =
    VersionContext.withVersions(A, A) {
      val h: HeaderType =
        if (size) ErgoTree.setSizeBit(ErgoTree.headerWithVersion(ZeroHeader, TV))
        else ErgoTree.headerWithVersion(ZeroHeader, TV)
      // The last-byte rows must NOT segregate: `withSegregation` lifts a Boolean constant into the
      // constants section, and then the TrueLeaf/FalseLeaf is no longer the body's last node.
      if (segregate) ErgoTree.withSegregation(h, root)
      else new ErgoTree(h, IndexedSeq.empty, Right(root))
    }

  /** `sigmaProp(<reader>.slice(0, n) == expected)`. */
  private def sliceScript(reader: Value[SCollection[SByte.type]], n: Int, expected: Array[Byte],
                          size: Boolean): ErgoTree =
    treeOf(BoolToSigmaProp(EQ(Slice(reader, IntConstant(0), IntConstant(n)), ByteArrayConstant(expected))), size)

  /** `sigmaProp(<reader>(<reader>.size - 1) == b)` — the LAST byte, which needs no fixed point on
    * the tree's LENGTH (each implementation indexes its own last byte). */
  private def lastByteScript(b: Byte, viaOr: Boolean): ErgoTree = {
    val r = ExtractScriptBytes(Self)
    val lastIndex = ArithOp[SInt.type](SizeOf(r), IntConstant(1), OpCodes.MinusCode)
    val check = EQ(ByIndex(r, lastIndex, None), ByteConstant(b))
    // `viaOr` keeps the proposition TRUE while putting a FalseLeaf last in the body
    val root = if (viaOr) BoolToSigmaProp(BinOr(check, FalseLeaf)) else BoolToSigmaProp(BinAnd(check, TrueLeaf))
    treeOf(root, size = false, segregate = false)
  }

  /** Solve `expected` = the window of the encoding of the very script that holds it. */
  private def stabilise(mk: Array[Byte] => ErgoTree, variant: String => String,
                        window: (String, String) => Array[Byte], n: Int): (String, String, Array[Byte]) = {
    var expected = Array.fill(n)(0.toByte)
    var canonical = ""
    var v = ""
    for (_ <- 1 to 12) {
      canonical = ser(mk(expected))
      v = variant(canonical)
      val next = window(canonical, v)
      if (next.sameElements(expected)) return (canonical, v, expected)
      expected = next
    }
    sys.error(s"kept-tree-bytes: did not stabilise (canonical=$canonical variant=$v)")
  }

  // ── blessing ───────────────────────────────────────────────────────────────────────────────
  private def validate(txHex: String, inputs: Seq[ErgoBox], dataInputs: Seq[ErgoBox]): TxEngine.Verdict =
    TxEngine.validateBytes(txHex, inputs.map(x => hx(x.bytes)), dataInputs.map(x => hx(x.bytes)),
      AuthoredTxStorageRent.headersHex, AuthoredTxStorageRent.preHeader, AuthoredTxStorageRent.params)

  private def spendOf(treeHex: String, label: String, dataInputs: Seq[ErgoBox]): (String, ErgoBox) =
    VersionContext.withVersions(A, A) {
      val bx = boxWithTreeHex(label, treeHex)
      val t = tx(Seq(input(bx, ext())), Seq(candidate(V, 1)))
      (hx(txBytes(t)), bx)
    }

  private def entry(i: Int, slug: String, description: String, treeHex: String, want: Boolean,
                    dataInputs: Seq[ErgoBox] = Nil, etv: Int = TV.toInt): Json = {
    val (txHex, bx) = spendOf(treeHex, s"santa:kept-tree-bytes:$slug", dataInputs)
    val v = validate(txHex, Seq(bx), dataInputs)
    require(v.valid == want,
      s"$slug: want valid=$want, got valid=${v.valid} ${v.reason.getOrElse("")}")
    Json.obj(
      "name"                 -> Json.fromString(s"$slug#$i"),
      "source"               -> Json.fromString("santa:authored-tx-kept-tree-bytes"),
      "description"          -> Json.fromString(description),
      "tx_bytes_hex"         -> Json.fromString(txHex),
      "input_boxes_hex"      -> Json.arr(Json.fromString(hx(bx.bytes))),
      "data_input_boxes_hex" -> Json.arr(dataInputs.map(x => Json.fromString(hx(x.bytes))): _*),
      "headers_hex"          -> Json.arr(AuthoredTxStorageRent.headersHex.map(Json.fromString): _*),
      "preHeader"            -> AuthoredTxStorageRent.preHeader,
      "parameters"           -> AuthoredTxStorageRent.params,
      "context"              -> Json.obj("height" -> Json.fromInt(H)),
      "version"              -> Json.obj("activated" -> Json.fromInt(A.toInt), "ergoTree" -> Json.fromInt(etv)),
      "expected"             -> Json.obj(
        "valid"  -> Json.fromBoolean(v.valid),
        "cost"   -> v.cost.map(Json.fromLong).getOrElse(Json.Null),
        "reason" -> v.reason.map(Json.fromString).getOrElse(Json.Null)))
  }

  private val note =
    "The spent box is PARSED from bytes, so its tree keeps this span (ErgoTreeSerializer.scala:178-185) " +
    "and its id is over it. An implementation that re-writes the tree on parse reads the writer's form " +
    "instead and answers the opposite way."

  /** One encoding -> three entries: the accept arm, its reject twin, and the canonical control. */
  private def triple(base: Int, slug: String, what: String, mk: Array[Byte] => ErgoTree,
                     variant: String => String, n: Int): Seq[Json] = {
    // accept: the script expects the bytes AS RECEIVED
    val (canonA, varA, expA) = stabilise(mk, variant, (_, v) => bs(v.take(n * 2)), n)
    // reject: the script expects the RE-WRITTEN bytes, so only a re-writing implementation accepts
    val (_, varR, expR) = stabilise(mk, variant, (c, _) => bs(c.take(n * 2)), n)
    Seq(
      entry(base, s"$slug-accept",
        s"$what The script compares the first $n bytes of `SELF.propositionBytes` against " +
        s"`${hx(expA)}` — the encoding AS RECEIVED — and the spend is VALID. $note", varA, want = true),
      entry(base + 1, s"$slug-rewritten-reject",
        s"The reject twin: the same shape, with the script expecting `${hx(expR)}` — the bytes the " +
        s"WRITER gives back for the same tree. The JVM reads the span it parsed, the comparison is " +
        s"false and the spend is INVALID. **SEPARATING:** an implementation that re-writes the tree " +
        s"on parse compares its own output, finds it equal, and ACCEPTS. $note", varR, want = false),
      entry(base + 2, s"$slug-canonical-control-reject",
        s"**CONTROL** for the accept arm: the SAME script (it expects `${hx(expA)}`) spent over the " +
        s"CANONICAL encoding of itself, `$canonA`. The window now holds the writer's bytes, so the " +
        s"comparison is false and the spend is INVALID. It is what shows the accept arm reads the " +
        s"tree's own encoding rather than a constant that happens to match.", canonA, want = false))
  }

  def inputEntries: Seq[Json] = VersionContext.withVersions(A, A) {
    val self = ExtractScriptBytes(Self)

    val countRows = triple(0, "self-propbytes-overlong-constants-count",
      "An overlong VLQ for the constants count (`81 00` for 1, `83 00` for 3): the same count in two " +
      "bytes instead of one, so the kept span is one byte LONGER than the writer's form.",
      exp => sliceScript(self, 3, exp, size = false), overlongCount, 3)

    val overRows = triple(10, "self-propbytes-declared-size-over",
      "A size-flagged tree whose declared size is one byte TOO LARGE. The parse does not use the " +
      "declared size to bound the span it keeps — it keeps `r.position - startPos`, the bytes the root " +
      "actually consumed — so the tree parses and the kept bytes carry the wrong size byte.",
      exp => sliceScript(self, 2, exp, size = true), c => declaredSize(c, +1), 2)

    val underRows = triple(20, "self-propbytes-declared-size-under",
      "The same one byte TOO SMALL. Measured, not assumed: it still PARSES (the declared size bounds " +
      "the reader's position limit, and the root here ends inside it), and the kept bytes carry the " +
      "wrong size byte just as the over-declared twin does.",
      exp => sliceScript(self, 2, exp, size = true), c => declaredSize(c, -1), 2)

    val trueLeaf = {
      val mk = (_: Array[Byte]) => lastByteScript(0x7f.toByte, viaOr = false)
      val canonical = ser(mk(Array.empty))
      val v = trailingShortForm(canonical, "0101", "7f")
      Seq(
        entry(30, "self-propbytes-last-byte-trueleaf-accept",
          "`7f` is `TrueLeaf`, which the parser accepts and the writer NEVER emits — it writes the " +
          "Boolean constant `0101` instead. So this is the one direction where a re-writing " +
          "implementation reads MORE bytes than the JVM kept: the kept span is one byte SHORTER. The " +
          "script reads the LAST byte of `SELF.propositionBytes` (which needs no fixed point on the " +
          s"length: each implementation indexes its own last byte) and requires `7f`: VALID. $note", v,
          want = true),
        entry(31, "self-propbytes-last-byte-trueleaf-rewritten-reject",
          "The reject twin, reached without changing the script: the SAME script spent over the " +
          s"CANONICAL encoding `$canonical`, whose last byte is `01` (the Boolean constant's value " +
          "byte). The comparison is false and the spend is INVALID. **SEPARATING:** an implementation " +
          "that re-writes reads `01` for BOTH encodings, so it rejects the accept arm above and " +
          "accepts nothing here — it is red on the pair, not on one row.", canonical, want = false))
    }

    val falseLeaf = {
      val mk = (_: Array[Byte]) => lastByteScript(0x80.toByte, viaOr = true)
      val canonical = ser(mk(Array.empty))
      val v = trailingShortForm(canonical, "0100", "80")
      Seq(
        entry(40, "self-propbytes-last-byte-falseleaf-accept",
          "The second lengthening form, which the probe did not ask for: `80` is `FalseLeaf`, written " +
          "back as `0100`. The proposition is `check || false` so that a FalseLeaf can sit LAST in the " +
          "body without making the script false. The script requires the last byte to be `80` " +
          s"(-128 as a signed byte): VALID. $note", v, want = true),
        entry(41, "self-propbytes-last-byte-falseleaf-rewritten-reject",
          "Its twin over the canonical encoding, whose last byte is `00`: INVALID. **SEPARATING** the " +
          "same way as the TrueLeaf pair, and together they say the class has two members rather than " +
          "one.", canonical, want = false))
    }

    countRows ++ overRows ++ underRows ++ trueLeaf ++ falseLeaf
  }

  // ── part 2: the deserialize-substitution cost reads the KEPT length ───────────────────────
  // `reductionWithDeserialize` charges `ergoTree.bytes.length * CostPerTreeByte` (2), so two
  // encodings of the SAME tree cost 2 per byte of difference. The substitution itself is
  // identical in both — only the span kept differs.
  private val substVarId: Byte = 1
  private val substVarBytes = "0402" // the serialized IntConstant(1) the node decodes to

  private def substTree: ErgoTree = VersionContext.withVersions(A, A) {
    treeOf(BoolToSigmaProp(EQ(DeserializeContext(substVarId, SInt), IntConstant(1))), size = true)
  }

  private def substSpend(treeHex: String, label: String): (String, ErgoBox) =
    VersionContext.withVersions(A, A) {
      val bx = boxWithTreeHex(label, treeHex)
      val t = tx(Seq(input(bx, ext(substVarId -> ByteArrayConstant(bs(substVarBytes))))),
        Seq(candidate(V, 1)))
      (hx(txBytes(t)), bx)
    }

  private def substEntry(i: Int, slug: String, description: String, treeHex: String): Json = {
    val (txHex, bx) = substSpend(treeHex, s"santa:kept-tree-bytes:$slug")
    val v = validate(txHex, Seq(bx), Nil)
    require(v.valid, s"$slug: want valid, got ${v.reason.getOrElse("")}")
    Json.obj(
      "name"                 -> Json.fromString(s"$slug#$i"),
      "source"               -> Json.fromString("santa:authored-tx-kept-tree-bytes"),
      "description"          -> Json.fromString(description),
      "tx_bytes_hex"         -> Json.fromString(txHex),
      "input_boxes_hex"      -> Json.arr(Json.fromString(hx(bx.bytes))),
      "data_input_boxes_hex" -> Json.arr(),
      "headers_hex"          -> Json.arr(AuthoredTxStorageRent.headersHex.map(Json.fromString): _*),
      "preHeader"            -> AuthoredTxStorageRent.preHeader,
      "parameters"           -> AuthoredTxStorageRent.params,
      "context"              -> Json.obj("height" -> Json.fromInt(H)),
      "version"              -> Json.obj("activated" -> Json.fromInt(A.toInt), "ergoTree" -> Json.fromInt(TV.toInt)),
      "expected"             -> Json.obj(
        "valid"  -> Json.fromBoolean(v.valid),
        "cost"   -> v.cost.map(Json.fromLong).getOrElse(Json.Null),
        "reason" -> v.reason.map(Json.fromString).getOrElse(Json.Null)))
  }

  def substCostEntries: Seq[Json] = VersionContext.withVersions(A, A) {
    val canonical = ser(substTree)
    val overlong  = fiveByteVlqSize(canonical)
    val delta = overlong.length / 2 - canonical.length / 2
    require(delta == 4, s"expected 4 extra bytes, got $delta")
    Seq(
      substEntry(0, "subst-cost-canonical-size-accept",
        s"A spend whose tree carries `DeserializeContext(1, Int)`, so the reduction goes through " +
        s"`reductionWithDeserialize` and is charged `ergoTree.bytes.length * CostPerTreeByte` (2) " +
        s"for the KEPT span. The canonical encoding is ${canonical.length / 2} bytes: `$canonical`. " +
        s"The context variable holds `$substVarBytes`, the serialized `IntConstant(1)`, so the node " +
        s"substitutes and the script reduces to `1 == 1`: VALID. The baseline for the twin below.",
        canonical),
      substEntry(1, "subst-cost-five-byte-vlq-size-accept",
        s"The SAME tree with the SAME declared size written as a FIVE-byte VLQ — still correct, just " +
        s"four bytes longer: `$overlong`. Nothing about the parse or the substitution changes; only " +
        s"the span the tree keeps. **SEPARATING ON COST:** the charge is per kept byte, so this " +
        s"spend costs exactly $delta x 2 = ${delta * 2} MORE than the canonical twin. An " +
        s"implementation that re-writes the tree on parse charges the canonical length for both and " +
        s"reports the SAME cost twice — a cost-only divergence, with both rows valid.",
        overlong))
  }

  private def envelope(op: String, es: Seq[Json]): Json = Json.obj(
    "schema"     -> Json.fromString("santa-transaction/v1"),
    "op"         -> Json.fromString(op),
    "blessed_by" -> Json.fromString(AuthoredTxStorageRent.BlessedBy),
    "entries"    -> Json.arr(es: _*))

  def blessAll(): Seq[(String, Json)] = Seq(
    InputPath     -> envelope("tx:authored:kept-tree-bytes-input", inputEntries),
    SubstCostPath -> envelope("tx:authored:kept-tree-bytes-substitution-cost", substCostEntries))

  def writeVectors(blessed: Seq[(String, Json)], vectorsRoot: java.nio.file.Path): Unit =
    blessed.foreach { case (rel, env) =>
      val f = vectorsRoot.resolve(rel)
      java.nio.file.Files.createDirectories(f.getParent)
      java.nio.file.Files.write(f, env.spaces2.getBytes(java.nio.charset.StandardCharsets.UTF_8))
    }
}
