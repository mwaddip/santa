package santa

// AuthoredBlockKeptTreeBytes — a box SPENT IN THE BLOCK THAT CREATES IT, whose tree keeps the
// bytes of the creating transaction. Request: part 4 of
// ~/projects/sigma-rust/prompts/santa-probe-kept-tree-bytes.md; authored on the user's call
// (2026-11-11) after the tier was shown to express it (santa.spike.SyntheticBlockKeptBytesSpike).
//
// WHY THIS CLASS NEEDS THE BLOCK TIER AT ALL. The box writer RE-WRITES a tree
// (`ErgoBoxCandidate.scala:142`), so a box in a node's own store never holds a non-canonical
// encoding. The ONLY route by which a script can see one is the in-block path: the node resolves a
// spent box among the block's own outputs first (`UtxoState.scala:77-81`,
// `DigestState.scala:47-49`), and those are the in-memory boxes derived from the PARSED creating
// transaction, whose trees keep that transaction's bytes. So the transaction tier's
// `kept-tree-bytes-input` rows pin the READING, and these pin its REACHABILITY.
//
// WHY THE BLOCK IS SYNTHETIC, AND WHY THAT IS HONEST. The authored block arm elsewhere mutates a
// captured donor, because a header is PoW-sealed and commits to the transactions root and the
// ADProofs root; no edit to a donor's transactions can survive, and the donor's difficulty is
// ~2.57e9 expected attempts. But testnet's own `initialDifficultyHex = "01"` means a HEIGHT-1
// block is mined in milliseconds — and difficulty 1 at height 1 is what testnet genuinely looks
// like, so this is not a block the real rules would reject.
//
// WHY THE HEADERS ARE FIXTURES. `AutolykosPowScheme.prove` draws a random one-time secret, so
// re-mining would churn the vector on every bless (the live NipopowProve problem). The two mined
// headers are therefore constants, and the blesser DERIVES the transactions, the proof and the
// digests and ASSERTS the embedded header commits to exactly what it derived — so any drift fails
// loudly with "re-mine", instead of silently re-mining or silently disagreeing.
//
// THE SPLICE. The JVM writer re-writes the tree when it frames the transactions section (as the box
// writer does everywhere), so the as-received form is spliced back into the section's BYTES — what
// a node actually receives (runner-contract-block §2). Until 2026-11-11 the splice went into the
// transactions' node-API JSON, which a runner rebuilding the section through a writer re-wrote
// again: donner went red on #0/#1/#3 for that reason alone. The transactions ROOT is unaffected —
// the ids hash a RE-SERIALIZATION, which re-writes the tree either way (measured: every id parsed
// off the spliced section is the writer's) — and so is the proof, because `ErgoState.boxChanges`
// drops a created-then-spent box entirely (BlockEngine reproduces that), so the created box never
// reaches the AVL state.

import io.circe.Json
import io.circe.parser.parse
import scorex.util.encode.Base16

import scorex.crypto.authds.{ADValue, SerializedAdProof}
import scorex.crypto.authds.avltree.batch.{BatchAVLProver, Insert, Remove}
import scorex.crypto.hash.{Blake2b256, Digest32}

import sigma.VersionContext
import sigma.ast.ErgoTree.ZeroHeader
import sigma.ast._
import sigma.ast.syntax.SigmaPropValue
import sigma.serialization.ErgoTreeSerializer.DefaultSerializer

import org.ergoplatform.{ErgoBox, ErgoBoxCandidate}
import org.ergoplatform.modifiers.history.{ADProofs, BlockTransactions}
import org.ergoplatform.modifiers.mempool.ErgoTransaction

import santa.runner.BlockEngine

import RentFixtures._

object AuthoredBlockKeptTreeBytes {
  val Path = "block/v6/authored/kept-tree-bytes-in-block.json"

  private val A: Byte = 3
  private val BlockVersion = 4
  private val V = 1000000000L
  private val Timestamp = 1763375459310L
  private val BlessedBy = "jvm:ergo-core-6.0.7-execTransactions-model"

  private def bs(h: String): Array[Byte] = Base16.decode(h).get
  private def hx(b: Array[Byte]): String = Base16.encode(b).toLowerCase
  private def ser(t: ErgoTree): String = VersionContext.withVersions(A, A)(hx(DefaultSerializer.serializeErgoTree(t)))

  /** The two mined headers (difficulty 1, height 1, nonce 0). Re-mine with
    * `santa.spike.SyntheticBlockKeptBytesSpike` test 4b if a derivation below ever changes. */
  private val AcceptHeaderJson =
    """{"extensionId":"9f7138106b432e37a9f4c6cea9af78a3c1e7bfbf264c02cb2ce751754982d60c","difficulty":"1","votes":"000000","timestamp":1763375459310,"size":218,"unparsedBytes":"","stateRoot":"5ea5544cccd2b8ae51d16b8f28c0457dffc843fc8182203102b73b2a943b110f01","height":2,"nBits":16842752,"version":4,"id":"edd5f289fceedd14b37f7080d2c070731efb01d3ab5e0d86f94f5e57da3be37e","adProofsRoot":"7628bc77f07caab8283936e02cc447d63f801c112b86d02f211a42869f72a7dc","transactionsRoot":"ab5a6f9700a80d6cefddbada6394351203cb83f61f9e35c4bdf4364d6ee61f21","extensionHash":"21ff60b0778516417dbe5787577eb501ec03ba2aa82d80ecf68d44acf13edbf0","powSolutions":{"pk":"03f01d6b9018ab421dd410404cb869072065522bf85734008f105cf385a023a80f","w":"036a2d96051157a9316ef6d663da48f2a55ec1c9450a1a93dcd7bf22c817121f7a","n":"0000000000000000","d":55839302299244085448913780307369928664600744824066643028317507815954236020079},"adProofsId":"303a45c85760bb77a964ddfd4557661f500e49e33f17d8e7847af1fb9d334cd0","transactionsId":"cfa2ffd809de333241c3a2938f453cf2f8f27ffa363fbcf45616d709235d4411","parentId":"cdfe9f0c0bd0c631431c236de2aabc04aab4f236e090ca32e85a5ae064145558"}"""

  private val RejectHeaderJson =
    """{"extensionId":"1ff1786ee9758558c7d9e040b53c835447c0052ff2d3b7d479e39109afd6e802","difficulty":"1","votes":"000000","timestamp":1763375459310,"size":218,"unparsedBytes":"","stateRoot":"b8b71af6782245b0bbcae33a967254719c6285eaa0f357ebd48dc2ec7dc1811d01","height":2,"nBits":16842752,"version":4,"id":"4044b61e44d6c51eb132133f19b09eadaf440292fa3372a81177a9949fe40386","adProofsRoot":"7628bc77f07caab8283936e02cc447d63f801c112b86d02f211a42869f72a7dc","transactionsRoot":"660dee133ecff527bc2be46c495d6fad422f0f318748341dd5b76222c4e361ea","extensionHash":"21ff60b0778516417dbe5787577eb501ec03ba2aa82d80ecf68d44acf13edbf0","powSolutions":{"pk":"03f01d6b9018ab421dd410404cb869072065522bf85734008f105cf385a023a80f","w":"027469498b1d708e781d89cb1e55c982dd70f92471137f17b3076ea4718f87193a","n":"0000000000000000","d":2861583847894408181104244427486736741934340725652418647444126705076385357277},"adProofsId":"c71c0c6f1399b6da82789f367866368bc341df7070822e4b6c3586aa62f55903","transactionsId":"0c24ca19184bececd5b452cd2630617ed967905af11682384100b114e211a6ec","parentId":"cdfe9f0c0bd0c631431c236de2aabc04aab4f236e090ca32e85a5ae064145558"}"""

  /** The parent, at height 1. Two reasons the block sits at HEIGHT 2 rather than 1: donner
    * refuses an empty headers window, and height 2 is not a voting start (`height % 1024 == 1`),
    * which keeps the block off the epoch-boundary path where a full node processes the extension
    * for parameters — a synthetic block's extension is empty, so an epoch-start block would have
    * been a FALSE RED against the node runners rather than a finding. Both were caught by grading
    * the first attempt, not by review. */
  private val ParentHeaderJson =
    """{"extensionId":"8afcf61bf235861610ac5cd670e91af09d6927839839fe2e5db4666b8d46b653","difficulty":"1","votes":"000000","timestamp":1763375459000,"size":218,"unparsedBytes":"","stateRoot":"e3640fcda960994cd814c2864723be5754a72d58816e00d9c542f5de358b7e1401","height":1,"nBits":16842752,"version":4,"id":"cdfe9f0c0bd0c631431c236de2aabc04aab4f236e090ca32e85a5ae064145558","adProofsRoot":"0000000000000000000000000000000000000000000000000000000000000000","transactionsRoot":"0000000000000000000000000000000000000000000000000000000000000000","extensionHash":"0000000000000000000000000000000000000000000000000000000000000000","powSolutions":{"pk":"03f01d6b9018ab421dd410404cb869072065522bf85734008f105cf385a023a80f","w":"0311e47c79ee48fb575f30f78aa0a365adb71cea6aa8c375ae13bcac6c3ddf2259","n":"0000000000000000","d":114616123637486601195580591850373309733781900411427054376049470819295223820013},"adProofsId":"a0fe3a57e10a2b3cc25f5a8f66deaf89328ac5c35304ec41f0a049c717f19305","transactionsId":"9a82cf0a5b79b7ef5064ef72bf60cbb4d8a31fe953e1399c2ce7daf6be4ef30a","parentId":"0000000000000000000000000000000000000000000000000000000000000000"}"""

  private val ParamsTable: Map[String, Int] = Map("1" -> 1250000, "2" -> 360, "3" -> 524288,
    "4" -> 1000000, "5" -> 100, "6" -> 2000, "7" -> 100, "8" -> 100, "9" -> 30,
    "123" -> BlockVersion)

  // ── the script, and the two encodings of it ───────────────────────────────────────────────
  private val N = 3

  private def sliceScript(expected: Array[Byte]): ErgoTree = VersionContext.withVersions(A, A) {
    val root: SigmaPropValue = BoolToSigmaProp(EQ(
      Slice(ExtractScriptBytes(Self), IntConstant(0), IntConstant(N)), ByteArrayConstant(expected)))
    ErgoTree.withSegregation(ErgoTree.headerWithVersion(ZeroHeader, 0.toByte), root)
  }

  private def overlongCount(canonical: String): String = {
    val c = Integer.parseInt(canonical.substring(2, 4), 16)
    require(c < 0x80, s"constants count $c is already multi-byte in $canonical")
    "10" + f"${c | 0x80}%02x" + "00" + canonical.substring(4)
  }

  /** The ACCEPT script: solved so its constant IS the first N bytes of its own as-received form. */
  private lazy val (acceptCanonical, acceptVariant) = {
    var expected = Array.fill(N)(0.toByte)
    var out: Option[(String, String)] = None
    for (_ <- 1 to 12 if out.isEmpty) {
      val c = ser(sliceScript(expected))
      val v = overlongCount(c)
      val next = bs(v.take(N * 2))
      if (next.sameElements(expected)) out = Some((c, v)) else expected = next
    }
    out.getOrElse(sys.error("accept script did not stabilise"))
  }

  /** The REJECT twin: the same shape, expecting the RE-WRITTEN window instead. */
  private lazy val (rejectCanonical, rejectVariant) = {
    val c = ser(sliceScript(bs(acceptCanonical.take(N * 2))))
    (c, overlongCount(c))
  }

  // ── the block, derived ────────────────────────────────────────────────────────────────────
  private final case class Built(parentDigest: String, postDigest: String, proofHex: String,
                                 canonical: String, variant: String, boxA: ErgoBox,
                                 boxXCanonicalBytes: String, boxXId: String, header: Json,
                                 txs: Seq[ErgoTransaction]) {
    private def headerId: String = header.hcursor.get[String]("id").fold(e => sys.error(s"header id: $e"), identity)

    /** The section as the JVM writer frames it: the created box's tree RE-WRITTEN. Lazy, so it is
      * framed where it is first read — in `rows`, outside the version context `build` runs in, as
      * BlockSectionBytes requires. */
    lazy val sectionCanonical: String = {
      val s = BlockSectionBytes.hexOf(headerId, BlockVersion.toByte, txs)
      require(s.sliding(canonical.length).count(_ == canonical) == 1,
        "the written tree must appear exactly once in the section")
      s
    }

    /** The section AS RECEIVED: `variant` spliced back over the re-written tree. Checked to reach the
      * parsed tree and nothing else — the same transactions (ids), the created box reading `variant`. */
    lazy val sectionSpliced: String = {
      val s = sectionCanonical.replace(canonical, variant)
      val back = BlockSectionBytes.parse(s)
      require(back.txs.map(_.id) == txs.map(_.id), "the splice moved a transaction id")
      require(hx(back.txs.head.outputs.head.ergoTree.bytes) == variant,
        "the parsed created box does not read the spliced bytes")
      s
    }
  }

  /** Build the block for one arm: tx1 spends a stored box and creates the box whose tree is
    * `variant`; tx2 spends that box in the same block. Asserts the embedded header commits to
    * every root this derives. */
  private def build(canonical: String, variant: String, headerJson: String): Built =
    VersionContext.withVersions(A, A) {
      val boxA = box("santa:sbk:stored", V, 1)
      val treeX = DefaultSerializer.deserializeErgoTree(bs(variant))
      val candX = new ErgoBoxCandidate(V, treeX, creationHeight = 1)
      val tx1 = tx(Seq(input(boxA, ext())), Seq(candX))
      val boxX = tx1.outputs.head
      val tx2 = tx(Seq(input(boxX, ext())), Seq(candidate(V, 1)))
      val boxY = tx2.outputs.head
      val txs: Seq[ErgoTransaction] = Seq(ErgoTransaction(tx1), ErgoTransaction(tx2))

      // the AVL state: boxA in; then the block's changes, which do NOT include boxX
      val prover = new BatchAVLProver[Digest32, Blake2b256.type](keyLength = 32, valueLengthOpt = None)
      prover.performOneOperation(Insert(boxA.id, ADValue @@ boxA.bytes)).get
      prover.generateProof() // flush: the digest below is the state BEFORE the block
      val parentDigest = hx(prover.digest)
      prover.performOneOperation(Remove(boxA.id)).get               // the engine's order:
      prover.performOneOperation(Insert(boxY.id, ADValue @@ boxY.bytes)).get // removes, then appends
      val proofHex = hx(prover.generateProof())
      val postDigest = hx(prover.digest)

      val header = parse(headerJson).fold(e => sys.error(s"header json: $e"), identity)
      def hdr(f: String): String = header.hcursor.get[String](f)
        .fold(e => sys.error(s"header.$f: $e"), identity)
      // the embedded header must commit to what we just derived, or it was mined for another body
      require(hdr("stateRoot") == postDigest,
        s"header.stateRoot ${hdr("stateRoot")} != derived $postDigest — re-mine (spike 4b)")
      require(hdr("adProofsRoot") == hx(ADProofs.proofDigest(SerializedAdProof @@ bs(proofHex))),
        s"header.adProofsRoot ${hdr("adProofsRoot")} != blake2b256(proof) — re-mine (spike 4b)")
      require(hdr("transactionsRoot") ==
        hx(BlockTransactions.transactionsRoot(txs, BlockVersion.toByte)),
        s"header.transactionsRoot ${hdr("transactionsRoot")} != derived — re-mine (spike 4b)")

      Built(parentDigest, postDigest, proofHex, canonical, variant, boxA,
        hx(boxX.bytes), hx(boxX.id), header, txs)
    }

  private lazy val acceptBlock = build(acceptCanonical, acceptVariant, AcceptHeaderJson)
  private lazy val rejectBlock = build(rejectCanonical, rejectVariant, RejectHeaderJson)

  // ── blessing ──────────────────────────────────────────────────────────────────────────────
  private def blockJsonOf(b: Built, section: String): Json = {
    val id = b.header.hcursor.get[String]("id").getOrElse(sys.error("header id"))
    Json.obj(
      "header"            -> b.header,
      "blockTransactions" -> Json.fromString(section),
      "adProofs"          -> Json.obj("headerId" -> Json.fromString(id),
                                      "proofBytes" -> Json.fromString(b.proofHex)),
      "extension"         -> Json.obj("headerId" -> Json.fromString(id),
                                      "digest" -> b.header.hcursor.get[String]("extensionHash")
                                        .fold(e => sys.error(s"extensionHash: $e"), Json.fromString),
                                      "fields" -> Json.arr(Json.arr(
                                        Json.fromString("0100"),
                                        Json.fromString("01cdfe9f0c0bd0c631431c236de2aabc04aab4f236e090ca32e85a5ae064145558")))))
  }

  /** One row; `description` is its role, emitted as the entry's prose. */
  private def entry(name: String, description: String, b: Built, section: String,
                    extraBoxes: Seq[(String, String)], want: Boolean): Json = {
    require(description.nonEmpty, s"$name: every row states its role")
    val boxes = ((hx(b.boxA.id) -> hx(b.boxA.bytes)) +: extraBoxes).map { case (id, hex) =>
      Json.obj("boxId" -> Json.fromString(id), "bytes" -> Json.fromString(hex)) }
    val block = blockJsonOf(b, section)
    val v = BlockEngine.validate(block, Vector(parse(ParentHeaderJson)
      .fold(e => sys.error(s"parent header json: $e"), identity)),
      ParamsTable.map { case (k, x) => k.toInt -> x },
      boxes.map { j =>
        val c = j.hcursor
        c.get[String]("boxId").getOrElse("?") -> bs(c.get[String]("bytes").getOrElse(""))
      }.toVector, b.parentDigest)
    require(v.valid == want,
      s"$name: want valid=$want, got valid=${v.valid} ${v.reason.getOrElse("")}")
    Json.obj(
      "name"          -> Json.fromString(name),
      "source"        -> Json.fromString(s"santa:authored-block-kept-tree-bytes:$name"),
      "description"   -> Json.fromString(description),
      "parent_digest" -> Json.fromString(b.parentDigest),
      "headers"       -> Json.arr(parse(ParentHeaderJson)
        .fold(e => sys.error(s"parent header json: $e"), identity)),
      "parameters"    -> Json.obj("table" -> Json.obj(
        ParamsTable.toSeq.sortBy(_._1.toInt).map { case (k, x) => k -> Json.fromInt(x) }: _*)),
      "block"         -> block,
      "boxes"         -> Json.arr(boxes: _*),
      "version"       -> Json.obj("activated" -> Json.fromInt(A.toInt), "ergoTree" -> Json.fromInt(0)),
      "expected"      -> Json.obj(
        "valid"       -> Json.fromBoolean(v.valid),
        "post_digest" -> v.postDigest.map(Json.fromString).getOrElse(Json.Null),
        "cost"        -> v.cost.map(Json.fromLong).getOrElse(Json.Null),
        "reason"      -> v.reason.map(Json.fromString).getOrElse(Json.Null)))
  }

  private val note =
    "The block is synthetic: height 1 at testnet's own initial difficulty 1, mined in milliseconds " +
    "(a captured donor cannot carry authored transactions — the header is PoW-sealed over the " +
    "transactions and ADProofs roots). The created box DROPS OUT of the state changes entirely, so " +
    "the AVL proof covers only the stored input and the final output."

  def entries: Seq[Json] = rows.map { case (n, d, b, t, x, w) => entry(n, d, b, t, x, w) }

  private def rows: Seq[(String, String, Built, String, Seq[(String, String)], Boolean)] = Seq(
    ("in-block-created-as-received-accept#0",
      "tx1 creates a box whose tree is a NON-CANONICAL encoding (an overlong VLQ constants count, " +
      s"`${acceptVariant.take(6)}…` for `${acceptCanonical.take(6)}…`) and tx2 SPENDS it in the " +
      "same block. Its script requires the first 3 bytes of `SELF.propositionBytes` to be the " +
      "AS-RECEIVED window, and the block is VALID: the node resolves a spent box among the block's " +
      "own outputs first, and that box is the in-memory box derived from the PARSED creating " +
      "transaction, whose tree keeps that transaction's bytes. **This is the row that makes the " +
      s"whole kept-bytes class reachable on chain** — a box in a store never holds this encoding, " +
      s"because the box writer re-writes. $note", acceptBlock, acceptBlock.sectionSpliced, Nil, true),

    ("in-block-created-rewritten-expectation-reject#1",
      "The reject twin: the identical block shape, with the created box's script expecting the " +
      "RE-WRITTEN window instead. The JVM reads the span the transaction carried, the comparison " +
      "is false, the spend fails and the BLOCK is invalid. **SEPARATING:** an implementation that " +
      "re-writes a tree on parse compares its own output, finds it equal, and accepts this block — " +
      s"and it also rejects #0, so it is wrong in both directions on the pair. $note",
      rejectBlock, rejectBlock.sectionSpliced, Nil, false),

    ("in-block-created-canonical-control-reject#2",
      "**CONTROL** for #0: the SAME block, with the created box's tree left in the writer's own " +
      "form in the section. The script still requires the as-received window, so the " +
      "comparison is now false and the block is invalid. The transactions root is unchanged — it " +
      "is computed over the SERIALIZED transactions, which re-write the tree either way, which is " +
      "also why #0's splice is invisible to the header. Without this row #0 could be matching a " +
      "constant that happens to agree.",
      acceptBlock, acceptBlock.sectionCanonical, Nil, false),

    ("in-block-shadowed-by-store-copy-accept#3",
      "**THE LOOKUP ORDER, graded.** The same valid block as #0, but the vector ALSO supplies the " +
      "created box in `boxes` — the store side — under the same id, carrying the CANONICAL " +
      "encoding. The two encodings share a box id (the id is over the writer's form), so a store " +
      "copy can shadow a block output. The JVM resolves the block's own outputs FIRST " +
      "(`UtxoState.scala:77-81`, `DigestState.scala:47-49`) and so still sees the as-received " +
      "bytes: VALID. **SEPARATING:** an implementation that consults its store first sees the " +
      "canonical bytes and rejects. It is the only row in the corpus that grades that precedence.",
      acceptBlock, acceptBlock.sectionSpliced,
      Seq(acceptBlock.boxXId -> acceptBlock.boxXCanonicalBytes), true))

  def blessAll(): Seq[(String, Json)] = Seq(Path -> Json.obj(
    "schema"     -> Json.fromString("santa-block/v1"),
    "op"         -> Json.fromString("block:authored:kept-tree-bytes-in-block"),
    "blessed_by" -> Json.fromString(BlessedBy),
    "entries"    -> Json.arr(entries: _*)))

  def writeVectors(blessed: Seq[(String, Json)], vectorsRoot: java.nio.file.Path): Unit =
    blessed.foreach { case (rel, env) =>
      val f = vectorsRoot.resolve(rel)
      java.nio.file.Files.createDirectories(f.getParent)
      java.nio.file.Files.write(f, env.spaces2.getBytes(java.nio.charset.StandardCharsets.UTF_8))
    }
}
