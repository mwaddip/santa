package santa

// AuthoredTxSubstitutionCost: ergots' Deserialize substitution cost requests (its unsent
// prompts/santa-substitution-cost-requests-2026-10-01.md, taken 2026-11-27). Spends of trees with a Deserialize node,
// blessed through TxEngine.validateBytes at block version 4 (V6, activated 3) and block version 3 (before V6,
// activated 2), over the storage-rent synthetic context (AuthoredTxTreeVersion.Block4 / Block3).
//
// What the JVM charges (sigma-state v6.0.7, `Interpreter.scala`): a tree with a Deserialize node is reduced by
// reductionWithDeserialize, which first checks the tree's bytes x 2 (CostPerTreeByte) against the input's remaining
// limit. From V6 it adds that charge; before V6 it checks it and drops it. Each completed decode adds twice its byte
// length (deserializeMeasured). The evaluator then compares JitCost (10 per block-cost unit) with the remaining limit.
// The initial cost of every spend here is 12100 (10000 + 2000 for the input + 100 for the output), so "limit L" means
// maxBlockCost = 12100 + L.
//
// Every entry's expected verdict is ergots' probe figure; the blesser requires the JVM to give it.

import io.circe.Json
import scorex.util.encode.Base16
import sigma.VersionContext
import sigma.ast.{ByteArrayConstant, EvaluatedValue, SType}
import sigma.crypto.CryptoConstants.dlogGroup
import sigma.serialization.{ErgoTreeSerializer, SigSerializer}
import sigmastate.{FiatShamirTree, UncheckedSchnorr, UnprovenSchnorr}
import sigmastate.crypto.CryptoFunctions
import sigmastate.crypto.DLogProtocol.{DLogProver, DLogProverInput, FirstDLogProverMessage}
import sigmastate.crypto.VerifierMessage.Challenge
import scorex.crypto.authds.ADKey
import scorex.crypto.hash.Blake2b256
import sigma.interpreter.ProverResult
import org.ergoplatform.{ErgoBox, ErgoLikeTransaction, Input}
import org.ergoplatform.wallet.boxes.ErgoBoxSerializer
import org.ergoplatform.ErgoBox.{NonMandatoryRegisterId, R4}
import santa.runner.TxEngine

import RentFixtures._

object AuthoredTxSubstitutionCost {
  val V6Path = "transaction/v6/authored/deserialize-substitution-cost.json"
  val V5Path = "transaction/v5/authored/deserialize-substitution-cost.json"
  private val V3: Byte = VersionContext.V6SoftForkVersion
  private val V = 1000000000L
  private val H = AuthoredTxStorageRent.H
  private val Init = 12100L
  private type Val = EvaluatedValue[_ <: SType]
  private type Ctx = AuthoredTxTreeVersion.Ctx

  private def b(h: String): Array[Byte] = Base16.decode(h).get
  private def bytes(h: String): Option[Val] = Some(ByteArrayConstant(b(h)))

  private def scalar(label: String): java.math.BigInteger = new java.math.BigInteger(1, digest(label)).mod(dlogGroup.order)
  /** The generator's secret, 1: spend F's register decodes to ProveDlog(generator). */
  private val one = DLogProverInput(java.math.BigInteger.ONE)
  /** A Schnorr proof of `secret`'s key over `msg`; the nonce comes from `nonceLabel`. */
  private def schnorr(secret: DLogProverInput, msg: Array[Byte], nonceLabel: String): Array[Byte] = {
    val pk = secret.publicImage
    val r = scalar(nonceLabel)
    val a = FirstDLogProverMessage(dlogGroup.exponentiate(dlogGroup.generator, r))
    val leaf = UnprovenSchnorr(pk, Some(a), Some(r), None, simulated = false)
    val e = Challenge @@ sigma.Colls.fromArray(CryptoFunctions.hashFn(FiatShamirTree.toBytes(leaf)(null) ++ msg))
    SigSerializer.toProofBytes(UncheckedSchnorr(pk, Some(a), e, DLogProver.secondMessage(secret, r, e)))
  }

  /** The spent box's bytes: a box built at (3, 3) with R4 = `r4` if given, its tree bytes then replaced by `treeHex` as
    * written (a box built from a parsed tree writes the tree canonically, and request 4's trees are not canonical).
    * Its id hashes those bytes, and the node's own read of them (outside any version context, as from the UTXO set)
    * must give the same id. */
  private def spent(label: String, treeHex: String, r4: Option[Val]): (Array[Byte], Array[Byte]) = {
    val (built, canonicalTree) = VersionContext.withVersions(V3, V3) {
      val t = ErgoTreeSerializer.DefaultSerializer.deserializeErgoTree(b(treeHex))
      val regs = r4.map(v => Map[NonMandatoryRegisterId, Val](R4 -> v)).getOrElse(Map.empty[NonMandatoryRegisterId, Val])
      (box(s"santa:tx-substitution-cost:$label", V, 1, tree = t, regs = regs).bytes,
        ErgoTreeSerializer.DefaultSerializer.serializeErgoTree(t))
    }
    val at = vlqU32(V).length
    require(built.drop(at).startsWith(canonicalTree), s"$label: unexpected box layout ${hex(built)}")
    val raw = built.take(at) ++ b(treeHex) ++ built.drop(at + canonicalTree.length)
    val id = Blake2b256.hash(raw): Array[Byte]
    require(java.util.Arrays.equals(ErgoBoxSerializer.parseBytes(raw).id, id), s"$label: the node's read of ${hex(raw)} gives another id")
    (raw, id)
  }

  /** One spend into one output of the same value; input 0's extension {1: `var1`} or empty; a proof by `prover` if
    * given. The JVM's verdict must be `want`: Right(cost) or Left(a substring of the reason). */
  private def entry(c: Ctx, i: Int, slug: String, description: String, treeHex: String, r4: Option[Val] = None,
                    var1: Option[Val] = None, limit: Option[Long] = None, prover: Option[DLogProverInput] = None,
                    want: Either[String, Long]): Json = {
    val (raw, id) = spent(s"${c.blockVersion}:$i", treeHex, r4)
    val e = var1.map(v => ext(1.toByte -> v)).getOrElse(ext())
    def in(proof: Array[Byte]) = Input(ADKey @@ id, ProverResult(proof, e))
    val txHex = VersionContext.withVersions(V3, V3) {
      val unsigned = tx(Seq(in(Array.emptyByteArray)), Seq(candidate(V, 1)))
      val proof = prover.map(p => schnorr(p, unsigned.messageToSign, s"santa:tx-substitution-cost:nonce:${c.blockVersion}:$i"))
      hex(txBytes(tx(Seq(in(proof.getOrElse(Array.emptyByteArray))), Seq(candidate(V, 1)))))
    }
    val params = limit.fold(AuthoredTxStorageRent.params)(l =>
      AuthoredTxStorageRent.params.mapObject(_.add("maxBlockCost", Json.fromLong(Init + l))))
    val v = TxEngine.validateBytes(txHex, Seq(hex(raw)), Nil, c.headersHex, c.preHeader, params)
    val name = s"$slug#$i"
    want match {
      case Right(cost) => require(v.valid && v.cost.contains(cost),
        s"$name: want valid at $cost, got valid=${v.valid} cost=${v.cost} ${v.reason.getOrElse("")}")
      case Left(r) => require(!v.valid && v.reason.exists(_.contains(r)),
        s"$name: want invalid for '$r', got valid=${v.valid} cost=${v.cost} ${v.reason.getOrElse("")}")
    }
    Json.obj(
      "name"                 -> Json.fromString(name),
      "source"               -> Json.fromString("santa:authored-tx-substitution-cost"),
      "description"          -> Json.fromString(description),
      "tx_bytes_hex"         -> Json.fromString(txHex),
      "input_boxes_hex"      -> Json.arr(Json.fromString(hex(raw))),
      "data_input_boxes_hex" -> Json.arr(),
      "headers_hex"          -> Json.arr(c.headersHex.map(Json.fromString): _*),
      "preHeader"            -> c.preHeader,
      "parameters"           -> params,
      "context"              -> Json.obj("height" -> Json.fromInt(H)),
      "version"              -> Json.obj("activated" -> Json.fromInt(c.blockVersion - 1), "ergoTree" -> Json.fromInt(0)),
      "expected"             -> Json.obj(
        "valid"  -> Json.fromBoolean(v.valid),
        "cost"   -> v.cost.map(Json.fromLong).getOrElse(Json.Null),
        "reason" -> v.reason.map(Json.fromString).getOrElse(Json.Null)))
  }

  // ── the spends (ergots' names) ──────────────────────────────────────────────────────────────────────
  // deserialize-substitution-spend #3's tree (spend D), built from that blesser's pieces
  private val BI  = "b2" + "860204000400" + "0400" + "00"
  private val FBI = "b5" + BI + "d9010104" + "0101"
  private val GV  = "e4" + "e30161"
  private val D   = "00" + "d1" + "95" + "0100" + ("93" + "d5" + "04" + "61" + "01" + FBI + GV) + "0101"
  private val C   = "00d195010093d5046100e4e301610101" // deserialize-substitution-spend #0's tree
  private val B   = "00d40801"                         // executeFromVar[SigmaProp](1)
  private val I   = "00d1d5040100"                     // sigmaProp(DeserializeRegister(R4, Boolean))
  private val H1  = "00d193d5040400d40401"             // sigmaProp(DeserializeRegister(R4, Int) == DeserializeContext(Int, 1))
  private val H3  = "00d193d5040400d5040400"           // sigmaProp(DeserializeRegister(R4, Int) == DeserializeRegister(R4, Int))
  private val F   = "00d5040800"                       // DeserializeRegister(R4, SigmaProp) as the root
  private val G   = "00d1d5040101d40101"               // sigmaProp(DeserializeRegister(R4, Boolean, default = DeserializeContext(Boolean, 1)))
  private val FR4 = "08cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798" // ProveDlog(generator)
  private val NestedTuple = "8602d401050402"
  private val Nested = "00d193c163010008d3000001" + NestedTuple + "00" * 32 + "00" + "0502" // sigmaProp(box.value == 1L)

  private val note = "The input spends a box of value 1000000000 into one output of the same value. The initial cost is " +
    "12100; 'limit L' is maxBlockCost = 12100 + L, the input's remaining limit."
  private val v6charge = "From V6 reductionWithDeserialize adds the tree's bytes x 2 (Interpreter.scala, CostPerTreeByte)."
  private val v5charge = "Before V6 reductionWithDeserialize checks the tree's bytes x 2 against the limit and drops it; " +
    "each completed decode still adds twice its length."

  private def v6Entries: Seq[Json] = {
    val c = AuthoredTxTreeVersion.Block4
    Seq(
      // request 1: the two-decode spends at V6
      entry(c, 0, "h1-two-decodes-v6-accept",
        s"Spend H1: sigmaProp(DeserializeRegister(R4, Int) == DeserializeContext(Int, 1)), R4 = var 1 = Coll[Byte](04 02). " +
        s"Two decodes in one spend. $v6charge Valid at 12130. $note", H1, r4 = bytes("0402"), var1 = bytes("0402"),
        want = Right(Init + 30)),
      entry(c, 1, "h3-two-register-decodes-v6-accept",
        s"Spend H3: sigmaProp(DeserializeRegister(R4, Int) == DeserializeRegister(R4, Int)), R4 = Coll[Byte](04 02). Two " +
        s"decodes of one register. $v6charge Valid at 12132. $note", H3, r4 = bytes("0402"), want = Right(Init + 32)),
      // request 2: maxBlockCost boundaries at V6
      entry(c, 2, "c-limit-31-v6-reject",
        s"Spend C (deserialize-substitution-spend #0's tree) at limit 31: the tree charge, 32, is checked first and " +
        s"exceeds it. Invalid. $note", C, limit = Some(31), want = Left("Estimated execution cost 32 exceeds the limit 31")),
      entry(c, 3, "c-limit-32-v6-evaluator-reject",
        s"Spend C at limit 32: the tree charge passes, but the evaluator compares JitCost, 325 over 320. Invalid, although " +
        s"a block-cost sum would land exactly on the limit. $note", C, limit = Some(32), want = Left("CostLimitException")),
      entry(c, 4, "c-limit-35-v6-evaluator-reject",
        s"Spend C at limit 35, its block cost: the evaluator's JitCost, 355, is over 350. Invalid. $note", C,
        limit = Some(35), want = Left("CostLimitException")),
      entry(c, 5, "c-limit-36-v6-accept",
        s"Spend C at limit 36: valid at 12135. $note", C, limit = Some(36), want = Right(Init + 35)),
      entry(c, 6, "f-limit-79-v6-reject",
        "Spend F: the root is DeserializeRegister(R4, SigmaProp), R4 holding the 35 bytes of ProveDlog(generator); the " +
        "input carries a valid Schnorr proof for it. At limit 79 the decode's estimate, 80, exceeds it. Invalid. " +
        s"$note", F, r4 = bytes(FR4), limit = Some(79), prover = Some(one),
        want = Left("Estimated execution cost 80 exceeds the limit 79")),
      entry(c, 7, "f-limit-80-v6-evaluator-reject",
        s"Spend F at limit 80: the reduction is rejected in the evaluator, JitCost 805 over 800. Invalid. $note", F,
        r4 = bytes(FR4), limit = Some(80), prover = Some(one),
        want = Left("Estimated execution cost JitCost(805) exceeds the limit JitCost(800)")),
      entry(c, 8, "f-limit-81-v6-signature-reject",
        "Spend F at limit 81: the reduction (80) fits, but it reduces to ProveDlog(generator), and the proof's " +
        "verification is estimated before it runs: 398 more, 478 in all, over the limit. Invalid. A validator that " +
        s"charges the reduction alone accepts here at 80. $note", F, r4 = bytes(FR4), limit = Some(81), prover = Some(one),
        want = Left("Estimated execution cost 478 exceeds the limit 81")),
      entry(c, 9, "f-limit-477-v6-signature-reject",
        s"Spend F at limit 477: one short of the spend's 478. Invalid. $note", F, r4 = bytes(FR4), limit = Some(477),
        prover = Some(one), want = Left("Estimated execution cost 478 exceeds the limit 477")),
      entry(c, 10, "f-limit-478-v6-accept",
        s"Spend F at limit 478: valid at 12578 (the reduction's 80 and the signature check's 398). $note", F,
        r4 = bytes(FR4), limit = Some(478), prover = Some(one), want = Right(Init + 478)),
      entry(c, 11, "h1-limit-27-v6-reject",
        s"Spend H1 at limit 27: the second decode brings it to 28, over the limit. Invalid. $note", H1, r4 = bytes("0402"),
        var1 = bytes("0402"), limit = Some(27), want = Left("CostLimitException")),
      entry(c, 12, "h1-limit-28-v6-evaluator-reject",
        s"Spend H1 at limit 28: rejected in the evaluator. Invalid. $note", H1, r4 = bytes("0402"), var1 = bytes("0402"),
        limit = Some(28), want = Left("CostLimitException")),
      entry(c, 13, "h1-limit-31-v6-accept",
        s"Spend H1 at limit 31: valid at 12130. $note", H1, r4 = bytes("0402"), var1 = bytes("0402"), limit = Some(31),
        want = Right(Init + 30)),
      // request 3: a Deserialize node inside a default
      entry(c, 14, "default-deserialize-both-v6-accept",
        "sigmaProp(DeserializeRegister(R4, Boolean, default = DeserializeContext(Boolean, 1))) (00 d1 d5 04 01 01 d4 01 " +
        "01), R4 = var 1 = Coll[Byte](01 01). The rewrite goes bottom-up, so the default's DeserializeContext is decoded " +
        s"and charged before the register is looked at, then dropped. Valid at 12128 (18 + 4 + 4 + 2). $note", G,
        r4 = bytes("0101"), var1 = bytes("0101"), want = Right(Init + 28)),
      entry(c, 15, "default-deserialize-var-only-v6-accept",
        s"The same tree, R4 absent, var 1 = Coll[Byte](01 01): the default decodes and is used. Valid at 12124. $note", G,
        var1 = bytes("0101"), want = Right(Init + 24)),
      entry(c, 16, "default-deserialize-register-only-v6-accept",
        s"The same tree, R4 = Coll[Byte](01 01), var 1 absent. Valid at 12124. $note", G, r4 = bytes("0101"),
        want = Right(Init + 24)),
      entry(c, 17, "default-deserialize-empty-var-v6-reject",
        "The same tree, R4 = Coll[Byte](01 01), var 1 an empty Coll[Byte]: the default's decode, run although R4 is " +
        s"present, reads past the end. Invalid. $note", G, r4 = bytes("0101"), var1 = bytes(""),
        want = Left("ArrayIndexOutOfBoundsException")),
      // request 4: trees whose bytes are not their own re-encoding
      entry(c, 18, "tree-charge-canonical-v6-accept",
        "sigmaProp(DeserializeContext(Int, 1) == 1), var 1 = Coll[Byte](04 02): the 8-byte tree 00 d1 93 d4 04 01 04 02. " +
        s"$v6charge Valid at 12122. $note", "00d193d404010402", var1 = bytes("0402"), want = Right(Init + 22)),
      entry(c, 19, "tree-charge-overlong-int-v6-accept",
        "The same tree with the Int constant written 82 00 (9 bytes). The tree charge counts ErgoTree.bytes, the span " +
        s"the parser consumed, so the over-long VLQ counts at its written length. Valid at 12124. $note",
        "00d193d40401048200", var1 = bytes("0402"), want = Right(Init + 24)),
      entry(c, 20, "tree-charge-size-bit-v6-accept",
        s"The same tree with the size bit (08 07 …, 9 bytes). Valid at 12124. $note", "0807d193d404010402",
        var1 = bytes("0402"), want = Right(Init + 24)),
      entry(c, 21, "tree-charge-overlong-size-v6-accept",
        s"The same tree with its size written 87 00 (10 bytes). Valid at 12126. $note", "088700d193d404010402",
        var1 = bytes("0402"), want = Right(Init + 26)),
      // request 5: a Deserialize node inside a nested box's register
      entry(c, 22, "nested-box-register-deserialize-v6-accept",
        "sigmaProp(ExtractAmount(box) == 1L), where the Box constant's R4 is the Tuple expression " +
        "(DeserializeContext[Boolean](5), 1). The JVM parses it with hasDeserialize = true: the nested box's registers " +
        "are read outside the nested tree's save and restore of the reader's flag (ErgoTreeSerializer.scala:160-168). " +
        s"So the spend goes through reductionWithDeserialize: the 54-byte tree's 108, plus 3. Valid at 12211. $note",
        Nested, want = Right(Init + 111)),
      entry(c, 23, "nested-box-register-plain-v6-accept",
        s"The control: the nested box's R4 is (1, 1) (86 02 04 02 04 02). No Deserialize node: valid at 12103. $note",
        Nested.replace(NestedTuple, "860204020402"), want = Right(Init + 3)))
  }

  private def v5Entries: Seq[Json] = {
    val c = AuthoredTxTreeVersion.Block3
    Seq(
      // request 1: the spends before V6
      entry(c, 0, "c-before-v6-accept",
        s"Spend C (deserialize-substitution-spend #0's tree) at block version 3. $v5charge Valid at 12103. $note", C,
        want = Right(Init + 3)),
      entry(c, 1, "d-before-v6-accept",
        s"Spend D (deserialize-substitution-spend #3, as it is) at block version 3: its decode adds 34. Valid at 12137. " +
        s"$note", D, r4 = bytes(FBI), want = Right(Init + 37)),
      entry(c, 2, "b-before-v6-accept",
        s"Spend B: executeFromVar[SigmaProp](1) (00 d4 08 01), var 1 = Coll[Byte](08 d3). Valid at 12104. $note", B,
        var1 = bytes("08d3"), want = Right(Init + 4)),
      entry(c, 3, "i-before-v6-accept",
        s"Spend I: sigmaProp(DeserializeRegister(R4, Boolean)) (00 d1 d5 04 01 00), R4 = Coll[Byte](01 01). Valid at " +
        s"12106. $note", I, r4 = bytes("0101"), want = Right(Init + 6)),
      entry(c, 4, "h1-before-v6-accept",
        s"Spend H1, two decodes, at block version 3. Valid at 12110. $note", H1, r4 = bytes("0402"), var1 = bytes("0402"),
        want = Right(Init + 10)),
      entry(c, 5, "h3-before-v6-accept",
        s"Spend H3, two decodes of one register, at block version 3. Valid at 12110. $note", H3, r4 = bytes("0402"),
        want = Right(Init + 10)),
      // request 2: maxBlockCost boundaries before V6
      entry(c, 6, "c-limit-31-before-v6-reject",
        s"Spend C at limit 31: the tree charge, 32, is checked against the limit before it is dropped, so the spend that " +
        s"costs 3 is invalid. $note", C, limit = Some(31), want = Left("Estimated execution cost 32 exceeds the limit 31")),
      entry(c, 7, "c-limit-32-before-v6-accept",
        s"Spend C at limit 32: valid at 12103. $note", C, limit = Some(32), want = Right(Init + 3)),
      entry(c, 8, "f-limit-69-before-v6-reject",
        s"Spend F (with its valid Schnorr proof) at limit 69: the decode's estimate, 70, exceeds it. Invalid. $note", F,
        r4 = bytes(FR4), limit = Some(69), prover = Some(one), want = Left("Estimated execution cost 70 exceeds the limit 69")),
      entry(c, 9, "f-limit-70-before-v6-evaluator-reject",
        s"Spend F at limit 70: rejected in the evaluator, JitCost 705 over 700. Invalid. $note", F, r4 = bytes(FR4),
        limit = Some(70), prover = Some(one), want = Left("Estimated execution cost JitCost(705) exceeds the limit JitCost(700)")),
      entry(c, 10, "f-limit-71-before-v6-signature-reject",
        "Spend F at limit 71: the reduction fits, but the proof's verification is estimated at 398 more, 468 in all. " +
        s"Invalid. A validator that charges the reduction alone accepts here at 70. $note", F, r4 = bytes(FR4),
        limit = Some(71), prover = Some(one), want = Left("Estimated execution cost 468 exceeds the limit 71")),
      entry(c, 11, "f-limit-467-before-v6-signature-reject",
        s"Spend F at limit 467: one short of 468. Invalid. $note", F, r4 = bytes(FR4), limit = Some(467),
        prover = Some(one), want = Left("Estimated execution cost 468 exceeds the limit 467")),
      entry(c, 12, "f-limit-468-before-v6-accept",
        s"Spend F at limit 468: valid at 12568. $note", F, r4 = bytes(FR4), limit = Some(468), prover = Some(one),
        want = Right(Init + 468)),
      entry(c, 13, "h1-limit-19-before-v6-reject",
        s"Spend H1 at limit 19: the tree charge, 20, is checked and exceeds it. Invalid. $note", H1, r4 = bytes("0402"),
        var1 = bytes("0402"), limit = Some(19), want = Left("Estimated execution cost 20 exceeds the limit 19")),
      entry(c, 14, "h1-limit-20-before-v6-accept",
        s"Spend H1 at limit 20: valid at 12110. $note", H1, r4 = bytes("0402"), var1 = bytes("0402"), limit = Some(20),
        want = Right(Init + 10)),
      // request 3 before V6
      entry(c, 15, "default-deserialize-both-before-v6-accept",
        s"The default tree (00 d1 d5 04 01 01 d4 01 01), R4 = var 1 = Coll[Byte](01 01), at block version 3. Valid at " +
        s"12110. $note", G, r4 = bytes("0101"), var1 = bytes("0101"), want = Right(Init + 10)),
      // request 5 before V6
      entry(c, 16, "nested-box-register-deserialize-before-v6-accept",
        s"The nested-box tree whose Box's R4 holds (DeserializeContext[Boolean](5), 1), at block version 3: the tree " +
        s"charge is checked and dropped. Valid at 12103. $note", Nested, want = Right(Init + 3)))
  }

  private def envelope(es: Seq[Json]): Json = Json.obj(
    "schema"     -> Json.fromString("santa-transaction/v1"),
    "op"         -> Json.fromString("tx:authored:deserialize-substitution-cost"),
    "blessed_by" -> Json.fromString("jvm:ergo-core-6.0.8-validateStateful"),
    "entries"    -> Json.arr(es: _*))

  def blessAll(): Seq[(String, Json)] = Seq(V6Path -> envelope(v6Entries), V5Path -> envelope(v5Entries))

  def writeVectors(blessed: Seq[(String, Json)], vectorsRoot: java.nio.file.Path): Unit =
    blessed.foreach { case (rel, env) =>
      val f = vectorsRoot.resolve(rel)
      java.nio.file.Files.createDirectories(f.getParent)
      java.nio.file.Files.write(f, env.spaces2.getBytes(java.nio.charset.StandardCharsets.UTF_8))
    }
}
