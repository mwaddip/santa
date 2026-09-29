package santa

// AuthoredTxSizedTreeRequests — the transaction part of ergots' sized-tree requests (2026-09-28). Blessed through
// TxEngine.validateBytes (ergo-core 6.0.6 validateStateful) under the storage-rent vectors' synthetic context
// (AuthoredTxStorageRent: ten v4 headers below H = 1051200, the launch parameters).
//
// 1. Spend verdicts (sized-tree-spend).
//    - A spend evaluates the box's ingest-parsed tree. A size-flagged tree that degraded because its root is not a
//      SigmaProp (rule 1001, not a soft fork) throws during interpretation. The JVM builds If without comparing its
//      branch types, and types it as its true branch. So sized If(false, Int 1, sigmaProp(true)) degrades and does not
//      spend, while a lenient parser evaluates it to sigmaProp(true).
//    - The JVM parses CAND(), COR() and CTHRESHOLD(0, []) (wire: SigmaBoolean.conjecture_bounds). A leafless CAND() or
//      CTHRESHOLD(0, []) spends with a 24-byte proof: its Fiat-Shamir challenge, which needs no secret (the JVM's own
//      prover makes it). With no proof it does not spend, and neither do COR(), CAND([TrueProp]) or
//      CTHRESHOLD(0, [TrueProp]). A trivial child does not make the conjecture trivial.
//    - The SigmaAnd and SigmaOr NODES (ea, eb) are not constants: they evaluate through allZK / anyZK, which call
//      CAND.normalized / COR.normalized (`CSigmaDslBuilder.scala:134-142`). Both require a non-empty list
//      (`SigmaBoolean.scala:165`, `:201`), so an empty node parses (wire: tree_sigmaboolean_bounds) but fails to
//      evaluate, while SigmaAnd of 256 sigmaProp(true) normalizes to TrueProp and spends with no proof (sigma-rust's
//      source findings, 2026-09-29).
// 2. Output bytes (sized-tree-output-bytes). An output declares its tree's size wrongly: 3 or 1 for the 2-byte body
//    08 d3. Inside the creating transaction:
//    - OUTPUTS(0).propositionBytes is the tree as received (ErgoTree.bytes, the parser's span);
//    - OUTPUTS(0).bytes is re-encoded with the true size, since the output box is built from its candidate and has no
//      parsed bytes, and so is OUTPUTS(0).id = blake2b256(bytes);
//    - the transaction's signing message is re-encoded too (bytesToSign).
//    The spent box's script checks the first three and holds proveDlog(pk); its Schnorr proof is built here
//    deterministically (fixed secret and nonce, Fiat-Shamir over the JVM's messageToSign), which pins the signing
//    message. The reject twins expect the wrong basis; they carry the same valid signature, so an impl with that basis
//    reduces the script to pk and accepts.

import io.circe.Json
import scorex.util.encode.Base16
import sigma.VersionContext
import sigma.ast.{BinAnd, BoolToSigmaProp, ByIndex, ByteArrayConstant, CalcBlake2b256, EQ, ErgoTree, ExtractBytes,
  ExtractId, ExtractScriptBytes, IntConstant, Outputs, SigmaAnd, SigmaPropConstant, Slice}
import sigma.crypto.CryptoConstants.dlogGroup
import sigma.data.AvlTreeData
import sigma.serialization.{ErgoTreeSerializer, SigSerializer}
import sigmastate.{FiatShamirTree, UncheckedSchnorr, UnprovenSchnorr}
import sigmastate.crypto.CryptoFunctions
import sigmastate.crypto.DLogProtocol.{DLogProver, DLogProverInput, FirstDLogProverMessage}
import sigmastate.crypto.VerifierMessage.Challenge
import sigmastate.helpers.{ContextEnrichingTestProvingInterpreter, ErgoLikeContextTesting}
import sigmastate.interpreter.HintsBag
import org.ergoplatform.{ErgoBox, ErgoLikeTransaction}
import santa.runner.TxEngine

import RentFixtures._

object AuthoredTxSizedTreeRequests {
  val SpendPath       = "transaction/v6/authored/sized-tree-spend.json"
  val OutputBytesPath = "transaction/v6/authored/sized-tree-output-bytes.json"
  private val V3: Byte = VersionContext.V6SoftForkVersion
  private val Activated = 3
  private val ErgoTreeV = 0
  private val V = 1000000000L                // the spent box's value
  private val H = AuthoredTxStorageRent.H

  private def b(h: String): Array[Byte] = Base16.decode(h).get
  private def scalar(label: String): java.math.BigInteger = new java.math.BigInteger(1, digest(label)).mod(dlogGroup.order)

  /** A box whose tree is `treeHex`: a plain box's bytes with the tree swapped, then parsed (its id is over them). */
  private def boxWithTree(label: String, treeHex: String): ErgoBox = VersionContext.withVersions(V3, V3) {
    val plain = hex(ErgoBox.sigmaSerializer.toBytes(box(label, V, 1)))
    val at = hex(vlqU32(V)).length
    require(plain.substring(at).startsWith("0008d3"), s"unexpected box layout $plain")
    ErgoBox.sigmaSerializer.fromBytes(b(plain.take(at) + treeHex + plain.drop(at + 6)))
  }

  private def validate(txHex: String, inputs: Seq[ErgoBox]): TxEngine.Verdict =
    TxEngine.validateBytes(txHex, inputs.map(x => hex(x.bytes)), Nil, AuthoredTxStorageRent.headersHex,
      AuthoredTxStorageRent.preHeader, AuthoredTxStorageRent.params)

  /** Bless one entry: the JVM's verdict must be `want`. */
  private def entry(name: String, arm: String, description: String, txHex: String, inputs: Seq[ErgoBox],
                    want: Boolean): Json = {
    val v = validate(txHex, inputs)
    require(v.valid == want, s"$name: want valid=$want, got valid=${v.valid} ${v.reason.getOrElse("")}")
    Json.obj(
      "name"                 -> Json.fromString(name),
      "source"               -> Json.fromString(s"santa:authored-tx-sized-tree-requests:$arm"),
      "description"          -> Json.fromString(description),
      "tx_bytes_hex"         -> Json.fromString(txHex),
      "input_boxes_hex"      -> Json.arr(inputs.map(x => Json.fromString(hex(x.bytes))): _*),
      "data_input_boxes_hex" -> Json.arr(),
      "headers_hex"          -> Json.arr(AuthoredTxStorageRent.headersHex.map(Json.fromString): _*),
      "preHeader"            -> AuthoredTxStorageRent.preHeader,
      "parameters"           -> AuthoredTxStorageRent.params,
      "context"              -> Json.obj("height" -> Json.fromInt(H)),
      "version"              -> Json.obj("activated" -> Json.fromInt(Activated), "ergoTree" -> Json.fromInt(ErgoTreeV)),
      "expected"             -> Json.obj(
        "valid"  -> Json.fromBoolean(v.valid),
        "cost"   -> v.cost.map(Json.fromLong).getOrElse(Json.Null),
        "reason" -> v.reason.map(Json.fromString).getOrElse(Json.Null)))
  }

  // ── 1. spend verdicts ─────────────────────────────────────────────────────────────────────────────
  /** The JVM prover's proof for a leafless conjecture: its Fiat-Shamir challenge. No secret, no randomness. */
  private def leaflessProof(bx: ErgoBox): Array[Byte] = VersionContext.withVersions(V3, V3) {
    val t = tx(Seq(input(bx, ext())), Seq(candidate(V, 1)))
    val ctx = ErgoLikeContextTesting(currentHeight = H, lastBlockUtxoRoot = AvlTreeData.dummy,
      minerPubkey = Array.fill(33)(2.toByte), boxesToSpend = IndexedSeq(bx), spendingTransaction = t, self = bx,
      activatedVersion = V3)
    val proof = new ContextEnrichingTestProvingInterpreter()
      .prove(Map.empty[String, Any], bx.ergoTree, ctx, t.messageToSign, HintsBag.empty).get.proof
    require(proof.length == 24, s"a leafless conjecture's proof is its 24-byte challenge, got ${proof.length}")
    proof
  }

  private def spendEntry(i: Int, slug: String, description: String, treeHex: String, proof: ErgoBox => Array[Byte],
                         want: Boolean): Json = {
    val bx = boxWithTree(s"santa:str:spend:$i", treeHex)
    val txHex = VersionContext.withVersions(V3, V3) { hex(txBytes(tx(Seq(input(bx, ext(), proof(bx))), Seq(candidate(V, 1))))) }
    entry(s"$slug#$i", "spend", description, txHex, Seq(bx), want)
  }

  private def spendEntries: Seq[Json] = {
    val none: ErgoBox => Array[Byte] = _ => Array.emptyByteArray
    val spendNote = "The input spends a box of value 1000000000 into one output of the same value; no proof unless noted."
    Seq(
      spendEntry(0, "if-rule-1001-degraded-spend-reject",
        s"The spent box's tree is the size-flagged v0 tree If(false, Int 1, sigmaProp(true)) (08 07 95 01 00 04 02 08 d3). " +
        "The JVM builds If without comparing its branch types and types it as its true branch, Int, so rule 1001 degrades " +
        "the tree at ingest. A spend interprets that UnparsedErgoTree, whose error is not a soft fork, and throws: the " +
        s"transaction is invalid. An impl that parses the tree leniently evaluates it to sigmaProp(true) and accepts. $spendNote",
        "0807" + "95" + "0100" + "0402" + "08d3", none, want = false),
      spendEntry(1, "if-true-branch-sigmaprop-accept",
        s"The twin: If(true, sigmaProp(true), Int 1), size-flagged. Typed as its true branch, a SigmaProp, it parses, and " +
        s"it evaluates to sigmaProp(true): valid. $spendNote",
        "0807" + "95" + "0101" + "08d3" + "0402", none, want = true),
      spendEntry(2, "if-false-branch-int-reject",
        s"If(false, sigmaProp(true), Int 1), size-flagged: it parses (typed as the SigmaProp branch) but evaluates to Int 1, " +
        s"not a SigmaProp: invalid. An impl that type-checks If's branches at parse rejects the box instead. $spendNote",
        "0807" + "95" + "0100" + "08d3" + "0402", none, want = false),
      spendEntry(3, "cand-empty-fiat-shamir-proof-accept",
        s"The spent box's tree is the SigmaProp constant CAND() (00 08 96 00), and the input carries the JVM prover's proof: " +
        "24 bytes, the conjecture's Fiat-Shamir challenge over the transaction's message, which needs no secret. The JVM " +
        s"verifies it: valid. An impl that normalizes CAND() to TrueProp, or rejects it at parse, diverges. $spendNote",
        "00" + "08" + "9600", leaflessProof, want = true),
      spendEntry(4, "cand-empty-no-proof-reject",
        s"The same CAND() box with no proof: CAND() is not TrueProp, so an empty proof fails: invalid. An impl that " +
        s"normalizes CAND() to TrueProp accepts it. $spendNote",
        "00" + "08" + "9600", none, want = false),
      spendEntry(5, "cthreshold-k0-empty-fiat-shamir-proof-accept",
        s"CTHRESHOLD(0, []) (00 08 98 00 00) with the JVM prover's 24-byte proof: valid, as CAND(). $spendNote",
        "00" + "08" + "980000", leaflessProof, want = true),
      spendEntry(6, "cthreshold-k0-empty-no-proof-reject",
        s"CTHRESHOLD(0, []) with no proof: invalid. $spendNote",
        "00" + "08" + "980000", none, want = false),
      spendEntry(7, "cor-empty-no-proof-reject",
        s"COR() (00 08 97 00) with no proof: invalid. (The JVM's own prover cannot prove COR(): 'Tree root should be real'.) " +
        spendNote, "00" + "08" + "9700", none, want = false),
      spendEntry(8, "cand-trueprop-child-no-proof-reject",
        s"CAND([TrueProp]) (00 08 96 01 d3) as a constant, with no proof: a trivial child does not make the conjecture " +
        "trivial; the constant is not reduced, and an empty proof fails: invalid. An impl that normalizes it to TrueProp " +
        s"accepts. $spendNote", "00" + "08" + "9601" + "d3", none, want = false),
      spendEntry(9, "cthreshold-k0-trueprop-child-no-proof-reject",
        s"CTHRESHOLD(0, [TrueProp]) (00 08 98 00 01 d3) with no proof: invalid. $spendNote",
        "00" + "08" + "9800" + "01" + "d3", none, want = false),
      spendEntry(10, "sigmaand-node-empty-reject",
        s"The spent box's tree is the SigmaAnd node with no items (00 ea 00), which parses. Unlike the CAND() constant " +
        "(#3), the node is evaluated: SigmaAnd goes through allZK, which calls CAND.normalized (CSigmaDslBuilder.scala:" +
        "134-136), and that requires a non-empty list (SigmaBoolean.scala:165): the reduction throws before any proof " +
        s"is checked, and the transaction is invalid. An impl that reduces the empty node to TrueProp accepts. $spendNote",
        "00" + "ea00", none, want = false),
      spendEntry(11, "sigmaor-node-empty-reject",
        s"The SigmaOr node with no items (00 eb 00): anyZK calls COR.normalized (:140-142), which requires a non-empty " +
        s"list too (:201): the reduction throws, invalid. (An impl that reduces it to FalseProp reaches the same " +
        s"verdict.) $spendNote",
        "00" + "eb00", none, want = false),
      spendEntry(12, "sigmaand-node-256-true-accept",
        s"The SigmaAnd node of 256 × sigmaProp(true) (00 ea 80 02 08 d3…), which parses (no bound at 255). " +
        "CAND.normalized skips every TrueProp and returns TrueProp, so the box spends with no proof: valid. An impl that " +
        s"bounds the node's items, or the CAND it builds, at 255 rejects it. $spendNote",
        "00" + "ea8002" + "08d3" * 256, none, want = true))
  }

  // ── 2. output bytes ───────────────────────────────────────────────────────────────────────────────
  private val secret = DLogProverInput(scalar("santa:sized-tree-requests:secret"))
  private val pk = secret.publicImage

  /** pk && OUTPUTS(0).propositionBytes == prop && OUTPUTS(0).bytes.slice(3, 7) == slice
    *    && OUTPUTS(0).id == blake2b256(OUTPUTS(0).bytes). */
  private def scriptTree(prop: String, slice: String): ErgoTree = VersionContext.withVersions(V3, V3) {
    val out0 = ByIndex(Outputs, IntConstant(0))
    val bytes = ExtractBytes(out0)
    val checks = BinAnd(BinAnd(
      EQ(ExtractScriptBytes(out0), ByteArrayConstant(b(prop))),
      EQ(Slice(bytes, IntConstant(3), IntConstant(7)), ByteArrayConstant(b(slice)))),
      EQ(ExtractId(out0), CalcBlake2b256(bytes)))
    ErgoTree.fromProposition(ErgoTree.ZeroHeader, SigmaAnd(BoolToSigmaProp(checks), SigmaPropConstant(pk)))
  }

  /** A Schnorr proof of pk over `msg`, deterministic: the nonce comes from `nonceLabel`. Built as the JVM prover would
    * (Fiat-Shamir over the leaf and the message), and checked by the verdict. */
  private def schnorr(msg: Array[Byte], nonceLabel: String): Array[Byte] = {
    val r = scalar(nonceLabel)
    val a = FirstDLogProverMessage(dlogGroup.exponentiate(dlogGroup.generator, r))
    val leaf = UnprovenSchnorr(pk, Some(a), Some(r), None, simulated = false)
    val e = Challenge @@ sigma.Colls.fromArray(CryptoFunctions.hashFn(FiatShamirTree.toBytes(leaf)(null) ++ msg))
    SigSerializer.toProofBytes(UncheckedSchnorr(pk, Some(a), e, DLogProver.secondMessage(secret, r, e)))
  }

  private val Enc = "0802" + "08d3" // SigmaProp(true), size-flagged, declared 2: the re-encoded form

  private def outputBytesEntry(i: Int, slug: String, description: String, raw: String, prop: String, slice: String,
                               want: Boolean): Json = {
    val scripted = VersionContext.withVersions(V3, V3) { box(s"santa:str:outbytes:$i", V, 1, scriptTree(prop, slice)) }
    val placeholder = VersionContext.withVersions(V3, V3) { ErgoTreeSerializer.DefaultSerializer.deserializeErgoTree(b(Enc)) }
    val outs = Seq(candidate(1000000L, 1, placeholder), candidate(V - 1000000L, 1))
    def build(proof: Array[Byte]): Array[Byte] = VersionContext.withVersions(V3, V3) {
      spliceUnique(txBytes(tx(Seq(input(scripted, ext(), proof)), outs)), b("c0843d" + Enc), b("c0843d" + raw))
    }
    val msg = VersionContext.withVersions(V3, V3) {
      ErgoLikeTransaction.serializer.fromBytes(build(Array.emptyByteArray)).messageToSign
    }
    val txHex = hex(build(schnorr(msg, s"santa:sized-tree-requests:nonce:$i")))
    entry(s"$slug#$i", "output-bytes", description, txHex, Seq(scripted), want)
  }

  private def outputBytesEntries: Seq[Json] = {
    val script = "The spent box's script is proveDlog(pk) && OUTPUTS(0).propositionBytes == P && OUTPUTS(0).bytes.slice(3, " +
      "7) == S && OUTPUTS(0).id == blake2b256(OUTPUTS(0).bytes), and the input carries a valid Schnorr proof for pk over " +
      "the transaction's message (which the JVM computes over the re-encoded outputs). Output 0 is 1000000 (3 VLQ bytes) " +
      "with the tree below; output 1 returns the rest."
    Seq(
      outputBytesEntry(0, "output-declared-over-accept",
        s"$script Output 0's tree is 08 03 08 d3: declared 3, body 2. P = 08 03 08 d3 (propositionBytes is the tree as " +
        "received), S = 08 02 08 d3 (the output box is built from its candidate and re-encoded with the true size; its " +
        "id hashes those bytes). Valid.", "0803" + "08d3", "0803" + "08d3", Enc, want = true),
      outputBytesEntry(1, "output-declared-under-accept",
        s"$script Output 0's tree is 08 01 08 d3: declared 1, body 2. P = 08 01 08 d3, S = 08 02 08 d3. Valid.",
        "0801" + "08d3", "0801" + "08d3", Enc, want = true),
      outputBytesEntry(2, "output-declared-true-control-accept",
        s"$script The control: output 0's tree declares its true size, 08 02 08 d3, so P = S = 08 02 08 d3. Valid.",
        Enc, Enc, Enc, want = true),
      outputBytesEntry(3, "output-declared-over-propbytes-reencoded-reject",
        s"$script Output 0's tree is 08 03 08 d3, but the script expects P = 08 02 08 d3, the re-encoded tree: in the JVM " +
        "propositionBytes is the tree as received, so the check fails: invalid. An impl that re-encodes propositionBytes " +
        "accepts.", "0803" + "08d3", Enc, Enc, want = false),
      outputBytesEntry(4, "output-declared-over-bytes-raw-reject",
        s"$script Output 0's tree is 08 03 08 d3, but the script expects S = 08 03 08 d3, the tree as received: in the JVM " +
        "OUTPUTS(0).bytes is re-encoded, so the check fails: invalid. An impl that keeps the received bytes in bytes " +
        "accepts.", "0803" + "08d3", "0803" + "08d3", "0803" + "08d3", want = false))
  }

  private def envelope(op: String, es: Seq[Json]): Json = Json.obj(
    "schema"     -> Json.fromString("santa-transaction/v1"),
    "op"         -> Json.fromString(op),
    "blessed_by" -> Json.fromString(AuthoredTxStorageRent.BlessedBy),
    "entries"    -> Json.arr(es: _*))

  def blessAll(): Seq[(String, Json)] = Seq(
    SpendPath       -> envelope("tx:authored:sized-tree-spend", spendEntries),
    OutputBytesPath -> envelope("tx:authored:sized-tree-output-bytes", outputBytesEntries))

  def writeVectors(blessed: Seq[(String, Json)], vectorsRoot: java.nio.file.Path): Unit =
    blessed.foreach { case (rel, env) =>
      val f = vectorsRoot.resolve(rel)
      java.nio.file.Files.createDirectories(f.getParent)
      java.nio.file.Files.write(f, env.spaces2.getBytes(java.nio.charset.StandardCharsets.UTF_8))
    }
}
