package santa

// Authored eval vectors — THE SPECIALISATION CENSUS, the rows where the JVM's verdict depends on
// how a collection is HELD at runtime rather than on its declared type.
// Request: `prompts/santa-probe-coll-specialisation-census.md` (sigma-rust session, 2026-11-09),
// measured by `santa.spike.CollSpecialisationCensusProbe` (part 1, the producer map) and
// `santa.spike.CollSpecialisationMatrixProbe` (part 1b + part 2, this file's rows).
//
// Follows `Coll.runtime_shapes_methodcall` B1/B2/B3/B4 (SANTA `e1ed78b`), which showed the Byte
// half: a `Coll[Byte]` CONSTANT throws where a `ConcreteCollection` of the same bytes returns.
//
// THE RULE behind every row, measured and then confirmed against the source: specialisation is
// decided by the STATIC TYPE AT THE CONSTRUCTION CALL SITE. `CollOverArray` is `@specialized`
// (`CollsOverArrays.scala:11`) and so is `CollBuilder.fromArray` (`:226`), so
//   - the data deserializer specialises EXACTLY TWO item types: `SBoolean`
//     (`CoreDataSerializer.scala:172`) and `SByte` (`:174`). Its generic branch (`:175-191`)
//     builds a primitive array through a ClassTag and then calls `fromArray` at an ABSTRACT
//     static type, so a `Coll[Short|Int|Long]` off the wire is the PLAIN class over `[S`/`[I`/`[J`;
//   - `ConcreteCollection` is generic for every item type (`values.scala:897`);
//   - `indices` is always `$mcI$sp` (`CollsOverArrays.scala:68`, `fromArray(toArray.indices
//     .toArray)` — a concrete `Array[Int]`), which is the ONLY in-script route to a specialised
//     Int collection, since no deserializer ever makes one.
//
// AND THE RULE FOR WHICH OPERATIONS CARE: an operation separates iff `CollOverArray` OVERRIDES it
// with an `A`-typed or `Coll[A]`-typed parameter AND the value is not stored into the primitive
// array. `getOrElse` (`:26`) RETURNS the default, so the specialised bridge unboxes it and throws
// while the generic class hands it back. `startsWith`/`endsWith` (`:44`/`:46`) read
// `ys.toArray`, so the specialised code casts the ARGUMENT's array. By contrast
// `updated`/`patch`/`updateMany` (`:100`/`:94`/`:106`) STORE into the array, so the generic class
// throws too (no separation), and `indexOf` is not defined here at all — `Colls.scala:199` is a
// trait default, `indexWhere(x => elem == x)`, so the item is only compared with boxed `==`
// (numeric widening, never a cast) and the class is invisible to it.
//
// Four families:
//   A  `Coll.specialisation_getorelse`      — the `$mcZ$sp` and `$mcI$sp` halves of the B pair
//   B  `Coll.specialisation_prefix_suffix`  — startsWith/endsWith, the second separating family
//   C  `ByIndex.default_specialisation`     — the node's default, WITH A TREE-VERSION SPLIT
//   D  `Coll.specialisation_blind_ops`      — the controls: EQ and indexOf see no difference
//
// A NOTE ON THE CLASS: these throws reach `EvalCore` as `InvocationTargetException` and the real
// `ClassCastException` is only in the cause (`Append` is the same story with a different pair,
// CCE specialised vs `ArrayStoreException` generic). The eval tier grades `errored` coarsely and
// matches no string, so grade on the verdict, never on the message.

import io.circe.Json

object AuthoredEvalCollSpecialisation {
  val OpGetOrElse  = "Coll.specialisation_getorelse"
  val OpPrefix     = "Coll.specialisation_prefix_suffix"
  val OpByIndex    = "ByIndex.default_specialisation"
  val OpBlind      = "Coll.specialisation_blind_ops"
  val OpPairPrefix = "Coll.specialisation_pair_prefix"
  val OpItemStore  = "Coll.item_store_type_check"
  val OpCrossWidth = "EQ.cross_width_numeric"
  val OpProducer   = "Coll.producer_specialisation_witness"
  val OpPairShape  = "Coll.pair_shape_map"

  private val V3: Byte = 3

  private val dummyInput: Json =
    Json.obj("kind" -> Json.fromString("Int"), "value" -> Json.fromInt(0))

  /** `(id, slug, treeHex, accepts, ergoTreeVersion, what)`. Hexes are the serializer's own, dumped
    * by `CollSpecialisationMatrixProbe` test 5 from AST-built expressions. */
  private type Row = (String, String, String, Boolean, Int, String)

  // ── A. getOrElse: the mcZ and mcI halves ────────────────────────────────────────────────
  private val getOrElseRows: Seq[Row] = Seq(
    ("Z1", "bool_constant_int_default_oob", "0b0bdc0c020d030502040a0402", false, 3,
      "`Coll[Boolean](true,false,true)` as a CONSTANT — the deserializer's OTHER specialised item " +
      "type (`CoreDataSerializer.scala:172`, `fromArray(r.getBits(len))`) — with `.getOrElse(5, 1)`, " +
      "an Int default. The specialised bridge unboxes the default to Boolean and throws " +
      "`ClassCastException: class java.lang.Integer cannot be cast to class java.lang.Boolean`. " +
      "**SEPARATING:** it proves B1/B2 are about SPECIALISATION and not about Byte — the same " +
      "mechanism on the only other item type the wire specialises."),
    ("Z2", "bool_constant_int_default_in_range", "0b0bdc0c020d03050204000402", false, 3,
      "The same at index 0, IN range: still throws, because the unboxing precedes the index. " +
      "**SEPARATING.**"),
    ("Z3", "bool_concrete_int_default_oob", "0b0bdc0c0285030502040a0402", true, 3,
      "The GENERIC twin — a `ConcreteCollection` of the same three booleans (`values.scala:897` " +
      "calls `fromArray` at an abstract type) — `.getOrElse(5, 1)` RETURNS the mistyped default, " +
      "`Int 1` at cost 79, out of a collection declared `Coll[Boolean]`. The other half of the " +
      "pair: same declared type, same call, opposite outcome."),
    ("Z4", "bool_concrete_int_default_in_range", "0b0bdc0c028503050204000402", true, 3,
      "The same at index 0: returns the element `true` at cost 79, where Z2 throws."),
    ("Z5", "bool_constant_bool_default_oob", "0b0bdc0c020d030502040a0100", true, 3,
      "**CONTROL for Z1/Z2:** the specialised receiver with a WELL-TYPED default returns `false` " +
      "at cost 49. It is what shows Z1/Z2 fail on the default's type and not on the receiver."),
    ("I1", "indices_bool_default_oob", "0b10dc0c02db0c0e0e0301020302040a0101", false, 3,
      "`Coll[Byte](1,2,3).indices.getOrElse(5, true)`. `indices` is the ONLY in-script route to a " +
      "specialised `Coll[Int]` (`CollsOverArrays.scala:68` builds it from a concrete `Array[Int]`; " +
      "no deserializer ever specialises Int), so this is a `$mcI$sp` receiver with a Boolean " +
      "default: `ClassCastException` to `java.lang.Integer`. **SEPARATING, and the third " +
      "specialised kind** — the separation is the specialisation itself, on every kind that " +
      "exists."),
    ("I2", "indices_bool_default_in_range", "0b10dc0c02db0c0e0e030102030204000101", false, 3,
      "The same at index 0, IN range. **SEPARATING.**"),
    ("I3", "int_concrete_bool_default_oob", "0b11dc0c0283030404000402040402040a0101", true, 3,
      "The GENERIC twin, a `ConcreteCollection` of the SAME three ints `(0,1,2)` that `indices` " +
      "produces: `.getOrElse(5, true)` RETURNS `true` at cost 79. Same declared `Coll[Int]`, same " +
      "items, same call — and the opposite verdict from I1."),
    ("I4", "int_concrete_bool_default_in_range", "0b11dc0c028303040400040204040204000101", true, 3,
      "The same at index 0: returns the element `Int 0` at cost 79, where I2 throws."),
    ("I5", "indices_int_default_oob", "0b10dc0c02db0c0e0e0301020302040a0412", true, 3,
      "**CONTROL for I1/I2:** the specialised `indices` receiver with a well-typed Int default " +
      "returns `9` at cost 75."))

  // ── B. startsWith / endsWith ────────────────────────────────────────────────────────────
  private val prefixRows: Seq[Row] = Seq(
    ("S1", "startswith_byte_constant_long_arg", "0b16dc0c1f0e030102030195010083010202018301050502", false, 3,
      "`Coll[Byte](1,2,3)` CONSTANT (specialised `$mcB$sp`) `.startsWith(ys)` where `ys` is " +
      "declared `Coll[Byte]` but evaluates to a `Coll[Long]` — the lie carried by an `If` whose " +
      "branches disagree, so no static check can reject it. `CollOverArray.startsWith` (`:44`) is " +
      "`toArray.startsWith(ys.toArray)`, and in specialised code that casts the ARGUMENT's array " +
      "to `[B`: `ClassCastException`. **SEPARATING, and the second separating family after " +
      "getOrElse** — here it is the argument's array that is cast, not an item."),
    ("S2", "startswith_byte_concrete_long_arg", "0b1adc0c1f8303020201020202030195010083010202018301050502", true, 3,
      "The GENERIC twin with the SAME argument RETURNS **`true`** at cost 90 — because Scala's " +
      "`Array.startsWith` compares boxed elements and `java.lang.Long(1) == java.lang.Byte(1)` " +
      "numerically. **THE WORST ROW IN THE FAMILY and the one to lead with:** the JVM does not " +
      "merely accept the ill-typed argument, it answers TRUE. An implementation holding one " +
      "representation cannot produce both this and S1."),
    ("S3", "startswith_bool_constant_long_arg", "0b12dc0c1f0d0305019501008501018301050502", false, 3,
      "The same on the `$mcZ$sp` kind: a `Coll[Boolean]` CONSTANT with a `Coll[Long]` argument " +
      "throws. **SEPARATING.**"),
    ("S4", "startswith_bool_concrete_long_arg", "0b12dc0c1f850305019501008501018301050502", true, 3,
      "Its generic twin returns **`false`** at cost 90 — the boxed comparison runs and simply " +
      "fails (a Boolean is not numerically equal to 1L). Together with S2 this says the generic " +
      "answer is the ITEMS' answer, not a refusal."),
    ("S5", "startswith_indices_long_arg", "0b19dc0c1fdb0c0e0e030102030195010083010404008301050502", false, 3,
      "The same on the `$mcI$sp` kind (`indices`): throws. **SEPARATING.**"),
    ("S6", "startswith_int_concrete_long_arg", "0b1adc0c1f8303040400040204040195010083010404008301050502", true, 3,
      "Its generic twin returns `false` at cost 90 (`0` is not `1L`)."),
    ("S7", "startswith_byte_constant_empty_long_arg", "0b12dc0c1f0e0301020301950100830002830005", false, 3,
      "A ZERO-LENGTH ill-typed argument still throws on the specialised receiver: the array cast " +
      "precedes any length check. **SEPARATING, and the sharpest of the set** — there is no " +
      "element to compare, so an implementation can only reproduce it by modelling the cast."),
    ("S8", "startswith_byte_concrete_empty_long_arg", "0b16dc0c1f83030202010202020301950100830002830005", true, 3,
      "Its generic twin returns `true` at cost 85 — vacuously, every collection starts with an " +
      "empty one."),
    ("S9", "startswith_byte_constant_wellformed", "0b0edc0c1f0e03010203018301020201", true, 3,
      "**CONTROL:** the specialised receiver with a well-typed `Coll[Byte](1)` argument returns " +
      "`true` at cost 45. S1 is not about startsWith being unavailable."),
    ("S10", "startswith_byte_concrete_wellformed", "0b12dc0c1f830302020102020203018301020201", true, 3,
      "**CONTROL:** the generic receiver, same argument, `true` at cost 75."),
    ("E1", "endswith_byte_constant_long_arg", "0b16dc0c200e030102030195010083010202018301050502", false, 3,
      "`endsWith` (`:46`) is the same shape as `startsWith` and throws on the specialised " +
      "receiver. **SEPARATING.**"),
    ("E2", "endswith_byte_concrete_long_arg", "0b1adc0c208303020201020202030195010083010202018301050502", true, 3,
      "Its generic twin returns **`false`** at cost 90 — with the SAME argument that made S2 " +
      "`true`. The asymmetry is the data, not the types: `(1,2,3)` starts with `1L` and does not " +
      "end with it. A row that pins the comparison as numeric rather than refused."),
    ("E3", "endswith_byte_constant_wellformed", "0b0edc0c200e03010203018301020203", true, 3,
      "**CONTROL:** the specialised receiver with `Coll[Byte](3)` returns `true` at cost 45."))

  // ── C. ByIndex's default, and the tree-version split ────────────────────────────────────
  private val byIndexRows: Seq[Row] = Seq(
    ("N1", "v0_byte_constant_bool_default_in_range", "00b20e030102030400010101", false, 0,
      "`ByIndex(Coll[Byte] CONSTANT, 0, default = true)` at **tree version 0**. Below v3 the " +
      "default is ALWAYS evaluated and handed to `getOrElse` (`transformers.scala:271-276`), so a " +
      "specialised receiver throws EVEN FOR AN IN-RANGE INDEX. **SEPARATING, and the v0 half of " +
      "the version witness** — compare N7, the same body at tree v3, which evaluates."),
    ("N2", "v0_byte_constant_bool_default_oob", "00b20e030102030412010101", false, 0,
      "The same at index 9, out of range: throws at v0 too. **SEPARATING.**"),
    ("N3", "v0_byte_concrete_bool_default_in_range", "00b28303020201020202030400010101", true, 0,
      "The GENERIC twin in range returns the element `Byte 1` at cost 75: the eager default is " +
      "evaluated and passed, but nothing casts it."),
    ("N4", "v0_byte_concrete_bool_default_oob", "00b28303020201020202030412010101", true, 0,
      "The generic twin OUT OF RANGE returns **the mistyped default itself — `true`, a " +
      "`java.lang.Boolean` out of a collection declared `Coll[Byte]`** — at cost 75. The JVM " +
      "hands a script a value of the wrong type rather than refusing. **The most consequential " +
      "accept in the round:** an implementation that type-checks the default here OVER-REJECTS."),
    ("N5", "v0_byte_constant_good_default_in_range", "00b20e030102030400010207", true, 0,
      "**CONTROL:** the specialised receiver with a well-typed `Byte 7` default returns `Byte 1` " +
      "at cost 45 — so N1 is about the default's type, and the 45 (not 40) is the EAGER default's " +
      "extra 5."),
    ("N6", "v0_byte_concrete_good_default_oob", "00b28303020201020202030412010207", true, 0,
      "**CONTROL:** the generic receiver out of range with a well-typed default returns `Byte 7` " +
      "at cost 75."),
    ("N7", "v3_byte_constant_bool_default_in_range", "0b0bb20e030102030400010101", true, 3,
      "**THE VERSION WITNESS.** The SAME body as N1 at **tree version 3**, where the default is " +
      "LAZY (`transformers.scala:261-270` uses `apply` in range and never touches the default): " +
      "it returns `Byte 1` at cost 40. One tree version apart, a specialised receiver with an " +
      "ill-typed default goes from a hard throw to a clean value, and the cost gap 40-vs-45 is " +
      "the eager evaluation that is no longer done."),
    ("N8", "v3_byte_constant_bool_default_oob", "0b0bb20e030102030412010101", false, 3,
      "At v3 the lazy default IS reached once the index is out of range, so the specialised " +
      "receiver throws again. **SEPARATING** — and with N7 it shows the v3 behaviour is laziness, " +
      "not a dropped check."),
    ("N9", "v3_byte_concrete_bool_default_in_range", "0b0fb28303020201020202030400010101", true, 3,
      "The generic twin in range: `Byte 1` at cost 70 (five less than N3 — the lazy default)."),
    ("N10", "v3_byte_concrete_bool_default_oob", "0b0fb28303020201020202030412010101", true, 3,
      "The generic twin out of range still returns the mistyped `true`, at cost 75. The " +
      "over-reject risk of N4 is not confined to old trees."),
    ("N11", "v3_byte_constant_good_default_in_range", "0b0bb20e030102030400010207", true, 3,
      "**CONTROL:** well-typed default, specialised receiver, `Byte 1` at cost 40 — the same as " +
      "N7, which is what makes N7's accept about laziness rather than about the default."),
    ("N12", "v3_byte_concrete_good_default_oob", "0b0fb28303020201020202030412010207", true, 3,
      "**CONTROL:** generic receiver, out of range, well-typed default: `Byte 7` at cost 75."))

  // ── D. the blind operations: the controls that pin NON-separation ────────────────────────
  private val blindRows: Seq[Row] = Seq(
    ("Q1", "eq_byte_constant_vs_concrete", "0b0f930e03010203830302020102020203", true, 3,
      "**CONTROL:** a specialised `Coll[Byte]` CONSTANT `==` a `ConcreteCollection` of the same " +
      "three bytes is **`true`** at cost 58. Equality is NOT provenance-sensitive, so an " +
      "implementation that models the two shapes must still compare them equal. Without this row " +
      "the family could be 'passed' by making the shapes unequal."),
    ("Q2", "eq_bool_constant_vs_concrete", "0b07930d0305850305", true, 3,
      "**CONTROL:** the same on the `$mcZ$sp` kind, `true` at cost 58."),
    ("Q3", "eq_indices_vs_concrete", "0b1293db0c0e0e03010203830304040004020404", true, 3,
      "**CONTROL:** `indices` (`$mcI$sp`) `==` a ConcreteCollection of the same ints, `true` at " +
      "cost 84."),
    ("X1", "indexof_indices_cross_width", "0b15dc0c1adb0c0e0e0301020302950100040205020400", true, 3,
      "`indices.indexOf(x, 0)` where `x` is declared Int and evaluates to `1L`: **finds it at " +
      "index 1**, cost 96 — the same answer as the well-typed control. `indexOf` is NOT defined " +
      "on `CollOverArray`: `Colls.scala:199` is a trait default, `indexWhere(x => elem == x)`, so " +
      "the item is compared with boxed `==` (numerically, across widths) and never cast. " +
      "**CONTROL for the whole round:** a specialised receiver does NOT make every ill-typed " +
      "argument fatal, and the rule is which methods the specialised class overrides."),
    ("X2", "indexof_int_concrete_cross_width", "0b16dc0c1a83030404000402040402950100040205020400", true, 3,
      "The generic twin answers identically, `1` at cost 100. No separation, by design of the " +
      "trait default."))


  // ── E. pair collections: the SIDE's class separates where the pair's own ops do not ──────
  private val pairPrefixRows: Seq[Row] = Seq(
    ("P1", "pair_spec_side_startswith_lie_ls", "0b30dc0c1fdc0c1d0e030102030183030404000402040401dc0c1d9501008301020201830105050201830304040004020404", false, 3,
      "`Coll[Byte] CONSTANT .zip(Coll(0,1,2))` — a `PairOfCols` whose **ls is specialised** " +
      "(`zip` keeps both sides as they are, `CollsOverArrays.scala:42`) — `.startsWith(ys)` where " +
      "`ys` is a pair collection whose own ls is declared `Coll[Byte]` and evaluates to " +
      "`Coll[Long]`. `PairOfCols.startsWith` (`:398-406`) DELEGATES to `ls.startsWith(yp.ls)`, so " +
      "the specialised side's array cast fires: `ClassCastException`. **SEPARATING, and the row " +
      "that distinguishes pair collections from the A-family:** `getOrElse`/`updated` on a pair " +
      "cast the whole default to `Tuple2` first and are blind to the sides' classes, but " +
      "startsWith/endsWith are not."),
    ("P2", "pair_gen_side_startswith_lie_ls", "0b34dc0c1fdc0c1d8303020201020202030183030404000402040401dc0c1d9501008301020201830105050201830304040004020404", true, 3,
      "The same with a GENERIC ls (a ConcreteCollection of the same bytes): returns **`true`** at " +
      "cost 190 — the delegated `startsWith` compares boxed elements and `1L == 1`, exactly as in " +
      "the flat S2 row. The pair's rs is identical on both sides, so the only difference is the " +
      "ls's runtime class."),
    ("P3", "pair_spec_side_startswith_wellformed", "0b2cdc0c1fdc0c1d0e030102030183030404000402040401dc0c1d83030202010202020301830304040004020404", true, 3,
      "**CONTROL:** the specialised-ls receiver with a well-typed pair argument returns `true` at " +
      "cost 155."),
    ("P4", "pair_gen_side_startswith_wellformed", "0b30dc0c1fdc0c1d8303020201020202030183030404000402040401dc0c1d83030202010202020301830304040004020404", true, 3,
      "**CONTROL:** the generic-ls receiver, same argument, `true` at cost 185."),
    ("P5", "pair_spec_side_endswith_lie_ls", "0b30dc0c20dc0c1d0e030102030183030404000402040401dc0c1d9501008301020201830105050201830304040004020404", false, 3,
      "`endsWith` on the same pair receiver: the same delegation, the same cast. **SEPARATING.**"),
    ("P6", "pair_gen_side_endswith_lie_ls", "0b34dc0c20dc0c1d8303020201020202030183030404000402040401dc0c1d9501008301020201830105050201830304040004020404", true, 3,
      "Its generic twin returns **`false`** at cost 190 — the same argument that made P2 `true`, " +
      "because `(1,2,3)` does not END with `1L`. The P2/P6 asymmetry pins the comparison as " +
      "numeric rather than refused, inside a pair."))

  // ── F. the item STORE: a primitive array rejects any wrong item, an Object array only a
  //       wrong CLASS. Neither is about specialisation — both classes behave alike — but Rust
  //       reportedly returns here for every item type but Byte. ──────────────────────────────
  private val itemStoreRows: Seq[Row] = Seq(
    ("U1", "updated_generic_bool_coll_int_item", "0b0bdc0c148503050204000402", false, 3,
      "`ConcreteCollection[Boolean](true,false,true).updated(0, 1)` — a GENERIC receiver with an " +
      "Int item. `CollOverArray.updated` (`:100`) is `toArray.updated(index, elem)` and the " +
      "backing array is `[Z`, so the store unboxes and throws `ClassCastException`. **SEPARATING " +
      "against an implementation that stores items untyped:** the generic class is NOT lenient " +
      "here, which is what distinguishes this family from `getOrElse`."),
    ("U2", "patch_generic_bool_coll_int_item", "0b10dc0c1385030503040083010404020402", false, 3,
      "The same through `patch` (`:94`, `toArray.patch(...)`). **SEPARATING.**"),
    ("U3", "updated_generic_byte_coll_bool_item", "0b11dc0c148303020201020202030204000101", false, 3,
      "The Byte item type: `ConcreteCollection[Byte](1,2,3).updated(0, true)` throws. " +
      "**SEPARATING.**"),
    ("U4", "patch_generic_byte_coll_bool_item", "0b14dc0c138303020201020202030304008501010402", false, 3,
      "The same through `patch`. **SEPARATING.**"),
    ("U5", "updated_generic_short_coll_bool_item", "0b0fdc0c14830203030203040204000101", false, 3,
      "The Short item type — note no producer ever yields a SPECIALISED Short collection, so this " +
      "row is the generic class throwing on its own account. **SEPARATING.**"),
    ("U6", "patch_generic_short_coll_bool_item", "0b12dc0c13830203030203040304008501010402", false, 3,
      "The same through `patch`. **SEPARATING.**"),
    ("U7", "updated_generic_int_coll_bool_item", "0b11dc0c148303040400040204040204000101", false, 3,
      "The Int item type. **SEPARATING.**"),
    ("U8", "patch_generic_int_coll_bool_item", "0b14dc0c138303040400040204040304008501010402", false, 3,
      "The same through `patch`. **SEPARATING.**"),
    ("U9", "updated_generic_long_coll_bool_item", "0b0fdc0c14830205050205040204000101", false, 3,
      "The Long item type. **SEPARATING.**"),
    ("U10", "patch_generic_long_coll_bool_item", "0b12dc0c13830205050205040304008501010402", false, 3,
      "The same through `patch`. **SEPARATING.**"),
    ("U11", "updated_coll_of_coll_compatible_reference_item", "0b13dc0c1483011083010404020204008301050502", true, 3,
      "`Coll[Coll[Int]](Coll(1)).updated(0, Coll[Long](1L))` — a REFERENCE-backed array " +
      "(`[Lsigma.Coll;`), so the store only checks the item's CLASS, and a `Coll` is a `Coll`: it " +
      "**RETURNS** at cost 100, and the result holds an item of the wrong item type. The " +
      "complement of U1-U10: an ill-typed item is rejected by a primitive array and WAVED THROUGH " +
      "by an Object array. An implementation that type-checks items uniformly over-rejects here."),
    ("U12", "updated_coll_of_box_incompatible_reference_item", "0b0bdc0c14830163a702040082", false, 3,
      "`Coll[Box](SELF).updated(0, groupGenerator)` — the same reference-backed store with an " +
      "INCOMPATIBLE class throws `ArrayStoreException` (not a ClassCastException: the array " +
      "itself refuses the element). **SEPARATING**, and it bounds U11: Object arrays are not " +
      "lenient about everything, only about compatible classes."))

  // ── G. cross-width numeric comparison: NOT what the probe expected ──────────────────────
  private val crossWidthRows: Seq[Row] = Seq(
    ("W1", "eq_int_as_long_vs_long", "0b0a93950100050204020502", false, 3,
      "`EQ(x, 1L)` where `x` is declared `Long` and evaluates to an `Int` (the `If` lie). It never " +
      "reaches `DataValueComparer`: `Value.checkType` refuses first — `InterpreterException: " +
      "Invalid type returned by evaluator`. **SEPARATING, and a REFUTATION of the natural " +
      "reading:** a cross-width comparison cannot be built at the SCALAR level at all, because " +
      "`EQ`'s operand is a checkType site. The widening that boxed `==` would do is unreachable " +
      "here."),
    ("W2", "neq_int_as_long_vs_long", "0b0a94950100050204020502", false, 3,
      "`NEQ` of the same operands: the same refusal, same site. **SEPARATING.**"),
    ("W3", "eq_long_as_int_vs_int", "0b0a93950100040205020402", false, 3,
      "The lie the other way round (declared `Int`, runs as `Long`). **SEPARATING.**"),
    ("W4", "eq_int_as_long_vs_long_unequal", "0b0a93950100050204040502", false, 3,
      "The same with operands that are NOT numerically equal (2 vs 1L) — it still throws, so the " +
      "refusal is about the TYPE and not about the comparison's outcome. **SEPARATING.**"),
    ("W5", "eq_pair_with_cross_width_component", "0b12938602950100040205020404860204020404", false, 3,
      "Inside a PAIR: `(x, 2) == (1, 2)` with `x` declared `Int` and running as `Long`. The pair's " +
      "component is a checkType site too, so this throws rather than comparing. **SEPARATING.**"),
    ("W6", "eq_coll_with_cross_width_items", "0b1393950100830104040283010505028301040402", true, 3,
      "Inside a COLLECTION the lie survives — a collection's ITEM type is not a checkType site " +
      "(the element-type gate round) — so the comparer does run, and it answers **`false`** at " +
      "cost 66: `DataValueComparer` bails on a runtime item-type difference before comparing any " +
      "item. So the JVM's answer for a cross-width collection comparison is FALSE, not the " +
      "numeric TRUE that `indexOf`/`startsWith` give for the same values. **The sharpest " +
      "statement of the round's asymmetry:** `==` widens where the item is passed as an ARGUMENT " +
      "(X1, S2) and does not where two collections are COMPARED."))

  // ── H. the producer map made testable: same declared type, different producer ────────────
  private val producerWitnessRows: Seq[Row] = Seq(
    ("H1", "self_bytes_bool_default", "0b0bdc0c02c3a70204c6010101", false, 3,
      "`SELF.bytes.getOrElse(99, true)`. `ExtractBytes` builds its result with " +
      "`Colls.fromArray(Array[Byte])` at a concrete static type, so it is **specialised** and the " +
      "Boolean default is unboxed: throws. **SEPARATING, and half of the producer pair** — " +
      "compare H2, the same declared `Coll[Byte]`, off the SAME BOX, which returns."),
    ("H2", "self_creation_info_bytes_bool_default", "0b0ddc0c028cc7a7020204c6010101", true, 3,
      "`SELF.creationInfo._2.getOrElse(99, true)` — also a `Coll[Byte]`, also off SELF, but it " +
      "arrives through the TUPLE machinery and is the **generic** class, so the same call RETURNS " +
      "`true` at cost 80. **THE OTHER HALF:** within one box, two `Coll[Byte]` values with " +
      "opposite verdicts on an identical call. Nothing about the declared type or the method " +
      "distinguishes them — only which producer built the collection."),
    ("H3", "int_tobytes_bool_default", "0b0edc0c02db0406040a0204c6010101", true, 3,
      "`5.toBytes.getOrElse(99, true)` RETURNS `true` at cost 58: the numeric `toBytes` is the " +
      "**generic** class."),
    ("H4", "bigint_tobytes_bool_default", "0b0fdc0c02db06060601050204c6010101", false, 3,
      "`bigInt(5).toBytes.getOrElse(99, true)` THROWS — **the same method id 6, one container " +
      "over, and the opposite verdict**, because `SBigIntMethods.toBytes` yields a specialised " +
      "collection where the fixed-width numerics do not. **SEPARATING**, and the row that shows " +
      "the producer map has to be learned per method, not per method NAME."),
    ("H5", "int_tobits_int_default", "0b0edc0c02db0407040a0204c6010402", false, 3,
      "`5.toBits.getOrElse(99, 1)` THROWS: every `toBits` returns the specialised Boolean class " +
      "(`$mcZ$sp`), so an Int default is unboxed to Boolean. **SEPARATING**, and the third " +
      "specialised kind reached through yet another producer."),
    ("H6", "self_bytes_good_default", "0b0cdc0c02c3a702049e9c010207", true, 3,
      "**CONTROL for H1:** a well-typed `Byte 7` default returns at cost 66. The index is **9999**, " +
      "out of range for any box, so this row tests the default's type and nothing else — at index " +
      "99 it was confounded by the box's serialized LENGTH (see H10/H11)."),
    ("H7", "int_tobytes_good_default", "0b0edc0c02db0406040a0204c6010207", true, 3,
      "**CONTROL for H3/H4:** `5.toBytes.getOrElse(99, 7)` returns `Byte 7` at cost 58."),
    ("H8", "self_proposition_bytes_bool_default", "0b0bdc0c02c2a70204c6010101", false, 3,
      "`SELF.propositionBytes.getOrElse(99, true)` throws — another specialised producer off the " +
      "same box. **SEPARATING.**"),
    ("H10", "self_bytes_size", "0b05db0c01c3a7", true, 3,
      "`SELF.bytes.size` = **46** at cost 40 — the canonical eval context's SELF box serialized " +
      "length, which contract §2 fully determines (value 1000000, this tree, no tokens, no " +
      "registers, txId 32x00, index 0, creationHeight 0). **A DIAGNOSTIC ROW, not a " +
      "specialisation one:** it exists because the first cut of H6 read index 99 and a runner " +
      "answered with a byte instead of the default, which can only mean its SELF.bytes is longer. " +
      "Any divergence here invalidates every row that reads SELF.bytes, so grade it first."),
    ("H11", "self_bytes_good_default_in_box_range", "0b0bdc0c02c3a70204c6010207", true, 3,
      "`SELF.bytes.getOrElse(99, 7)` — index 99 is OUT of range for a 46-byte box, so the JVM " +
      "returns the default `Byte 7` at cost 66. **The consequence row for H10**, and deliberately " +
      "confounded with it: an implementation whose SELF box serializes longer than 99 bytes " +
      "returns a byte of its own box here instead. Read it together with H10, never alone."),
    ("H9", "blake2b256_bool_default", "0b0cdc0c02cbc3a70204c6010101", false, 3,
      "`blake2b256(SELF.bytes).getOrElse(99, true)` throws: every hash, `Xor`, " +
      "`LongToByteArray`, `minerPubKey`, `AvlTree.digest` and `GroupElement.getEncoded` is " +
      "specialised. **SEPARATING**, and the cheapest row to reproduce from a fresh implementation."))


  // ── I. a collection of pairs is NOT always a PairOfCols ─────────────────────────────────
  // `PairOfCols.map` builds `new CollOverArray(res, builder)` directly (`CollsOverArrays.scala`
  // ~:318), so mapping a pair collection — by the identity function — turns it into a GENERIC
  // collection over a `[Lscala.Tuple2;` array, which has no Tuple2 bridge. Everything else keeps
  // the PairOfCols: slice, reverse, updated, patch, append, filter (measured). `SELF.tokens` is
  // that same shape, API-built AND off the wire (`CBox.scala:56-58` reads R2, and `ErgoBox`
  // derives R2 itself), which makes the last rows consensus-reachable from any box.
  private val pairShapeRows: Seq[Row] = Seq(
    ("M1", "paircols_const_bool_default_oob", "0b0fdc0c020c58020204060802040a0101", false, 3,
      "`Coll[(Int,Int)]((1,2),(3,4))` as a CONSTANT — a `PairOfCols` — `.getOrElse(5, true)` " +
      "throws: `PairOfCols.getOrElse` (`:309`) overrides the interface method with an `(L,R)` " +
      "parameter, so the bridge casts the default to `Tuple2` before the index matters. " +
      "**SEPARATING, and the anchor for this family.**"),
    ("M2", "paircols_const_bool_default_in_range", "0b0fdc0c020c5802020406080204000101", false, 3,
      "The same at index 0. **SEPARATING.**"),
    ("M3", "mapped_pair_bool_default_oob", "0b19dc0c02dc0c030c58020204060801d9010158720102040a0101", true, 3,
      "**THE FINDING:** the SAME collection put through `.map(x => x)` — the identity — RETURNS " +
      "the mistyped default `true` at cost 99. `PairOfCols.map` builds a plain `CollOverArray` " +
      "over a `[Lscala.Tuple2;` array, which has no Tuple2 bridge, so the same declared " +
      "`Coll[(Int,Int)]` with the same items answers where M1 throws. A transformation that " +
      "changes NOTHING observable about the collection changes the verdict."),
    ("M4", "mapped_pair_bool_default_in_range", "0b19dc0c02dc0c030c58020204060801d901015872010204000101", true, 3,
      "The same at index 0: returns the element `(1,2)` at cost 99, where M2 throws."),
    ("M5", "byindex_paircols_bool_default", "0b0db20c580202040608040a010101", false, 3,
      "`ByIndex(PairOfCols, 5, default = true)` throws for the same reason. **SEPARATING.**"),
    ("M6", "byindex_mapped_pair_bool_default", "0b17b2dc0c030c58020204060801d90101587201040a010101", true, 3,
      "`ByIndex` on the MAPPED shape returns `true` at cost 95 — the node-level twin of M3/M1."),
    ("M7", "mapped_from_concrete_bool_default", "0b21dc0c02dc0c0383025886020402040486020406040801d9010158720102040a0101", true, 3,
      "The same through the other producer: a `ConcreteCollection` of tuples is also a " +
      "`PairOfCols`, and mapping it gives the Tuple2-array shape — `true` at cost 164. So the " +
      "shape follows the LAST operation, not the original construction."),
    ("M8", "paircols_const_pair_default_control", "0b13dc0c020c58020204060802040a860204120412", true, 3,
      "**CONTROL:** the PairOfCols with a well-typed pair default returns `(9,9)` at cost 69."),
    ("M9", "mapped_pair_pair_default_control", "0b1ddc0c02dc0c030c58020204060801d9010158720102040a860204120412", true, 3,
      "**CONTROL:** the mapped shape with a pair default returns `(9,9)` at cost 119."),
    ("M10", "eq_paircols_vs_mapped", "0b19930c580202040608dc0c030c58020204060801d90101587201", true, 3,
      "**CONTROL, and the one that bounds the finding:** a `PairOfCols` `==` its own mapped copy " +
      "is **`true`** at cost 95. The two representations must compare EQUAL even though they " +
      "answer `getOrElse` differently, so an implementation cannot satisfy M1/M3 by making the " +
      "shapes unequal."),
    ("M11", "eq_mapped_vs_mapped", "0b2b93dc0c030c58020204060801d90101587201dc0c0383025886020402040486020406040801d90101587201", true, 3,
      "**CONTROL:** two mapped collections from different producers, `true` at cost 210."),
    ("M12", "mapped_startswith_paircols", "0b1edc0c1fdc0c030c58020204060801d9010158720101830158860204020404", true, 3,
      "**CONTROL:** `startsWith` is blind to the split — the mapped receiver with a `PairOfCols` " +
      "argument answers `true` at cost 115. (`PairOfCols.startsWith` delegates to the sides while " +
      "the Tuple2-array form compares whole tuples; with equal items both say the same thing.)"),
    ("M13", "paircols_startswith_mapped", "0b1edc0c1f0c58020204060801dc0c0383015886020402040401d90101587201", true, 3,
      "**CONTROL:** the mirror, `true` at cost 105. M12/M13 together say the two shapes are " +
      "interchangeable for prefix comparison, which is what makes M1-vs-M3 specific to the " +
      "item-typed parameter."),
    ("M14", "self_tokens_bool_default_in_range", "0b0cdc0c02db6308a70204000101", true, 3,
      "**THE REACHABLE ONE:** `SELF.tokens.getOrElse(0, true)` RETURNS `true` at cost 73. " +
      "`CBox.tokens` reads R2 (`CBox.scala:56-58`) and `ErgoBox` derives R2 itself, so the " +
      "collection is the Tuple2-array shape — **measured identical on an API-built box and on a " +
      "box parsed from its own bytes, empty and non-empty** — and no exotic construction is " +
      "needed: every box has `tokens`. An implementation that models pair collections as two " +
      "columns throws here where the JVM answers."),
    ("M15", "self_tokens_bool_default_oob", "0b0cdc0c02db6308a702040a0101", true, 3,
      "The same at index 5, also `true` at cost 73 (the canonical SELF has no tokens, so every " +
      "index is out of range)."),
    ("M16", "self_tokens_size", "0b07db0c01db6308a7", true, 3,
      "**CONTROL and a §2 premise pin:** `SELF.tokens.size` is **0** at cost 47 — the canonical " +
      "eval context's SELF box carries no tokens. Read it with H10: both state a fact about the " +
      "box that M14/M15 depend on."),
    ("M17", "eq_self_tokens_empty_pair_coll", "0b0993db6308a783004d0e", true, 3,
      "**CONTROL:** `SELF.tokens == Coll[(Coll[Byte],Long)]()` is `true` at cost 60 — the empty " +
      "Tuple2-array shape compares equal to an empty `ConcreteCollection` of the same type."))

  private def entriesOf(op: String, rows: Seq[Row]): Json = {
    val entries = rows.zipWithIndex.map { case ((id, slug, tree, accepts, etv, what), i) =>
      val name   = s"$slug-${if (accepts) "accept" else "errored"}#$i"
      val script = s"[$id] $what"
      if (accepts) SpecExtract.authoredEntryV(op, script, tree, name, dummyInput, V3, etv)
      else SpecExtract.authoredRejectEntryV(op, script, tree, name, dummyInput, V3, etv)
    }
    SpecExtract.authoredEnvelope(op, entries, s"santa:authored-coll-specialisation")
      .mapObject(_.add("blessed_by", Json.fromString("jvm:sigma-state-6.0.7")))
  }

  def extract(): Map[String, Json] = Map(
    OpGetOrElse  -> entriesOf(OpGetOrElse, getOrElseRows),
    OpPrefix     -> entriesOf(OpPrefix, prefixRows),
    OpByIndex    -> entriesOf(OpByIndex, byIndexRows),
    OpBlind      -> entriesOf(OpBlind, blindRows),
    OpPairPrefix -> entriesOf(OpPairPrefix, pairPrefixRows),
    OpItemStore  -> entriesOf(OpItemStore, itemStoreRows),
    OpCrossWidth -> entriesOf(OpCrossWidth, crossWidthRows),
    OpProducer   -> entriesOf(OpProducer, producerWitnessRows),
    OpPairShape  -> entriesOf(OpPairShape, pairShapeRows))

  def writeVectors(outDir: java.nio.file.Path): Unit =
    SpecExtract.writeStaging("AuthoredEvalCollSpecialisation", extract(), outDir)
}
