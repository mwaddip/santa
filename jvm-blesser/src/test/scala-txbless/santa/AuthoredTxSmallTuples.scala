package santa

// AuthoredTxSmallTuples — the VALIDATION half of the small-tuples family. The wire half
// (`wire/v6/authored/{ErgoTree,Box,Transaction}.small_tuples.json`) shows what parses and what
// round-trips; these build real transactions and record ergo-core's verdict and cost.
//
// Blessed through TxEngine.validateBytes (ergo-core 6.0.7 validateStateful) under the storage-rent
// vectors' synthetic context (ten v4 headers below H = 1051200, the launch parameters).
//
// Each case appears TWICE, and the two sides answer different questions:
//
//   as an OUTPUT   the transaction creates a box whose tree is the case. The JVM accepts every
//                  case here, so an implementation that refuses the tree at parse REJECTS A VALID
//                  TRANSACTION. **This is the separating side** for the expression rows, and only
//                  at tree version 0 — at v3 a refusing implementation degrades the output tree
//                  and accepts, exactly as it does on the wire.
//   as a SPEND     the transaction spends a box whose tree is the case, so the script actually
//                  RUNS. This is where `Tuple.eval`'s arity refusal becomes a verdict with a cost,
//                  and where the `*_control` rows set the baseline (12103 for the constant pairs,
//                  12105 for the two-item expression). This side separates too, which I did not
//                  expect: I wrote that it could not, on the grounds that the JVM says invalid
//                  because the script fails and a refusing implementation says invalid because the
//                  tree does not parse. MEASURED, those are not the same outcome — a runner whose
//                  input box fails to decode reports `errored`, not `valid=false`, and the tier
//                  grades the two apart (fork `6f49c97c`: `errored`, "BoundedVecOutOfBounds
//                  LowerBoundError { lower_bound: 2, got: 1 }"). The contract's total per-entry
//                  outcomes with no abstention are what make that distinction available.
//
// THE SHARPEST ROWS IN THE FAMILY ARE THE TRIPLE'S SPENDS, and they are not about small tuples at
// all. A 3-item tuple TYPE is writable, so `const_triple_type` is the one non-pair row whose box
// can really exist on chain — and the fork SPENDS it, valid at cost 12103, where the JVM refuses
// (`SelectField` meets the raw `Coll[Any]` that `toDslTuple` returns for any non-pair). That is a
// reachable, consensus-relevant OVER-ACCEPT: a box anyone can create, which one implementation
// lets you spend and the other does not. The eval tier's `Tuple.non_pair_type_check` had the
// reduction half of this since 6.0.6; these two rows are the spend half.
//
// THREE CASES OF THE WIRE FAMILY ARE ABSENT, and the reason is a limit of this tier rather than a
// gap in the corpus:
//
//   const_one_item_type / const_zero_item_type — a 0/1-item tuple TYPE. `validateBytes` parses
//     OUTSIDE its `Try` (the parse is a precondition of a verdict, not a verdict), so a
//     transaction whose parse throws comes back from `TxEngine.txEntry` as
//     `{"valid":null,"cost":null,"error":"panicked"}` — measured. That is a never-panic contract
//     violation, i.e. RUDOLPH WOULD PANIC ON ITS OWN VECTOR, not a reject this tier can bless. The
//     wire tier carries those rows, which is the honest home for a parse-level refusal, and it is
//     already red on the fork's v3 rows. Making this tier able to say "does not parse" means
//     wrapping that parse in `validateBytes`, which is a contract change and not one to make while
//     authoring vectors.
//   tuple_expr_128_items — the same thing from the other direction: the carrier's parse throws
//     NegativeArraySizeException, as an output and as an input box alike.
//
// AND THERE IS NO SPEND ROW FOR THE NESTED-TUPLE REGISTER, which is worth recording because it
// took a measurement to establish. `SELF.R4[((Int),Int)]` is the only declared type whose RType
// `getReg` accepts — and `TypeSerializer` cannot write `((Int),Int)`, so no script the JVM can
// write reads that register. The two writable near-misses were measured and both refused:
// `(Coll[Any],Int)` serializes to `4c0c61` and `(Coll[Int],Int)` to `4c10`, and each gives
// `InvalidType: Cannot getReg[...](4): invalid type of value TestValue(Coll(Coll(1),1))` —
// `stypeToRType(STuple(SInt))` is `TupleType(...)`, not `collRType(AnyType)`. So the register is
// valid, permanent and UNREADABLE: it rides in an accepted output forever and nothing on chain can
// look at it. Its output row is here; a spend row would need an unwritable box, hence unreachable.

import io.circe.Json
import scorex.util.encode.Base16
import sigma.VersionContext
import sigma.ast.ErgoTree
import sigma.serialization.ErgoTreeSerializer
import org.ergoplatform.{ErgoBox, ErgoBoxCandidate}
import santa.runner.TxEngine

import RentFixtures._
import SmallTupleTrees._

object AuthoredTxSmallTuples {
  val Path = "transaction/v6/authored/small-tuples.json"
  val Source = "santa:authored-small-tuples"
  private val Activated = 3
  private val V = 1000000000L                // the spent box's value, carried whole to the output
  private val H = AuthoredTxStorageRent.H

  private def b(h: String): Array[Byte] = Base16.decode(h).get
  private def ctx[A](f: => A): A = VersionContext.withVersions(Activated.toByte, Activated.toByte)(f)

  /** The cases this tier can express: everything the JVM can WRITE and whose carrier PARSES.
    * See the header for the three it cannot and why. */
  val expressible: Seq[Case] = cases.filter(c => c.arm != TypeWrite && c.arm != CountBound)

  private def slugIsTriple(c: Case): Boolean = c.slug == "const_triple_type"

  private def treeOf(treeHex: String): ErgoTree =
    ctx(ErgoTreeSerializer.DefaultSerializer.deserializeErgoTree(b(treeHex)))

  private def validate(txHex: String, inputs: Seq[ErgoBox]): TxEngine.Verdict =
    TxEngine.validateBytes(txHex, inputs.map(x => hex(x.bytes)), Nil,
      AuthoredTxStorageRent.headersHex, AuthoredTxStorageRent.preHeader, AuthoredTxStorageRent.params)

  private def entry(name: String, description: String, treeVersion: Int,
                    bx: ErgoBox, out: ErgoBoxCandidate): Json = {
    val txHex = ctx(hex(txBytes(tx(Seq(input(bx, ext())), Seq(out)))))
    val v = validate(txHex, Seq(bx))
    Json.obj(
      "name"                 -> Json.fromString(name),
      "source"               -> Json.fromString(Source),
      "description"          -> Json.fromString(description),
      "tx_bytes_hex"         -> Json.fromString(txHex),
      "input_boxes_hex"      -> Json.arr(Json.fromString(hex(bx.bytes))),
      "data_input_boxes_hex" -> Json.arr(),
      "headers_hex"          -> Json.arr(AuthoredTxStorageRent.headersHex.map(Json.fromString): _*),
      "preHeader"            -> AuthoredTxStorageRent.preHeader,
      "parameters"           -> AuthoredTxStorageRent.params,
      "context"              -> Json.obj("height" -> Json.fromInt(H)),
      "version"              -> Json.obj("activated" -> Json.fromInt(Activated),
                                         "ergoTree"  -> Json.fromInt(treeVersion)),
      "expected"             -> Json.obj(
        "valid"  -> Json.fromBoolean(v.valid),
        "cost"   -> v.cost.map(Json.fromLong).getOrElse(Json.Null),
        "reason" -> v.reason.map(Json.fromString).getOrElse(Json.Null)))
  }

  /** The case's tree as the transaction's OUTPUT, spending a plain box. */
  private def outputEntry(c: Case, treeVersion: Byte, idx: Int): Json = {
    val treeHex = if (treeVersion == V0) v0(c.body) else v3(c.body)
    val tag = if (treeVersion == V0) "v0" else "v3"
    val role =
      if (c.arm == ExprAccept && treeVersion == V0)
        "THE SEPARATING ROW of this pair: the JVM accepts the transaction, and an implementation " +
        "whose tuples hold 2..255 refuses the output tree at parse with no size bit to degrade " +
        "behind, so it rejects a VALID transaction."
      else if (c.arm == ExprAccept)
        "THE CONTROL of this pair: at a size-flagged output tree a refusing implementation " +
        "degrades and accepts, so the row is green whether the tuple was understood or skipped."
      else
        "A WRITABLE TWIN: green by construction, the shape without the small tuple."
    entry(s"small-tuple-output-${c.slug}-$tag#$idx",
      s"${c.what}. The tree is the transaction's OUTPUT, at tree version ${treeVersion.toInt} " +
      (if (treeVersion == V0) "UNSIZED" else "SIZE-FLAGGED") +
      s"; the input is a plain `sigmaProp(true)` box and the output carries its whole value, so " +
      s"the Erg balance cannot mask anything. $role",
      treeVersion.toInt, ctx(box(s"santa:st:tx:out:${c.slug}:$tag", V, 1)),
      ctx(candidate(V, 1, treeOf(treeHex))))
  }

  /** The case's tree as the box being SPENT, so the script runs. */
  private def spendEntry(c: Case, treeVersion: Byte, idx: Int): Json = {
    val treeHex = if (treeVersion == V0) v0(c.body) else v3(c.body)
    val tag = if (treeVersion == V0) "v0" else "v3"
    val role = c.arm match {
      case ExprAccept =>
        "`Tuple.eval` refuses any count but two (`values.scala:825-827`), so the script FAILS and " +
        "the transaction is INVALID — with no cost, since the failure is the verification. This " +
        "row SEPARATES as well, and not for the reason it looks like: an implementation that " +
        "refuses the tree at parse cannot even DECODE the input box, so it reports `errored` " +
        "rather than `valid=false`, and the tier grades those apart. `invalid` and `errored` are " +
        "different outcomes (runner contract: total per-entry outcomes, no abstention), which is " +
        "what lets a spend row distinguish `the script ran and failed` from `the box was never " +
        "readable`."
      case c2 if c2 == Control && slugIsTriple(c) =>
        "THE SHARPEST ROW OF THE FAMILY, and not about small tuples: a 3-item tuple TYPE is " +
        "writable, so this box can really exist on chain, and `SelectField` on it fails exactly " +
        "as it does on a one-item tuple — `toDslTuple` returns the raw `Coll[Any]` for ANY " +
        "non-pair. An implementation that selects the field anyway SPENDS a box the JVM refuses: " +
        "a reachable over-accept. (Fork `6f49c97c` does, valid at cost 12103.) The reduction half " +
        "is `eval/v6/authored/Tuple.non_pair_type_check`; this is the spend half."
      case _ =>
        "A control: the shape without the small tuple, and the cost baseline the rest are read " +
        "against."
    }
    entry(s"small-tuple-spend-${c.slug}-$tag#$idx",
      s"${c.what}. The tree guards the box being SPENT, at tree version ${treeVersion.toInt} " +
      (if (treeVersion == V0) "UNSIZED" else "SIZE-FLAGGED") + s", so the script is evaluated. $role",
      treeVersion.toInt,
      ctx(box(s"santa:st:tx:in:${c.slug}:$tag", V, 1, treeOf(treeHex))), ctx(candidate(V, 1)))
  }

  /** R4 = `Tuple(Tuple(1), 1)` on an accepted output — the one register row this tier can carry. */
  private def registerEntry(idx: Int): Json =
    entry(s"small-tuple-output-register_nested_tuple_expr#$idx",
      "R4 of the transaction's OUTPUT holds `Tuple(Tuple(1), 1)` — a pair whose FIRST item is a " +
      "one-item tuple, as an EXPRESSION, under a plain `sigmaProp(true)` script. " +
      "`TupleSerializer` writes a count and no type, so the JVM writes the register itself and " +
      "the transaction is VALID. SEPARATING: an implementation whose tuples hold 2..255 refuses " +
      "the nested one and rejects a valid transaction. There is deliberately no spend row — " +
      "`((Int),Int)` is the only declared type `getReg` accepts and `TypeSerializer` cannot write " +
      "it, so no script the JVM can write reads this register. It is valid, permanent and " +
      "unreadable.",
      0, ctx(box("santa:st:tx:reg:spent", V, 1)),
      ctx(candidate(V, 1, regs = Map(ErgoBox.R4 -> NestedTupleRegister))))

  def extract(): Json = {
    var i = -1
    def next(): Int = { i += 1; i }
    val outputs = for { c <- expressible; tv <- Seq(V0, Activated.toByte) } yield outputEntry(c, tv, next())
    val spends  = for { c <- expressible; tv <- Seq(V0, Activated.toByte) } yield spendEntry(c, tv, next())
    val entries = outputs ++ spends :+ registerEntry(next())
    Json.obj(
      "schema"     -> Json.fromString("santa-transaction/v1"),
      "op"         -> Json.fromString("small-tuples"),
      "blessed_by" -> Json.fromString("jvm:ergo-core-6.0.7-validateStateful"),
      "entries"    -> Json.arr(entries: _*))
  }

  def writeVectors(vectorsRoot: java.nio.file.Path): Unit = {
    val path = vectorsRoot.resolve(Path)
    java.nio.file.Files.createDirectories(path.getParent)
    java.nio.file.Files.write(path, extract().spaces2.getBytes(java.nio.charset.StandardCharsets.UTF_8))
  }
}
