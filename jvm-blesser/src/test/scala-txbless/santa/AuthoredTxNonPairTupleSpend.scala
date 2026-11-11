package santa

// AuthoredTxNonPairTupleSpend — the CONSENSUS form of the non-pair-tuple family: boxes whose script
// reads a non-pair tuple VALUE, spent through ergo-core's own `validateStateful`.
// Request: `~/projects/sigma-rust/prompts/santa-probe-tuple-values.md` ask 2 ("the spend verdict for
// N12 and for one row of each group that returns"). Measured by `santa.spike.TupleValuesProbe`.
//
// WHY THIS TIER CARRIES THE ROUND. The eval tier shows the JVM answers; the wire tier shows the
// boxes parse. Neither says what a NODE does, and that is the only thing a divergence here costs
// money over: rows 1-3 are boxes anyone can create, which the JVM SPENDS at 12104/12104/12103,
// and which sigma-rust refuses at `eda571a6` because it holds the tuple in a dedicated node. An
// implementation that refuses them cannot follow the chain across such a spend.
//
// AND THE TWO INVALID ROWS ARE THE OTHER DIRECTION — same tier, opposite risk: the box is
// creatable and the script CANNOT be satisfied, so an implementation that evaluates where the JVM
// throws lets a spender TAKE the box.
//
//   4  `AtLeast(2, (sigmaProp(true) × 3))` — the array cast (`Object[]` -> `SigmaProp[]`). The probe
//      read this as returning a value; the measurement refuted that, and the spend FAILS.
//   5  `sigmaProp(t(0) == t(1))` — THE CROSS-TIER KEYSTONE. The wire family
//      (`ErgoTree.non_pair_tuple_operand`'s `eq-any-any-v3`) has this exact tree ACCEPTED and
//      round-tripping byte-identically, because the deserialization builder's SameType check is
//      satisfied when both operands are `SAny`; here the same tree's spend is INVALID, because
//      `Value.checkType` has no `SAny` case. A box anyone can create and no one can spend.
//
// ROW 6 IS THE DESERIALIZE ROUTE, and it is the only place the "script from a context variable"
// question can be asked at all: `DeserializeContext` has no eval handler (it is one of the thirteen
// unevaluated node kinds — the substitution happens in `Interpreter.applyDeserializeContext`, before
// reduction), so the eval tier is structurally blind to it. The spend works, at 12138 against
// 12104 for the inline form: +34 for the substitution.
//
// Blessed through TxEngine.validateBytes (ergo-core 6.0.7 validateStateful) under the storage-rent
// vectors' synthetic context, as every tx family is. `reason` is diagnostic only (runner contract
// §2) and is not matched.

import io.circe.Json
import scorex.util.encode.Base16
import sigma.VersionContext
import sigma.ast.ByteArrayConstant
import sigma.serialization.ErgoTreeSerializer
import santa.runner.TxEngine

import RentFixtures._

object AuthoredTxNonPairTupleSpend {
  val Path   = "transaction/v6/authored/non-pair-tuple-spend.json"
  val Source = "santa:authored-non-pair-tuple-spend"
  private val Activated = 3
  private val V = 1000000000L
  private val H = AuthoredTxStorageRent.H

  /** The expression `EQ(SizeOf((1,2,3)), 3)`, carried in context var 2 by row 6. */
  val InnerScript = "93b1480404040204060406"

  private def b(h: String): Array[Byte] = Base16.decode(h).get
  private def ctx[A](f: => A): A = VersionContext.withVersions(Activated.toByte, Activated.toByte)(f)

  /** `(slug, treeHex, varTwo, wantValid, what)` — trees dumped from the probe (tests F/H). */
  private val rows: Seq[(String, String, Boolean, Boolean, String)] = Seq(
    ("size_eq", "0b0cd193b1480404040204060406", false, true,
      "`sigmaProp(SizeOf((1,2,3)) == 3)` guards the box. A non-pair tuple's value IS a generic " +
      "`Coll[Any]`, so the script reduces to true and the box SPENDS at cost 12104. **THE " +
      "SEPARATING ROW of the round:** this is a box anyone can create, and sigma-rust refuses the " +
      "spend at fork `eda571a6` because it holds tuples in a dedicated node — an implementation " +
      "that cannot evaluate this cannot follow the chain past such a spend."),
    ("exists", "0b12d1ae48040404020406d90101049372010402", false, true,
      "`sigmaProp(Exists((1,2,3), x => x == 1))` — the higher-order half, also **valid at 12104**. " +
      "It is here because `Exists` reaches the collection through the interface rather than the " +
      "array, so an implementation can fail this and the row above for the same reason or for " +
      "different ones. **SEPARATING.**"),
    ("and_bool", "0b09d19648010101010101", false, true,
      "`sigmaProp(AND((true,true,true)))` — a tuple of BOOLEANS driving a connective: **valid at " +
      "12103**, one cheaper than the two rows above. The third independent shape, and the cheapest " +
      "spend of the family. **SEPARATING.**"),
    ("at_least", "0b0a98040448080808d3d3d3", false, false,
      "`AtLeast(2, (sigmaProp(true) × 3))` — the box is creatable and the spend is **INVALID**: " +
      "`AtLeast` casts the collection's array to `SigmaProp[]` and an `Object[]` cannot be cast, so " +
      "the script throws. The probe read this one as returning a value and the measurement refuted " +
      "it. **SEPARATING THE OTHER WAY:** an implementation that reduces it lets a spender TAKE the " +
      "box."),
    ("eq_any_operands", "0b18d193b248040404020406040000b248040404020406040200", false, false,
      "`sigmaProp(t(0) == t(1))` — **THE CROSS-TIER KEYSTONE.** The wire family's `eq-any-any-v3` " +
      "has this exact tree ACCEPTED and round-tripping byte-identically (the deserialization " +
      "builder's SameType check is satisfied when both operands are `SAny`), and here the spend is " +
      "**INVALID**, because `Value.checkType`/`isValueOfType` has no `SAny` case at all. A box " +
      "anyone can create and nobody can spend. **SEPARATING THE OTHER WAY.**"),
    ("deserialize_from_var", "0b04d1d40102", true, true,
      "`sigmaProp(deserializeContext[Boolean](2))` with context var 2 holding the serialized " +
      "`EQ(SizeOf((1,2,3)), 3)` — the tuple constant reached through a script read from the " +
      "SPENDER's own context. **Valid at 12138**, against 12104 for the inline form: +34 for the " +
      "substitution. This row exists only here: `DeserializeContext` has no eval handler at all " +
      "(one of the thirteen unevaluated node kinds), so the eval tier cannot ask the question. " +
      "**SEPARATING.**"),
    ("control_true", "0b0208d3", false, true,
      "**THE CONTROL** — a bare `SigmaProp(TrueProp)` at the same tree version, spending at **cost " +
      "12105**. It is what shows the invalid rows above are about their scripts and not about the " +
      "carrier, the context or the fee arithmetic."))

  def extract(): Json = {
    val entries = rows.zipWithIndex.map { case ((slug, treeHex, varTwo, wantValid, what), i) =>
      val extension =
        if (varTwo) ext(2.toByte -> ByteArrayConstant(b(InnerScript)))
        else ext()
      val bx = ctx(box(s"santa:npts:$slug", V, 1,
        ErgoTreeSerializer.DefaultSerializer.deserializeErgoTree(b(treeHex))))
      val txHex = ctx(hex(txBytes(tx(Seq(input(bx, extension)), Seq(candidate(V, 1))))))
      val v = TxEngine.validateBytes(txHex, Seq(hex(bx.bytes)), Nil,
        AuthoredTxStorageRent.headersHex, AuthoredTxStorageRent.preHeader, AuthoredTxStorageRent.params)
      require(v.valid == wantValid,
        s"row $slug: want valid=$wantValid, got ${v.valid} ${v.reason.getOrElse("")}")
      Json.obj(
        "name"                 -> Json.fromString(s"non-pair-tuple-spend-$slug#$i"),
        "source"               -> Json.fromString(Source),
        "description"          -> Json.fromString(what),
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
      "op"         -> Json.fromString("non-pair-tuple-spend"),
      "blessed_by" -> Json.fromString("jvm:ergo-core-6.0.7-validateStateful"),
      "entries"    -> Json.arr(entries: _*))
  }

  def writeVectors(vectorsRoot: java.nio.file.Path): Unit = {
    val path = vectorsRoot.resolve(Path)
    java.nio.file.Files.createDirectories(path.getParent)
    java.nio.file.Files.write(path, extract().spaces2.getBytes(java.nio.charset.StandardCharsets.UTF_8))
  }
}
