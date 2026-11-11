package santa

// Authored eval vectors — where a non-pair tuple VALUE comes from, and what the TYPE-DIRECTED
// consumers do with one. Request: `~/projects/sigma-rust/prompts/santa-probe-tuple-values.md`
// (rows S1-S4, E1-E3, W1/W2, measured in that round but not authored with the operations) and
// `santa-probe-tuple-values-2.md` §1-§3. Measured by `santa.spike.TupleValuesProbe`.
// Companions: `Tuple.non_pair_value_ops` (the operations), `Tuple.non_pair_value_gates` (the
// refusals), `wire/…/non_pair_tuple_operand` (where each refusal happens).
//
// TWO OPS, because the two questions are different.
//
// `Tuple.non_pair_value_producers` — a script can meet such a value by five routes, and all five
// answer: a nested tuple's field (`SelectField`), an item of a data COLLECTION of tuples, an
// `Option`'s content, `Global.deserializeTo`, and the collection itself as the root. The last is
// the probe's §1 question and the row to read twice: a data `Coll[(Int,Int,Int)]` carries
// `tItem = collRType(AnyType)` (`CoreDataSerializer`'s generic branch), NOT the tuple type, so the
// blesser writes `elem` = `{"tag":"SColl","elem":{"tag":"SAny"}}` and each item as a `Coll[SAny]`.
// Measured, not read: the parsed constant's value is a `CollOverArray` with
// `tItem=CollType(GeneralType(Any))` whose item 0 is a `CollOverArray` with `tItem=GeneralType(Any)`.
//
// **Is that item type observable to a script? YES, and my first answer here was wrong.** When this
// family was written I reasoned that it is not: equality compares item types before any item, but
// the operands' STATIC types must agree first, and `EQ(Coll[(Int,Int,Int)], Coll[Coll[Int]])` is
// refused AT PARSE by the builder's SameType check — so with data as *the only route* to such a
// collection, any two would share the item type. **The premise was false.** The sigma-rust session's
// branch review (probe 3, 2026-11-11) found a SECOND producer: a collection EXPRESSION in a
// register or a context variable is built at `stypeToRType(elementType)`
// (`ConcreteCollection.value`, `values.scala:879-884`), which for a non-pair tuple is `TupleType`
// and NOT `Coll[Any]` — measured. Both collections then have the same DECLARED type, so the
// comparison passes SameType and the item types differ: a register holding the expression form
// compares **`false` @81** against this data constant, where the data form compares **`true`
// @119** (the early bail saves 38). So the item type IS observable, by comparing the two
// producers; what cannot be written is a comparison between two differently-DECLARED collections.
//
// E1 and its control stay exactly as they were — two data collections compare equal at 49 — and
// the pair that exposes the difference is NOT in this family, because our own register surface can
// only express one of the two producers: `decodeColl` falls into its nested-collection arm for an
// `STuple` element (STuple extends SCollection) and builds `tItem = stypeToRType(elem)` = the
// TUPLE type, i.e. the EXPRESSION route. The data route is not expressible as a register at all,
// and the two are indistinguishable in the vector JSON — so authoring that pair would bury a
// premise the vector cannot state. On the taskboard as an envelope decision.
//
// The three E rows are also the round's one COST divergence: sigma-rust answers all three correctly
// and **13 low** in every container (36/27/30 against 49/40/43), which is one number to find rather
// than three bugs.
//
// `Tuple.non_pair_value_type_directed` — `Global.serialize` writes by the ARGUMENT's static type
// (`serialize_eval` uses `mc.args(0).tpe`, `methods.scala:1982`), so the tuple itself serializes
// (`020406` @33 — the items, not the type) while two shapes that reach it fail:
//   · an `SAny`-typed argument — `SerializerException: Don't know how to serialize (1, SAny)`;
//   · a tuple type whose VALUE is shorter than the type declares, which `Slice` produces —
//     `AssertionError: Type (SInt,SInt,SInt) doesn't correspond to value`, the length assert.
// The second is the sharpest consequence of the declared type lying about the length
// (`Tuple.non_pair_value_ops` T4/T5 are the same `Slice`, read through `SizeOf` instead).
//
// AND `SubstConstants` CANNOT SUBSTITUTE ONE, BY CONSTRUCTION. `toArrayOfConstants` lifts each item
// by the collection's `tItem` (`eval/Extensions.scala:53-64`), and for a collection of non-pair
// tuples that lift yields a **`Coll[SAny]`-typed constant** — measured directly, not inferred from
// the error. `SubstConstants` then requires the new constant's type to equal the one it replaces,
// which is the tuple type, so the two can never agree however the script is built. Substituting an
// `Int` into the same script fails the same requirement, which is why that is NOT the control: the
// control is the identical machinery over a script whose constant 0 IS an `Int`, where it returns
// the re-serialized script at cost 315.

import io.circe.Json

object AuthoredEvalNonPairTupleConsumers {
  val OpProducers    = "Tuple.non_pair_value_producers"
  val OpTypeDirected = "Tuple.non_pair_value_type_directed"
  val Source         = "santa:authored-non-pair-tuple-consumers"
  private val V3: Byte = 3

  private val dummyInput: Json =
    Json.obj("kind" -> Json.fromString("Int"), "value" -> Json.fromInt(0))

  /** `(id, slug, treeHex, accepts, what)` — hexes dumped from the probe (tests P2/P3b/P4). */
  private val producers: Seq[(String, String, String, Boolean, String)] = Seq(
    ("P1", "select_field_of_nested_tuple", "0b0cb18c4c480404040204060801", true,
      "`SizeOf(SelectField(((1,2,3), 4), 1))` — **3 at cost 29.** The first route: a non-pair tuple " +
      "nested inside a PAIR, pulled out by `SelectField`. `checkType` is shallow, so the outer " +
      "pair's field access never inspects the inner tuple's arity. **SEPARATING.**"),
    ("C1", "select_field_of_nested_coll_control", "0b0cb18c86021003020406040801", true,
      "**CONTROL for P1** — the same shape with a `Coll[Int]` in the field instead of a tuple: 3 at " +
      "cost 49. Dearer than P1 by 20 because the field is built by a `Tuple` NODE here rather than " +
      "read out of a constant, which is what shows P1's cost belongs to the constant."),
    ("P2", "item_of_data_collection", "0b0eb1b20c4804040401020406040000", true,
      "`SizeOf(ByIndex(Coll((1,2,3)), 0))` — **3 at cost 54.** The second route: an ITEM of a data " +
      "collection of tuples. The item's runtime type is `Coll[Any]`, so `SizeOf` reads it. " +
      "**SEPARATING.**"),
    ("P3", "content_of_an_option", "0b0bb1e4244804040401020406", true,
      "`SizeOf(OptionGet(Some((1,2,3))))` — **3 at cost 34.** The third route. Note the v0 twin of " +
      "this row does not exist: `SOption` data requires ErgoTree v3, and an unsized tree has no " +
      "size bit to degrade behind, so the v0 form is REJECTED outright. **SEPARATING.**"),
    ("P4", "deserialize_to_tuple", "0b0fb1dc6a04dd010e0302040648040404", true,
      "`SizeOf(Global.deserializeTo[(Int,Int,Int)](020406))` — **3 at cost 160**, the dearest route " +
      "and the fourth: the value is built by the DATA deserializer at eval time from three bytes, " +
      "which is the path `CoreDataSerializer` takes on a register or an extension. **SEPARATING.**"),
    ("P5", "data_collection_root", "0b090c4804040401020406", true,
      "`Coll((1,2,3))` as the ROOT — **cost 5**, and the row to read for its VALUE rather than its " +
      "cost: `{\"kind\":\"Coll\",\"elem\":{\"tag\":\"SColl\",\"elem\":{\"tag\":\"SAny\"}},…}` with " +
      "each item a `Coll[SAny]`. A data collection of non-pair tuples carries " +
      "`tItem = collRType(AnyType)` and NOT the tuple type, so this is the second place the corpus " +
      "writes `{\"tag\":\"SAny\"}` (the first being `Tuple.non_pair_value_ops` T5). **SEPARATING:** " +
      "an implementation that keeps the tuple type as the item type writes a different `elem` here " +
      "and is red for the encoding. All three conformers with an eval arm do exactly that. **And " +
      "the item type IS observable to a script, through a second producer** — a collection " +
      "EXPRESSION in a register carries the TUPLE type, so it compares `false` against this data " +
      "form where another data collection compares `true`; see the family header, which corrects " +
      "an earlier claim of mine that it could not be seen."),
    ("E1", "eq_two_data_collections", "0b13930c48040404010204060c4804040401020406", true,
      "`Coll((1,2,3)) == Coll((1,2,3))` — **true at cost 49.** `DataValueComparer` compares item " +
      "types before any item, and two data collections of non-pair tuples share `Coll[Any]`, so " +
      "they compare equal. **SEPARATING, and the round's one COST divergence:** sigma-rust answers " +
      "`true` at 36 — correct value, 13 low — and the same 13 on E2 and E3, so it is one number."),
    ("C2", "eq_two_coll_of_coll_control", "0b0d931c01030204061c0103020406", true,
      "**CONTROL for E1** — `Coll(Coll[Int](1,2,3)) == Coll(Coll[Int](1,2,3))`, the same nesting " +
      "with a declared item type: true at cost 41. The 8 between them is the tuple collection's " +
      "own encoding, not a different comparison. (This cross pair — a tuple collection against " +
      "THIS one — cannot be written: the operands' static types disagree and the builder's " +
      "SameType check refuses it at PARSE. That does NOT make the item type unobservable: two " +
      "collections of the same declared type and different item types are writable, and they " +
      "separate — see the family header.)"),
    ("E2", "eq_two_options", "0b1393244804040401020406244804040401020406", true,
      "`Some((1,2,3)) == Some((1,2,3))` — **true at cost 40.** Scala's `==` reaches inside the " +
      "`Option` and then compares two `Coll[Any]`s. **SEPARATING** (sigma-rust: 27, the same 13)."),
    ("E3", "eq_two_pairs_holding_tuples", "0b13934c48040404020406084c4804040402040608", true,
      "`((1,2,3), 4) == ((1,2,3), 4)` — **true at cost 43**, the third container. **SEPARATING** " +
      "(sigma-rust: 30, the same 13). The three E rows together say the undercharge is in the " +
      "tuple's own comparison and not in any one container."),
    ("V1", "eq_coll_of_pair_holding_tuple_vs_concrete",
      "0b1dd1930c4c48040404010204060883014c480404044c4804040402040608", true,
      "`sigmaProp(Coll[((Int,Int,Int),Int)](((1,2,3),4)) == ConcreteCollection(((1,2,3),4)))` — " +
      "**`SigmaProp` true at cost 91.** A DATA collection of pairs-holding-a-tuple compared with a " +
      "collection EXPRESSION of the same shape. Both carry the same DECLARED type, so the " +
      "builder's `SameType` check passes and the comparison is actually reached. It belongs beside " +
      "E1-E3 because it is the fourth container in which that comparison is exercised, and the " +
      "nesting is what makes it the deepest. **SEPARATING, and graded so rather than assumed:** " +
      "ergots answers **78, exactly 13 LOW**, which is the SAME 13 it is low by on E1/E2/E3 " +
      "(49/40/43 against 36/27/30) — so the undercharge is a CONSTANT offset in its model of this " +
      "comparison and not a function of the shape, and this deepest container is the fourth place " +
      "that shows it. arkadianet ERRORS, having no such nested comparison at all. The sigma-rust " +
      "fork is GREEN: it read 13 low here before its tuple-values round, fixed it, and has pinned " +
      "this very tree and cost as a unit test on its own branch. **I authored this row expecting " +
      "it to be a regression guard on all four and the grading refuted that** — which row " +
      "separates is a fact about two implementations and never derivable from the oracle, so it is " +
      "recorded here from the conform run and not from the measurement."))
  private val typeDirected: Seq[(String, String, String, Boolean, String)] = Seq(
    ("W1", "serialize_tuple", "0b0cdc6a03dd0148040404020406", true,
      "`Global.serialize((1,2,3))` — **`020406` at cost 33.** `DataSerializer` writes the ITEMS and " +
      "not the type, which is why a tuple and a one-item collection of it differ by exactly the " +
      "length byte (W2). **REGRESSION GUARD, not a separating row:** sigma-rust already matches " +
      "this one in value AND cost, which is itself worth knowing — its serializer is right where " +
      "its evaluator is not — and the guard is what catches a representation port breaking it."),
    ("W2", "serialize_collection_of_tuple", "0b0edc6a03dd010c4804040401020406", true,
      "`Global.serialize(Coll((1,2,3)))` — **`01020406` at cost 36**: the same three bytes behind a " +
      "length. **REGRESSION GUARD** for the same reason as W1: already matching, and the row that " +
      "would catch the port writing the tuple's TYPE instead of its items."),
    ("C1", "serialize_coll_int_control", "0b0adc6a03dd011003020406", true,
      "**CONTROL for W1/W2** — `Global.serialize(Coll[Int](1,2,3))` is `03020406` at cost 36. The " +
      "same 36 as W2 and the same three value bytes: a collection of three Ints and a collection " +
      "holding one triple serialize identically but for the length, which is what shows W1/W2 are " +
      "about the ITEMS reaching the writer."),
    ("R2", "serialize_any_typed_argument", "0b10dc6a03dd01b248040404020406040000", false,
      "`Global.serialize(ByIndex((1,2,3), 0))` — **throws `SerializerException: Don't know how to " +
      "serialize (1, SAny)`** (in the cause of an `InvocationTargetException`). `serialize_eval` " +
      "writes by the ARGUMENT's static type, `ByIndex` over a tuple is typed `SAny`, and " +
      "`DataSerializer` has no `SAny` arm. **SEPARATING:** an implementation that serializes by the " +
      "runtime value rather than the declared type answers here and over-accepts."),
    ("R3", "serialize_length_lying_tuple", "0b11dc6a03dd01b44804040402040604000404", false,
      "`Global.serialize(Slice((1,2,3), 0, 2))` — **throws `AssertionError: Type " +
      "(SInt,SInt,SInt) doesn't correspond to value`.** `Slice`'s static type stays the 3-item " +
      "tuple while its value holds 2, and the tuple arm of the writer asserts the two agree. THE " +
      "SHARPEST CONSEQUENCE of the declared type lying about the length — `Tuple.non_pair_value_ops` " +
      "T4/T5 are this same `Slice` read through `SizeOf`, where the lie is harmless. " +
      "**SEPARATING.**"),
    ("S1", "subst_constants_tuple_new_values",
      "0b22740e131b1102480404040204060406d193b1730073011001000c4804040401020406", false,
      "`SubstConstants(script, Coll(0), Coll((1,2,3)))` where the script is SEGREGATED and its " +
      "constant 0 IS the tuple — **throws `IllegalArgumentException: expected new constant to have " +
      "the same (SInt,SInt,SInt) tpe`.** `toArrayOfConstants` lifts each item by the collection's " +
      "`tItem`, which for a collection of non-pair tuples yields a **`Coll[SAny]`-typed constant** " +
      "(measured directly), and `SubstConstants` requires the new constant's type to equal the old " +
      "one's. **So substituting a non-pair tuple constant is impossible BY CONSTRUCTION**, not " +
      "merely refused: no script can be built whose constant type the lift could match. " +
      "**SEPARATING:** an implementation that lifts by the DECLARED tuple type instead substitutes " +
      "successfully and over-accepts."),
    ("C2", "subst_constants_int_script_control", "0b16740e0d1b0b02040e040ed19373007301100100100112", true,
      "**CONTROL for S1** — the identical machinery over a script whose constant 0 is an `Int`, " +
      "substituting `Coll[Int](9)`: it RETURNS the re-serialized script at cost 315. Note what is " +
      "NOT the control: substituting an `Int` into S1's tuple-constant script fails the SAME " +
      "requirement, so that comparison would be circular and prove nothing. This one shows the " +
      "machinery works and only the tuple case cannot be expressed."),
    ("S2", "subst_constants_positions_as_tuple",
      "0b17740e0d1b0b02040e040ed1937300730160010400100112", false,
      "`SubstConstants(script, (0), Coll[Int](9))` — the POSITIONS argument given as a 1-item " +
      "TUPLE instead of a `Coll[Int]`. **Throws `ClassCastException: [Ljava.lang.Object; cannot be " +
      "cast to [I`.** The positions parameter is a primitive `Array[Int]`, and a 1-item tuple's " +
      "value is a generic `Coll[Any]` over an Object array, so the cast fails before any " +
      "substitution is attempted. This is the THIRD distinct way `SubstConstants` meets a non-pair " +
      "tuple, after S1 (the new VALUES cannot be lifted to the old constant's type) and C2 (the " +
      "machinery working over honest `Int` arguments) — and the only one that fails on the " +
      "POSITIONS side. **C2 is its control too**, being the identical call with the positions as a " +
      "real `Coll[Int]`: that returns the re-serialized script at 315, so the difference here is " +
      "the argument's representation and nothing else. **REGRESSION GUARD by expectation, and the " +
      "grading is what settles it:** the sigma-rust fork refuses positions-as-a-tuple too (its own " +
      "PR 77 relay), so every eval-arm conformer is expected to match. If any accepts it, this row " +
      "SEPARATING is the correction to make — a role is never derivable from the oracle alone."))
  private def entriesOf(op: String, rows: Seq[(String, String, String, Boolean, String)]): Json = {
    val entries = rows.zipWithIndex.map { case ((id, slug, tree, accepts, what), i) =>
      val name = s"$slug-${if (accepts) "accept" else "errored"}#$i"
      val script = s"[$id] $what"
      if (accepts) SpecExtract.authoredEntry(op, script, tree, name, dummyInput, V3)
      else SpecExtract.authoredRejectEntry(op, script, tree, name, dummyInput, V3)
    }
    SpecExtract.authoredEnvelope(op, entries, Source)
      .mapObject(_.add("blessed_by", Json.fromString("jvm:sigma-state-6.0.7")))
  }

  def extract(): Map[String, Json] = Map(
    OpProducers    -> entriesOf(OpProducers, producers),
    OpTypeDirected -> entriesOf(OpTypeDirected, typeDirected))

  def writeVectors(outDir: java.nio.file.Path): Unit =
    SpecExtract.writeStaging("AuthoredEvalNonPairTupleConsumers", extract(), outDir)
}
