package santa
import scala.util.{Failure, Success}
import io.circe.Json
import org.ergoplatform.ErgoBox
import org.ergoplatform.http.api.ApiCodecs
import org.ergoplatform.modifiers.history.CPreHeader
import org.ergoplatform.modifiers.history.header.Header
import org.ergoplatform.modifiers.mempool.ErgoTransaction
import org.ergoplatform.nodeView.state.{UpcomingStateContext, VotingData}
import org.ergoplatform.settings.{ChainSettings, ChainSettingsReader, ErgoValidationSettings,
  ErgoValidationSettingsUpdate, Parameters, TestnetLaunchParameters}
import org.ergoplatform.wallet.interpreter.ErgoInterpreter

object TxValidate extends ApiCodecs {
  private val ChainConf = "src/test/resources/chain-testnet.conf"
  final case class Verdict(valid: Boolean, cost: Option[Long], reason: Option[String])

  /** Bless one captured tx. activated = the vector's version.activated (3 for v6);
    * blockVersion = activated + 1. ts/nBits are cosmetic (preHeader). */
  def validate(txJson: Json, inputBoxes: Seq[Json], dataInputBoxes: Seq[Json],
               height: Int, activated: Int, ts: Long = 0L, nBits: Long = 0L): Verdict = {
    implicit val chainSettings: ChainSettings =
      ChainSettingsReader.read(ChainConf).getOrElse(sys.error(s"chain settings: $ChainConf"))
    val tx = txJson.as[ErgoTransaction].fold(e => sys.error(s"tx decode: $e"), identity)
    def box(j: Json): ErgoBox = j.as[ErgoBox].fold(e => sys.error(s"box decode: $e"), identity)
    val boxesToSpend = inputBoxes.map(box).toIndexedSeq
    val dataBoxes = dataInputBoxes.map(box).toIndexedSeq
    val blockVersion = (activated + 1).toByte
    val params = new Parameters(height,
      TestnetLaunchParameters.parametersTable.updated(Parameters.BlockVersion, blockVersion.toInt),
      ErgoValidationSettingsUpdate.empty)
    val preHeader = CPreHeader(blockVersion, Header.GenesisParentId, ts, nBits, height,
      Array.fill(3)(0.toByte), org.ergoplatform.mining.group.generator)
    val ctx = UpcomingStateContext(Seq.empty, None, preHeader, chainSettings.genesisStateDigest,
      params, ErgoValidationSettings.initial, VotingData.empty)
    implicit val verifier: ErgoInterpreter = ErgoInterpreter(params)
    tx.validateStateful(boxesToSpend, dataBoxes, ctx, 0L).result.toTry match {
      case Success(cost) => Verdict(valid = true,  cost = Some(cost.toLong), reason = None)
      case Failure(e)    => Verdict(valid = false, cost = None, reason = Some(s"${e.getClass.getName}: ${e.getMessage}"))
    }
  }
}
