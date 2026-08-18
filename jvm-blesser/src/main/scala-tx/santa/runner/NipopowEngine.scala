package santa.runner

import io.circe.Json
import io.circe.syntax._

import org.ergoplatform.modifiers.history.extension.ExtensionCandidate
import org.ergoplatform.modifiers.history.header.{Header, HeaderSerializer}
import org.ergoplatform.modifiers.history.popow._
import org.ergoplatform.settings.{ChainSettings, ChainSettingsReader}
import scorex.util.{ModifierId, bytesToId, idToBytes}

object NipopowEngine {
  private val ChainConf = "src/test/resources/chain-fakepow.conf"

  private lazy val cs: ChainSettings =
    ChainSettingsReader.read(ChainConf).getOrElse(sys.error(s"Failed to read $ChainConf"))
  private lazy val nipopow = new NipopowAlgos(cs)
  private lazy val serializer = new NipopowProofSerializer(nipopow)

  private def hexToBytes(hex: String): Array[Byte] =
    hex.grouped(2).map(Integer.parseInt(_, 16).toByte).toArray

  private def bytesToHex(bytes: Array[Byte]): String =
    bytes.map(b => f"${b & 0xff}%02x").mkString

  def nipopowEntry(chainJson: Vector[Json], e: Json): (String, Json) = {
    val c    = e.hcursor
    val name = c.get[String]("name").toOption.getOrElse("?")
    try {
      val kind = c.get[String]("kind").toOption
        .getOrElse(sys.error(s"nipopow entry '$name': missing kind"))

      kind match {
        case "nipopow_interlinks" =>
          val popowHeaders = buildPoPowChain(chainJson)
          val interlinksArrays = popowHeaders.map { ph =>
            Json.arr(ph.interlinks.map(id => Json.fromString(bytesToHex(idToBytes(id)))): _*)
          }
          name -> Json.obj(
            "interlinks" -> Json.arr(interlinksArrays: _*),
            "error" -> Json.Null)

        case "nipopow_prove" =>
          val popowHeaders = buildPoPowChain(chainJson)
          val m = c.downField("payload").get[Int]("m").toOption
            .getOrElse(sys.error(s"nipopow entry '$name': missing payload.m"))
          val k = c.downField("payload").get[Int]("k").toOption
            .getOrElse(sys.error(s"nipopow entry '$name': missing payload.k"))
          val headerIdOpt = c.downField("payload").get[String]("headerId").toOption

          val chain = if (headerIdOpt.isDefined) {
            val hid = headerIdOpt.get
            val idx = popowHeaders.indexWhere(ph => bytesToHex(idToBytes(ph.id)) == hid.toLowerCase)
            if (idx < 0) sys.error(s"nipopow entry '$name': headerId $hid not found in chain")
            popowHeaders.take(idx + k + 1)
          } else {
            popowHeaders
          }

          val proof = nipopow.prove(chain)(PoPowParams(m, k, continuous = false))
            .getOrElse(sys.error(s"nipopow entry '$name': prove failed"))
          val proofBytes = serializer.toBytes(proof)
          name -> Json.obj(
            "proofHex" -> Json.fromString(bytesToHex(proofBytes)),
            "error" -> Json.Null)

        case _ =>
          name -> Json.obj("error" -> Json.fromString("not-implemented"))
      }
    } catch {
      case scala.util.control.NonFatal(t) =>
        name -> Json.obj(
          "error" -> Json.fromString("panicked"),
          "note"  -> Json.fromString(s"${t.getClass.getName}: ${Option(t.getMessage).getOrElse("")}"))
    }
  }

  private def buildPoPowChain(chainJson: Vector[Json]): Seq[PoPowHeader] = {
    val headers = chainJson.map { hj =>
      val hex = hj.hcursor.get[String]("headerHex").toOption.getOrElse(sys.error("missing headerHex"))
      HeaderSerializer.parseBytes(hexToBytes(hex))
    }

    val popowHeaders = new Array[PoPowHeader](headers.length)

    // Genesis: interlinks = [genesis.id]
    val genesisInterlinks: Seq[ModifierId] = Seq(headers(0).id)
    val genesisExt = ExtensionCandidate(NipopowAlgos.packInterlinks(genesisInterlinks))
    val genesisProof = NipopowAlgos.proofForInterlinkVector(genesisExt)
      .getOrElse(sys.error("Failed to build genesis interlink proof"))
    popowHeaders(0) = PoPowHeader(headers(0), genesisInterlinks, genesisProof)

    for (i <- 1 until headers.length) {
      val prev = popowHeaders(i - 1)
      val interlinks = nipopow.updateInterlinks(prev.header, prev.interlinks)
      val ext = ExtensionCandidate(NipopowAlgos.packInterlinks(interlinks))
      val proof = NipopowAlgos.proofForInterlinkVector(ext)
        .getOrElse(sys.error(s"Failed to build interlink proof at height ${headers(i).height}"))
      popowHeaders(i) = PoPowHeader(headers(i), interlinks, proof)
    }
    popowHeaders.toSeq
  }
}
