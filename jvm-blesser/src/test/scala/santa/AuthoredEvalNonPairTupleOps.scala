package santa

// Authored eval vectors — EVERY OPERATION over a non-pair tuple VALUE, each with a plain-collection
// control. Request: `~/projects/sigma-rust/prompts/santa-probe-tuple-values.md` (sigma-rust session,
// 2026-11-11, queue item 7: it is moving non-pair tuples from `Value::Tup` to a generic collection
// of `Any` and wanted the JVM's answer wherever such a value meets a consumer). Measured by
// `santa.spike.TupleValuesProbe`; write-up `docs/findings/eval-non-pair-tuple-values.md`.
//
// THE MECHANISM: a pair is a Scala `Tuple2`; every other tuple is a generic `Coll[Any]`.
// `CoreDataSerializer.deserialize` reads a tuple's items into `Colls.fromArray(arr)(sigma.AnyType)`
// and hands them to `Evaluation.toDslTuple`, which pairs up only at arity 2
// (`CoreDataSerializer.scala:139-144`, `Evaluation.scala:98-101`), and `Tuple.value` builds the same
// `Coll[Any]` (`values.scala:818-822`). `STuple extends SCollection[SAny.type]`
// (`SType.scala:838`), so such a value type-checks into every collection position.
//
// THE RULE the rows establish: a non-pair tuple works wherever the operation needs only the `Coll`
// INTERFACE, and throws where it needs the backing ARRAY of a particular class — `CollOverArray`'s
// `toArray` on a `Coll[Any]` is an `Object[]`. Measured on both sides of every row:
//
//   T1-T11, T14-T16, T19, T20  the interface is enough: value AND COST identical to the control
//   T12, T13, T17, T18, T21, T22  the array is needed: ArrayStoreException / ClassCastException,
//                                 where the control RETURNS
//
// WHY EVERY ROW HAS A CONTROL, and it is not ceremony: while measuring this family a
// `ConcreteCollection` control made `AND`/`OR`/`XorOf` look 30 dearer on the tuple side, and
// type-var-typed arguments made seven `SCollection` methods look broken. Both were the harness's
// doing. A row whose control is absent cannot tell "the tuple decides this" from "the operation
// fails anyway" — and that is the whole claim of the family.
//
// ROOTS ARE SCALAR except T5/C5, the one pair with a bare `Coll[Any]` root. That pair pins the
// SValue ENCODING of such a value — `{"kind":"Coll","elem":{"tag":"SAny"},"items":[…]}`, each item
// by its RUNTIME kind — which is the only place in this family where the item TYPE is observable at
// all: wrapping `Slice` in `SizeOf` hides exactly the difference the port has to get right. The
// sigma-rust session confirmed (2026-11-11) that after its port a non-pair tuple value is
// `CollValue::Generic` with item type `SAny` and that blitzen's `encode_coll` writes the item type
// from the value, so this is its encoding too, with no runner change. It is the corpus's FIRST
// `{"tag":"SAny"}`; the schema has always allowed it.
//
// Z1/Z2 ARE READABLE AND UNWRITABLE. A 0- or 1-item tuple TYPE parses and evaluates (`SizeOf` gives
// 0 and 1 at cost 19), but `TypeSerializer.serialize` refuses to write it
// (`TypeSerializer.scala:95`, "Invalid Tuple type with less than 2 items"). So no BOX can carry such
// a script — its id cannot even be computed, the id being over the writer's form — which is why
// these rows are eval-tier only and have no spend arm. `Tuple.small_tuples` covers the same types at
// the `SelectField`/`EQ` type-check sites, where they are REFUSED; here the same types are ACCEPTED
// by a consumer that does not type-check them. The pair of families is the point: one type, two
// consumers, opposite verdicts.
//
// P1 is the lower boundary: a PAIR is a `Tuple2`, not a `Coll`, so `SizeOf` on it throws
// `ClassCastException` — the control that shows the family is about NON-pair tuples specifically.

import io.circe.Json

object AuthoredEvalNonPairTupleOps {
  val Op     = "Tuple.non_pair_value_ops"
  val Source = "santa:authored-non-pair-tuple-values"
  private val V3: Byte = 3

  private val dummyInput: Json =
    Json.obj("kind" -> Json.fromString("Int"), "value" -> Json.fromInt(0))

  /** `(id, slug, treeHex, accepts, what)`. Hexes are the serializer's own, dumped from the probe
    * (`santa.spike.TupleValuesProbe`, test V) — every tuple row and its control built from the same
    * AST with only the receiver swapped, which is what makes the pair comparable. */
  private val rows: Seq[(String, String, String, Boolean, String)] = Seq(
    ("T1", "size_tuple", "0b08b148040404020406", true,
      "`SizeOf((1,2,3))` — **3 at cost 19**. A non-pair tuple's value IS a generic `Coll[Any]`, so " +
      "`SizeOf` reads its length. **SEPARATING:** an implementation holding tuples in a dedicated " +
      "node refuses this and OVER-REJECTS. Compare C1: same value, same cost, ordinary collection."),
    ("C1", "size_coll_control", "0b06b11003020406", true,
      "**CONTROL for T1** — `SizeOf(Coll[Int](1,2,3))`, 3 at cost 19. Identical to T1, which is the " +
      "family's first claim: the representation costs nothing extra."),
    ("T2", "index_tuple", "0b0bb248040404020406040000", true,
      "`ByIndex((1,2,3), 0)` — **1 at cost 40**. The node's static result type is `SAny`, and the " +
      "value is the `Int`. **SEPARATING.**"),
    ("C2", "index_coll_control", "0b09b21003020406040000", true,
      "**CONTROL for T2** — 1 at cost 40, identical."),
    ("T3", "index_default_oob_tuple", "0b0db248040404020406040a010412", true,
      "`ByIndex((1,2,3), 5, default 9)` — **9 at cost 45**: the default is returned, not thrown. " +
      "Worth pinning because `ByIndex`'s default is the one place where a SPECIALISED receiver " +
      "throws even in range (`Coll.specialisation_getorelse`); a tuple is the generic class, so it " +
      "behaves like any generic collection. **SEPARATING.**"),
    ("C3", "index_default_oob_coll_control", "0b0bb21003020406040a010412", true,
      "**CONTROL for T3** — 9 at cost 45, identical."),
    ("T4", "slice_size_tuple", "0b0db1b44804040402040604000404", true,
      "`SizeOf(Slice((1,2,3), 0, 2))` — **2 at cost 41**. `Slice` over a tuple yields a `Coll[Any]` " +
      "of 2 items while the node's STATIC type stays the 3-item tuple: the declared type lies about " +
      "the length, which is why the expected value can never be derived from the root type. " +
      "**SEPARATING.**"),
    ("C4", "slice_size_coll_control", "0b0bb1b4100302040604000404", true,
      "**CONTROL for T4** — 2 at cost 41, identical."),
    ("T5", "slice_bare_root_tuple", "0b0cb44804040402040604000404", true,
      "`Slice((1,2,3), 0, 2)` with a BARE root — **`Coll[Any](1, 2)` at cost 27**, encoded " +
      "`{\"kind\":\"Coll\",\"elem\":{\"tag\":\"SAny\"},\"items\":[…]}` with each item by its runtime " +
      "kind. THE ENCODING PIN, and the corpus's first `{\"tag\":\"SAny\"}`: T4 wraps the same call in " +
      "`SizeOf` and so hides the item type, which is exactly the thing a port has to get right. " +
      "**SEPARATING, twice over** — on the value and on its item type (compare C5, `SInt`)."),
    ("C5", "slice_bare_root_coll_control", "0b0ab4100302040604000404", true,
      "**CONTROL for T5** — `Slice(Coll[Int](1,2,3), 0, 2)` is `Coll[Int](1, 2)` at the same cost " +
      "27, with `elem` `SInt`. The pair T5/C5 is the whole statement: same items, same cost, and " +
      "the item TYPE is the only observable the representation moves."),
    ("T6", "append_self_size_tuple", "0b10b1b34804040402040648040404020406", true,
      "`SizeOf(Append(t, t))` — **6 at cost 46**. Appending a tuple to a tuple works: both sides are " +
      "reference-backed, so the arraycopy is legal. Pairs with T12/T13, where ONE primitive-backed " +
      "operand makes the same node throw. **SEPARATING.**"),
    ("C6", "append_self_size_coll_control", "0b0cb1b310030204061003020406", true,
      "**CONTROL for T6** — 6 at cost 46, identical."),
    ("T7", "filter_size_tuple", "0b12b1b548040404020406d90101049172010402", true,
      "`SizeOf(Filter(t, x => x > 1))` — **2 at cost 150**. The lambda takes `Int` and the items are " +
      "`Int`s, so the filter runs; the result is a `Coll[Any]` of 2. **SEPARATING.**"),
    ("C7", "filter_size_coll_control", "0b10b1b51003020406d90101049172010402", true,
      "**CONTROL for T7** — 2 at cost 150, identical."),
    ("T8", "map_tuple", "0b11ad48040404020406d90101049a72010402", true,
      "`MapCollection(t, x => x + 1)` — **`Coll[Int](2,3,4)` at cost 121**. The NODE maps a tuple " +
      "with a concretely-typed lambda, and the result is a proper `Coll[Int]` (not `Coll[Any]`) " +
      "because the mapper's range decides it. Note `map` as a METHOD CALL does NOT work on the same " +
      "receiver — the method's output type variable cannot unify with an `SAny` item type — so the " +
      "node and the MethodCall differ here. **SEPARATING.**"),
    ("C8", "map_coll_control", "0b0fad1003020406d90101049a72010402", true,
      "**CONTROL for T8** — `Coll[Int](2,3,4)` at cost 121, identical in value AND item type: once " +
      "a mapper has run, nothing of the receiver's representation survives in the result."),
    ("T9", "exists_tuple", "0b11ae48040404020406d90101049372010402", true,
      "`Exists(t, x => x == 1)` — **true at cost 32**. **SEPARATING.**"),
    ("C9", "exists_coll_control", "0b0fae1003020406d90101049372010402", true,
      "**CONTROL for T9** — true at cost 32, identical."),
    ("T10", "forall_tuple", "0b11af48040404020406d90101049172010400", true,
      "`ForAll(t, x => x > 0)` — **true at cost 119**. **SEPARATING.**"),
    ("C10", "forall_coll_control", "0b0faf1003020406d90101049172010400", true,
      "**CONTROL for T10** — true at cost 119, identical."),
    ("T11", "fold_tuple", "0b17b0480404040204060400d90101589a8c7201018c720102", true,
      "`Fold(t, 0, p => p._1 + p._2)` — **6 at cost 169**. The accumulator is an `Int` here; a " +
      "non-pair tuple as the ACCUMULATOR is fatal instead (`Value.checkType` refuses the zero), " +
      "which is a separate row of its own. **SEPARATING.**"),
    ("C11", "fold_coll_control", "0b15b010030204060400d90101589a8c7201018c720102", true,
      "**CONTROL for T11** — 6 at cost 169, identical."),
    ("T12", "append_primitive_right_tuple", "0b0cb1b34804040402040610010e", false,
      "`SizeOf(Append(t, Coll[Int](7)))` — **throws `ArrayStoreException: can not copy int[] into " +
      "object array[]`**. The first row where the ARRAY and not the interface is needed: `Append` " +
      "arraycopies the operand into the receiver's array, and a primitive-backed operand cannot go " +
      "into an `Object[]`. **SEPARATING:** C12 is the same node with an ordinary collection and it " +
      "RETURNS 4, so an implementation that answers here over-accepts."),
    ("C12", "append_primitive_right_coll_control", "0b0ab1b3100302040610010e", true,
      "**CONTROL for T12** — `SizeOf(Append(Coll[Int](1,2,3), Coll[Int](7)))` is 4 at cost 46. What " +
      "makes T12's throw the tuple's doing rather than `Append`'s."),
    ("T13", "append_primitive_left_tuple", "0b0cb1b310010e48040404020406", false,
      "The same with the operands SWAPPED: `SizeOf(Append(Coll[Int](7), t))` — **throws " +
      "`ArrayStoreException: can not copy object array[] into int[]`**, the mirror message. The " +
      "asymmetry is worth having: BOTH directions fail, for opposite reasons, so an implementation " +
      "cannot fix one by ordering the copy differently. **SEPARATING.**"),
    ("C13", "append_primitive_left_coll_control", "0b0ab1b310010e1003020406", true,
      "**CONTROL for T13** — 4 at cost 46."),
    ("T14", "and_tuple", "0b089648010101010101", true,
      "`AND((true,true,true))` — **true at cost 20**. A tuple of Booleans drives the Boolean " +
      "connectives, which read the collection through the interface. **SEPARATING.**"),
    ("C14", "and_coll_control", "0b04960d0307", true,
      "**CONTROL for T14** — `AND(Coll[Boolean](true,true,true))` as a CONSTANT, true at cost 20. " +
      "It has to be a constant and not a `ConcreteCollection`: the latter costs 30 more to build, " +
      "which would make the tuple look cheaper than it is. That mistake was made and caught here."),
    ("T15", "or_tuple", "0b089748010101010101", true,
      "`OR((true,true,true))` — **true at cost 15** (it short-circuits on the first item, which is " +
      "why it is cheaper than `AND` on an all-true input). **SEPARATING.**"),
    ("C15", "or_coll_control", "0b04970d0307", true, "**CONTROL for T15** — true at cost 15."),
    ("T16", "xor_of_tuple", "0b08ff48010101010101", true,
      "`XorOf((true,true,true))` — **true at cost 30**. **SEPARATING.**"),
    ("C16", "xor_of_coll_control", "0b04ff0d0307", true, "**CONTROL for T16** — true at cost 30."),
    ("T17", "blake2b256_tuple", "0b09b1cb48020202010203", false,
      "`SizeOf(CalcBlake2b256((1b,2b,3b)))` — **throws `ClassCastException: [Ljava.lang.Object; " +
      "cannot be cast to class [B`**. A hash needs the real `byte[]`, and a tuple of Bytes is an " +
      "`Object[]`. **SEPARATING:** C17 returns 32."),
    ("C17", "blake2b256_coll_control", "0b07b1cb0e03010203", true,
      "**CONTROL for T17** — `SizeOf(CalcBlake2b256(Coll[Byte](1,2,3)))` is 32 at cost 46."),
    ("T18", "sha256_tuple", "0b09b1cc48020202010203", false,
      "`SizeOf(CalcSha256((1b,2b,3b)))` — the same cast, the same throw. Both hashes are here " +
      "because they are separate nodes with separate costs, and an implementation can easily fix " +
      "one. **SEPARATING.**"),
    ("C18", "sha256_coll_control", "0b07b1cc0e03010203", true,
      "**CONTROL for T18** — 32 at cost 107 (SHA-256 is dearer than Blake2b-256 here)."),
    ("T19", "xor_left_tuple", "0b0d9b480202020102030e03090909", true,
      "`Xor((1b,2b,3b), Coll[Byte](9,9,9))` — **`Coll[Byte](8,11,10)` at cost 22. THE EXCEPTION TO " +
      "THE RULE:** `Xor` consumes a `Coll[Byte]` and is the only such consumer measured that does " +
      "NOT need the array, so it answers over a tuple where every hash and conversion throws. An " +
      "implementation that rejects a non-Byte item type here OVER-REJECTS, which is the state " +
      "sigma-rust reports for itself. **SEPARATING.**"),
    ("C19", "xor_left_coll_control", "0b0b9b0e030102030e03090909", true,
      "**CONTROL for T19** — `Coll[Byte](8,11,10)` at cost 22, identical."),
    ("T20", "xor_right_tuple", "0b0d9b0e0309090948020202010203", true,
      "The same with the tuple on the RIGHT — `Coll[Byte](8,11,10)` at cost 22 again, so `Xor` is " +
      "blind to the representation in both operand positions. Pairs with T12/T13, where the " +
      "operand position decides the message but not the verdict. **SEPARATING.**"),
    ("C20", "xor_right_coll_control", "0b0b9b0e030909090e03010203", true,
      "**CONTROL for T20** — identical at cost 22."),
    ("T21", "at_least_tuple", "0b0a98040448080808d3d3d3", false,
      "`AtLeast(2, (sigmaProp(true) × 3))` — **throws `ClassCastException: [Ljava.lang.Object; " +
      "cannot be cast to class [Lsigma.SigmaProp;`**. The probe predicted a VALUE here and the " +
      "measurement refuted it: `AtLeast` casts the collection's array to `SigmaProp[]`. This is the " +
      "row with consensus teeth — an `AtLeast` over a tuple is a SPEND the JVM refuses. " +
      "**SEPARATING.**"),
    ("C21", "at_least_coll_control", "0b0c98040483030808d308d308d3", true,
      "**CONTROL for T21** — the same `AtLeast` over a collection of the same three props returns a " +
      "`SigmaProp` at cost 63."),
    ("T22", "byte_array_to_bigint_tuple", "0b087b48020202010203", false,
      "`ByteArrayToBigInt((1b,2b,3b))` — the `Object[]`-to-`byte[]` cast again, on a conversion " +
      "rather than a hash. **SEPARATING.**"),
    ("C22", "byte_array_to_bigint_coll_control", "0b067b0e03010203", true,
      "**CONTROL for T22** — `BigInt 66051` (0x010203) at cost 35."),
    ("P1", "pair_size_errored", "0b04b1580204", false,
      "`SizeOf((1, 2))` — a PAIR, and it **throws `ClassCastException: scala.Tuple2 cannot be cast " +
      "to class sigma.Coll`**. THE LOWER BOUNDARY of the family: `Evaluation.toDslTuple` pairs up at " +
      "arity 2 and only there, so the pair is a Scala `Tuple2` and is NOT a collection at all. An " +
      "implementation that holds every tuple the same way gets either this row or all the others " +
      "wrong. (The probe's own hex for this row declared size 5 over a 4-byte body; this is the " +
      "corrected tree.) **SEPARATING.**"),
    ("Z1", "unit_tuple_size", "0b03b16000", true,
      "`SizeOf(())` — the ZERO-item tuple type: **0 at cost 19**. It parses, it evaluates, and it " +
      "can NEVER be written back: `TypeSerializer.serialize` refuses a tuple type with fewer than " +
      "2 items (`TypeSerializer.scala:95`), so no box can carry this script — its id is over the " +
      "writer's form and cannot be computed. Eval-tier only, no spend arm, by construction. " +
      "`Tuple.small_tuples` has the same type REFUSED at the `SelectField`/`EQ` type-check sites; " +
      "`SizeOf` does not type-check its input, which is why it answers. **SEPARATING.**"),
    ("Z2", "one_item_tuple_size", "0b05b160010402", true,
      "`SizeOf((1))` — the ONE-item tuple type: **1 at cost 19**. Same story as Z1: readable and " +
      "NEVER writable, since `TypeSerializer.serialize` refuses any tuple type with fewer than 2 " +
      "items (`TypeSerializer.scala:95`), so there is no box and no spend arm for it either. The " +
      "pair Z1/Z2 pins that the reader accepts a count the writer refuses, at both ends of the " +
      "gap. **SEPARATING.**"))

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
    SpecExtract.writeStaging("AuthoredEvalNonPairTupleOps", extract(), outDir)
}
