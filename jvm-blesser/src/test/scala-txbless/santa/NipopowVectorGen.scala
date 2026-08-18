package santa

import io.circe.Json
import io.circe.syntax._
import org.ergoplatform.mining.DefaultFakePowScheme
import org.ergoplatform.modifiers.history.extension.ExtensionCandidate
import org.ergoplatform.modifiers.history.header.{Header, HeaderSerializer}
import org.ergoplatform.modifiers.history.popow._
import org.ergoplatform.settings.{ChainSettings, ChainSettingsReader}
import scorex.crypto.authds.ADDigest
import scorex.crypto.hash.Digest32
import scorex.util.{ModifierId, idToBytes}

import java.io.{File, PrintWriter}

object NipopowVectorGen {

  private val ChainConf = "src/test/resources/chain-fakepow.conf"
  private val OutputDir = "../vectors/nipopow/any/authored"

  private val Schema = "santa-nipopow/v1"
  private val BlessedBy = "jvm:ergo-core-6.0.2.1-NipopowAlgos"

  private val HashLen = 32
  private val EmptyDigest32: Digest32 = Digest32 @@ Array.fill(HashLen)(0: Byte)
  private val EmptyStateRoot: ADDigest = ADDigest @@ Array.fill(HashLen + 1)(0: Byte)

  private val MinerSecret: BigInt = BigInt("12345678901234567890")

  def chainSettings: ChainSettings =
    ChainSettingsReader.read(ChainConf).getOrElse(sys.error(s"Failed to read $ChainConf"))

  def generate(): Unit = {
    val cs = chainSettings
    val powScheme = cs.powScheme.asInstanceOf[DefaultFakePowScheme]
    val nipopow = new NipopowAlgos(cs)

    generateChain("jvm-chain-32", 32, powScheme, nipopow, cs)
    generateChain("jvm-chain-64", 64, powScheme, nipopow, cs)
  }

  // Builds one nipopow_prove entry: payload = {m, k, headerId}, expected = {proofHex}.
  // headerIdHex is None for a tip-relative proof (payload.headerId -> null), Some(hex) for
  // an anchored proof (truncated chain, proving up to a fixed headerId).
  private def proveEntry(
      m: Int,
      k: Int,
      headerIdHex: Option[String],
      nameSuffix: String,
      sourceLabel: String,
      proofBytes: Array[Byte]
  ): Json =
    Json.obj(
      "name" -> s"prove-m${m}-k${k}-$nameSuffix".asJson,
      "kind" -> "nipopow_prove".asJson,
      "source" -> s"santa:authored:$sourceLabel".asJson,
      "payload" -> Json.obj(
        "m" -> m.asJson,
        "k" -> k.asJson,
        "headerId" -> headerIdHex.fold(Json.Null)(_.asJson)
      ),
      "expected" -> Json.obj("proofHex" -> bytesToHex(proofBytes).asJson)
    )

  private def generateChain(
      label: String,
      length: Int,
      powScheme: DefaultFakePowScheme,
      nipopow: NipopowAlgos,
      cs: ChainSettings
  ): Unit = {
    val popowHeaders = new Array[PoPowHeader](length)

    // Genesis: extensionRoot = empty extension digest (no interlinks on-chain).
    // PoPowHeader convention: interlinks = [genesis.id].
    val emptyExt = ExtensionCandidate(Seq.empty)
    val genesis = powScheme.prove(
      None, Header.InitialVersion, cs.initialNBits,
      EmptyStateRoot, EmptyDigest32, EmptyDigest32,
      1700000000000L, emptyExt.digest,
      Array.fill(3)(0: Byte), MinerSecret
    ).get

    val genesisInterlinks: Seq[ModifierId] = Seq(genesis.id)
    val genesisInterlinkExt = ExtensionCandidate(NipopowAlgos.packInterlinks(genesisInterlinks))
    val genesisProof = NipopowAlgos.proofForInterlinkVector(genesisInterlinkExt)
      .getOrElse(sys.error("Failed to build genesis interlink proof"))
    popowHeaders(0) = PoPowHeader(genesis, genesisInterlinks, genesisProof)

    for (i <- 1 until length) {
      val prev = popowHeaders(i - 1)

      // Interlinks OF this block, computed from the previous block.
      val interlinks = nipopow.updateInterlinks(prev.header, prev.interlinks)

      // Extension with these interlinks — its digest becomes the header's extensionRoot.
      val ext = ExtensionCandidate(NipopowAlgos.packInterlinks(interlinks))

      val ts = 1700000000000L + i * 120000L
      val header = powScheme.prove(
        Some(prev.header), Header.InitialVersion, cs.initialNBits,
        EmptyStateRoot, EmptyDigest32, EmptyDigest32,
        ts, ext.digest,
        Array.fill(3)(0: Byte), MinerSecret
      ).get

      val proof = NipopowAlgos.proofForInterlinkVector(ext)
        .getOrElse(sys.error(s"Failed to build interlink proof at height ${header.height}"))
      popowHeaders(i) = PoPowHeader(header, interlinks, proof)
    }

    val serializer = new NipopowProofSerializer(nipopow)
    val chain = popowHeaders.toSeq

    val tipCases = Seq(
      (2, 2),
      (3, 3),
      (6, 5)
    ).flatMap { case (m, k) =>
      if (chain.length >= m + k) {
        nipopow.prove(chain)(PoPowParams(m, k, continuous = false)) match {
          case scala.util.Success(proof) =>
            Some(proveEntry(m, k, None, "tip", "NipopowAlgos.prove", serializer.toBytes(proof)))
          case scala.util.Failure(ex) =>
            System.err.println(s"WARN: prove failed for m=$m k=$k on $label: ${ex.getMessage}")
            None
        }
      } else None
    }

    // Anchored case: truncate chain at headerId + k - 1, then prove the truncated chain.
    val midIdx = length / 2 - 1
    val anchoredCases = Seq((2, 2)).flatMap { case (m, k) =>
      val endIdx = math.min(midIdx + k, length - 1)
      val truncated = chain.take(endIdx + 1)
      if (truncated.length >= m + k) {
        nipopow.prove(truncated)(PoPowParams(m, k, continuous = false)) match {
          case scala.util.Success(proof) =>
            val anchor = chain(midIdx)
            val anchorIdHex = bytesToHex(idToBytes(anchor.id))
            Some(proveEntry(
              m, k, Some(anchorIdHex), s"anchored-h${anchor.header.height}",
              "truncated-prove", serializer.toBytes(proof)
            ))
          case scala.util.Failure(ex) =>
            System.err.println(s"WARN: anchored prove failed for $label: ${ex.getMessage}")
            None
        }
      } else None
    }

    val chainJson = chain.map { ph =>
      val headerBytes = HeaderSerializer.toBytes(ph.header)
      val interlinksArr = ph.interlinks.map(id => bytesToHex(idToBytes(id)).asJson)
      Json.obj(
        "height" -> ph.header.height.asJson,
        "headerHex" -> bytesToHex(headerBytes).asJson,
        "interlinks" -> Json.arr(interlinksArr: _*)
      )
    }

    val vectorJson = Json.obj(
      "schema" -> Schema.asJson,
      "blessed_by" -> BlessedBy.asJson,
      "chain" -> Json.arr(chainJson: _*),
      "entries" -> Json.arr(
        Json.obj(
          "name" -> "interlinks".asJson,
          "kind" -> "nipopow_interlinks".asJson,
          "source" -> "santa:authored:jvm-chain".asJson,
          "expected" -> Json.obj()
        ) +: (tipCases ++ anchoredCases): _*
      )
    )

    val outDir = new File(OutputDir)
    outDir.mkdirs()
    val outFile = new File(outDir, s"NipopowProve.$label.json")
    val pw = new PrintWriter(outFile)
    pw.write(vectorJson.spaces2)
    pw.close()

    // Diagnostic: print maxLevel per height
    chain.foreach { ph =>
      val lvl = if (ph.header.height == 1) "genesis" else nipopow.maxLevelOf(ph.header).toString
      println(f"  h=${ph.header.height}%2d level=$lvl%7s interlinks=${ph.interlinks.size}")
    }
    println(s"Wrote ${outFile.getAbsolutePath} ($length headers, ${tipCases.size + anchoredCases.size} cases)")
  }

  private def bytesToHex(bytes: Array[Byte]): String =
    bytes.map(b => f"${b & 0xff}%02x").mkString
}
