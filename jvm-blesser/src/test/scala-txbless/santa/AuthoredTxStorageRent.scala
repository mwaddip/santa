package santa

// AuthoredTxStorageRent — transaction-tier vectors pinning every arm of the JVM's storage-rent spend
// (ergo v6.0.6 `ergo-wallet/.../wallet/interpreter/ErgoInterpreter.scala`):
//   - the gate (`verify`, :72-77): box age `preHeader.height - self.creationHeight >= StoragePeriod`
//     (signed Int), an EMPTY spending proof, and extension variable 127 present;
//   - the fallbacks (`Try { .. }.recoverWith`, :78-84): var 127 not a Short, or not an index of an output
//     candidate -> ordinary script verification;
//   - the verdict (`checkExpiredBox`, :42-55): FINAL — a false never falls back to the script;
//   - the fee: `storageFeeFactor * box.bytes.length` is Scala Int * Int and wraps at 32 bits (:43);
//   - the cost: a rent input costs `StorageContractCost` = 50 in place of a script cost (:81,
//     ErgoTransaction.verifyInput).
//
// Every box is `sigmaProp(true)`, so every spend needs only an empty proof and the ERG flows are the only
// thing that differs between arms. That makes the script path accept everything: a REJECT here can only
// come from the rent verdict, and an impl that falls through to the script ACCEPTS the reject (the
// over-accept surfaces as a verdict, not an incidental error).
//
// Context: SYNTHETIC but internally consistent — no captured chain reaches the StoragePeriod height on
// testnet. Ten v4 headers at heights H-10..H-1 (NEWEST-first, parent-linked), preHeader at H with
// parentId = headers(0).id; H = StoragePeriod, so a height-0 box is exactly old enough (the gate's
// boundary). Scripts never read the headers. Parameters: the launch table (storageFeeFactor 1250000,
// the live mainnet value).
//
// blessAll() FAILS LOUD unless the oracle agrees with the arm, for the arm's reason: a rent reject must
// carry the JVM's `#i => Success((false,50))` (the rent verdict, final); every accept's cost must equal the
// tx's initial + asset costs plus 50 per rent input and the measured sigmaProp(true) script cost per
// script input.

import io.circe.Json
import scorex.crypto.authds.ADDigest
import scorex.crypto.hash.Digest32
import scorex.util.ModifierId
import scorex.util.encode.Base16
import sigma.VersionContext
import sigma.ast.{ByteArrayConstant, Constant, IntConstant, SInt, STuple, SType, ShortConstant, Tuple}
import sigma.interpreter.ContextExtension
import sigma.serialization.{ErgoTreeSerializer, GroupElementSerializer, SigmaSerializer}
import org.ergoplatform.{ErgoBox, ErgoBoxCandidate, ErgoLikeTransaction, Input}
import org.ergoplatform.mining.AutolykosSolution
import org.ergoplatform.modifiers.history.header.{Header, HeaderSerializer}
import santa.runner.TxEngine

import RentFixtures._

object AuthoredTxStorageRent {
  val BlessedBy = "jvm:ergo-core-6.0.6-validateStateful"
  private val Activated = 3
  private val ErgoTreeV = 0

  /** The validating height: a box created at height 0 is exactly StoragePeriod old. */
  val H: Int = StoragePeriod

  // ── context ─────────────────────────────────────────────────────────────────────────────────────
  private val Launch: Seq[(String, Int)] = Seq(
    "maxBlockCost" -> 1000000, "storageFeeFactor" -> StorageFeeFactor, "minValuePerByte" -> 360,
    "inputCost" -> 2000, "dataInputCost" -> 100, "outputCost" -> 100, "tokenAccessCost" -> 100)
  private val params: Json = Json.obj(Launch.map { case (k, v) => k -> Json.fromInt(v) }: _*)
  private def param(k: String): Int = Launch.toMap.apply(k)

  private val NBits  = 117440512L      // 0x07000000; never decoded by stateful tx validation
  private val BaseTs = 1690000000000L  // ms; two-minute blocks
  private val minerPk = org.ergoplatform.mining.group.generator

  /** Ten parent-linked v4 headers below `tip`, NEWEST-first (ErgoStateContext.lastHeaders order). */
  private def headersBelow(tip: Int): Seq[Header] = {
    val firstHeight = tip - 10
    var parent: ModifierId = scorex.util.bytesToId(digest(s"santa:rent-ctx:parent-of:$firstHeight"))
    val oldestFirst = (firstHeight until tip).map { h =>
      val hdr = Header(
        version          = 4.toByte,
        parentId         = parent,
        ADProofsRoot     = Digest32 @@ digest(s"santa:rent-ctx:$h:adproofs"),
        stateRoot        = ADDigest @@ (digest(s"santa:rent-ctx:$h:state") :+ 0.toByte), // 33 bytes
        transactionsRoot = Digest32 @@ digest(s"santa:rent-ctx:$h:txs"),
        timestamp        = BaseTs + (h - firstHeight).toLong * 120000L,
        nBits            = NBits,
        height           = h,
        extensionRoot    = Digest32 @@ digest(s"santa:rent-ctx:$h:extension"),
        powSolution      = AutolykosSolution(minerPk, AutolykosSolution.wForV2,
                             digest(s"santa:rent-ctx:$h:nonce").take(8), AutolykosSolution.dForV2),
        votes            = Array[Byte](0, 0, 0),
        unparsedBytes    = Array.emptyByteArray)
      parent = hdr.id
      hdr
    }
    oldestFirst.reverse
  }

  private val headers: Seq[Header] = headersBelow(H)
  private val headersHex: Seq[String] = headers.map(h => hex(HeaderSerializer.toBytes(h)))
  private val preHeader: Json = Json.obj(
    "version"   -> Json.fromInt(4),
    "parentId"  -> Json.fromString(headers.head.id),
    "timestamp" -> Json.fromString((headers.head.timestamp + 120000L).toString),
    "nBits"     -> Json.fromLong(NBits),
    "height"    -> Json.fromInt(H),
    "minerPk"   -> Json.fromString(hex(GroupElementSerializer.toBytes(minerPk))),
    "votes"     -> Json.fromString("000000"))

  // ── costs (ErgoTransaction.validateStateful / verifyAssets) ─────────────────────────────────────
  /** interpreterInitCost + per-input / per-output costs (no data inputs here). */
  private def initialCost(nIn: Int, nOut: Int): Long =
    10000L + nIn.toLong * param("inputCost") + nOut.toLong * param("outputCost")

  /** ErgoBoxAssetExtractor.totalAssetsAccessCost: entries and distinct ids, inputs plus outputs. */
  private def assetsCost(inBoxes: Seq[ErgoBox], outs: Seq[ErgoBoxCandidate]): Long = {
    def entries(ts: Seq[Seq[(Seq[Byte], Long)]]) = ts.map(_.size).sum
    def distinct(ts: Seq[Seq[(Seq[Byte], Long)]]) = ts.flatten.map(_._1).distinct.size
    def toks(c: ErgoBoxCandidate) = c.additionalTokens.toArray.toSeq.map { case (id, n) => (id.toArray.toSeq, n) }
    val ins = inBoxes.map(toks); val os = outs.map(toks)
    param("tokenAccessCost").toLong * (entries(ins) + entries(os) + distinct(ins) + distinct(os))
  }

  // ── one blessed entry ───────────────────────────────────────────────────────────────────────────
  sealed trait Path
  /** Input `idx` takes the rent path; its verdict is final. */
  final case class Rent(idx: Int) extends Path
  /** Input `idx` takes the ordinary script path. */
  final case class Script(idx: Int) extends Path

  final case class Spend(box: ErgoBox, extension: ContextExtension, proof: Array[Byte] = Array.emptyByteArray)

  /** The measured cost of verifying one sigmaProp(true) input by script (the recover / non-rent path). */
  lazy val scriptCost: Long = {
    val young = box("santa:rent:script-cost-probe", 1000000000L, H - 10)
    val t = tx(Seq(input(young, ext())), Seq(candidate(young.value, H)))
    val v = validate(txBytes(t), Seq(young))
    require(v.valid, s"script-cost probe must be valid: ${v.reason}")
    val c = v.cost.get - initialCost(1, 1)
    require(c != StorageContractCost, s"sigmaProp(true) script cost $c equals StorageContractCost — paths indistinguishable")
    c
  }

  private def validate(txBytes: Array[Byte], inBoxes: Seq[ErgoBox]): TxEngine.Verdict =
    TxEngine.validateBytes(hex(txBytes), inBoxes.map(b => hex(b.bytes)), Nil, headersHex, preHeader, params)

  /** Bless one entry: build the tx, check ERG balance, validate under the synthetic context, and fail loud
    * unless the verdict is the arm's — reject: input `rejectAt` fails with the rent verdict (false, 50);
    * accept: cost = initial + assets + 50 per Rent + scriptCost per Script. `patchTx` rewrites the serialized
    * tx before blessing, for encodings the JVM serializer never writes (it re-serializes trees canonically). */
  private def entry(name: String, source: String, description: String, spends: Seq[Spend],
                    outputs: Seq[ErgoBoxCandidate], paths: Seq[Path], rejectAt: Option[Int],
                    patchTx: Array[Byte] => Array[Byte] = identity): Json = {
    val inBoxes = spends.map(_.box)
    require(inBoxes.map(_.value).sum == outputs.map(_.value).sum,
      s"$name: ERG not preserved (${inBoxes.map(_.value).sum} in, ${outputs.map(_.value).sum} out) — would reject for the wrong reason")
    val t = tx(spends.map(s => Input(s.box.id, sigma.interpreter.ProverResult(s.proof, s.extension))), outputs)
    val bytes = patchTx(txBytes(t))
    val v = validate(bytes, inBoxes)
    rejectAt match {
      case Some(i) =>
        val want = s"#$i => Success((false,$StorageContractCost))"
        require(!v.valid && v.reason.exists(_.contains(want)),
          s"$name: want a rent-verdict reject at input #$i ('$want'), got valid=${v.valid} reason=${v.reason.getOrElse("")}")
      case None =>
        val nRent   = paths.count(_.isInstanceOf[Rent])
        val nScript = paths.count(_.isInstanceOf[Script])
        require(nRent + nScript == spends.size, s"$name: every input needs a declared path")
        val want = initialCost(spends.size, outputs.size) + assetsCost(inBoxes, outputs) +
          nRent * StorageContractCost + nScript * scriptCost
        require(v.valid && v.cost.contains(want),
          s"$name: want accept at cost $want ($nRent rent, $nScript script), got valid=${v.valid} cost=${v.cost} reason=${v.reason.getOrElse("")}")
    }
    Json.obj(
      "name"                 -> Json.fromString(name),
      "source"               -> Json.fromString(source),
      "description"          -> Json.fromString(description),
      "tx_bytes_hex"         -> Json.fromString(hex(bytes)),
      "input_boxes_hex"      -> Json.arr(inBoxes.map(b => Json.fromString(hex(b.bytes))): _*),
      "data_input_boxes_hex" -> Json.arr(),
      "headers_hex"          -> Json.arr(headersHex.map(Json.fromString): _*),
      "preHeader"            -> preHeader,
      "parameters"           -> params,
      "context"              -> Json.obj("height" -> Json.fromInt(H)),
      "version"              -> Json.obj("activated" -> Json.fromInt(Activated), "ergoTree" -> Json.fromInt(ErgoTreeV)),
      "expected"             -> Json.obj(
        "valid"  -> Json.fromBoolean(v.valid),
        "cost"   -> v.cost.map(Json.fromLong).getOrElse(Json.Null),
        "reason" -> v.reason.map(Json.fromString).getOrElse(Json.Null)))
  }

  private def src(arm: String, c: String) = s"santa:authored-tx-storage-rent:$arm:$c"
  private val rentVar = ext(StorageIndexVarId -> ShortConstant(0))
  private val V = 5000000000L

  /** A box padded with an R4 byte string to exactly `size` serialized bytes. */
  private def boxOfSize(label: String, value: Long, height: Int, size: Int): ErgoBox = {
    def mk(n: Int) = box(label, value, height, regs = Map(ErgoBox.R4 -> ByteArrayConstant(Array.fill[Byte](n)(0x5a))))
    val n = (0 to size).find(n => mk(n).bytes.length >= size)
      .getOrElse(sys.error(s"$label: cannot pad to $size bytes"))
    require(mk(n).bytes.length == size, s"$label: padding skips $size bytes (VLQ length step) — choose another size")
    mk(n)
  }

  /** A box whose value is its own storage fee plus `extra` (the fee depends on the value's VLQ length). */
  private def boxWithValueFeePlus(label: String, extra: Long): ErgoBox = {
    var v = StorageFeeFactor.toLong * 43
    var b = box(label, v, 0)
    var guard = 0
    while (b.value != trueStorageFee(b) + extra) {
      v = trueStorageFee(b) + extra; b = box(label, v, 0); guard += 1
      require(guard < 10, s"$label: value/fee fixpoint did not converge")
    }
    b
  }

  // ── the vectors ─────────────────────────────────────────────────────────────────────────────────
  val RecreationPath = "transaction/v6/authored/storage-rent-recreation.json"
  val FallbackPath   = "transaction/v6/authored/storage-rent-fallback.json"
  val GatePath       = "transaction/v6/authored/storage-rent-gate.json"
  val MixedPath      = "transaction/v6/authored/storage-rent-mixed-inputs.json"
  val DustPath       = "transaction/v6/authored/storage-rent-dust.json"
  val FeeWrapPath    = "transaction/v6/authored/storage-rent-fee-wrap.json"

  private def recreationEntries: Seq[Json] = {
    val plain = box("santa:rent:recreation", V, 0)
    val fee = trueStorageFee(plain)
    val token = (tokenId("santa:rent:token"), 1000L)
    val withToken = box("santa:rent:recreation-token", V, 0, tokens = Seq(token))
    val tokenFee = trueStorageFee(withToken)
    val withR4 = box("santa:rent:recreation-r4", V, 0, regs = Map(ErgoBox.R4 -> IntConstant(42)))
    val r4Fee = trueStorageFee(withR4)
    Seq(
      entry("rent-recreation-accept", src("recreation", "accept"),
        "A height-0 sigmaProp(true) box spent at height StoragePeriod with an empty proof and var 127 = " +
        "Short(0): output 0 recreates it at the current height holding value - storageFee, the fee goes to " +
        "output 1. The rent path accepts; the input costs StorageContractCost = 50, not a script cost.",
        Seq(Spend(plain, rentVar)), Seq(recreate(plain, V - fee, H), candidate(fee, H)), Seq(Rent(0)), None),
      entry("rent-recreation-tokens-accept", src("recreation", "tokens-accept"),
        "The accepted recreation for a token-bearing box: output 0 keeps the token (R2 preserved).",
        Seq(Spend(withToken, rentVar)), Seq(recreate(withToken, V - tokenFee, H), candidate(tokenFee, H)),
        Seq(Rent(0)), None),
      entry("rent-recreation-height-reject", src("recreation", "height-reject"),
        "Output 0 recreates the box at height - 1 instead of the current height. checkExpiredBox is false and " +
        "the verdict is FINAL: the trivially-true script must not rescue the spend.",
        Seq(Spend(plain, rentVar)), Seq(recreate(plain, V - fee, H - 1), candidate(fee, H)), Nil, Some(0)),
      entry("rent-recreation-value-reject", src("recreation", "value-reject"),
        "Output 0 holds value - storageFee - 1: one nanoERG short of covering the fee. Final reject.",
        Seq(Spend(plain, rentVar)), Seq(recreate(plain, V - fee - 1, H), candidate(fee + 1, H)), Nil, Some(0)),
      entry("rent-recreation-script-reject", src("recreation", "script-reject"),
        "Output 0 has the right height and value but a different script (sigmaProp(false)): R1 is not " +
        "preserved. Final reject.",
        Seq(Spend(plain, rentVar)), Seq(candidate(V - fee, H, tree = FalseTree), candidate(fee, H)), Nil, Some(0)),
      entry("rent-recreation-tokens-reject", src("recreation", "tokens-reject"),
        "A token-bearing box whose output 0 drops (burns) the token: R2 is not preserved. Final reject.",
        Seq(Spend(withToken, rentVar)), Seq(candidate(V - tokenFee, H), candidate(tokenFee, H)), Nil, Some(0)),
      entry("rent-recreation-register-reject", src("recreation", "register-reject"),
        "A box with R4 = Int(42) whose output 0 drops R4: an additional register is not preserved. Final reject.",
        Seq(Spend(withR4, rentVar)), Seq(candidate(V - r4Fee, H), candidate(r4Fee, H)), Nil, Some(0))) ++
      tupleRegisterEntries ++ scriptEncodingEntries
  }

  /** Parse with the JVM's own serializers, as TxEngine does (v6 context). */
  private def jvmParseBox(bytes: Array[Byte]): ErgoBox = VersionContext.withVersions(Activated.toByte, Activated.toByte) {
    ErgoBox.sigmaSerializer.parse(SigmaSerializer.startReader(bytes))
  }
  private def jvmParseTx(bytes: Array[Byte]): ErgoLikeTransaction = VersionContext.withVersions(Activated.toByte, Activated.toByte) {
    ErgoLikeTransaction.serializer.parse(SigmaSerializer.startReader(bytes))
  }

  /** `checkExpiredBox` compares registers with `ErgoBox.get` equality (`ErgoInterpreter.scala:50-52`): the
    * stored EvaluatedValue nodes, not their values. A Tuple *expression* (a legal register value — `Tuple`
    * extends EvaluatedValue so it can sit in a register, values.scala:807) never equals a Constant
    * (`ConstantNode.equals` only matches a Constant, values.scala:356), even when both hold (1, 2). */
  private def tupleRegisterEntries: Seq[Json] = {
    val tupleExpr  = Tuple(IntConstant(1), IntConstant(2))
    val tupleConst = Constant[SType]((1, 2).asInstanceOf[SType#WrappedType], STuple(SInt, SInt))
    val b   = box("santa:rent:recreation-tuple", V, 0, regs = Map(ErgoBox.R4 -> tupleExpr))
    val fee = trueStorageFee(b)
    val keep  = Seq(recreate(b, V - fee, H), candidate(fee, H))
    val asConst = Seq(candidate(V - fee, H, regs = Map(ErgoBox.R4 -> tupleConst)), candidate(fee, H))
    // The committed bytes must carry what the entries claim, as the JVM reads them back.
    val inR4 = jvmParseBox(b.bytes).additionalRegisters(ErgoBox.R4)
    require(inR4.isInstanceOf[Tuple] && inR4 == tupleExpr, s"input R4 must parse back as the Tuple expression: $inR4")
    val outR4 = jvmParseTx(txBytes(tx(Seq(input(b, rentVar)), asConst))).outputCandidates(0).additionalRegisters(ErgoBox.R4)
    require(outR4.isInstanceOf[Constant[_]] && outR4.tpe == STuple(SInt, SInt) && outR4 == tupleConst,
      s"output R4 must parse back as the (Int, Int) Constant (1, 2): $outR4")
    require(inR4 != outR4 && outR4 != inR4, "Tuple expression and tuple Constant must be unequal under ErgoBox.get equality")
    Seq(
      entry("rent-recreation-tuple-register-accept", src("recreation", "tuple-register-accept"),
        "A box whose R4 is stored as a Tuple EXPRESSION (Tuple(Int(1), Int(2)), opcode encoding), recreated with " +
        "the same Tuple expression in R4: registers preserved, the rent path accepts. Twin of the reject below.",
        Seq(Spend(b, rentVar)), keep, Seq(Rent(0)), None),
      entry("rent-recreation-tuple-register-reject", src("recreation", "tuple-register-reject"),
        "The same box recreated with R4 as the equal tuple CONSTANT ((1, 2): (Int, Int)). checkExpiredBox compares " +
        "registers with ErgoBox.get equality (ErgoInterpreter.scala:50-52): a Tuple node never equals a Constant " +
        "node (values.scala:356, :807), so R4 is not preserved even though both evaluate to (1, 2). Final reject.",
        Seq(Spend(b, rentVar)), asConst, Nil, Some(0)))
  }

  /** R1 is `ByteArrayConstant(propositionBytes)` (ErgoBoxCandidate.get, ScriptRegId): the tree's RETAINED
    * wire bytes. A non-canonical encoding of the same tree — the constants count as an overlong VLQ — parses
    * to the same tree, but its bytes differ, so R1 is not preserved. The JVM serializer always writes the
    * canonical form (serializeErgoTree), so the overlong bytes are spliced into the serialized tx. */
  private def scriptEncodingEntries: Seq[Json] = {
    val canonicalHex = "10010101d17300"   // sigmaProp(true), constant-segregated: count 01, [Boolean true], d1 7300
    val overlongHex  = "1081000101d17300" // the same tree with the constants count as the 2-byte VLQ 81 00
    val canonical = Base16.decode(canonicalHex).get
    val overlong  = Base16.decode(overlongHex).get
    val (tree, reserializedOverlong, retainedOverlong) = VersionContext.withVersions(Activated.toByte, 0) {
      val t = ErgoTreeSerializer.DefaultSerializer.deserializeErgoTree(canonical)
      val o = ErgoTreeSerializer.DefaultSerializer.deserializeErgoTree(overlong)
      (t, ErgoTreeSerializer.DefaultSerializer.serializeErgoTree(o), o.bytes)
    }
    require(tree.bytes.sameElements(canonical) && reserializedOverlong.sameElements(canonical) &&
      retainedOverlong.sameElements(overlong),
      "the overlong tree must be the same tree (re-serializes canonically) with different retained bytes")
    val b   = box("santa:rent:recreation-segregated", V, 0, tree = tree)
    val fee = trueStorageFee(b)
    val outs = Seq(recreate(b, V - fee, H), candidate(fee, H)) // output 1 is 0008d3: the only segregated tree is output 0's
    def toOverlong(tx: Array[Byte]): Array[Byte] = {
      val at = tx.indexOfSlice(canonical)
      require(at >= 0 && tx.indexOfSlice(canonical, at + 1) < 0, "the canonical tree must occur exactly once in the tx")
      tx.take(at) ++ overlong ++ tx.drop(at + canonical.length) // output bodies carry no length prefix
    }
    val parsedOut = jvmParseTx(toOverlong(txBytes(tx(Seq(input(b, rentVar)), outs)))).outputCandidates(0)
    require(parsedOut.propositionBytes.sameElements(overlong) && !parsedOut.propositionBytes.sameElements(b.propositionBytes),
      "the JVM must retain the overlong bytes as output 0's propositionBytes (R1)")
    Seq(
      entry("rent-recreation-segregated-script-accept", src("recreation", "segregated-script-accept"),
        s"A box guarded by the constant-segregated sigmaProp(true) tree $canonicalHex, recreated with the same " +
        "tree bytes: R1 preserved, the rent path accepts. Twin of the reject below.",
        Seq(Spend(b, rentVar)), outs, Seq(Rent(0)), None),
      entry("rent-recreation-noncanonical-script-reject", src("recreation", "noncanonical-script-reject"),
        s"The same recreation, but output 0's tree is encoded as $overlongHex: the constants count written as an " +
        "overlong VLQ (81 00 for 1). It parses to the same tree, yet R1 is ByteArrayConstant(propositionBytes) — the " +
        "retained wire bytes — so R1 is not preserved. Final reject. An impl comparing re-serialized trees " +
        "accepts it.",
        Seq(Spend(b, rentVar)), outs, Nil, Some(0), patchTx = toOverlong))
  }

  private def fallbackEntries: Seq[Json] = {
    // Every fallback output VIOLATES the recreation (height - 1), so an impl that wrongly takes the rent
    // path rejects; the JVM's recoverWith verifies the trivially-true script and accepts.
    def one(c: String, label: String, var127: sigma.ast.EvaluatedValue[_ <: sigma.ast.SType], description: String) = {
      val b = box(s"santa:rent:fallback-$label", V, 0)
      entry(s"rent-fallback-$c-accept", src("fallback", c), description,
        Seq(Spend(b, ext(StorageIndexVarId -> var127))), Seq(candidate(V, H - 1)), Seq(Script(0)), None)
    }
    Seq(
      one("var127-int", "int", IntConstant(0),
        "Rent-eligible box whose var 127 is Int(0), not a Short: `asInstanceOf[Short]` throws inside the Try, " +
        "recoverWith falls back to the script, which accepts. Output 0 violates the recreation, so taking the " +
        "rent path would reject; the input costs the script, not 50."),
      one("index-out-of-range", "range", ShortConstant(1),
        "Rent-eligible box whose var 127 = Short(1) with a single output: outputCandidates(1) throws, the " +
        "script path accepts."),
      one("index-negative", "negative", ShortConstant(-1),
        "Rent-eligible box whose var 127 = Short(-1): outputCandidates(-1) throws, the script path accepts."))
  }

  private def gateEntries: Seq[Json] = {
    val young = box("santa:rent:gate-age", V, 1) // age = StoragePeriod - 1
    val old   = box("santa:rent:gate-proof", V, 0)
    Seq(
      entry("rent-gate-age-below-period-accept", src("gate", "age-below-period"),
        "A box created at height 1, so its age is StoragePeriod - 1: not rent-eligible. Same violating output " +
        "as rent-recreation-height-reject, which the one-block-older box fails: the script path accepts. Pins " +
        "the gate's boundary from below.",
        Seq(Spend(young, rentVar)), Seq(candidate(V, H - 1)), Seq(Script(0)), None),
      entry("rent-gate-nonempty-proof-accept", src("gate", "nonempty-proof"),
        "A rent-eligible box with var 127 = Short(0) but a non-empty (1-byte) spending proof: the rent path " +
        "requires `proof.length == 0`, so the input takes the script path, where sigmaProp(true) accepts " +
        "whatever the proof bytes. The output violates the recreation.",
        Seq(Spend(old, rentVar, Array[Byte](0))), Seq(candidate(V, H - 1)), Seq(Script(0)), None))
  }

  private def mixedEntries: Seq[Json] = {
    val rent  = box("santa:rent:mixed-rent", V, 0)
    val fee   = trueStorageFee(rent)
    val young = box("santa:rent:mixed-script", 2000000000L, H - 10)
    val outs  = Seq(recreate(rent, V - fee, H), candidate(fee + young.value, H))
    Seq(
      entry("rent-mixed-rent-then-script-accept", src("mixed-inputs", "rent-then-script"),
        "A storage-rent input followed by an ordinary scripted input. The tx cost is the initial cost plus 50 " +
        "for the rent input plus the JVM's script cost for the other, accumulated in input order.",
        Seq(Spend(rent, rentVar), Spend(young, ext())), outs, Seq(Rent(0), Script(1)), None),
      entry("rent-mixed-script-then-rent-accept", src("mixed-inputs", "script-then-rent"),
        "The same two inputs in the opposite order: the scripted input first, the rent input second.",
        Seq(Spend(young, ext()), Spend(rent, rentVar)), outs, Seq(Script(0), Rent(1)), None))
  }

  private def dustEntries: Seq[Json] = {
    val atFee    = boxWithValueFeePlus("santa:rent:dust-at-fee", 0L)
    val aboveFee = boxWithValueFeePlus("santa:rent:dust-above-fee", 1L)
    require(atFee.value == trueStorageFee(atFee) && jvmStorageFee(atFee).toLong == trueStorageFee(atFee))
    Seq(
      entry("rent-dust-value-equals-fee-accept", src("dust", "value-equals-fee"),
        "A box whose value equals its storage fee exactly (value - fee == 0): `storageFeeNotCovered` is " +
        "`value - fee <= 0`, so the box is spendable regardless of the output. Output 0 takes the whole value " +
        "at the wrong height; the rent path accepts and costs 50.",
        Seq(Spend(atFee, rentVar)), Seq(candidate(atFee.value, H - 1)), Seq(Rent(0)), None),
      entry("rent-dust-value-above-fee-reject", src("dust", "value-above-fee"),
        "A box one nanoERG above its storage fee: not dust, so the same violating output fails the " +
        "recreation. Final reject.",
        Seq(Spend(aboveFee, rentVar)), Seq(candidate(aboveFee.value, H - 1)), Nil, Some(0)))
  }

  private def feeWrapEntries: Seq[Json] = {
    val b1718 = boxOfSize("santa:rent:wrap-1718", V, 0, 1718)
    val w1718 = jvmStorageFee(b1718).toLong // -2147467296: Int overflow
    val t1718 = trueStorageFee(b1718)       //  2147500000
    require(w1718 == -2147467296L && t1718 == 2147500000L, s"1718-byte fee: jvm $w1718, true $t1718")
    val b3436 = boxOfSize("santa:rent:wrap-3436", V, 0, 3436)
    val w3436 = jvmStorageFee(b3436).toLong // 32704: wrapped past 2^32
    require(w3436 == 32704L && trueStorageFee(b3436) == 4295000000L, s"3436-byte fee: jvm $w3436")
    val funding = box("santa:rent:wrap-funding", 3000000000L, H - 10)
    val need = V - w1718 // 7147467296: what the JVM demands in output 0
    Seq(
      entry("rent-fee-wrap-1718-true-fee-reject", src("fee-wrap", "1718-true-fee"),
        "A 1718-byte box: storageFeeFactor * 1718 = 2147500000 overflows Int to -2147467296, so the JVM " +
        "demands output 0 >= value + 2147467296. Output 0 keeps value - 2147500000 (the unwrapped fee): the " +
        "JVM rejects, finally. 64-bit fee arithmetic accepts it.",
        Seq(Spend(b1718, rentVar)), Seq(recreate(b1718, V - t1718, H), candidate(t1718, H)), Nil, Some(0)),
      entry("rent-fee-wrap-1718-wrapped-fee-accept", src("fee-wrap", "1718-wrapped-fee"),
        "The same box recreated holding exactly value - (-2147467296) = value + 2147467296, funded by a second " +
        "(scripted) input: the JVM's boundary, accepted. The rent input costs 50, the funding input its script.",
        Seq(Spend(b1718, rentVar), Spend(funding, ext())),
        Seq(recreate(b1718, need, H), candidate(V + funding.value - need, H)), Seq(Rent(0), Script(1)), None),
      entry("rent-fee-wrap-1718-wrapped-fee-minus-one-reject", src("fee-wrap", "1718-wrapped-fee-minus-one"),
        "One nanoERG below the JVM's boundary: output 0 = value + 2147467295. Final reject.",
        Seq(Spend(b1718, rentVar), Spend(funding, ext())),
        Seq(recreate(b1718, need - 1, H), candidate(V + funding.value - need + 1, H)), Nil, Some(0)),
      entry("rent-fee-wrap-3436-accept", src("fee-wrap", "3436-boundary"),
        "A 3436-byte box: storageFeeFactor * 3436 = 4295000000 wraps past 2^32 to 32704, so the JVM charges a " +
        "32704-nanoERG fee. Output 0 = value - 32704: exactly the JVM's boundary, accepted.",
        Seq(Spend(b3436, rentVar)), Seq(recreate(b3436, V - w3436, H), candidate(w3436, H)), Seq(Rent(0)), None),
      entry("rent-fee-wrap-3436-reject", src("fee-wrap", "3436-boundary-minus-one"),
        "The same box with output 0 = value - 32705: one nanoERG below the wrapped fee's boundary. Final " +
        "reject. With the unwrapped fee (4295000000) this output is far above the requirement.",
        Seq(Spend(b3436, rentVar)), Seq(recreate(b3436, V - w3436 - 1, H), candidate(w3436 + 1, H)), Nil, Some(0)))
  }

  private def envelope(op: String, es: Seq[Json]): Json = Json.obj(
    "schema"     -> Json.fromString("santa-transaction/v1"),
    "op"         -> Json.fromString(op),
    "blessed_by" -> Json.fromString(BlessedBy),
    "entries"    -> Json.arr(es: _*))

  def blessAll(): Seq[(String, Json)] = Seq(
    RecreationPath -> envelope("tx:authored:storage-rent-recreation", recreationEntries),
    FallbackPath   -> envelope("tx:authored:storage-rent-fallback", fallbackEntries),
    GatePath       -> envelope("tx:authored:storage-rent-gate", gateEntries),
    MixedPath      -> envelope("tx:authored:storage-rent-mixed-inputs", mixedEntries),
    DustPath       -> envelope("tx:authored:storage-rent-dust", dustEntries),
    FeeWrapPath    -> envelope("tx:authored:storage-rent-fee-wrap", feeWrapEntries))

  def writeVectors(blessed: Seq[(String, Json)], vectorsRoot: java.nio.file.Path): Unit =
    blessed.foreach { case (rel, env) =>
      val f = vectorsRoot.resolve(rel)
      java.nio.file.Files.createDirectories(f.getParent)
      java.nio.file.Files.write(f, env.spaces2.getBytes(java.nio.charset.StandardCharsets.UTF_8))
    }
}
