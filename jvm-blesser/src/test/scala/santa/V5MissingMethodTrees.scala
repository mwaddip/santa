package santa

// 15 method ids the JVM parses at every tree version and sigma-rust's tables lack. Shared by the
// eval and wire blessers. Request: `prompts/santa-probe-v5-methods-missing-in-rust.md`
// (sigma-rust session, 2026-11-09); measured by `santa.spike.V5MethodsMissingProbe`.
//
// THE PROBE'S COUNT TABLE IS EXACTLY RIGHT, and its id hypothesis with it — checked by dumping
// every (typeId, methodId) `SMethod.fromIds` resolves, rather than by reading:
//   SGroupElement (7)  v0 ids 2,3,4,5          v3 adds 6 (expUnsigned)   -> 4 / 5
//   SOption       (36) v0 ids 2,3,4,7,8        v3 the same               -> 5 / 5
//   SBox          (99) v0 ids 1..18            v3 adds 19                -> 18 / 19
// (totals: 96 parser-visible methods at v0, 199 at v3.)
//
// The SOption set is five ids, of which Rust lacks THREE — 2, 3 and 4. I first read the probe's
// "Rust 2 / 2" as a subset not containing ids 7 `map` and 8 `filter` and told the sigma-rust
// session its table understated the gap; that was wrong. Their 2/2 is what Rust HAS, and those two
// ARE ids 7 and 8 (both in its table at v0, both with evaluators in `eval/soption.rs`). The
// probe's own table named the three missing ids correctly and I should have taken it rather than
// re-deriving a different answer from the counts. Measured while correcting it, since the probe
// listed it as unmeasured: the JVM DOES evaluate both as MethodCalls —
// `getVar[Int](1).map(x => x)` and `.filter(x => true)` each give `Some(Int 0)` at **cost 49**
// (`FixedCost(JitCost(20))` plus the receiver and the lambda). Not vectored; this family covers
// the three Rust lacks.
//
// THE OUTCOME SPLITS THE FIFTEEN IN TWO, which is the answer to the question the probe could not
// read off the source: four of the six method NAMES invoke, and two do not.
//
//   exp 3, multiply 4, isDefined 2, get 3    EVALUATE, with costs
//   R0..R9 (9..18)                           `NoSuchMethodException: sigma.Box.R0()`
//   getOrElse 4                              `NoSuchMethodException: scala.Option.getOrElse(int)`
//
// Both failures are the reflective lookup, and both confirm the probe's own suspicions: the
// register methods are `R4[T](implicit cT: RType[T])`, so a lookup for a no-arg `R4()` finds
// nothing; Scala's `getOrElse` takes a by-name `Function0`, so `getOrElse(int)` finds nothing.
// **So eleven of the fifteen ids are a DEAD parser-visible surface** — reachable by a parser,
// listed in the table, and not invocable. Same shape as the thirteen unevaluated node kinds.
//
// The DEDICATED NODE for each of those operations works fine (`ExtractRegisterAs(SELF, R4,
// Option[Int])` gives `Option null` at cost 60; `OptionGetOrElse` gives a value at 30), so this is
// a property of the MethodCall encoding and not of the operation.

import sigma.VersionContext

object V5MissingMethodTrees {
  val Activated: Byte = VersionContext.V6SoftForkVersion // 3
  val V0: Byte = 0

  /** An eval row: the bare expression. `accept` rows evaluate; the rest error. */
  case class Row(n: Int, slug: String, expr: String, accept: Boolean, what: String)

  val rows: Seq[Row] = Seq(
    Row(1, "group_element_exp", "dc0703db6a01dd01060105", accept = true,
      "`groupGenerator.exp(BigInt 5)` — SGroupElement id 3, `Exponentiate.costKind` Fixed 900. " +
      "EVALUATES to g^5 at cost 928. The dedicated `Exponentiate` node costs 915, so the " +
      "MethodCall encoding adds 13."),
    Row(2, "group_element_multiply", "dc0704db6a01dd01db6a01dd", accept = true,
      "`groupGenerator.multiply(groupGenerator)` — SGroupElement id 4, `MultiplyGroup` Fixed 40. " +
      "EVALUATES to g^2 at cost 82, against the dedicated `MultiplyGroup` node's 60."),
    Row(3, "box_r0_is_defined", "e6db6309a7", accept = false,
      "`SELF.R0.isDefined` with R0 as a PropertyCall (SBox id 9). **The JVM PARSES it and CANNOT " +
      "INVOKE it:** `NoSuchMethodException: sigma.Box.R0()`. The descriptor is " +
      "`R0[T](implicit cT: RType[T])`, so a reflective lookup for a no-arg `R0()` finds nothing — " +
      "the probe's own suspicion, confirmed. The dedicated `ExtractRegisterAs` node does the same " +
      "job at cost 60."),
    Row(4, "box_r4_is_defined", "e6db630da7", accept = false,
      "`SELF.R4.isDefined` (SBox id 13). Same `NoSuchMethodException: sigma.Box.R4()`. R0 is " +
      "mandatory and R4 is not, and it makes NO difference — the failure is the lookup, before " +
      "any register is read, so all ten of R0..R9 are dead the same way."),
    Row(5, "option_is_defined_some", "db2402dc6a09dd01040a04", accept = true,
      "`Global.some[Int](5).isDefined` — SOption id 2, Fixed 10. EVALUATES to `true` at cost 33."),
    Row(6, "option_is_defined_none", "db2402db6a0add04", accept = true,
      "`Global.none[Int].isDefined`. EVALUATES to `false` at cost 28 — 5 less than the `some` " +
      "row, since `none` costs less to build."),
    Row(7, "option_get_some", "db2403dc6a09dd01040a04", accept = true,
      "`Global.some[Int](5).get` — SOption id 3, Fixed 15. EVALUATES to `5` at cost 38."),
    Row(8, "option_get_none", "db2403db6a0add04", accept = false,
      "`Global.none[Int].get` — the method INVOKES and then throws, as Scala's `Option.get` does " +
      "on `None`: `InvocationTargetException`. Note the contrast with rows 3/4/9/10, where the " +
      "lookup itself fails — this row reached the method and that one did not, and only the class " +
      "tells them apart."),
    Row(9, "option_get_or_else_some", "dc2404dc6a09dd01040a04010412", accept = false,
      "`Global.some[Int](5).getOrElse(9)` — SOption id 4, Fixed 20. **PARSES and CANNOT BE " +
      "INVOKED:** `NoSuchMethodException: scala.Option.getOrElse(int)`. Scala's `getOrElse` takes " +
      "a BY-NAME default (`Function0`), so no `getOrElse(int)` exists — the probe's second " +
      "suspicion, also confirmed. The dedicated `OptionGetOrElse` node works, at cost 30."),
    Row(10, "option_get_or_else_none", "dc2404db6a0add04010412", accept = false,
      "`Global.none[Int].getOrElse(9)`. The same `NoSuchMethodException` — the receiver being " +
      "`none` changes nothing, because the lookup fails before the receiver is used."))

  def rowBySlug(slug: String): Row =
    rows.find(_.slug == slug).getOrElse(sys.error(s"no such row: $slug"))

  /** A bare expression's root is not a SigmaProp, so a STRICT parse degrades it on rule 1001; the
    * eval tier reads these through the lenient path, as every value-returning eval vector does. */
  def evalTree(expr: String): String =
    "0b" + RentFixtures.hex(RentFixtures.vlqU32((expr.length / 2).toLong)) + expr

  /** The probe's seven wrapped trees, at v0 UNSIZED and v3 SIZE-FLAGGED. Both parse identity on
    * the JVM. **v0 is the separating version:** an implementation that refuses the method id at
    * lookup has no degrade path at an unsized tree and REJECTS the carrier, while at v3 it
    * degrades and echoes the very bytes the JVM echoes — and the wire tier is degrade-blind, so
    * the v3 row cannot tell a correct parse from a degrade. The same mirror as the small-tuples
    * and type-id-order families. */
  val wrapped: Seq[(String, String, String)] = Seq(
    ("exp",       "00cddc0703db6a01dd01060101",   "0b0ccddc0703db6a01dd01060101"),
    ("multiply",  "00cddc0704db6a01dd01db6a01dd", "0b0dcddc0704db6a01dd01db6a01dd"),
    ("r0",        "00d1e6db6309a7",               "0b06d1e6db6309a7"),
    ("r4",        "00d1e6db630da7",               "0b06d1e6db630da7"),
    ("is_defined","00d1db2402e30104",             "0b07d1db2402e30104"),
    ("get",       "00d1db2403e30101",             "0b07d1db2403e30101"),
    ("get_or_else","00d1dc2404e30101010101",      "0b0ad1dc2404e30101010101"))

  /** The two trees whose EVAL fails for a reason that is not the method under test: both ask for
    * `getVar[Boolean](1)` and the canonical eval context binds var 1 as an Int, so they error
    * `InvalidType: Cannot getVar[Boolean](1)`. Harmless here — the wire tier grades the
    * round-trip and nothing else — but they must never be reused as spend or eval vectors. */
  val evalBlindTrees = Set("get", "get_or_else")
}
