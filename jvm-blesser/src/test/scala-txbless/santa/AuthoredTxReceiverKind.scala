package santa

// AuthoredTxReceiverKind — the CONSENSUS form of the receiver-kind family, and the tier where it
// matters. Each of the seven `sigmaProp(row == control)` trees guards a box, and the box is spent.
//
// The wire half shows all seven parse and round-trip verbatim; the eval half shows six of them
// throw. This half is what makes that a consensus question: the box is one anyone can create (it
// parses, it is a legal script, a node carries it happily), and on the JVM six of the seven spends
// FAIL while the control spends at cost 12109. An implementation whose evaluators never read their
// receiver reduces all seven to `TrivialProp(true)` and would let a spender take every one of
// them — an over-accept on a reachable box, which is the strongest shape a finding takes here.
//
// Blessed through TxEngine.validateBytes (ergo-core 6.0.7 validateStateful) under the storage-rent
// vectors' synthetic context, as every tx probe is.

import io.circe.Json
import scorex.util.encode.Base16
import sigma.VersionContext
import sigma.serialization.ErgoTreeSerializer
import santa.runner.TxEngine

import RentFixtures._

object AuthoredTxReceiverKind {
  val Path = "transaction/v6/authored/receiver-kind.json"
  val Source = "santa:authored-receiver-kind"
  private val Activated = 3
  private val V = 1000000000L
  private val H = AuthoredTxStorageRent.H

  private def b(h: String): Array[Byte] = Base16.decode(h).get
  private def ctx[A](f: => A): A = VersionContext.withVersions(Activated.toByte, Activated.toByte)(f)

  def extract(): Json = {
    val entries = ReceiverKindTrees.wrappedTrees.zipWithIndex.map { case ((n, treeHex), i) =>
      val r = ReceiverKindTrees.rows.find(_.n == n).getOrElse(sys.error(s"row $n"))
      val isControl = n == ReceiverKindTrees.SpendControlRow
      val bx = ctx(box(s"santa:rk:$n", V, 1, ErgoTreeSerializer.DefaultSerializer.deserializeErgoTree(b(treeHex))))
      val txHex = ctx(hex(txBytes(tx(Seq(input(bx, ext())), Seq(candidate(V, 1))))))
      val v = TxEngine.validateBytes(txHex, Seq(hex(bx.bytes)), Nil,
        AuthoredTxStorageRent.headersHex, AuthoredTxStorageRent.preHeader, AuthoredTxStorageRent.params)
      require(v.valid == isControl,
        s"row $n: want valid=$isControl, got ${v.valid} ${v.reason.getOrElse("")}")
      val tail =
        if (isControl)
          "THE CONTROL: both sides of the EQ are the well-formed call, so the script reduces to " +
          "true and the box SPENDS at cost 12109. It is what makes the six invalid rows about the " +
          "receiver rather than about the shape of the carrier."
        else
          "**THE SEPARATING ROW, and the consensus form of this family.** The box parses, " +
          "round-trips verbatim (see the wire half) and a node carries it happily — so this is a " +
          "box ANYONE CAN CREATE. On the JVM the script fails at eval and the spend is INVALID. " +
          "An implementation whose evaluator never reads its receiver reduces the tree to " +
          "`TrivialProp(true)` and lets a spender TAKE the box: an over-accept on reachable state."
      Json.obj(
        "name"                 -> Json.fromString(s"receiver-kind-spend-${r.slug}#$i"),
        "source"               -> Json.fromString(Source),
        "description"          -> Json.fromString(s"`sigmaProp(row $n == control)` guards the box " +
          s"being SPENT. ${r.what} $tail"),
        "tx_bytes_hex"         -> Json.fromString(txHex),
        "input_boxes_hex"      -> Json.arr(Json.fromString(hex(bx.bytes))),
        "data_input_boxes_hex" -> Json.arr(),
        "headers_hex"          -> Json.arr(AuthoredTxStorageRent.headersHex.map(Json.fromString): _*),
        "preHeader"            -> AuthoredTxStorageRent.preHeader,
        "parameters"           -> AuthoredTxStorageRent.params,
        "context"              -> Json.obj("height" -> Json.fromInt(H)),
        "version"              -> Json.obj("activated" -> Json.fromInt(Activated),
                                           "ergoTree"  -> Json.fromInt(Activated)),
        "expected"             -> Json.obj(
          "valid"  -> Json.fromBoolean(v.valid),
          "cost"   -> v.cost.map(Json.fromLong).getOrElse(Json.Null),
          "reason" -> v.reason.map(Json.fromString).getOrElse(Json.Null)))
    }
    Json.obj(
      "schema"     -> Json.fromString("santa-transaction/v1"),
      "op"         -> Json.fromString("receiver-kind"),
      "blessed_by" -> Json.fromString("jvm:ergo-core-6.0.7-validateStateful"),
      "entries"    -> Json.arr(entries: _*))
  }

  def writeVectors(vectorsRoot: java.nio.file.Path): Unit = {
    val path = vectorsRoot.resolve(Path)
    java.nio.file.Files.createDirectories(path.getParent)
    java.nio.file.Files.write(path, extract().spaces2.getBytes(java.nio.charset.StandardCharsets.UTF_8))
  }
}
