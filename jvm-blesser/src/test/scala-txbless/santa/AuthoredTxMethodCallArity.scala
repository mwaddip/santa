package santa

// AuthoredTxMethodCallArity — the EVAL half of the wrong-arity family. The wire half
// (`wire/v6/authored/ErgoTree.method_call_arity.json`) shows all four trees parse and round-trip;
// these spend the same four trees and record what the interpreter does with them.
//
// Blessed through TxEngine.validateBytes (ergo-core 6.0.7 validateStateful) under the storage-rent
// vectors' synthetic context (ten v4 headers below H = 1051200, the launch parameters).
//
// `MethodCall.eval` (`values.scala:1361-1393`) evaluates the object and every argument, then
// invokes a FixedCost method through `invokeFixed` — a `userDefinedInvoke` handler if it has one,
// else `javaMethod.invoke` — and any other method through `evalMethod.invoke`. Reflection fails on
// any argument count other than the method's; a `userDefinedInvoke` handler reads what it needs and
// ignores the rest. Eight methods have one (`methods.scala:311-458`): the numeric `toBytes`,
// `toBits`, `bitwiseInverse` (which read no argument) and `bitwiseOr`, `bitwiseAnd`, `bitwiseXor`,
// `shiftLeft`, `shiftRight` (which read `other.head`).
//
// So the split is by HOW the method is invoked, not by whether the arity is right:
//   bitwiseOr with a surplus argument  -> VALID, and at the SAME cost as the legal-arity control:
//                                         the handler reads other.head and the surplus is free
//   indexOf with a surplus argument    -> invalid (reflection refuses the count)
//   indexOf as a PropertyCall, no args -> invalid (same reason, zero instead of surplus)
//
// This tier is where the family actually discriminates. The wire arm cannot: the JVM parses these
// trees, an implementation that fails to parse them DEGRADES instead (every tree above version 0
// carries the size flag, so a parse failure is soft), and a degraded tree echoes its received
// bytes — identical either way.
//
// Here the two shapes come apart, but only on row 1. A tree that degraded cannot be spent, which
// happens to agree with the JVM on rows 2 and 3: those are green on a degrading implementation for
// a DIFFERENT reason than the JVM's, and the board cannot tell. Row 1 is the one row where the JVM
// spends and a degrading implementation does not — measured on fork `65c94a03`, which degrades all
// three wrong-arity trees and takes exactly this one red.

import io.circe.Json
import scorex.util.encode.Base16
import sigma.VersionContext
import org.ergoplatform.ErgoBox
import santa.runner.TxEngine

import RentFixtures._

object AuthoredTxMethodCallArity {
  val Path = "transaction/v6/authored/method-call-arity.json"
  private val V3: Byte = VersionContext.V6SoftForkVersion
  private val Activated = 3
  private val ErgoTreeV = 0
  private val V = 1000000000L                // the spent box's value
  private val H = AuthoredTxStorageRent.H

  private def b(h: String): Array[Byte] = Base16.decode(h).get

  /** A box whose tree is `treeHex`: a plain box's bytes with the tree swapped, then parsed. */
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
  private def entry(name: String, description: String, treeHex: String, want: Boolean): Json = {
    val bx = boxWithTree(s"santa:mca:$name", treeHex)
    val txHex = VersionContext.withVersions(V3, V3) {
      hex(txBytes(tx(Seq(input(bx, ext())), Seq(candidate(V, 1)))))
    }
    val v = validate(txHex, Seq(bx))
    require(v.valid == want, s"$name: want valid=$want, got valid=${v.valid} ${v.reason.getOrElse("")}")
    Json.obj(
      "name"                 -> Json.fromString(name),
      "source"               -> Json.fromString("santa:authored-method-call-arity"),
      "description"          -> Json.fromString(description),
      "tx_bytes_hex"         -> Json.fromString(txHex),
      "input_boxes_hex"      -> Json.arr(Json.fromString(hex(bx.bytes))),
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

  /** Which of the four wire trees spend, by name. */
  private val Wants = Map(
    "user-defined-invoke-extra-arg"      -> true,
    "reflective-extra-arg"               -> false,
    "property-call-of-arg-taking-method" -> false,
    "legal-arity-control"                -> true)

  private val Descriptions = Map(
    "user-defined-invoke-extra-arg" ->
      ("`1.bitwiseOr(2, 4)` — SInt method 9 is FixedCost with a `userDefinedInvoke` handler, which " +
       "reads `other.head` and ignores the surplus argument, so the spend is VALID. Its cost is " +
       "the legal-arity control's: the surplus argument is free. An impl that refuses a wrong " +
       "arity — at parse or at eval — over-rejects here."),
    "reflective-extra-arg" ->
      ("`Coll(1, 2).indexOf(2, 0, 7)` — SCollection method 26 is PerItemCost, so it is invoked " +
       "through `evalMethod`, and reflection refuses an argument count that is not the method's: " +
       "the spend is INVALID. The JVM reaches that refusal by PARSING the tree and failing at " +
       "eval. An implementation that instead degrades the tree at parse is also invalid here, for " +
       "a different reason, and this entry cannot tell the two apart — row 1 is the one that can."),
    "property-call-of-arg-taking-method" ->
      ("`Coll(1, 2).indexOf` as a PropertyCall — the method takes arguments and is given none. " +
       "Reflection refuses the count exactly as above: INVALID at eval on a tree the JVM parsed. " +
       "Same caveat as the row above: a degrading implementation agrees for the wrong reason."),
    "legal-arity-control" ->
      ("`1.bitwiseOr(2)` — the same shape at the declared arity. VALID, and the cost the surplus- " +
       "argument row must match. The control is what makes the family about arity rather than " +
       "about bitwiseOr."))

  def extract(): Json = {
    val entries = AuthoredWireMethodCallArity.Trees.zipWithIndex.map { case ((name, script, treeHex), i) =>
      entry(s"method-call-arity-$name#$i", s"${Descriptions(name)} Script: $script.", treeHex, Wants(name))
    }
    Json.obj(
      "schema"     -> Json.fromString("santa-transaction/v1"),
      "op"         -> Json.fromString("method-call-arity"),
      "blessed_by" -> Json.fromString("jvm:ergo-core-6.0.7-validateStateful"),
      "entries"    -> Json.arr(entries: _*))
  }

  def writeVectors(vectorsRoot: java.nio.file.Path): Unit = {
    val path = vectorsRoot.resolve(Path)
    java.nio.file.Files.createDirectories(path.getParent)
    java.nio.file.Files.write(path, extract().spaces2.getBytes(java.nio.charset.StandardCharsets.UTF_8))
  }
}
