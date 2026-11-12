package santa

// AuthoredTxNumericReceiverWidth — the CONSENSUS form of the numeric receiver-width family
// (`AuthoredEvalNumericReceiverWidth`, sigma-rust's santa-probe-receiver-kind-2, 2026-11-12), built
// at that session's request as round 1's `AuthoredTxReceiverKind` was. Each of the twelve eval rows
// guards a box as `sigmaProp(row == row)`, and the box is spent.
//
// WHY `row == row` AND NOT `row == control`, as round 1 had. Round 1's wrong receivers were of
// another KIND, and an evaluator that ignored its receiver returned the control's value, so
// `row == control` reduced to true there. Here the receiver is of another WIDTH, and the evaluator
// that misses the owner's cast computes at the OPERAND's width: on the fork before its PR 82,
// `SLong.shiftLeft(1)` on `Int 1` gave `Int 2` where the control gives `Long 2`, and the toBytes rows
// give the operand's bytes. `row == control` would then be false on that evaluator too, the spend
// would fail on both sides, and the vector would catch nothing. `row == row` is true on ANY evaluator
// that returns a value, whatever value, so it makes the over-accept an actual accept; on the JVM the
// left operand throws first and the spend is invalid.
//
// The controls (N0, N5) are the same wrapper around the right-width call: they spend, which shows
// the wrapper and the carrier are sound and the ten invalid rows are about the operand's width.
//
// Blessed through TxEngine.validateBytes (ergo-core 6.0.7 validateStateful) under the storage-rent
// vectors' synthetic context, as every tx probe is.

import io.circe.Json
import scorex.util.encode.Base16
import sigma.VersionContext
import sigma.ast._
import sigma.serialization.ErgoTreeSerializer
import santa.runner.TxEngine

import RentFixtures._

object AuthoredTxNumericReceiverWidth {
  val Path = "transaction/v6/authored/numeric-receiver-width.json"
  val Op = "numeric-receiver-width"
  val Source = "santa:authored-numeric-receiver-width"
  private val Activated = 3
  private val V = 1000000000L
  private val H = AuthoredTxStorageRent.H

  private def b(h: String): Array[Byte] = Base16.decode(h).get
  private def ctx[A](f: => A): A = VersionContext.withVersions(Activated.toByte, Activated.toByte)(f)

  /** The ids of the rows whose call is well-formed: their spends are the controls. */
  val Controls: Set[String] = Set("N0", "N5")

  /** `sigmaProp(row == row)` as a size-flagged v3 tree, through the eval half's own tree writer. */
  def wrapped(row: () => Value[SType]): String = ctx {
    AuthoredEvalNumericReceiverWidth.tree(
      BoolToSigmaProp(EQ(row(), row())).asInstanceOf[Value[SType]])
  }

  def extract(): Json = {
    val entries = AuthoredEvalNumericReceiverWidth.Rows.zipWithIndex.map { case ((id, what, _, ast, owner), i) =>
      val isControl = Controls(id)
      val treeHex = wrapped(ast)
      val bx = ctx(box(s"santa:nrw:$id", V, 1, ErgoTreeSerializer.DefaultSerializer.deserializeErgoTree(b(treeHex))))
      val txHex = ctx(hex(txBytes(tx(Seq(input(bx, ext())), Seq(candidate(V, 1))))))
      val v = TxEngine.validateBytes(txHex, Seq(hex(bx.bytes)), Nil,
        AuthoredTxStorageRent.headersHex, AuthoredTxStorageRent.preHeader, AuthoredTxStorageRent.params)
      require(v.valid == isControl,
        s"row $id: want valid=$isControl, got ${v.valid} ${v.reason.getOrElse("")}")
      val tail = owner match {
        case None =>
          "THE CONTROL: the call is well-formed, so both operands evaluate, the script reduces to " +
          "true and the box SPENDS. It is what makes the invalid rows about the operand's width " +
          "rather than about the wrapper or the carrier."
        case Some(cls) =>
          val side = if (id == "A1") "argument" else "receiver"
          s"**THE SEPARATING ROW.** The box parses and a node carries it, so ANYONE CAN CREATE it. " +
          s"On the JVM the left operand throws `ClassCastException` to `$cls` (the handler casts the " +
          s"$side to the owner's class) and the spend is INVALID. An evaluator that computes at the " +
          "operand's width returns a value, and `x == x` is true for any value it returns, so it " +
          "lets a spender TAKE the box: an over-accept on reachable state."
      }
      Json.obj(
        "name"                 -> Json.fromString(s"numeric-receiver-width-spend-${id.toLowerCase}#$i"),
        "source"               -> Json.fromString(Source),
        "description"          -> Json.fromString(s"[$id] `sigmaProp(row == row)` around `$what` guards " +
          s"the box being SPENT. $tail"),
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
      "op"         -> Json.fromString(Op),
      "blessed_by" -> Json.fromString("jvm:ergo-core-6.0.7-validateStateful"),
      "entries"    -> Json.arr(entries: _*))
  }

  def writeVectors(vectorsRoot: java.nio.file.Path): Unit = {
    val path = vectorsRoot.resolve(Path)
    java.nio.file.Files.createDirectories(path.getParent)
    java.nio.file.Files.write(path, extract().spaces2.getBytes(java.nio.charset.StandardCharsets.UTF_8))
  }
}
