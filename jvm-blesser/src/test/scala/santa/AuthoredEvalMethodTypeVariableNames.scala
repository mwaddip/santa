package santa

// Authored eval vectors for sigma-rust's santa-probe-method-signatures (2026-11-14, its queue item 29, fork branch
// fork/fix/method-signatures). Measured first in santa.spike.MethodSignaturesProbe (local).
//
// THE RULE, read in 6.0.7 and measured: a method call is specialised at PARSE by unifying the method's declared
// domain with the static types of the receiver and the arguments (`SMethod.specializeFor`, SMethod.scala:193-199).
// In `unifyTypes` (sigma/ast/package.scala:39-42) two type variables unify only when their NAMES are equal, and a
// variable against any other type binds to it. When the unification fails, `specializeFor` returns the method
// unspecialised and the call keeps the method's OWN variable in its static type. So when a tree gives a receiver or
// an argument a static type that names a type variable, the names in the method TABLE decide the call's type, and
// with it whatever reads that type. The 6.0.7 tables: every `Coll` method names `IV` (item) and `OV` (second), every
// `Option` method `T` (item) and `R` (`map`'s result), the `Global` generics `T`.
//
// HOW A ROW GETS SUCH A TYPE. `as[τ](x)` below is `if (false) Coll[τ]()(0) else x`: the untaken branch is never
// evaluated and the `If` takes its type, so `x` evaluates under the static type `τ`.
//
// THE CONSUMERS, which turn the call's static type into an outcome: `serialize(call)` (the writer goes by the static
// type and has no writer for a type variable: SerializerException) and an `Int` read out of the call `+ 1` (`+` is
// looked up by its left operand's type; a type variable finds no entry: `KeyNotFoundException: key 103`, 103 = 0x67
// being STypeVar's type code). Every flip row has a CONTROL beside it with every position `Int`.
//
// FOUR FAMILIES:
//  * v6 (activated 3, tree v3): sigma-rust's 57 rows, their expression bodies verbatim. Coll 38 + 4 parse rows,
//    Option 12 + 3 parse rows. The `Option` receivers are `Global.some(1)`, a v6 method.
//  * v5 (activated 2, tree v0), measured here, not asked for: the rule is not version-gated. The `plus` rows of
//    `patch`, `updated`, `updateMany` and `zip` evaluate identically at tree v0 under activated 2 and 3 (same
//    values, same costs), and `Option.map`/`filter` flip the same way over a `getVar[Int](1)` receiver (var 1 =
//    Int 0, so `Some(0)`). The parse rows rebuilt without v6 methods (`Coll[Option[τ]]()(0)` for `Global.none[τ]`).
//
// THE PARSE ROWS are `if (true) true else <a comparison of the call with a value of a named type>`: `==` checks its
// operands' types when the node is built (`SameType2`, SigmaBuilder.scala:786-788, plain `t1 == t2`), so the JVM
// refuses one name at PARSE (`ConstraintFailed`) and builds another, in a branch that is never taken. The eval tier
// grades a refusal as errored and a built tree as `true`; the wire tier (AuthoredWireMethodTypeVariableNames) grades
// accept against reject. A SECOND naming rule shows up in the v6 parse rows: the reader types
// `deserializeTo[Coll[T]]` (and `none[Coll[T]]`) with the explicit argument substituted TWICE, `Coll[Coll[T]]`,
// because the argument names the Global method's own variable `T`: once into the method by `withConcreteTypes`
// (MethodCallSerializer.scala:84), again into the node's type by `MethodCall.tpe` (values.scala:1355-1358).

import io.circe.Json
import scorex.util.encode.Base16

import sigma.serialization.SigmaSerializer

object AuthoredEvalMethodTypeVariableNames {
  val V3: Byte = 3
  val V2: Byte = 2
  val Source = "santa:authored-method-type-variable-names"
  val Stamp = "jvm:sigma-state-6.0.7"
  val OpColl6 = "Coll.method_type_variable_names_v6"
  val OpOpt6 = "Option.method_type_variable_names_v6"
  val OpColl5 = "Coll.method_type_variable_names"
  val OpOpt5 = "Option.method_type_variable_names"
  val Ops: Seq[String] = Seq(OpColl6, OpOpt6, OpColl5, OpOpt5)

  val IntInput: Json = Json.obj("kind" -> Json.fromString("Int"), "value" -> Json.fromInt(0))

  private def hx(bs: Array[Byte]): String = Base16.encode(bs).toLowerCase
  private def vlq(n: Int): String = { val w = SigmaSerializer.startWriter(); w.putUInt(n.toLong); hx(w.toBytes) }

  /** One row: `body` is the expression; v6 rows ride a sized v3 tree, v5 rows an unsized v0 tree. */
  case class Row(op: String, id: String, script: String, body: String, accept: Boolean, slug: String) {
    def v6: Boolean = op.endsWith("_v6")
    def treeHex: String = if (v6) "0b" + vlq(body.length / 2) + body else "00" + body
    def activated: Byte = if (v6) V3 else V2
    def ergoTree: Int = if (v6) 3 else 0
  }

  // ── the consumer rows: script text from (method, consumer, positions) ────────────────────────────────
  /** What the method declares at each position the rows vary: the receiver first, then the varied argument. */
  private val Declared: Map[String, Seq[String]] = Map(
    "patch" -> Seq("IV", "IV"), "updated" -> Seq("IV", "IV"), "updateMany" -> Seq("IV", "IV"),
    "zip" -> Seq("IV", "OV"), "reverse" -> Seq("IV"), "get" -> Seq("IV"), "map" -> Seq("T"), "filter" -> Seq("T"))
  private val Signature: Map[String, String] = Map(
    "patch" -> "`patch: (Coll[IV], Int, Coll[IV], Int) => Coll[IV]`",
    "updated" -> "`updated: (Coll[IV], Int, IV) => Coll[IV]`",
    "updateMany" -> "`updateMany: (Coll[IV], Coll[Int], Coll[IV]) => Coll[IV]`",
    "zip" -> "`zip: (Coll[IV], Coll[OV]) => Coll[(IV, OV)]`",
    "reverse" -> "`reverse: (Coll[IV]) => Coll[IV]`", "get" -> "`get: (Coll[IV], Int) => Option[IV]`",
    "map" -> "`map: (Option[T], T => R) => Option[R]`", "filter" -> "`filter: (Option[T], T => Boolean) => Option[T]`")

  /** `x` under the static type `τ` when the position names a variable; plain `x` for an `Int` position. */
  private def as(t: String, x: String): String = if (t == "Int") x else s"as[$t]($x)"
  private def asColl(t: String, x: String): String = if (t == "Int") x else s"as[Coll[$t]]($x)"
  private def asOpt(t: String, x: String): String = if (t == "Int") x else s"as[Option[$t]]($x)"
  /** The static type of the call when `specializeFor` leaves it unspecialised: the table's own range. */
  private val Unspecialised: Map[String, String] = Map(
    "patch" -> "Coll[IV]", "updated" -> "Coll[IV]", "updateMany" -> "Coll[IV]", "zip" -> "Coll[(IV, OV)]",
    "reverse" -> "Coll[IV]", "get" -> "Option[IV]", "map" -> "Option[R]", "filter" -> "Option[T]")

  /** The ErgoScript-like text of a consumer row. `optRecv` is the Option receiver's real value. */
  private def expr(m: String, consumer: String, p: Seq[String], optRecv: String): String = {
    val q = p ++ Seq("Int", "Int")
    val (call, read) = m match {
      case "patch"      => (s"${asColl(q(0), "Coll(3,1,2)")}.patch(0, ${asColl(q(1), "Coll(3,1)")}, 1)", "(0) + 1")
      case "updated"    => (s"${asColl(q(0), "Coll(3,1,2)")}.updated(1, ${as(q(1), "7")})", "(0) + 1")
      case "updateMany" => (s"${asColl(q(0), "Coll(3,1,2)")}.updateMany(Coll(0,1), ${asColl(q(1), "Coll(3,1)")})", "(0) + 1")
      case "zip"        => (s"Coll(3,1,2).zip(${asColl(q(1), "Coll(3,1)")})", "(0)._1 + 1")
      case "reverse"    => ("Coll(3,1,2).reverse", "(0) + 1")
      case "get"        => ("Coll(3,1,2).get(1)", ".get + 1")
      case "map"        => (s"${asOpt(q(0), optRecv)}.map((x: Int) => 7)", ".get + 1")
      case "filter"     => (s"${asOpt(q(0), optRecv)}.filter((x: Int) => true)", ".get + 1")
    }
    if (consumer == "ser") s"serialize($call)" else s"$call$read"
  }

  private val NameRule = "two type variables unify only when their names are equal (`unifyTypes`, " +
    "sigma/ast/package.scala:39-42)"
  private def consumerThrow(consumer: String): String =
    if (consumer == "ser") "`serialize` writes by the static type and has no writer for a type variable"
    else "`+` is looked up by its left operand's type, a type variable, and finds no entry (key 103 = 0x67, " +
      "STypeVar's type code)"

  /** `want`: Right((value, cost)) for a value, Left(exception) for a throw. `p`: the varied positions. */
  private def consumer(op: String, id: String, m: String, consumerKind: String, p: Seq[String], body: String,
                       want: Either[String, (String, Int)], optRecv: String = "Global.some(1)",
                       controlOf: String = ""): Row = {
    val e = expr(m, consumerKind, p, optRecv)
    val vars = p.zipWithIndex.filter(_._1 != "Int")
    val outcome = want.fold(ex => s"THROWS `$ex`", { case (v, c) => s"→ $v at cost $c" })
    val why = vars match {
      case Seq() =>
        s"CONTROL${if (controlOf.nonEmpty) s" for $controlOf" else ""}: every position `Int`, so the call " +
          "specialises on any table and the consumer reads an `Int`-typed call."
      case Seq((v, i)) =>
        val d = Declared(m)(i)
        if (want.isRight)
          s"`$v` stands where ${Signature(m)} declares `$d` itself: equal names unify with no substitution, and the " +
            "`Int` positions bind the rest, so the call is typed by its arguments. SEPARATING: a table that names " +
            s"this position otherwise cannot unify it with `$v`, leaves the call unspecialised, and throws."
        else
          s"`$v` stands where ${Signature(m)} declares `$d`: $NameRule, so `specializeFor` (SMethod.scala:193-199) " +
            s"returns the method unspecialised and the call is typed `${Unspecialised(m)}`; ${consumerThrow(consumerKind)}. " +
            s"SEPARATING: a table that names this position `$v` specialises and returns a value."
    }
    Row(op, id, s"`$e` $outcome. $why", body, want.isRight, slugOf(m, consumerKind, p, want.isRight))
  }
  private def slugOf(m: String, c: String, p: Seq[String], accept: Boolean): String =
    (Seq(m, c) ++ p.map(_.toLowerCase)).mkString("-") + (if (accept) "-accept" else "-errored")

  private val SerEx = "SerializerException"
  private val KeyEx = "KeyNotFoundException: key 103"
  private def v(s: String, c: Int): Either[String, (String, Int)] = Right((s, c))
  private def t(ex: String): Either[String, (String, Int)] = Left(ex)

  // ── v6: sigma-rust's rows, bodies verbatim ──────────────────────────────────────────────────────────
  val Coll6: Seq[Row] = {
    val o = OpColl6
    Seq(
      consumer(o, "P1", "patch", "ser", Seq("T", "Int"), "dc6a03dd01dc0c13950100b283000c6701540400001003060204030400100206020402", t(SerEx)),
      consumer(o, "P2", "patch", "plus", Seq("T", "Int"), "9ab2dc0c13950100b283000c67015404000010030602040304001002060204020400000402", t(KeyEx)),
      consumer(o, "P3", "patch", "ser", Seq("IV", "Int"), "dc6a03dd01dc0c13950100b283000c670249560400001003060204030400100206020402", v("Coll(4,6,2,2,4)", 105)),
      consumer(o, "P4", "patch", "plus", Seq("IV", "Int"), "9ab2dc0c13950100b283000c6702495604000010030602040304001002060204020400000402", v("4", 126)),
      consumer(o, "P5", "patch", "ser", Seq("Int", "T"), "dc6a03dd01dc0c131003060204030400950100b283000c670154040000100206020402", t(SerEx)),
      consumer(o, "P6", "patch", "plus", Seq("Int", "T"), "9ab2dc0c131003060204030400950100b283000c6701540400001002060204020400000402", t(KeyEx)),
      consumer(o, "P7", "patch", "ser", Seq("Int", "IV"), "dc6a03dd01dc0c131003060204030400950100b283000c67024956040000100206020402", v("Coll(4,6,2,2,4)", 105)),
      consumer(o, "P8", "patch", "plus", Seq("Int", "IV"), "9ab2dc0c131003060204030400950100b283000c670249560400001002060204020400000402", v("4", 126)),
      consumer(o, "P9", "patch", "ser", Seq("Int", "Int"), "dc6a03dd01dc0c131003060204030400100206020402", v("Coll(4,6,2,2,4)", 90), controlOf = "P1/P3/P5/P7"),
      consumer(o, "P10", "patch", "plus", Seq("Int", "Int"), "9ab2dc0c1310030602040304001002060204020400000402", v("4", 111), controlOf = "P2/P4/P6/P8"),
      consumer(o, "U1", "updated", "ser", Seq("T", "Int"), "dc6a03dd01dc0c14950100b283000c6701540400001003060204020402040e", t(SerEx)),
      consumer(o, "U2", "updated", "plus", Seq("T", "Int"), "9ab2dc0c14950100b283000c6701540400001003060204020402040e0400000402", t(KeyEx)),
      consumer(o, "U3", "updated", "ser", Seq("IV", "Int"), "dc6a03dd01dc0c14950100b283000c670249560400001003060204020402040e", v("Coll(3,6,14,4)", 86)),
      consumer(o, "U4", "updated", "plus", Seq("IV", "Int"), "9ab2dc0c14950100b283000c670249560400001003060204020402040e0400000402", v("4", 110)),
      consumer(o, "U5", "updated", "ser", Seq("Int", "T"), "dc6a03dd01dc0c141003060204020402950100b28300670154040000040e", t(SerEx)),
      consumer(o, "U6", "updated", "plus", Seq("Int", "T"), "9ab2dc0c141003060204020402950100b28300670154040000040e0400000402", t(KeyEx)),
      consumer(o, "U7", "updated", "ser", Seq("Int", "IV"), "dc6a03dd01dc0c141003060204020402950100b2830067024956040000040e", v("Coll(3,6,14,4)", 86)),
      consumer(o, "U8", "updated", "plus", Seq("Int", "IV"), "9ab2dc0c141003060204020402950100b2830067024956040000040e0400000402", v("4", 110)),
      consumer(o, "U9", "updated", "ser", Seq("Int", "Int"), "dc6a03dd01dc0c141003060204020402040e", v("Coll(3,6,14,4)", 71), controlOf = "U1/U3/U5/U7"),
      consumer(o, "U10", "updated", "plus", Seq("Int", "Int"), "9ab2dc0c141003060204020402040e0400000402", v("4", 95), controlOf = "U2/U4/U6/U8"),
      consumer(o, "M1", "updateMany", "ser", Seq("T", "Int"), "dc6a03dd01dc0c15950100b283000c6701540400001003060204021002000210020602", t(SerEx)),
      consumer(o, "M2", "updateMany", "plus", Seq("T", "Int"), "9ab2dc0c15950100b283000c67015404000010030602040210020002100206020400000402", t(KeyEx)),
      consumer(o, "M3", "updateMany", "ser", Seq("IV", "Int"), "dc6a03dd01dc0c15950100b283000c670249560400001003060204021002000210020602", v("Coll(3,6,2,4)", 87)),
      consumer(o, "M4", "updateMany", "plus", Seq("IV", "Int"), "9ab2dc0c15950100b283000c6702495604000010030602040210020002100206020400000402", v("4", 111)),
      consumer(o, "M5", "updateMany", "ser", Seq("Int", "T"), "dc6a03dd01dc0c1510030602040210020002950100b283000c67015404000010020602", t(SerEx)),
      consumer(o, "M6", "updateMany", "plus", Seq("Int", "T"), "9ab2dc0c1510030602040210020002950100b283000c670154040000100206020400000402", t(KeyEx)),
      consumer(o, "M7", "updateMany", "ser", Seq("Int", "IV"), "dc6a03dd01dc0c1510030602040210020002950100b283000c6702495604000010020602", v("Coll(3,6,2,4)", 87)),
      consumer(o, "M8", "updateMany", "plus", Seq("Int", "IV"), "9ab2dc0c1510030602040210020002950100b283000c67024956040000100206020400000402", v("4", 111)),
      consumer(o, "M9", "updateMany", "ser", Seq("Int", "Int"), "dc6a03dd01dc0c151003060204021002000210020602", v("Coll(3,6,2,4)", 72), controlOf = "M1/M3/M5/M7"),
      consumer(o, "M10", "updateMany", "plus", Seq("Int", "Int"), "9ab2dc0c1510030602040210020002100206020400000402", v("4", 96), controlOf = "M2/M4/M6/M8"),
      consumer(o, "Z1", "zip", "plus", Seq("Int", "IV"), "9a8cb2dc0c1d100306020401950100b283000c6702495604000010020602040000010402", t(KeyEx)),
      consumer(o, "Z2", "zip", "plus", Seq("Int", "OV"), "9a8cb2dc0c1d100306020401950100b283000c67024f5604000010020602040000010402", v("4", 105)),
      consumer(o, "Z3", "zip", "ser", Seq("Int", "Int"), "dc6a03dd01dc0c1d10030602040110020602", v("Coll(2,6,6,2,2)", 59)),
      consumer(o, "Z4", "zip", "plus", Seq("Int", "Int"), "9a8cb2dc0c1d10030602040110020602040000010402", v("4", 90), controlOf = "Z1/Z2"),
      consumer(o, "R1", "reverse", "ser", Seq("Int"), "dc6a03dd01db0c1e1003060204", v("Coll(3,4,2,6)", 62)),
      consumer(o, "R2", "reverse", "plus", Seq("Int"), "9ab2db0c1e10030602040400000402", v("3", 86)),
      consumer(o, "G1", "get", "ser", Seq("Int"), "dc6a03dd01dc0c211003060204010402", v("Coll(1,2)", 67)),
      consumer(o, "G2", "get", "plus", Seq("Int"), "9ae4dc0c2110030602040104020402", v("2", 79))
    ).map(r => if (r.id == "Z2") r.copy(script = r.script.replace("so the call is typed by its arguments",
        "so the call is typed `Coll[(Int, OV)]` and `_1` reads the `Int`")) else r) ++ Seq(
      Row(o, "X2", "`if (true) true else deserializeTo[Coll[T]](Coll(0.toByte)).reverse(0) == " +
        "deserializeTo[T](Coll(0.toByte))` — REFUSED AT PARSE (`ConstraintFailed`, `SameType2`): the reader types " +
        "`deserializeTo[Coll[T]]` as `Coll[Coll[T]]` (the explicit argument names the Global method's own `T` and " +
        "lands twice: `withConcreteTypes`, MethodCallSerializer.scala:84, then `MethodCall.tpe`, values.scala:1355), " +
        "`reverse` binds `IV := Coll[T]`, so `reverse(0)` is a `Coll[T]` against a `T`. Agrees with the fork; pins " +
        "the double substitution.",
        "950101010193b2db0c1edc6a04dd010e01000c670154040000dc6a04dd010e0100670154", accept = false,
        "parse-deserializeto-coll-t-reverse-index-errored"),
      Row(o, "X3", "`if (true) true else deserializeTo[Coll[T]](Coll(0.toByte)).reverse(0)(0) == " +
        "deserializeTo[T](Coll(0.toByte))` → true at cost 20: X2 with one more `(0)`, so a `T` against a `T`: " +
        "BUILT. CONTROL for X2.",
        "950101010193b2b2db0c1edc6a04dd010e01000c670154040000040000dc6a04dd010e0100670154", accept = true,
        "parse-deserializeto-coll-t-reverse-index-index-accept"),
      Row(o, "X6", "`if (true) true else deserializeTo[Coll[U]](Coll(0.toByte)).reverse == " +
        "deserializeTo[Coll[IV]](Coll(0.toByte))` → true at cost 20: `reverse` declares `IV`, `U` is not `IV`, so " +
        "the call stays unspecialised, typed `Coll[IV]`, which equals the right side's `Coll[IV]`: BUILT. SEPARATING: " +
        "a table that names `reverse`'s variable otherwise types the left side by that name and refuses the tree " +
        "at parse.",
        "950101010193db0c1edc6a04dd010e01000c670155dc6a04dd010e01000c67024956", accept = true,
        "parse-deserializeto-coll-u-reverse-vs-coll-iv-accept"),
      Row(o, "X7", "`if (true) true else deserializeTo[Coll[T]](Coll(0.toByte)).reverse == " +
        "deserializeTo[Coll[T]](Coll(0.toByte))` → true at cost 20: both sides `Coll[Coll[T]]` (the double " +
        "substitution of X2; `reverse` binds `IV := Coll[T]`): BUILT. SEPARATING for an implementation that " +
        "substitutes the explicit argument once: it types the receiver `Coll[T]`, the call unspecialised, " +
        "`Coll[IV]` against `Coll[T]`, and refuses.",
        "950101010193db0c1edc6a04dd010e01000c670154dc6a04dd010e01000c670154", accept = true,
        "parse-deserializeto-coll-t-reverse-vs-coll-t-accept"))
  }

  val Opt6: Seq[Row] = {
    val o = OpOpt6
    Seq(
      consumer(o, "A1", "map", "ser", Seq("T"), "dc6a03dd01dc2407950100b2830024670154040000dc6a09dd0104020401d9010104040e", v("Coll(1,14)", 96)),
      consumer(o, "A2", "map", "plus", Seq("T"), "9ae4dc2407950100b2830024670154040000dc6a09dd0104020401d9010104040e0402", v("8", 108)),
      consumer(o, "A3", "map", "ser", Seq("IV"), "dc6a03dd01dc2407950100b283002467024956040000dc6a09dd0104020401d9010104040e", t(SerEx)),
      consumer(o, "A4", "map", "plus", Seq("IV"), "9ae4dc2407950100b283002467024956040000dc6a09dd0104020401d9010104040e0402", t(KeyEx)),
      consumer(o, "A5", "map", "ser", Seq("Int"), "dc6a03dd01dc2407dc6a09dd0104020401d9010104040e", v("Coll(1,14)", 81), controlOf = "A1/A3"),
      consumer(o, "A6", "map", "plus", Seq("Int"), "9ae4dc2407dc6a09dd0104020401d9010104040e0402", v("8", 93), controlOf = "A2/A4"),
      consumer(o, "F1", "filter", "ser", Seq("T"), "dc6a03dd01dc2408950100b2830024670154040000dc6a09dd0104020401d90101040101", v("Coll(1,2)", 96)),
      consumer(o, "F2", "filter", "plus", Seq("T"), "9ae4dc2408950100b2830024670154040000dc6a09dd0104020401d901010401010402", v("2", 108)),
      consumer(o, "F3", "filter", "ser", Seq("IV"), "dc6a03dd01dc2408950100b283002467024956040000dc6a09dd0104020401d90101040101", t(SerEx)),
      consumer(o, "F4", "filter", "plus", Seq("IV"), "9ae4dc2408950100b283002467024956040000dc6a09dd0104020401d901010401010402", t(KeyEx)),
      consumer(o, "F5", "filter", "ser", Seq("Int"), "dc6a03dd01dc2408dc6a09dd0104020401d90101040101", v("Coll(1,2)", 81), controlOf = "F1/F3"),
      consumer(o, "F6", "filter", "plus", Seq("Int"), "9ae4dc2408dc6a09dd0104020401d901010401010402", v("2", 93), controlOf = "F2/F4"),
      Row(o, "X1", "`if (true) true else Global.none[Coll[T]].map((x: Coll[T]) => 1).get == 1` — REFUSED AT PARSE " +
        "(`ConstraintFailed`, `SameType2`): the explicit argument names `none`'s own `T` and lands twice " +
        "(MethodCallSerializer.scala:84, values.scala:1355), so the receiver is `Option[Coll[Coll[T]]]`; `map` " +
        "binds `T` to two different types, the unification fails, the call stays `Option[R]`, and `.get == 1` " +
        "compares an `R` with an `Int`. Agrees with the fork. The v5 family's N4 builds the same call over a " +
        "receiver substituted once, and it is BUILT.",
        "950101010193e4dc2407db6a0add0c67015401d901010c67015404020402", accept = false,
        "parse-none-coll-t-map-errored"),
      Row(o, "X4", "`if (true) true else Global.none[T].map((x: T) => 1).get == 1` → true at cost 20: `T` stands " +
        "where `map` declares `T`, so they unify, `R := Int`, the call is `Option[Int]`: BUILT. SEPARATING: a table " +
        "that names `Option.map`'s item otherwise leaves the call unspecialised and refuses the tree at parse.",
        "950101010193e4dc2407db6a0add67015401d9010167015404020402", accept = true, "parse-none-t-map-accept"),
      Row(o, "X5", "`if (true) true else Global.none[IV].map((x: IV) => 1).get == 1` — REFUSED AT PARSE " +
        s"(`ConstraintFailed`, `SameType2`): `IV` is not `map`'s `T`; $NameRule, so the call stays `Option[R]` and " +
        "`.get == 1` compares an `R` with an `Int`. SEPARATING: a table that names `Option.map`'s item `IV` builds it.",
        "950101010193e4dc2407db6a0add6702495601d901016702495604020402", accept = false, "parse-none-iv-map-errored"))
  }

  // ── v5 (activated 2, tree v0): measured here ────────────────────────────────────────────────────────
  val Coll5: Seq[Row] = {
    val o = OpColl5
    val v5 = " Tree v0 under activated 2: the rule is not version-gated (the same value and cost at activated 3)."
    Seq(
      consumer(o, "P2", "patch", "plus", Seq("T", "Int"), "9ab2dc0c13950100b283000c67015404000010030602040304001002060204020400000402", t(KeyEx)),
      consumer(o, "P4", "patch", "plus", Seq("IV", "Int"), "9ab2dc0c13950100b283000c6702495604000010030602040304001002060204020400000402", v("4", 126)),
      consumer(o, "P6", "patch", "plus", Seq("Int", "T"), "9ab2dc0c131003060204030400950100b283000c6701540400001002060204020400000402", t(KeyEx)),
      consumer(o, "P8", "patch", "plus", Seq("Int", "IV"), "9ab2dc0c131003060204030400950100b283000c670249560400001002060204020400000402", v("4", 126)),
      consumer(o, "P10", "patch", "plus", Seq("Int", "Int"), "9ab2dc0c1310030602040304001002060204020400000402", v("4", 111), controlOf = "P2/P4/P6/P8"),
      consumer(o, "U2", "updated", "plus", Seq("T", "Int"), "9ab2dc0c14950100b283000c6701540400001003060204020402040e0400000402", t(KeyEx)),
      consumer(o, "U4", "updated", "plus", Seq("IV", "Int"), "9ab2dc0c14950100b283000c670249560400001003060204020402040e0400000402", v("4", 110)),
      consumer(o, "U6", "updated", "plus", Seq("Int", "T"), "9ab2dc0c141003060204020402950100b28300670154040000040e0400000402", t(KeyEx)),
      consumer(o, "U8", "updated", "plus", Seq("Int", "IV"), "9ab2dc0c141003060204020402950100b2830067024956040000040e0400000402", v("4", 110)),
      consumer(o, "U10", "updated", "plus", Seq("Int", "Int"), "9ab2dc0c141003060204020402040e0400000402", v("4", 95), controlOf = "U2/U4/U6/U8"),
      consumer(o, "M2", "updateMany", "plus", Seq("T", "Int"), "9ab2dc0c15950100b283000c67015404000010030602040210020002100206020400000402", t(KeyEx)),
      consumer(o, "M4", "updateMany", "plus", Seq("IV", "Int"), "9ab2dc0c15950100b283000c6702495604000010030602040210020002100206020400000402", v("4", 111)),
      consumer(o, "M6", "updateMany", "plus", Seq("Int", "T"), "9ab2dc0c1510030602040210020002950100b283000c670154040000100206020400000402", t(KeyEx)),
      consumer(o, "M8", "updateMany", "plus", Seq("Int", "IV"), "9ab2dc0c1510030602040210020002950100b283000c67024956040000100206020400000402", v("4", 111)),
      consumer(o, "M10", "updateMany", "plus", Seq("Int", "Int"), "9ab2dc0c1510030602040210020002100206020400000402", v("4", 96), controlOf = "M2/M4/M6/M8"),
      consumer(o, "Z1", "zip", "plus", Seq("Int", "IV"), "9a8cb2dc0c1d100306020401950100b283000c6702495604000010020602040000010402", t(KeyEx)),
      consumer(o, "Z2", "zip", "plus", Seq("Int", "OV"), "9a8cb2dc0c1d100306020401950100b283000c67024f5604000010020602040000010402", v("4", 105)),
      consumer(o, "Z4", "zip", "plus", Seq("Int", "Int"), "9a8cb2dc0c1d10030602040110020602040000010402", v("4", 90), controlOf = "Z1/Z2")
    ).map { r =>
      val r2 = if (r.id == "Z2") r.copy(script = r.script.replace("so the call is typed by its arguments",
        "so the call is typed `Coll[(Int, OV)]` and `_1` reads the `Int`")) else r
      r2.copy(script = r2.script + v5)
    }
  }

  /** `as[Option[ty]](getVar[Int](1))`; Option[Int] takes its one-byte code `28`, as the JVM writes it. */
  private def lieOpt(ty: String): String = {
    val optTy = if (ty == "04") "28" else "24" + ty
    s"950100b28300${optTy}040000e30104"
  }
  private def noneV5(ty: String): String = { val optTy = if (ty == "04") "28" else "24" + ty; s"b28300${optTy}040000" }
  private val TV = "670154"; private val IVv = "67024956"; private val RV = "670152"; private val UV = "670155"

  val Opt5: Seq[Row] = {
    val o = OpOpt5
    val rv = "getVar[Int](1)"
    val v5 = " Tree v0 under activated 2 (the same at activated 3); var 1 = Int 0, so the receiver is `Some(0)`."
    def map(ty: String) = s"9ae4dc2407${lieOpt(ty)}01d9010104040e0402"
    def filter(ty: String) = s"9ae4dc2408${lieOpt(ty)}01d901010401010402"
    def parse(ty: String) = s"950101010193e4dc2407${noneV5(ty)}01d90101${ty}04020402"
    val consumers = Seq(
      consumer(o, "A2", "map", "plus", Seq("T"), map(TV), v("8", 99), optRecv = rv),
      consumer(o, "A4", "map", "plus", Seq("IV"), map(IVv), t(KeyEx), optRecv = rv),
      consumer(o, "A7", "map", "plus", Seq("R"), map(RV), t(KeyEx), optRecv = rv),
      consumer(o, "A6", "map", "plus", Seq("Int"), map("04"), v("8", 99), optRecv = rv, controlOf = "A2/A4/A7"),
      consumer(o, "F2", "filter", "plus", Seq("T"), filter(TV), v("1", 99), optRecv = rv),
      consumer(o, "F4", "filter", "plus", Seq("IV"), filter(IVv), t(KeyEx), optRecv = rv),
      consumer(o, "F6", "filter", "plus", Seq("Int"), filter("04"), v("1", 99), optRecv = rv, controlOf = "F2/F4")
    ).map(r => if (r.id == "A7") r.copy(script = r.script.replace("SEPARATING:", "`R` is `map`'s OTHER variable, " +
        "its result, so a table that swaps or reuses the two names answers here. SEPARATING:")) else r)
    val none = "Coll[Option[%s]]()(0)"
    (consumers ++ Seq(
      Row(o, "N1", s"`if (true) true else ${none.format("T")}.map((x: T) => 1).get == 1` → true at cost 20: `T` " +
        "stands where `map` declares `T`, so they unify, `R := Int`, the call is `Option[Int]`: BUILT. SEPARATING: " +
        "a table that names `Option.map`'s item otherwise leaves the call unspecialised and refuses the tree at parse.",
        parse(TV), accept = true, "parse-option-t-map-accept"),
      Row(o, "N2", s"`if (true) true else ${none.format("IV")}.map((x: IV) => 1).get == 1` — REFUSED AT PARSE " +
        s"(`ConstraintFailed`, `SameType2`): `IV` is not `map`'s `T`; $NameRule, so the call stays `Option[R]` and " +
        "`.get == 1` compares an `R` with an `Int`. SEPARATING: a table that names `Option.map`'s item `IV` builds it.",
        parse(IVv), accept = false, "parse-option-iv-map-errored"),
      Row(o, "N3", s"`if (true) true else ${none.format("U")}.map((x: U) => 1).get == 1` — REFUSED AT PARSE, as N2: " +
        "a name in no table. SEPARATING: an implementation that unifies two type variables whatever their names " +
        "builds it.",
        parse(UV), accept = false, "parse-option-u-map-errored"),
      Row(o, "N4", s"`if (true) true else ${none.format("Coll[T]")}.map((x: Coll[T]) => 1).get == 1` → true at cost " +
        "20: `T := Coll[T]` from the receiver and again from the lambda, consistently, `R := Int`: BUILT. The v6 " +
        "family's X1 is the same call over `Global.none[Coll[T]]`, whose receiver the reader substitutes twice, and " +
        "it is refused.",
        parse("0c" + TV), accept = true, "parse-option-coll-t-map-accept"),
      Row(o, "N5", s"`if (true) true else ${none.format("Int")}.map((x: Int) => 1).get == 1` → true at cost 20. " +
        "CONTROL for N1-N4.",
        parse("04"), accept = true, "parse-option-int-map-control-accept"))).map(r =>
      if (r.id.startsWith("N")) r.copy(script = r.script + " Tree v0 under activated 2 (the same at activated 3).")
      else r.copy(script = r.script + v5))
  }

  val Rows: Seq[Row] = Coll6 ++ Opt6 ++ Coll5 ++ Opt5

  def extract(): Map[String, Json] =
    Ops.map { op =>
      val entries = Rows.filter(_.op == op).zipWithIndex.map { case (r, i) =>
        val name = s"${r.slug}#$i"
        if (r.accept) SpecExtract.authoredEntryV(op, s"[${r.id}] ${r.script}", r.treeHex, name, IntInput,
          r.activated, ergoTree = r.ergoTree)
        else SpecExtract.authoredRejectEntryV(op, s"[${r.id}] ${r.script}", r.treeHex, name, IntInput,
          r.activated, ergoTree = r.ergoTree)
      }
      op -> SpecExtract.authoredEnvelope(op, entries, Source).mapObject(_.add("blessed_by", Json.fromString(Stamp)))
    }.toMap

  def writeVectors(outDir: java.nio.file.Path): Unit =
    SpecExtract.writeStaging("AuthoredEvalMethodTypeVariableNames", extract(), outDir)
}
