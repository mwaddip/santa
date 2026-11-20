package santa

// Authored eval vectors — the GATES that refuse a non-pair tuple VALUE, and the one that does not.
// Companion to `Tuple.non_pair_value_ops` (the operations that accept it). Request:
// `~/projects/sigma-rust/prompts/santa-probe-tuple-values.md` + its N13 correction, and
// `santa-probe-tuple-values-2.md` §5 (the addendum of 2026-11-11: K1/K2/K5, arithmetic reached
// through the `apply` METHOD). Measured by `santa.spike.TupleValuesProbe`.
//
// THREE GATES, in three different places, and the point of the family is that they are NOT one
// rule (`TransformingSigmaBuilder`, which `DeserializationSigmaBuilder` extends at
// `SigmaBuilder.scala:750`):
//
//   comparison  `check2(OnlyNumeric)` at `:699` — refused AT PARSE, both versions, no escape:
//               `SAny` is not numeric, so neither `SAny`-vs-`SInt` nor `SAny`-vs-`SAny` gets past
//               the reader. Those rows are PARSE refusals and so live in the WIRE tier, where
//               accept / degrade / reject are graded apart; the eval tier would flatten them into
//               the same `errored` as the rows below and say nothing about where they failed.
//   equality    `check2(SameType)` at `:691` — refused at parse against a CONCRETE type, but
//               `SAny`-vs-`SAny` SATISFIES it, so the tree parses (and re-writes byte-identically)
//               and then dies at EVAL: `SType.isValueOfType` has no `SAny` case at all
//               (`SType.scala:212`, `case _ => sys.error(s"Unknown type $tpe")`). G1/G2.
//   arithmetic  no parse check whatsoever — `arithOp` at `:707-712` only applies the upcast. The
//               failure is at eval and it is in the op TABLE: `ArithOp.eval` resolves its
//               implementation by `impls(left.tpe.typeCode)`, and `SAny`'s code is 97, so the
//               message is `KeyNotFoundException: key 97`. G3-G5.
//
// AND THE ASYMMETRY THAT FALLS OUT OF THE THIRD, which is this family's own finding: because the
// LEFT operand picks the implementation, an `Any`-typed operand on the RIGHT is INVISIBLE — G6/G7/G8
// return, at EXACTLY the cost of their plain-collection controls (60 / 64 / 54). So `t(0) + 1`
// throws and `1 + t(0)` answers 2, one operand order apart, with no cost difference to show for it.
// The sigma-rust session predicted this from `trees.scala:707`/`:861-869` and asked for G7's cost,
// which is 64. It holds for the ByIndex NODE and for the `apply` METHOD alike, and for `min` as
// well as `+`, so it is the table lookup and not one operation's quirk.
//
// G9/G10 are the OTHER kind of refusal: not an operand's type but the tuple VALUE itself reaching a
// `Value.checkType` site, where `isValueOfType` throws "Unsupported tuple type" for any arity but 2
// (`SType.scala:202`). A `Fold` ACCUMULATOR is such a site, and so is every item of a
// `ConcreteCollection` — which is why a collection OF non-pair tuples cannot be built in script at
// all, while the identical value arriving from a REGISTER evaluates fine (measured: `3` at cost
// 124). That provenance pair is NOT authorable yet: the eval tier's input decoder refuses a non-pair
// tuple ("expected pair, got 3 items"), so the register half needs a contract change first. G10 is
// the half that can be committed today, and C6 is what makes it about the ARITY rather than about
// `ConcreteCollection`: the same shape built from PAIRS returns 2.

// APPENDED 2026-11-20 (ergots' stage-2 review; spike santa.spike.ErgotsStage2RequestsProbe):
//   G11-G14 / C7-C9  the arithmetic gate with UnsignedBigInt items — `key 97` on the left whatever the item holds,
//                    and the asymmetry (an `Any` operand on the right answers at the control's cost);
//   G15-G22 / C10-C17  a lambda's ARGUMENT is a third `checkType` site (`values.scala:1074`): ForAll / Exists / Map /
//                    Filter over ONE data `(Int, Int, Int)` throw `Unsupported tuple type`, over an EMPTY one they
//                    answer (the lambda is never applied), and the empty Map / Filter results differ in their item
//                    type (the lambda's tuple type against the data constant's Coll[Any]).

import io.circe.Json

object AuthoredEvalNonPairTupleGates {
  val Op     = "Tuple.non_pair_value_gates"
  val Source = "santa:authored-non-pair-tuple-gates"
  private val V3: Byte = 3

  private val dummyInput: Json =
    Json.obj("kind" -> Json.fromString("Int"), "value" -> Json.fromInt(0))

  /** `(id, slug, treeHex, accepts, what)`. Hexes are the serializer's own, dumped from the probe
    * (test Y); the addendum's K1/K2/K5 hexes were rebuilt here from the AST and came out
    * byte-identical to the ones it sent. */
  private val rows: Seq[(String, String, String, Boolean, String)] = Seq(
    ("G1", "eq_any_operands_bare", "0b1793b248040404020406040000b248040404020406040200", false,
      "`ByIndex(t,0) == ByIndex(t,1)` — BOTH operands `SAny`, so the deserialization builder's " +
      "SameType check is SATISFIED and the tree PARSES (it even re-writes byte-identically); the " +
      "throw is at eval, `RuntimeException: Unknown type SAny`, because `isValueOfType` has no " +
      "`SAny` case. **SEPARATING:** an implementation that compares the two items here over-accepts " +
      "— and one that refuses the tree at PARSE is also wrong, just invisibly so on this tier (the " +
      "wire family grades that)."),
    ("G2", "eq_any_operands_sigmaprop", "0b18d193b248040404020406040000b248040404020406040200", false,
      "The same comparison under `sigmaProp` — the REACHABLE form, since this is a tree a box can " +
      "actually carry (G1's bare Boolean root cannot be a script). Same parse, same throw at eval " +
      "— `Unknown type SAny` — and the spend it guards therefore fails. **SEPARATING,** and the " +
      "row that gives the gate consensus teeth."),
    ("C1", "eq_control_coll_items", "0b1393b21003020406040000b21003020406040200", true,
      "**CONTROL for G1** — the same double-`ByIndex` equality over `Coll[Int](1,2,3)`: **false at " +
      "cost 83**. Two items, same shapes, and it answers; so G1's throw is the `SAny` item type and " +
      "not the comparison of two indexed items."),
    ("C2", "eq_control_coll_sigmaprop", "0b14d193b21003020406040000b21003020406040200", true,
      "**CONTROL for G2** — the same under `sigmaProp`: a `SigmaProp` (false) at cost 98."),
    ("G3", "plus_node_left_any", "0b0e9ab2480404040204060400000402", false,
      "`ByIndex(t,0) + 1` — **`KeyNotFoundException: key 97`**. Nothing checks the operand at parse; " +
      "`ArithOp.eval` looks its implementation up by the LEFT operand's type code, and 97 is `SAny`. " +
      "The message names no type, which is worth knowing before matching on text. **SEPARATING:** " +
      "sigma-rust evaluated this to 2 at `eda571a6`."),
    ("G4", "plus_method_left_any", "0b109adc0c0a480404040204060104000402", false,
      "The same arithmetic with the item fetched by the `apply` METHOD instead of the `ByIndex` " +
      "node — `(1,2,3).apply(0) + 1`. Same `key 97`. The pair G3/G4 matters because the two routes " +
      "to the item differ elsewhere (`map` works as a node and not as a method), and here they do " +
      "NOT: the gate is the arithmetic, not the fetch. This is the sigma-rust addendum's K1, " +
      "reachable on its fork master today. **SEPARATING.**"),
    ("G5", "min_method_left_any", "0b10a1dc0c0a480404040204060104000402", false,
      "`min((1,2,3).apply(0), 1)` — the addendum's K5. `min` is a different `ArithOpCompanion` with " +
      "its own opcode and it fails identically, `KeyNotFoundException: key 97`, which is what shows " +
      "the fault is the shared table lookup rather than `Plus`. **SEPARATING.**"),
    ("G6", "plus_node_right_any", "0b0e9a0402b248040404020406040000", true,
      "`1 + ByIndex(t,0)` — **2 at cost 60. THE ASYMMETRY:** the same two values, the operands " +
      "swapped, and it ANSWERS, because the implementation is chosen by the LEFT operand's type " +
      "(`SInt`) and the right one is never consulted for it. **SEPARATING the other way:** an " +
      "implementation that refuses an `Any`-typed operand wherever it appears OVER-REJECTS a tree " +
      "the JVM evaluates."),
    ("G7", "plus_method_right_any", "0b109a0402dc0c0a48040404020406010400", true,
      "`1 + (1,2,3).apply(0)` — the addendum's K2, **2 at cost 64**, which is the number it asked " +
      "for. Identical to C4, its plain-collection control: the `Any`-typed right operand is FREE. " +
      "**SEPARATING the other way.**"),
    ("G8", "min_method_right_any", "0b10a10402dc0c0a48040404020406010400", true,
      "`min(1, (1,2,3).apply(0))` — **1 at cost 54**, identical to C5. The asymmetry holds for " +
      "`min` too, so an implementation cannot fix G5 by refusing the type in both positions. " +
      "**SEPARATING the other way.**"),
    ("C3", "plus_node_control", "0b0c9ab210030204060400000402", true,
      "**CONTROL for G3/G6** — `ByIndex(Coll[Int](1,2,3),0) + 1` is 2 at cost **60**, the same as " +
      "G6. The arithmetic costs what it costs; the representation adds nothing."),
    ("C4", "plus_method_control", "0b0e9adc0c0a10030204060104000402", true,
      "**CONTROL for G4/G7** — the `apply`-method form over an ordinary collection: 2 at cost " +
      "**64**, equal to G7 and 4 dearer than the node form, which is the method call's own cost."),
    ("C5", "min_method_control", "0b0ea1dc0c0a10030204060104000402", true,
      "**CONTROL for G5/G8** — 1 at cost **54**, equal to G8."),
    ("G9", "fold_tuple_accumulator", "0b1ab1b0100302040648040404020406d901014c480404048c720101", false,
      "`Fold(Coll[Int](1,2,3), (1,2,3), p => p._1).size` — the tuple as the ACCUMULATOR. The zero " +
      "is a `Value.checkType` site, and `isValueOfType` throws **\"Unsupported tuple type\"** for " +
      "any arity but 2 (`SType.scala:202`) — a different refusal from the `SAny` ones above: it is " +
      "the tuple VALUE being rejected, not an operand's type. `Tuple.non_pair_value_ops` T11/C11 " +
      "hold the accepting twin, where the accumulator is an `Int` and the same tuple is the INPUT. " +
      "**SEPARATING.**"),
    ("G10", "concrete_collection_of_tuples", "0b15b18302480404044804040402040648040404020406", false,
      "`SizeOf(Coll((1,2,3), (1,2,3)))` — a `ConcreteCollection` whose ITEMS are non-pair tuples. " +
      "Every item goes through the same `checkType` site — the same **\"Unsupported tuple type\"** " +
      "as G9 — so the collection cannot be built AT ALL and the throw precedes any operation on it. **The other half of this is not authorable " +
      "yet:** the identical collection arriving in a REGISTER evaluates (`3` at cost 124, measured), " +
      "so the JVM's answer depends on where the collection came from — the same provenance shape as " +
      "`Coll.runtime_shapes_methodcall` B1/B3 — but the eval tier's input decoder cannot express a " +
      "non-pair tuple today, so the register half waits on that. **SEPARATING.**"),
    ("C6", "concrete_collection_of_pairs_control", "0b10b1830258860204020404860204060408", true,
      "**CONTROL for G10** — the same shape built from PAIRS: `SizeOf(Coll((1,2), (3,4)))` is **2 " +
      "at cost 84**. `ConcreteCollection` takes pairs happily, so G10 is about the ARITY and not " +
      "about building a collection of tuples."),
    // ── appended 2026-11-20 (ergots' stage-2 review; spike santa.spike.ErgotsStage2RequestsProbe) ──
    ("G11", "plus_left_any_ubi_apply", "0b29d1dad90101099372017201019ab248090909010101020103040000b248090909010101020103040200", false,
      "`sigmaProp(Apply((x: UnsignedBigInt) => x == x, t(0) + t(1)))` with `t` the CONSTANT `(1u, 2u, 3u)` " +
      "(UnsignedBigInt items; ergots' tree, byte for byte) — **`KeyNotFoundException: key 97`**: `ByIndex` over " +
      "a tuple types its result `Any`, and `ArithOp.eval` looks its implementation up by the LEFT operand's type " +
      "code (97, `SAny`) whatever the item holds — G3's gate with UnsignedBigInt items. The lambda is never " +
      "reached. **SEPARATING:** an implementation that dispatches on the runtime value (an UnsignedBigInt) answers."),
    ("C7", "plus_ubi_apply_control", "0b25d1dad90101099372017201019ab21503010101020103040000b21503010101020103040200", true,
      "**CONTROL for G11** — the same over `Coll[UnsignedBigInt](1u, 2u, 3u)`: a `SigmaProp` (true) at cost 165."),
    ("G12", "plus_left_any_ubi_both", "0b1d9ab248090909010101020103040000b248090909010101020103040200", false,
      "`t(0) + t(1)` over the same UnsignedBigInt tuple, as the root — **`KeyNotFoundException: key 97`**, both " +
      "operands `Any`. **SEPARATING.**"),
    ("G13", "plus_left_any_ubi_right_ubi", "0b129ab248090909010101020103040000090101", false,
      "`t(0) + 1u` — **`KeyNotFoundException: key 97`**: a real `UnsignedBigInt` on the right does not help, the " +
      "LEFT operand picks the implementation. **SEPARATING.**"),
    ("C8", "plus_ubi_control", "0b199ab21503010101020103040000b21503010101020103040200", true,
      "**CONTROL for G12/G13** — `c(0) + c(1)` over `Coll[UnsignedBigInt](1u, 2u, 3u)`: `3` at cost 95."),
    ("G14", "plus_right_any_ubi", "0b129a090101b248090909010101020103040000", true,
      "`1u + t(0)` — **`2` at cost 60: THE ASYMMETRY with UnsignedBigInt items.** The left operand is an " +
      "`UnsignedBigInt` constant, so its implementation is found, and the `Any`-typed right operand holds an " +
      "UnsignedBigInt it can add; identical to C9. **SEPARATING:** an implementation that refuses an `Any` " +
      "operand on either side is red."),
    ("C9", "plus_right_ubi_control", "0b109a090101b21503010101020103040000", true,
      "**CONTROL for G14** — `1u + c(0)`: `2` at cost 60."),
    ("G15", "forall_empty_data_tuples", "0b11d1af0c4804040400d90101480404040101", true,
      "`sigmaProp(ForAll(c, (x: (Int, Int, Int)) => true))` with `c` an EMPTY data `Coll[(Int, Int, Int)]` " +
      "(ergots' tree, byte for byte) — **a `SigmaProp` (true) at cost 29**, as its control: the lambda is never " +
      "applied, so its argument is never checked. **SEPARATING:** an implementation that refuses a tuple-typed " +
      "lambda over an empty collection of tuples is red. G16 is the same call over ONE item."),
    ("C10", "forall_empty_coll_control", "0b0ad1af1c00d90101100101", true,
      "**CONTROL for G15** — the same over an empty `Coll[Coll[Int]]` with a `Coll[Int]`-typed lambda: true at 29."),
    ("G16", "forall_one_data_tuple", "0b14d1af0c4804040401020406d90101480404040101", false,
      "the same call over ONE item, `Coll((1, 2, 3))` — **throws `Unsupported tuple type`**: applying the lambda " +
      "checks its argument against its declared type (`FuncValue`, `values.scala:1074`: `Value.checkType` → " +
      "`SType.isValueOfType`, which refuses any arity but 2) — the third `checkType` site after G9 (an " +
      "accumulator) and G10 (a collection's items). So no lambda over a data collection of non-pair tuples is " +
      "ever applied. **SEPARATING.**"),
    ("C11", "forall_one_coll_control", "0b0ed1af1c0103020406d90101100101", true,
      "**CONTROL for G16** — over `Coll(Coll[Int](1, 2, 3))`: true at 39."),
    ("G17", "exists_empty_data_tuples", "0b11d1ae0c4804040400d90101480404040101", true,
      "`sigmaProp(Exists(c, (x: (Int, Int, Int)) => true))` over the EMPTY data `Coll[(Int, Int, Int)]` — **a " +
      "`SigmaProp` (false) at cost 29**, as its control. **SEPARATING.**"),
    ("C12", "exists_empty_coll_control", "0b0ad1ae1c00d90101100101", true,
      "**CONTROL for G17** — over an empty `Coll[Coll[Int]]`: false at 29."),
    ("G18", "exists_one_data_tuple", "0b14d1ae0c4804040401020406d90101480404040101", false,
      "`Exists` over ONE item — **throws `Unsupported tuple type`**, the lambda's argument check as in G16. " +
      "**SEPARATING.**"),
    ("C13", "exists_one_coll_control", "0b0ed1ae1c0103020406d90101100101", true,
      "**CONTROL for G18** — over `Coll(Coll[Int](1, 2, 3))`: true at 39."),
    ("G19", "map_empty_data_tuples", "0b10ad0c4804040400d90101480404047201", true,
      "`Map(c, (x: (Int, Int, Int)) => x)` as the root over the EMPTY data collection — **an empty collection at " +
      "cost 31 whose `elem` is the TUPLE type `(Int, Int, Int)`**: `Map` builds its result at the LAMBDA's result " +
      "type (`stypeToRType` of the tuple, a `TupleType`), not at the input's `Coll[Any]` (compare G21). " +
      "**SEPARATING:** the value, not only the verdict."),
    ("C14", "map_empty_coll_control", "0b09ad1c00d90101107201", true,
      "**CONTROL for G19** — over an empty `Coll[Coll[Int]]`: an empty `Coll[Coll[Int]]` at 31."),
    ("G20", "map_one_data_tuple", "0b13ad0c4804040401020406d90101480404047201", false,
      "`Map` over ONE item — **throws `Unsupported tuple type`**, the lambda's argument check as in G16. " +
      "**SEPARATING.**"),
    ("C15", "map_one_coll_control", "0b0dad1c0103020406d90101107201", true,
      "**CONTROL for G20** — over `Coll(Coll[Int](1, 2, 3))`: the same collection at 41."),
    ("G21", "filter_empty_data_tuples", "0b10b50c4804040400d90101480404040101", true,
      "`Filter(c, (x: (Int, Int, Int)) => true)` as the root over the EMPTY data collection — **an empty " +
      "collection at cost 31 whose `elem` is `Coll[Any]`**: `Filter` keeps the input's own item type, which for a " +
      "data collection of non-pair tuples is `Coll[Any]` (`Tuple.non_pair_value_producers` P5). G19 and G21 " +
      "differ in nothing but the item type of their empty results. **SEPARATING.**"),
    ("C16", "filter_empty_coll_control", "0b09b51c00d90101100101", true,
      "**CONTROL for G21** — over an empty `Coll[Coll[Int]]`: an empty `Coll[Coll[Int]]` at 31."),
    ("G22", "filter_one_data_tuple", "0b13b50c4804040401020406d90101480404040101", false,
      "`Filter` over ONE item — **throws `Unsupported tuple type`**, the lambda's argument check as in G16. " +
      "**SEPARATING.**"),
    ("C17", "filter_one_coll_control", "0b0db51c0103020406d90101100101", true,
      "**CONTROL for G22** — over `Coll(Coll[Int](1, 2, 3))`: the same collection at 41."))

  def extract(): Map[String, Json] = {
    val entries = rows.zipWithIndex.map { case ((id, slug, tree, accepts, what), i) =>
      val name = s"$slug-${if (accepts) "accept" else "errored"}#$i"
      val script = s"[$id] $what"
      if (accepts) SpecExtract.authoredEntry(Op, script, tree, name, dummyInput, V3)
      else SpecExtract.authoredRejectEntry(Op, script, tree, name, dummyInput, V3)
    }
    Map(Op -> SpecExtract.authoredEnvelope(Op, entries, Source)
      .mapObject(_.add("blessed_by", Json.fromString("jvm:sigma-state-6.0.7"))))
  }

  def writeVectors(outDir: java.nio.file.Path): Unit =
    SpecExtract.writeStaging("AuthoredEvalNonPairTupleGates", extract(), outDir)
}
