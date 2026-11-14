package santa

// Authored eval vectors for sigma-rust's santa-probe-runtime-type (fork PR 92, from its vector backlog, 2026-11-14).
// Measured first in santa.spike.RuntimeTypeProbe (local): every selected row as read, value against refusal.
// Wire-tier companion: AuthoredWireRuntimeTypeSites (whole trees and boxes, where reject against degrade is the point).
//
// THE RULE, read in 6.0.7: `Evaluation.stypeToRType` throws `RuntimeException: Don't know how to convert SType … to
// RType` for a type variable, for a function type with other than one argument or with type parameters, and for any
// type that contains one. Every place that resolves a runtime type from a static type fails for such a type,
// whatever it was about to do:
//  * at EVALUATION — the `ConcreteCollection` node (`Coll[τ]()`), `GetVar`, `ExtractRegisterAs`, `Box.getReg`'s
//    descriptor, `Context.getVarFromInput`, `Global.some`/`none`, `deserializeTo`'s range — before it reads anything:
//    an absent register or variable does not save it;
//  * at PARSE — the data reader resolves a collection's item type before it reads the items, so collection DATA of
//    such an item type (a constant, a box's register) is refused, empty or not; a box constant holding it cannot be
//    read at all, so even `box.value` (which reads no register) is refused.
// An implementation that answers an empty collection, `None` or `Some(x)` without resolving the type, or that parses
// such data, disagrees with the JVM.
//
// EVERY ROOT IS WRITABLE. sigma-rust's rows are bare expressions (`Coll[τ]()`, `getVar[τ](1)`, a box), whose value
// a lenient implementation could compute and then fail to write, landing on `errored` beside the JVM's throw: a false
// green. So each row is wrapped in a consumer of a writable type: `.size` for a collection, `.isDefined` for an
// option, `.value` for a box constant (memory "reject-arm-must-surface-over-accept").
//
// TWO FAMILIES, per site the selected type shapes plus CONTROLS (the same site with a type that has a runtime type:
// `Int`; `(Int) => Int` beside the function shapes; `Coll[Int]` / `Option[Int]` for deserializeTo's range):
//  * Evaluation.stypeToRType_sites_v6 (activated 3, tree v3), the function shapes included (v3 types);
//  * Evaluation.stypeToRType_sites (activated 2, tree v0): the rows at tree v0 — the same outcomes as at activated 3.
// Left out: rows whose outcome needs a context sigma-rust's probe lacked are measured here only as controls (getVar,
// getVarFromInput); `getVar[(Int) => Int](1)` against our Int-valued var 1 throws for a reason of its own.

import io.circe.Json
import scorex.util.encode.Base16

import sigma.serialization.SigmaSerializer

object AuthoredEvalRuntimeTypeSites {
  val V3: Byte = 3
  val V2: Byte = 2
  val Source = "santa:authored-runtime-type-sites"
  val Stamp = "jvm:sigma-state-6.0.7"
  val Op6 = "Evaluation.stypeToRType_sites_v6"
  val Op5 = "Evaluation.stypeToRType_sites"
  val Ops: Seq[String] = Seq(Op6, Op5)

  val IntInput: Json = Json.obj("kind" -> Json.fromString("Int"), "value" -> Json.fromInt(0))

  private def hx(bs: Array[Byte]): String = Base16.encode(bs).toLowerCase
  private def vlq(n: Int): String = { val w = SigmaSerializer.startWriter(); w.putUInt(n.toLong); hx(w.toBytes) }

  /** `body` is sigma-rust's expression WRAPPED in its writable consumer. */
  case class Row(op: String, site: String, shape: String, body: String, accept: Boolean, control: Boolean) {
    def v6: Boolean = op == Op6
    def treeHex: String = if (v6) "0b" + vlq(body.length / 2) + body else "00" + body
    def activated: Byte = if (v6) V3 else V2
    def ergoTree: Int = if (v6) 3 else 0
    def id: String = s"$site|$shape"
  }

  val Shapes: Map[String, String] = Map(
    "Int" -> "Int", "CollInt" -> "Coll[Int]", "OptInt" -> "Option[Int]", "F1" -> "(Int) => Int",
    "T" -> "T", "CollT" -> "Coll[T]", "CollCollT" -> "Coll[Coll[T]]", "OptT" -> "Option[T]",
    "PairTInt" -> "(T, Int)", "PairIntT" -> "(Int, T)", "TripleT" -> "(Int, Int, T)",
    "CollTripleT" -> "Coll[(Int, Int, T)]", "F2" -> "(Int, Int) => Int", "F0" -> "() => Int",
    "FP" -> "[T](Int) => Int", "FTInt" -> "(T) => Int", "FIntT" -> "(Int) => T", "FPU" -> "[U](Int) => Int",
    "CollF2" -> "Coll[(Int, Int) => Int]")

  private val Box0 = "a box constant with no registers"
  private val BoxR = "a box constant whose R4 is `Coll[τ]()`"
  /** (the expression with τ for the shape, the site's resolution point, eval or parse) */
  val Sites: Map[String, (String, String)] = Map(
    "coll-empty" -> ("`Coll[τ]().size`", "the `ConcreteCollection` node resolves its item type's runtime type at " +
      "evaluation, before there is anything to build"),
    "reg-node-absent" -> (s"`box.R4[τ].isDefined`, $Box0", "`ExtractRegisterAs` resolves τ's runtime type before it " +
      "reads the register: an ABSENT register does not save it"),
    "getreg-method-absent" -> (s"`box.getReg[τ](4).isDefined`, $Box0", "`Box.getReg`'s descriptor resolves τ before " +
      "the register is read"),
    "getvar-node" -> ("`getVar[τ](1).isDefined`", "`GetVar` resolves τ before it looks the variable up"),
    "getvarfrominput" -> ("`CONTEXT.getVarFromInput[τ](0, 1).isDefined`", "`getVarFromInput` resolves τ before it " +
      "looks the variable up"),
    "none" -> ("`Global.none[τ].isDefined`", "`none` resolves its type argument's runtime type"),
    "some-int-arg" -> ("`Global.some[τ](1).isDefined`", "`some` resolves its type argument's runtime type (its Int " +
      "argument is not checked against τ first)"),
    "deserializeto-00" -> ("`deserializeTo[τ](Coll(0.toByte))`, `.size` / `.isDefined`", "`deserializeTo` resolves " +
      "its range's runtime type"),
    "const-coll-empty" -> ("a constant `Coll[τ]()`, `.size`", "the data reader resolves a collection's item type " +
      "BEFORE it reads the items, so an EMPTY collection is refused too"),
    "const-collcoll-1-empty" -> ("a constant `Coll[Coll[τ]](Coll())`, `.size`", "the data reader resolves the item " +
      "type before reading"),
    "const-coll-1-item" -> ("a constant `Coll[τ]` of one item (the byte 00), `.size`", "the data reader resolves the " +
      "item type before reading"),
    "const-box-with-reg" -> (s"`.value` of $BoxR", "the box's registers are read when the box is, and the register's " +
      "collection data resolves its item type"),
    "box-reg-other-absent" -> (s"`R5[Int].isDefined` of $BoxR", "the box cannot be read at all, so a register the " +
      "row never asks for still refuses it"),
    "box-reg-own-type" -> (s"`R4[Coll[τ]].isDefined` of $BoxR", "the box cannot be read"),
    "box-reg-other-absent-method" -> (s"`getReg[Int](5).isDefined` of $BoxR", "the box cannot be read"),
    "box-reg-value" -> (s"`.value` of $BoxR, read by the ExtractAmount node", "the box cannot be read, so even the " +
      "value, which reads no register, is refused"))

  val Rows: Seq[Row] = Seq(
    Row(Op5, "coll-empty", "Int", "b1830004", accept = true, control = true),
    Row(Op6, "coll-empty", "Int", "b1830004", accept = true, control = true),
    Row(Op5, "getvar-node", "Int", "e6e30104", accept = true, control = true),
    Row(Op6, "getvar-node", "Int", "e6e30104", accept = true, control = true),
    Row(Op5, "reg-node-absent", "Int", "e6c663c0843d0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f817980000000000000000000000000000000000000000000000000000000000000000000000000404", accept = true, control = true),
    Row(Op6, "reg-node-absent", "Int", "e6c663c0843d0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f817980000000000000000000000000000000000000000000000000000000000000000000000000404", accept = true, control = true),
    Row(Op6, "getreg-method-absent", "Int", "e6dc631363c0843d0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f8179800000000000000000000000000000000000000000000000000000000000000000000000001040804", accept = true, control = true),
    Row(Op6, "getvarfrominput", "Int", "e6dc650cfe020300020104", accept = true, control = true),
    Row(Op6, "none", "Int", "e6db6a0add04", accept = true, control = true),
    Row(Op6, "some-int-arg", "Int", "e6dc6a09dd01040204", accept = true, control = true),
    Row(Op5, "const-coll-empty", "Int", "b10c0400", accept = true, control = true),
    Row(Op6, "const-coll-empty", "Int", "b10c0400", accept = true, control = true),
    Row(Op5, "const-collcoll-1-empty", "Int", "b10c0c040100", accept = true, control = true),
    Row(Op6, "const-collcoll-1-empty", "Int", "b10c0c040100", accept = true, control = true),
    Row(Op5, "const-coll-1-item", "Int", "b10c040100", accept = true, control = true),
    Row(Op6, "const-coll-1-item", "Int", "b10c040100", accept = true, control = true),
    Row(Op5, "const-box-with-reg", "Int", "c163c0843d0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f817980000010c0400000000000000000000000000000000000000000000000000000000000000000000", accept = true, control = true),
    Row(Op6, "const-box-with-reg", "Int", "c163c0843d0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f817980000010c0400000000000000000000000000000000000000000000000000000000000000000000", accept = true, control = true),
    Row(Op5, "box-reg-other-absent", "Int", "e6c663c0843d0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f817980000010c04000000000000000000000000000000000000000000000000000000000000000000000504", accept = true, control = true),
    Row(Op6, "box-reg-other-absent", "Int", "e6c663c0843d0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f817980000010c04000000000000000000000000000000000000000000000000000000000000000000000504", accept = true, control = true),
    Row(Op5, "box-reg-own-type", "Int", "e6c663c0843d0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f817980000010c0400000000000000000000000000000000000000000000000000000000000000000000040c04", accept = true, control = true),
    Row(Op6, "box-reg-own-type", "Int", "e6c663c0843d0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f817980000010c0400000000000000000000000000000000000000000000000000000000000000000000040c04", accept = true, control = true),
    Row(Op6, "box-reg-other-absent-method", "Int", "e6dc631363c0843d0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f817980000010c040000000000000000000000000000000000000000000000000000000000000000000001040a04", accept = true, control = true),
    Row(Op5, "box-reg-value", "Int", "c163c0843d0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f817980000010c0400000000000000000000000000000000000000000000000000000000000000000000", accept = true, control = true),
    Row(Op6, "box-reg-value", "Int", "c163c0843d0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f817980000010c0400000000000000000000000000000000000000000000000000000000000000000000", accept = true, control = true),
    Row(Op6, "deserializeto-00", "CollInt", "b1dc6a04dd010e010010", accept = true, control = true),
    Row(Op6, "deserializeto-00", "OptInt", "e6dc6a04dd010e010028", accept = true, control = true),
    Row(Op6, "coll-empty", "F1", "b183007001040400", accept = true, control = true),
    Row(Op6, "reg-node-absent", "F1", "e6c663c0843d0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798000000000000000000000000000000000000000000000000000000000000000000000000047001040400", accept = true, control = true),
    Row(Op6, "getreg-method-absent", "F1", "e6dc631363c0843d0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f817980000000000000000000000000000000000000000000000000000000000000000000000000104087001040400", accept = true, control = true),
    Row(Op6, "getvarfrominput", "F1", "e6dc650cfe02030002017001040400", accept = true, control = true),
    Row(Op6, "none", "F1", "e6db6a0add7001040400", accept = true, control = true),
    Row(Op6, "some-int-arg", "F1", "e6dc6a09dd0104027001040400", accept = true, control = true),
    Row(Op6, "const-coll-empty", "F1", "b10c700104040000", accept = true, control = true),
    Row(Op6, "const-collcoll-1-empty", "F1", "b10c0c70010404000100", accept = true, control = true),
    Row(Op6, "const-box-with-reg", "F1", "c163c0843d0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f817980000010c700104040000000000000000000000000000000000000000000000000000000000000000000000", accept = true, control = true),
    Row(Op6, "box-reg-other-absent", "F1", "e6c663c0843d0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f817980000010c7001040400000000000000000000000000000000000000000000000000000000000000000000000504", accept = true, control = true),
    Row(Op6, "box-reg-own-type", "F1", "e6c663c0843d0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f817980000010c700104040000000000000000000000000000000000000000000000000000000000000000000000040c7001040400", accept = true, control = true),
    Row(Op6, "box-reg-other-absent-method", "F1", "e6dc631363c0843d0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f817980000010c70010404000000000000000000000000000000000000000000000000000000000000000000000001040a04", accept = true, control = true),
    Row(Op6, "box-reg-value", "F1", "c163c0843d0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f817980000010c700104040000000000000000000000000000000000000000000000000000000000000000000000", accept = true, control = true),
    Row(Op5, "coll-empty", "T", "b18300670154", accept = false, control = false),
    Row(Op6, "coll-empty", "T", "b18300670154", accept = false, control = false),
    Row(Op5, "getvar-node", "T", "e6e301670154", accept = false, control = false),
    Row(Op6, "getvar-node", "T", "e6e301670154", accept = false, control = false),
    Row(Op5, "reg-node-absent", "T", "e6c663c0843d0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f8179800000000000000000000000000000000000000000000000000000000000000000000000004670154", accept = false, control = false),
    Row(Op6, "reg-node-absent", "T", "e6c663c0843d0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f8179800000000000000000000000000000000000000000000000000000000000000000000000004670154", accept = false, control = false),
    Row(Op6, "getreg-method-absent", "T", "e6dc631363c0843d0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798000000000000000000000000000000000000000000000000000000000000000000000000010408670154", accept = false, control = false),
    Row(Op6, "getvarfrominput", "T", "e6dc650cfe0203000201670154", accept = false, control = false),
    Row(Op6, "none", "T", "e6db6a0add670154", accept = false, control = false),
    Row(Op5, "const-coll-empty", "T", "b10c67015400", accept = false, control = false),
    Row(Op6, "const-coll-empty", "T", "b10c67015400", accept = false, control = false),
    Row(Op5, "const-collcoll-1-empty", "T", "b10c0c6701540100", accept = false, control = false),
    Row(Op6, "const-collcoll-1-empty", "T", "b10c0c6701540100", accept = false, control = false),
    Row(Op5, "const-box-with-reg", "T", "c163c0843d0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f817980000010c67015400000000000000000000000000000000000000000000000000000000000000000000", accept = false, control = false),
    Row(Op6, "const-box-with-reg", "T", "c163c0843d0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f817980000010c67015400000000000000000000000000000000000000000000000000000000000000000000", accept = false, control = false),
    Row(Op5, "box-reg-other-absent", "T", "e6c663c0843d0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f817980000010c670154000000000000000000000000000000000000000000000000000000000000000000000504", accept = false, control = false),
    Row(Op6, "box-reg-other-absent", "T", "e6c663c0843d0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f817980000010c670154000000000000000000000000000000000000000000000000000000000000000000000504", accept = false, control = false),
    Row(Op5, "box-reg-own-type", "T", "e6c663c0843d0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f817980000010c67015400000000000000000000000000000000000000000000000000000000000000000000040c670154", accept = false, control = false),
    Row(Op6, "box-reg-own-type", "T", "e6c663c0843d0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f817980000010c67015400000000000000000000000000000000000000000000000000000000000000000000040c670154", accept = false, control = false),
    Row(Op6, "box-reg-other-absent-method", "T", "e6dc631363c0843d0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f817980000010c6701540000000000000000000000000000000000000000000000000000000000000000000001040a04", accept = false, control = false),
    Row(Op5, "box-reg-value", "T", "c163c0843d0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f817980000010c67015400000000000000000000000000000000000000000000000000000000000000000000", accept = false, control = false),
    Row(Op6, "box-reg-value", "T", "c163c0843d0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f817980000010c67015400000000000000000000000000000000000000000000000000000000000000000000", accept = false, control = false),
    Row(Op5, "coll-empty", "CollT", "b183000c670154", accept = false, control = false),
    Row(Op6, "coll-empty", "CollT", "b183000c670154", accept = false, control = false),
    Row(Op5, "reg-node-absent", "CollT", "e6c663c0843d0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798000000000000000000000000000000000000000000000000000000000000000000000000040c670154", accept = false, control = false),
    Row(Op6, "reg-node-absent", "CollT", "e6c663c0843d0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798000000000000000000000000000000000000000000000000000000000000000000000000040c670154", accept = false, control = false),
    Row(Op6, "getreg-method-absent", "CollT", "e6dc631363c0843d0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f817980000000000000000000000000000000000000000000000000000000000000000000000000104080c670154", accept = false, control = false),
    Row(Op6, "getvarfrominput", "CollT", "e6dc650cfe02030002010c670154", accept = false, control = false),
    Row(Op6, "none", "CollT", "e6db6a0add0c670154", accept = false, control = false),
    Row(Op6, "some-int-arg", "CollT", "e6dc6a09dd0104020c670154", accept = false, control = false),
    Row(Op6, "deserializeto-00", "CollT", "b1dc6a04dd010e01000c670154", accept = false, control = false),
    Row(Op5, "const-coll-empty", "CollT", "b10c0c67015400", accept = false, control = false),
    Row(Op6, "const-coll-empty", "CollT", "b10c0c67015400", accept = false, control = false),
    Row(Op5, "const-collcoll-1-empty", "CollT", "b10c0c0c6701540100", accept = false, control = false),
    Row(Op6, "const-collcoll-1-empty", "CollT", "b10c0c0c6701540100", accept = false, control = false),
    Row(Op5, "const-coll-1-item", "CollT", "b10c0c6701540100", accept = false, control = false),
    Row(Op6, "const-coll-1-item", "CollT", "b10c0c6701540100", accept = false, control = false),
    Row(Op5, "const-box-with-reg", "CollT", "c163c0843d0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f817980000010c0c67015400000000000000000000000000000000000000000000000000000000000000000000", accept = false, control = false),
    Row(Op6, "const-box-with-reg", "CollT", "c163c0843d0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f817980000010c0c67015400000000000000000000000000000000000000000000000000000000000000000000", accept = false, control = false),
    Row(Op5, "box-reg-other-absent", "CollT", "e6c663c0843d0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f817980000010c0c670154000000000000000000000000000000000000000000000000000000000000000000000504", accept = false, control = false),
    Row(Op6, "box-reg-other-absent", "CollT", "e6c663c0843d0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f817980000010c0c670154000000000000000000000000000000000000000000000000000000000000000000000504", accept = false, control = false),
    Row(Op5, "box-reg-own-type", "CollT", "e6c663c0843d0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f817980000010c0c67015400000000000000000000000000000000000000000000000000000000000000000000040c0c670154", accept = false, control = false),
    Row(Op6, "box-reg-own-type", "CollT", "e6c663c0843d0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f817980000010c0c67015400000000000000000000000000000000000000000000000000000000000000000000040c0c670154", accept = false, control = false),
    Row(Op6, "box-reg-other-absent-method", "CollT", "e6dc631363c0843d0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f817980000010c0c6701540000000000000000000000000000000000000000000000000000000000000000000001040a04", accept = false, control = false),
    Row(Op5, "box-reg-value", "CollT", "c163c0843d0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f817980000010c0c67015400000000000000000000000000000000000000000000000000000000000000000000", accept = false, control = false),
    Row(Op6, "box-reg-value", "CollT", "c163c0843d0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f817980000010c0c67015400000000000000000000000000000000000000000000000000000000000000000000", accept = false, control = false),
    Row(Op5, "coll-empty", "CollCollT", "b183000c0c670154", accept = false, control = false),
    Row(Op6, "coll-empty", "CollCollT", "b183000c0c670154", accept = false, control = false),
    Row(Op6, "getvar-node", "CollCollT", "e6e3010c0c670154", accept = false, control = false),
    Row(Op5, "reg-node-absent", "CollCollT", "e6c663c0843d0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798000000000000000000000000000000000000000000000000000000000000000000000000040c0c670154", accept = false, control = false),
    Row(Op6, "reg-node-absent", "CollCollT", "e6c663c0843d0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798000000000000000000000000000000000000000000000000000000000000000000000000040c0c670154", accept = false, control = false),
    Row(Op6, "getreg-method-absent", "CollCollT", "e6dc631363c0843d0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f817980000000000000000000000000000000000000000000000000000000000000000000000000104080c0c670154", accept = false, control = false),
    Row(Op6, "getvarfrominput", "CollCollT", "e6dc650cfe02030002010c0c670154", accept = false, control = false),
    Row(Op6, "none", "CollCollT", "e6db6a0add0c0c670154", accept = false, control = false),
    Row(Op6, "some-int-arg", "CollCollT", "e6dc6a09dd0104020c0c670154", accept = false, control = false),
    Row(Op6, "deserializeto-00", "CollCollT", "b1dc6a04dd010e01000c0c670154", accept = false, control = false),
    Row(Op5, "const-coll-empty", "CollCollT", "b10c0c0c67015400", accept = false, control = false),
    Row(Op6, "const-coll-empty", "CollCollT", "b10c0c0c67015400", accept = false, control = false),
    Row(Op5, "const-collcoll-1-empty", "CollCollT", "b10c0c0c0c6701540100", accept = false, control = false),
    Row(Op6, "const-collcoll-1-empty", "CollCollT", "b10c0c0c0c6701540100", accept = false, control = false),
    Row(Op5, "const-coll-1-item", "CollCollT", "b10c0c0c6701540100", accept = false, control = false),
    Row(Op6, "const-coll-1-item", "CollCollT", "b10c0c0c6701540100", accept = false, control = false),
    Row(Op5, "const-box-with-reg", "CollCollT", "c163c0843d0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f817980000010c0c0c67015400000000000000000000000000000000000000000000000000000000000000000000", accept = false, control = false),
    Row(Op6, "const-box-with-reg", "CollCollT", "c163c0843d0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f817980000010c0c0c67015400000000000000000000000000000000000000000000000000000000000000000000", accept = false, control = false),
    Row(Op5, "box-reg-other-absent", "CollCollT", "e6c663c0843d0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f817980000010c0c0c670154000000000000000000000000000000000000000000000000000000000000000000000504", accept = false, control = false),
    Row(Op6, "box-reg-other-absent", "CollCollT", "e6c663c0843d0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f817980000010c0c0c670154000000000000000000000000000000000000000000000000000000000000000000000504", accept = false, control = false),
    Row(Op5, "box-reg-own-type", "CollCollT", "e6c663c0843d0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f817980000010c0c0c67015400000000000000000000000000000000000000000000000000000000000000000000040c0c0c670154", accept = false, control = false),
    Row(Op6, "box-reg-own-type", "CollCollT", "e6c663c0843d0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f817980000010c0c0c67015400000000000000000000000000000000000000000000000000000000000000000000040c0c0c670154", accept = false, control = false),
    Row(Op6, "box-reg-other-absent-method", "CollCollT", "e6dc631363c0843d0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f817980000010c0c0c6701540000000000000000000000000000000000000000000000000000000000000000000001040a04", accept = false, control = false),
    Row(Op5, "box-reg-value", "CollCollT", "c163c0843d0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f817980000010c0c0c67015400000000000000000000000000000000000000000000000000000000000000000000", accept = false, control = false),
    Row(Op6, "box-reg-value", "CollCollT", "c163c0843d0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f817980000010c0c0c67015400000000000000000000000000000000000000000000000000000000000000000000", accept = false, control = false),
    Row(Op5, "coll-empty", "OptT", "b1830024670154", accept = false, control = false),
    Row(Op6, "coll-empty", "OptT", "b1830024670154", accept = false, control = false),
    Row(Op5, "getvar-node", "OptT", "e6e30124670154", accept = false, control = false),
    Row(Op5, "reg-node-absent", "OptT", "e6c663c0843d0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f817980000000000000000000000000000000000000000000000000000000000000000000000000424670154", accept = false, control = false),
    Row(Op6, "reg-node-absent", "OptT", "e6c663c0843d0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f817980000000000000000000000000000000000000000000000000000000000000000000000000424670154", accept = false, control = false),
    Row(Op6, "getreg-method-absent", "OptT", "e6dc631363c0843d0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f8179800000000000000000000000000000000000000000000000000000000000000000000000001040824670154", accept = false, control = false),
    Row(Op6, "getvarfrominput", "OptT", "e6dc650cfe020300020124670154", accept = false, control = false),
    Row(Op6, "none", "OptT", "e6db6a0add24670154", accept = false, control = false),
    Row(Op6, "some-int-arg", "OptT", "e6dc6a09dd01040224670154", accept = false, control = false),
    Row(Op6, "deserializeto-00", "OptT", "e6dc6a04dd010e010024670154", accept = false, control = false),
    Row(Op5, "const-coll-empty", "OptT", "b10c2467015400", accept = false, control = false),
    Row(Op6, "const-coll-empty", "OptT", "b10c2467015400", accept = false, control = false),
    Row(Op5, "const-collcoll-1-empty", "OptT", "b10c0c246701540100", accept = false, control = false),
    Row(Op6, "const-collcoll-1-empty", "OptT", "b10c0c246701540100", accept = false, control = false),
    Row(Op6, "const-coll-1-item", "OptT", "b10c246701540100", accept = false, control = false),
    Row(Op5, "coll-empty", "PairTInt", "b183003c67015404", accept = false, control = false),
    Row(Op6, "coll-empty", "PairTInt", "b183003c67015404", accept = false, control = false),
    Row(Op5, "reg-node-absent", "PairTInt", "e6c663c0843d0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798000000000000000000000000000000000000000000000000000000000000000000000000043c67015404", accept = false, control = false),
    Row(Op6, "reg-node-absent", "PairTInt", "e6c663c0843d0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798000000000000000000000000000000000000000000000000000000000000000000000000043c67015404", accept = false, control = false),
    Row(Op6, "getreg-method-absent", "PairTInt", "e6dc631363c0843d0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f817980000000000000000000000000000000000000000000000000000000000000000000000000104083c67015404", accept = false, control = false),
    Row(Op6, "getvarfrominput", "PairTInt", "e6dc650cfe02030002013c67015404", accept = false, control = false),
    Row(Op6, "none", "PairTInt", "e6db6a0add3c67015404", accept = false, control = false),
    Row(Op6, "some-int-arg", "PairTInt", "e6dc6a09dd0104023c67015404", accept = false, control = false),
    Row(Op5, "const-coll-empty", "PairTInt", "b10c3c6701540400", accept = false, control = false),
    Row(Op6, "const-coll-empty", "PairTInt", "b10c3c6701540400", accept = false, control = false),
    Row(Op5, "const-collcoll-1-empty", "PairTInt", "b10c0c3c670154040100", accept = false, control = false),
    Row(Op6, "const-collcoll-1-empty", "PairTInt", "b10c0c3c670154040100", accept = false, control = false),
    Row(Op5, "const-box-with-reg", "PairTInt", "c163c0843d0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f817980000010c3c6701540400000000000000000000000000000000000000000000000000000000000000000000", accept = false, control = false),
    Row(Op6, "const-box-with-reg", "PairTInt", "c163c0843d0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f817980000010c3c6701540400000000000000000000000000000000000000000000000000000000000000000000", accept = false, control = false),
    Row(Op5, "box-reg-other-absent", "PairTInt", "e6c663c0843d0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f817980000010c3c67015404000000000000000000000000000000000000000000000000000000000000000000000504", accept = false, control = false),
    Row(Op6, "box-reg-other-absent", "PairTInt", "e6c663c0843d0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f817980000010c3c67015404000000000000000000000000000000000000000000000000000000000000000000000504", accept = false, control = false),
    Row(Op5, "box-reg-own-type", "PairTInt", "e6c663c0843d0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f817980000010c3c6701540400000000000000000000000000000000000000000000000000000000000000000000040c3c67015404", accept = false, control = false),
    Row(Op6, "box-reg-own-type", "PairTInt", "e6c663c0843d0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f817980000010c3c6701540400000000000000000000000000000000000000000000000000000000000000000000040c3c67015404", accept = false, control = false),
    Row(Op6, "box-reg-other-absent-method", "PairTInt", "e6dc631363c0843d0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f817980000010c3c670154040000000000000000000000000000000000000000000000000000000000000000000001040a04", accept = false, control = false),
    Row(Op5, "box-reg-value", "PairTInt", "c163c0843d0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f817980000010c3c6701540400000000000000000000000000000000000000000000000000000000000000000000", accept = false, control = false),
    Row(Op6, "box-reg-value", "PairTInt", "c163c0843d0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f817980000010c3c6701540400000000000000000000000000000000000000000000000000000000000000000000", accept = false, control = false),
    Row(Op5, "coll-empty", "PairIntT", "b1830040670154", accept = false, control = false),
    Row(Op6, "coll-empty", "PairIntT", "b1830040670154", accept = false, control = false),
    Row(Op5, "reg-node-absent", "PairIntT", "e6c663c0843d0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f817980000000000000000000000000000000000000000000000000000000000000000000000000440670154", accept = false, control = false),
    Row(Op6, "reg-node-absent", "PairIntT", "e6c663c0843d0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f817980000000000000000000000000000000000000000000000000000000000000000000000000440670154", accept = false, control = false),
    Row(Op6, "getreg-method-absent", "PairIntT", "e6dc631363c0843d0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f8179800000000000000000000000000000000000000000000000000000000000000000000000001040840670154", accept = false, control = false),
    Row(Op6, "getvarfrominput", "PairIntT", "e6dc650cfe020300020140670154", accept = false, control = false),
    Row(Op6, "none", "PairIntT", "e6db6a0add40670154", accept = false, control = false),
    Row(Op6, "some-int-arg", "PairIntT", "e6dc6a09dd01040240670154", accept = false, control = false),
    Row(Op5, "const-coll-empty", "PairIntT", "b10c4067015400", accept = false, control = false),
    Row(Op6, "const-coll-empty", "PairIntT", "b10c4067015400", accept = false, control = false),
    Row(Op5, "const-collcoll-1-empty", "PairIntT", "b10c0c406701540100", accept = false, control = false),
    Row(Op6, "const-collcoll-1-empty", "PairIntT", "b10c0c406701540100", accept = false, control = false),
    Row(Op5, "const-box-with-reg", "PairIntT", "c163c0843d0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f817980000010c4067015400000000000000000000000000000000000000000000000000000000000000000000", accept = false, control = false),
    Row(Op6, "const-box-with-reg", "PairIntT", "c163c0843d0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f817980000010c4067015400000000000000000000000000000000000000000000000000000000000000000000", accept = false, control = false),
    Row(Op5, "box-reg-other-absent", "PairIntT", "e6c663c0843d0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f817980000010c40670154000000000000000000000000000000000000000000000000000000000000000000000504", accept = false, control = false),
    Row(Op6, "box-reg-other-absent", "PairIntT", "e6c663c0843d0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f817980000010c40670154000000000000000000000000000000000000000000000000000000000000000000000504", accept = false, control = false),
    Row(Op5, "box-reg-own-type", "PairIntT", "e6c663c0843d0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f817980000010c4067015400000000000000000000000000000000000000000000000000000000000000000000040c40670154", accept = false, control = false),
    Row(Op6, "box-reg-own-type", "PairIntT", "e6c663c0843d0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f817980000010c4067015400000000000000000000000000000000000000000000000000000000000000000000040c40670154", accept = false, control = false),
    Row(Op6, "box-reg-other-absent-method", "PairIntT", "e6dc631363c0843d0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f817980000010c406701540000000000000000000000000000000000000000000000000000000000000000000001040a04", accept = false, control = false),
    Row(Op5, "box-reg-value", "PairIntT", "c163c0843d0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f817980000010c4067015400000000000000000000000000000000000000000000000000000000000000000000", accept = false, control = false),
    Row(Op6, "box-reg-value", "PairIntT", "c163c0843d0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f817980000010c4067015400000000000000000000000000000000000000000000000000000000000000000000", accept = false, control = false),
    Row(Op5, "coll-empty", "TripleT", "b18300480404670154", accept = false, control = false),
    Row(Op6, "coll-empty", "TripleT", "b18300480404670154", accept = false, control = false),
    Row(Op5, "reg-node-absent", "TripleT", "e6c663c0843d0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f8179800000000000000000000000000000000000000000000000000000000000000000000000004480404670154", accept = false, control = false),
    Row(Op6, "reg-node-absent", "TripleT", "e6c663c0843d0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f8179800000000000000000000000000000000000000000000000000000000000000000000000004480404670154", accept = false, control = false),
    Row(Op6, "getreg-method-absent", "TripleT", "e6dc631363c0843d0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798000000000000000000000000000000000000000000000000000000000000000000000000010408480404670154", accept = false, control = false),
    Row(Op6, "getvarfrominput", "TripleT", "e6dc650cfe0203000201480404670154", accept = false, control = false),
    Row(Op6, "none", "TripleT", "e6db6a0add480404670154", accept = false, control = false),
    Row(Op6, "some-int-arg", "TripleT", "e6dc6a09dd010402480404670154", accept = false, control = false),
    Row(Op5, "const-collcoll-1-empty", "TripleT", "b10c0c4804046701540100", accept = false, control = false),
    Row(Op6, "const-collcoll-1-empty", "TripleT", "b10c0c4804046701540100", accept = false, control = false),
    Row(Op5, "box-reg-other-absent", "TripleT", "e6c663c0843d0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f817980000010c480404670154000000000000000000000000000000000000000000000000000000000000000000000504", accept = false, control = false),
    Row(Op6, "box-reg-other-absent", "TripleT", "e6c663c0843d0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f817980000010c480404670154000000000000000000000000000000000000000000000000000000000000000000000504", accept = false, control = false),
    Row(Op5, "box-reg-own-type", "TripleT", "e6c663c0843d0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f817980000010c48040467015400000000000000000000000000000000000000000000000000000000000000000000040c480404670154", accept = false, control = false),
    Row(Op6, "box-reg-own-type", "TripleT", "e6c663c0843d0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f817980000010c48040467015400000000000000000000000000000000000000000000000000000000000000000000040c480404670154", accept = false, control = false),
    Row(Op6, "box-reg-other-absent-method", "TripleT", "e6dc631363c0843d0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f817980000010c4804046701540000000000000000000000000000000000000000000000000000000000000000000001040a04", accept = false, control = false),
    Row(Op5, "coll-empty", "CollTripleT", "b183000c480404670154", accept = false, control = false),
    Row(Op6, "coll-empty", "CollTripleT", "b183000c480404670154", accept = false, control = false),
    Row(Op6, "getvar-node", "CollTripleT", "e6e3010c480404670154", accept = false, control = false),
    Row(Op5, "reg-node-absent", "CollTripleT", "e6c663c0843d0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798000000000000000000000000000000000000000000000000000000000000000000000000040c480404670154", accept = false, control = false),
    Row(Op6, "reg-node-absent", "CollTripleT", "e6c663c0843d0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798000000000000000000000000000000000000000000000000000000000000000000000000040c480404670154", accept = false, control = false),
    Row(Op6, "getreg-method-absent", "CollTripleT", "e6dc631363c0843d0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f817980000000000000000000000000000000000000000000000000000000000000000000000000104080c480404670154", accept = false, control = false),
    Row(Op6, "getvarfrominput", "CollTripleT", "e6dc650cfe02030002010c480404670154", accept = false, control = false),
    Row(Op6, "none", "CollTripleT", "e6db6a0add0c480404670154", accept = false, control = false),
    Row(Op6, "some-int-arg", "CollTripleT", "e6dc6a09dd0104020c480404670154", accept = false, control = false),
    Row(Op6, "deserializeto-00", "CollTripleT", "b1dc6a04dd010e01000c480404670154", accept = false, control = false),
    Row(Op5, "const-coll-empty", "CollTripleT", "b10c0c48040467015400", accept = false, control = false),
    Row(Op6, "const-coll-empty", "CollTripleT", "b10c0c48040467015400", accept = false, control = false),
    Row(Op5, "const-collcoll-1-empty", "CollTripleT", "b10c0c0c4804046701540100", accept = false, control = false),
    Row(Op6, "const-collcoll-1-empty", "CollTripleT", "b10c0c0c4804046701540100", accept = false, control = false),
    Row(Op5, "const-coll-1-item", "CollTripleT", "b10c0c4804046701540100", accept = false, control = false),
    Row(Op6, "const-coll-1-item", "CollTripleT", "b10c0c4804046701540100", accept = false, control = false),
    Row(Op5, "const-box-with-reg", "CollTripleT", "c163c0843d0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f817980000010c0c48040467015400000000000000000000000000000000000000000000000000000000000000000000", accept = false, control = false),
    Row(Op6, "const-box-with-reg", "CollTripleT", "c163c0843d0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f817980000010c0c48040467015400000000000000000000000000000000000000000000000000000000000000000000", accept = false, control = false),
    Row(Op5, "box-reg-other-absent", "CollTripleT", "e6c663c0843d0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f817980000010c0c480404670154000000000000000000000000000000000000000000000000000000000000000000000504", accept = false, control = false),
    Row(Op6, "box-reg-other-absent", "CollTripleT", "e6c663c0843d0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f817980000010c0c480404670154000000000000000000000000000000000000000000000000000000000000000000000504", accept = false, control = false),
    Row(Op5, "box-reg-own-type", "CollTripleT", "e6c663c0843d0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f817980000010c0c48040467015400000000000000000000000000000000000000000000000000000000000000000000040c0c480404670154", accept = false, control = false),
    Row(Op6, "box-reg-own-type", "CollTripleT", "e6c663c0843d0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f817980000010c0c48040467015400000000000000000000000000000000000000000000000000000000000000000000040c0c480404670154", accept = false, control = false),
    Row(Op6, "box-reg-other-absent-method", "CollTripleT", "e6dc631363c0843d0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f817980000010c0c4804046701540000000000000000000000000000000000000000000000000000000000000000000001040a04", accept = false, control = false),
    Row(Op5, "box-reg-value", "CollTripleT", "c163c0843d0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f817980000010c0c48040467015400000000000000000000000000000000000000000000000000000000000000000000", accept = false, control = false),
    Row(Op6, "box-reg-value", "CollTripleT", "c163c0843d0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f817980000010c0c48040467015400000000000000000000000000000000000000000000000000000000000000000000", accept = false, control = false),
    Row(Op6, "coll-empty", "F2", "b18300700204040400", accept = false, control = false),
    Row(Op6, "reg-node-absent", "F2", "e6c663c0843d0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f8179800000000000000000000000000000000000000000000000000000000000000000000000004700204040400", accept = false, control = false),
    Row(Op6, "getreg-method-absent", "F2", "e6dc631363c0843d0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798000000000000000000000000000000000000000000000000000000000000000000000000010408700204040400", accept = false, control = false),
    Row(Op6, "getvarfrominput", "F2", "e6dc650cfe0203000201700204040400", accept = false, control = false),
    Row(Op6, "none", "F2", "e6db6a0add700204040400", accept = false, control = false),
    Row(Op6, "some-int-arg", "F2", "e6dc6a09dd010402700204040400", accept = false, control = false),
    Row(Op6, "const-coll-empty", "F2", "b10c70020404040000", accept = false, control = false),
    Row(Op6, "const-collcoll-1-empty", "F2", "b10c0c7002040404000100", accept = false, control = false),
    Row(Op6, "const-box-with-reg", "F2", "c163c0843d0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f817980000010c70020404040000000000000000000000000000000000000000000000000000000000000000000000", accept = false, control = false),
    Row(Op6, "box-reg-other-absent", "F2", "e6c663c0843d0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f817980000010c700204040400000000000000000000000000000000000000000000000000000000000000000000000504", accept = false, control = false),
    Row(Op6, "box-reg-own-type", "F2", "e6c663c0843d0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f817980000010c70020404040000000000000000000000000000000000000000000000000000000000000000000000040c700204040400", accept = false, control = false),
    Row(Op6, "box-reg-other-absent-method", "F2", "e6dc631363c0843d0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f817980000010c7002040404000000000000000000000000000000000000000000000000000000000000000000000001040a04", accept = false, control = false),
    Row(Op6, "box-reg-value", "F2", "c163c0843d0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f817980000010c70020404040000000000000000000000000000000000000000000000000000000000000000000000", accept = false, control = false),
    Row(Op6, "coll-empty", "F0", "b1830070000400", accept = false, control = false),
    Row(Op6, "reg-node-absent", "F0", "e6c663c0843d0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f817980000000000000000000000000000000000000000000000000000000000000000000000000470000400", accept = false, control = false),
    Row(Op6, "getreg-method-absent", "F0", "e6dc631363c0843d0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f8179800000000000000000000000000000000000000000000000000000000000000000000000001040870000400", accept = false, control = false),
    Row(Op6, "getvarfrominput", "F0", "e6dc650cfe020300020170000400", accept = false, control = false),
    Row(Op6, "none", "F0", "e6db6a0add70000400", accept = false, control = false),
    Row(Op6, "some-int-arg", "F0", "e6dc6a09dd01040270000400", accept = false, control = false),
    Row(Op6, "const-coll-empty", "F0", "b10c7000040000", accept = false, control = false),
    Row(Op6, "const-collcoll-1-empty", "F0", "b10c0c700004000100", accept = false, control = false),
    Row(Op6, "const-box-with-reg", "F0", "c163c0843d0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f817980000010c7000040000000000000000000000000000000000000000000000000000000000000000000000", accept = false, control = false),
    Row(Op6, "box-reg-other-absent", "F0", "e6c663c0843d0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f817980000010c70000400000000000000000000000000000000000000000000000000000000000000000000000504", accept = false, control = false),
    Row(Op6, "box-reg-own-type", "F0", "e6c663c0843d0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f817980000010c7000040000000000000000000000000000000000000000000000000000000000000000000000040c70000400", accept = false, control = false),
    Row(Op6, "box-reg-other-absent-method", "F0", "e6dc631363c0843d0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f817980000010c700004000000000000000000000000000000000000000000000000000000000000000000000001040a04", accept = false, control = false),
    Row(Op6, "box-reg-value", "F0", "c163c0843d0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f817980000010c7000040000000000000000000000000000000000000000000000000000000000000000000000", accept = false, control = false),
    Row(Op6, "coll-empty", "FP", "b183007001040401670154", accept = false, control = false),
    Row(Op6, "reg-node-absent", "FP", "e6c663c0843d0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798000000000000000000000000000000000000000000000000000000000000000000000000047001040401670154", accept = false, control = false),
    Row(Op6, "const-coll-empty", "FP", "b10c700104040167015400", accept = false, control = false),
    Row(Op6, "const-collcoll-1-empty", "FP", "b10c0c70010404016701540100", accept = false, control = false),
    Row(Op6, "const-box-with-reg", "FP", "c163c0843d0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f817980000010c700104040167015400000000000000000000000000000000000000000000000000000000000000000000", accept = false, control = false),
    Row(Op6, "box-reg-other-absent", "FP", "e6c663c0843d0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f817980000010c7001040401670154000000000000000000000000000000000000000000000000000000000000000000000504", accept = false, control = false),
    Row(Op6, "box-reg-own-type", "FP", "e6c663c0843d0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f817980000010c700104040167015400000000000000000000000000000000000000000000000000000000000000000000040c7001040401670154", accept = false, control = false),
    Row(Op6, "box-reg-other-absent-method", "FP", "e6dc631363c0843d0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f817980000010c70010404016701540000000000000000000000000000000000000000000000000000000000000000000001040a04", accept = false, control = false),
    Row(Op6, "box-reg-value", "FP", "c163c0843d0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f817980000010c700104040167015400000000000000000000000000000000000000000000000000000000000000000000", accept = false, control = false),
    Row(Op6, "coll-empty", "FTInt", "b1830070016701540400", accept = false, control = false),
    Row(Op6, "getvar-node", "FTInt", "e6e30170016701540400", accept = false, control = false),
    Row(Op6, "reg-node-absent", "FTInt", "e6c663c0843d0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f817980000000000000000000000000000000000000000000000000000000000000000000000000470016701540400", accept = false, control = false),
    Row(Op6, "getreg-method-absent", "FTInt", "e6dc631363c0843d0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f8179800000000000000000000000000000000000000000000000000000000000000000000000001040870016701540400", accept = false, control = false),
    Row(Op6, "getvarfrominput", "FTInt", "e6dc650cfe020300020170016701540400", accept = false, control = false),
    Row(Op6, "none", "FTInt", "e6db6a0add70016701540400", accept = false, control = false),
    Row(Op6, "some-int-arg", "FTInt", "e6dc6a09dd01040270016701540400", accept = false, control = false),
    Row(Op6, "const-coll-empty", "FTInt", "b10c7001670154040000", accept = false, control = false),
    Row(Op6, "const-collcoll-1-empty", "FTInt", "b10c0c700167015404000100", accept = false, control = false),
    Row(Op6, "const-box-with-reg", "FTInt", "c163c0843d0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f817980000010c7001670154040000000000000000000000000000000000000000000000000000000000000000000000", accept = false, control = false),
    Row(Op6, "box-reg-other-absent", "FTInt", "e6c663c0843d0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f817980000010c70016701540400000000000000000000000000000000000000000000000000000000000000000000000504", accept = false, control = false),
    Row(Op6, "box-reg-own-type", "FTInt", "e6c663c0843d0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f817980000010c7001670154040000000000000000000000000000000000000000000000000000000000000000000000040c70016701540400", accept = false, control = false),
    Row(Op6, "box-reg-other-absent-method", "FTInt", "e6dc631363c0843d0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f817980000010c700167015404000000000000000000000000000000000000000000000000000000000000000000000001040a04", accept = false, control = false),
    Row(Op6, "box-reg-value", "FTInt", "c163c0843d0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f817980000010c7001670154040000000000000000000000000000000000000000000000000000000000000000000000", accept = false, control = false),
    Row(Op6, "coll-empty", "FIntT", "b1830070010467015400", accept = false, control = false),
    Row(Op6, "getvar-node", "FIntT", "e6e30170010467015400", accept = false, control = false),
    Row(Op6, "reg-node-absent", "FIntT", "e6c663c0843d0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f817980000000000000000000000000000000000000000000000000000000000000000000000000470010467015400", accept = false, control = false),
    Row(Op6, "getreg-method-absent", "FIntT", "e6dc631363c0843d0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f8179800000000000000000000000000000000000000000000000000000000000000000000000001040870010467015400", accept = false, control = false),
    Row(Op6, "getvarfrominput", "FIntT", "e6dc650cfe020300020170010467015400", accept = false, control = false),
    Row(Op6, "none", "FIntT", "e6db6a0add70010467015400", accept = false, control = false),
    Row(Op6, "some-int-arg", "FIntT", "e6dc6a09dd01040270010467015400", accept = false, control = false),
    Row(Op6, "const-coll-empty", "FIntT", "b10c7001046701540000", accept = false, control = false),
    Row(Op6, "const-collcoll-1-empty", "FIntT", "b10c0c700104670154000100", accept = false, control = false),
    Row(Op6, "const-box-with-reg", "FIntT", "c163c0843d0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f817980000010c7001046701540000000000000000000000000000000000000000000000000000000000000000000000", accept = false, control = false),
    Row(Op6, "box-reg-other-absent", "FIntT", "e6c663c0843d0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f817980000010c70010467015400000000000000000000000000000000000000000000000000000000000000000000000504", accept = false, control = false),
    Row(Op6, "box-reg-own-type", "FIntT", "e6c663c0843d0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f817980000010c7001046701540000000000000000000000000000000000000000000000000000000000000000000000040c70010467015400", accept = false, control = false),
    Row(Op6, "box-reg-other-absent-method", "FIntT", "e6dc631363c0843d0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f817980000010c700104670154000000000000000000000000000000000000000000000000000000000000000000000001040a04", accept = false, control = false),
    Row(Op6, "box-reg-value", "FIntT", "c163c0843d0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f817980000010c7001046701540000000000000000000000000000000000000000000000000000000000000000000000", accept = false, control = false),
    Row(Op6, "coll-empty", "FPU", "b183007001040401670155", accept = false, control = false),
    Row(Op6, "reg-node-absent", "FPU", "e6c663c0843d0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798000000000000000000000000000000000000000000000000000000000000000000000000047001040401670155", accept = false, control = false),
    Row(Op6, "getreg-method-absent", "FPU", "e6dc631363c0843d0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f817980000000000000000000000000000000000000000000000000000000000000000000000000104087001040401670155", accept = false, control = false),
    Row(Op6, "getvarfrominput", "FPU", "e6dc650cfe02030002017001040401670155", accept = false, control = false),
    Row(Op6, "none", "FPU", "e6db6a0add7001040401670155", accept = false, control = false),
    Row(Op6, "some-int-arg", "FPU", "e6dc6a09dd0104027001040401670155", accept = false, control = false),
    Row(Op6, "const-coll-empty", "FPU", "b10c700104040167015500", accept = false, control = false),
    Row(Op6, "const-collcoll-1-empty", "FPU", "b10c0c70010404016701550100", accept = false, control = false),
    Row(Op6, "const-box-with-reg", "FPU", "c163c0843d0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f817980000010c700104040167015500000000000000000000000000000000000000000000000000000000000000000000", accept = false, control = false),
    Row(Op6, "box-reg-other-absent", "FPU", "e6c663c0843d0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f817980000010c7001040401670155000000000000000000000000000000000000000000000000000000000000000000000504", accept = false, control = false),
    Row(Op6, "box-reg-own-type", "FPU", "e6c663c0843d0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f817980000010c700104040167015500000000000000000000000000000000000000000000000000000000000000000000040c7001040401670155", accept = false, control = false),
    Row(Op6, "box-reg-other-absent-method", "FPU", "e6dc631363c0843d0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f817980000010c70010404016701550000000000000000000000000000000000000000000000000000000000000000000001040a04", accept = false, control = false),
    Row(Op6, "box-reg-value", "FPU", "c163c0843d0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f817980000010c700104040167015500000000000000000000000000000000000000000000000000000000000000000000", accept = false, control = false),
    Row(Op6, "coll-empty", "CollF2", "b183000c700204040400", accept = false, control = false),
    Row(Op6, "getvar-node", "CollF2", "e6e3010c700204040400", accept = false, control = false),
    Row(Op6, "reg-node-absent", "CollF2", "e6c663c0843d0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798000000000000000000000000000000000000000000000000000000000000000000000000040c700204040400", accept = false, control = false),
    Row(Op6, "getreg-method-absent", "CollF2", "e6dc631363c0843d0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f817980000000000000000000000000000000000000000000000000000000000000000000000000104080c700204040400", accept = false, control = false),
    Row(Op6, "getvarfrominput", "CollF2", "e6dc650cfe02030002010c700204040400", accept = false, control = false),
    Row(Op6, "none", "CollF2", "e6db6a0add0c700204040400", accept = false, control = false),
    Row(Op6, "some-int-arg", "CollF2", "e6dc6a09dd0104020c700204040400", accept = false, control = false),
    Row(Op6, "deserializeto-00", "CollF2", "b1dc6a04dd010e01000c700204040400", accept = false, control = false),
    Row(Op6, "const-coll-empty", "CollF2", "b10c0c70020404040000", accept = false, control = false),
    Row(Op6, "const-collcoll-1-empty", "CollF2", "b10c0c0c7002040404000100", accept = false, control = false),
    Row(Op6, "const-coll-1-item", "CollF2", "b10c0c7002040404000100", accept = false, control = false),
    Row(Op6, "const-box-with-reg", "CollF2", "c163c0843d0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f817980000010c0c70020404040000000000000000000000000000000000000000000000000000000000000000000000", accept = false, control = false),
    Row(Op6, "box-reg-other-absent", "CollF2", "e6c663c0843d0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f817980000010c0c700204040400000000000000000000000000000000000000000000000000000000000000000000000504", accept = false, control = false),
    Row(Op6, "box-reg-own-type", "CollF2", "e6c663c0843d0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f817980000010c0c70020404040000000000000000000000000000000000000000000000000000000000000000000000040c0c700204040400", accept = false, control = false),
    Row(Op6, "box-reg-other-absent-method", "CollF2", "e6dc631363c0843d0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f817980000010c0c7002040404000000000000000000000000000000000000000000000000000000000000000000000001040a04", accept = false, control = false),
    Row(Op6, "box-reg-value", "CollF2", "c163c0843d0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f817980000010c0c70020404040000000000000000000000000000000000000000000000000000000000000000000000", accept = false, control = false))

  /** The script: the expression for the shape, the outcome (with the JVM's own message on a refusal), the role. */
  private def script(r: Row, outcome: Either[String, (Json, Long)]): String = {
    val (expr, why) = Sites(r.site)
    val ty = Shapes(r.shape)
    val e = expr.replace("τ", ty)
    outcome match {
      case Right((v, c)) =>
        val vs = v.hcursor.downField("value").focus.map(_.noSpaces).getOrElse(v.noSpaces)
        s"$e, τ = `$ty` → $vs at cost $c. CONTROL: `$ty` has a runtime type, so the site resolves it and goes on."
      case Left(err) =>
        val parse = r.site.startsWith("const-") || r.site.startsWith("box-reg-")
        s"$e, τ = `$ty` — ${if (parse) "REFUSED AT PARSE" else "THROWS"} (`${err.take(110)}`): $why. SEPARATING: an " +
          "implementation that " + (if (parse) "parses such data" else "answers without resolving the type") +
          " returns a value."
    }
  }

  def extract(): Map[String, Json] =
    Ops.map { op =>
      val entries = Rows.filter(_.op == op).zipWithIndex.map { case (r, i) =>
        val (_, outcome) = EvalCore.evalApplied(r.treeHex, IntInput, activated = r.activated)
        val name = s"${r.site}-${r.shape.toLowerCase}-${if (r.accept) "control-accept" else "errored"}#$i"
        val s = s"[${r.id}] ${script(r, outcome)}"
        if (r.accept) SpecExtract.authoredEntryV(op, s, r.treeHex, name, IntInput, r.activated, ergoTree = r.ergoTree)
        else SpecExtract.authoredRejectEntryV(op, s, r.treeHex, name, IntInput, r.activated, ergoTree = r.ergoTree)
      }
      op -> SpecExtract.authoredEnvelope(op, entries, Source).mapObject(_.add("blessed_by", Json.fromString(Stamp)))
    }.toMap

  def writeVectors(outDir: java.nio.file.Path): Unit =
    SpecExtract.writeStaging("AuthoredEvalRuntimeTypeSites", extract(), outDir)
}
