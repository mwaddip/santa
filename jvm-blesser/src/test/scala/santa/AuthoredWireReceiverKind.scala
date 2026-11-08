package santa

// Authored wire vectors — the seven `sigmaProp(row == control)` trees from the receiver-kind probe.
//
// ALL SEVEN ARE ACCEPTS, and this family is deliberately a set of CONTROLS rather than evidence.
// `MethodCallSerializer.parse` checks no receiver, so the JVM parses every one and round-trips it
// verbatim — and so does every implementation the probe describes. Nothing here separates.
//
// It is worth shipping anyway, for one reason: it is what licenses the eval and transaction halves.
// Those say a wrong receiver FAILS, and a reader could take that to mean the bytes are refused
// somewhere. These rows pin that the refusal is at EVAL and nowhere earlier — the tree is a
// perfectly legal script, it parses, it round-trips, and a node will happily carry it in a box.
// If one of these ever goes red the eval rows stop meaning what they say.
//
// Row 1 is the control of the controls: `sigmaProp(control == control)`, which also REDUCES, so it
// is the one of the seven whose box can be spent. See `AuthoredTxReceiverKind`.

import io.circe.Json

object AuthoredWireReceiverKind {
  val Op     = "ErgoTree.receiver_kind"
  val Source = "santa:authored-receiver-kind"
  private val Activated: Byte = ReceiverKindTrees.Activated

  private def accept(name: String, description: String, in: String): Json = {
    val out = WireCanonicalize.canonicalize("ErgoTree", in, Activated, Activated)
    require(out == in, s"$name: the JVM must round-trip an accept vector to itself — in $in, out $out")
    Json.obj(
      "name"        -> Json.fromString(name),
      "kind"        -> Json.fromString("ErgoTree"),
      "source"      -> Json.fromString(Source),
      "description" -> Json.fromString(description),
      "bytes_hex"   -> Json.fromString(in),
      "version"     -> Json.obj("activated" -> Json.fromInt(Activated.toInt),
                                "ergoTree"  -> Json.fromInt(Activated.toInt)))
  }

  def extract(): Map[String, Json] = {
    val entries = ReceiverKindTrees.wrappedTrees.zipWithIndex.map { case ((n, tree), i) =>
      val r = ReceiverKindTrees.rows.find(_.n == n).getOrElse(sys.error(s"row $n"))
      val tail =
        if (n == ReceiverKindTrees.SpendControlRow)
          "The CONTROL of the controls: both sides are the well-formed call, so it parses AND " +
          "reduces to `SigmaProp(true)` at cost 96 — the one of the seven whose box can be spent."
        else
          "A CONTROL, not evidence: the wrong receiver is refused at EVAL and nowhere earlier, so " +
          "this tree is a legal script that parses and round-trips verbatim on every " +
          "implementation. It is here to license the eval and transaction rows — if it ever goes " +
          "red, their reds stop meaning what they say."
      accept(s"receiver-kind-${r.slug}-wrapped-accept#$i",
        s"`sigmaProp(row ${n} == control)`. ${r.what} $tail", tree)
    }
    Map(Op -> Json.obj(
      "schema"     -> Json.fromString("santa-wire/v1"),
      "op"         -> Json.fromString(Op),
      "blessed_by" -> Json.fromString("jvm:sigma-state-6.0.7"),
      "entries"    -> Json.arr(entries: _*)))
  }

  def writeVectors(outDir: java.nio.file.Path): Unit =
    SpecExtract.writeStaging("AuthoredWireReceiverKind", extract(), outDir)
}
