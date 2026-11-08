package santa

// Authored eval vectors — the third half of the small-tuples family, and the one that asks what
// the interpreter DOES rather than what the serializer allows.
//
// Three refusals, three different mechanisms, all measured on 6.0.7 at activated 3 / tree 3:
//
//   a 1-item tuple CONSTANT   `SelectField` meets a `Coll[Any]` where its declared type says
//                             `(Int)`, because `Evaluation.toDslTuple` hands back the raw
//                             collection for anything but a pair (`Evaluation.scala:99-102`).
//                             The JVM reports it as its OWN `Value.typeError`
//                             (`values.scala:235`), not a ClassCastException.
//   a 0-item tuple CONSTANT   `RuntimeException: Unsupported tuple type ()` — equality never
//                             reaches a comparison at all. The 1-item twin (`(1) == (1)`) says
//                             `Unsupported tuple type (SInt$)`, so this is about the arity of the
//                             TYPE and not about its emptiness.
//   a tuple EXPRESSION        `Tuple.eval` refuses any count but two (`values.scala:825-827`),
//                             "Invalid tuple Tuple(Vector(...))". 127 items fail the same way;
//                             128 never reaches eval, because the PARSE throws
//                             NegativeArraySizeException on a signed count byte.
//
// And one thing that WORKS where everything else fails: `Global.serialize` on a 1-item tuple,
// cost 65 against the pair twin's 51. `DataSerializer` writes a tuple's ITEMS and no type, so the
// write-side refusal that kills every box and transaction never fires here. That row is the reason
// to be careful saying "the JVM cannot write a small tuple": it cannot write the TYPE.
//
// DELIBERATELY NOT HERE: a row for the 3-item tuple constant. `Tuple.non_pair_type_check` already
// pins the triple at all three checkType sites (EQ, a ValDef whose ValUse is selected from, and a
// lambda argument) with pair controls, and `valdef-triple-errored#2` is the SelectField-on-a-triple
// site. The triple earns its place in the WIRE and TRANSACTION halves of this family instead,
// where it is the one non-pair tuple type the JVM can write — so its carrier round-trips and its
// spend is a box that can really exist. Also not here: `Global.serialize` on 4- and 5-tuples, which
// `Global.serialize[Box] tuple arity` already carries.
//
// Every tree is the v3 size-flagged form. The v0 twins carry the same eval verdict (none of these
// mechanisms is version-gated) and so add nothing HERE — v0 is where the wire tier separates, and
// `Global.serialize` needs v3 anyway, being a v6 method.
//
// `SpecExtract.authored{,Reject}Entry` re-derives every verdict through `EvalCore.evalApplied` and
// fails loud if a reject evaluates or an accept does not, so this family cannot rot into a false
// expectation.

import io.circe.Json
import sigma.ast._
import sigma.serialization.{SigmaSerializer, ValueSerializer}
import sigma.VersionContext
import scorex.util.encode.Base16

import SmallTupleTrees._

object AuthoredEvalSmallTuples {
  val Op           = "Tuple.small_tuples"
  val OpSerialize  = "Global.serialize_small_tuple"
  val Source       = "santa:authored-small-tuples"
  private val V3: Byte = Activated

  /** These trees read no input; the eval tier applies one regardless, so it is a placeholder. */
  private val dummyInput: Json =
    Json.obj("kind" -> Json.fromString("Int"), "value" -> Json.fromInt(0))

  /** The cases whose eval verdict this family records: everything but the triple, which
    * `Tuple.non_pair_type_check` already covers (see the header). */
  val evalCases: Seq[Case] = cases.filter(_.slug != "const_triple_type")

  private def script(c: Case): String = {
    val mechanism = c.arm match {
      case TypeWrite  => "// parses; eval fails — toDslTuple returns a raw Coll[Any] for a non-pair"
      case ExprAccept => "// parses and round-trips; Tuple.eval refuses any count but two"
      case CountBound => "// the PARSE throws: the count byte 0x80 reads as -128"
      case Control    => "// the writable twin, and the cost baseline"
    }
    s"${c.what}  $mechanism"
  }

  // ── Global.serialize on a one-item tuple ─────────────────────────────────────────────────────
  private def ctx[A](f: => A): A = VersionContext.withVersions(V3, V3)(f)
  private def bodyOf(prop: sigma.ast.syntax.SigmaPropValue): String = ctx {
    val w = SigmaSerializer.startWriter(); ValueSerializer.serialize(prop, w); Base16.encode(w.toBytes)
  }
  /** `sigmaProp(Global.serialize(c) == Coll(0))` over a tuple constant: the twin is written by the
    * JVM and the one-item constant substituted, exactly as the TypeWrite bodies are. */
  private lazy val serializePairBody: String = bodyOf(BoolToSigmaProp(EQ(
    MethodCall(Global, SGlobalMethods.serializeMethod,
      IndexedSeq(Constant[SType]((1, 2).asInstanceOf[SType#WrappedType], STuple(SInt, SInt))), Map()),
    ByteArrayConstant(Array[Byte](0)))))
  private lazy val serializeOneItemBody: String = {
    val i = serializePairBody.indexOf(PairConst)
    require(i >= 0 && serializePairBody.indexOf(PairConst, i + 1) < 0,
      s"the pair constant must occur once in $serializePairBody")
    serializePairBody.take(i) + OneItemConst + serializePairBody.drop(i + PairConst.length)
  }
  def serializeOneItemTree: String = v3(serializeOneItemBody)
  def serializePairTree: String    = v3(serializePairBody)

  def extract(): Map[String, Json] = {
    // NOTE the difference from the carrier halves: `Case.rejects` is about the CARRIER (a 0/1-item
    // tuple type cannot be written, a 128-item count cannot be parsed), and at EVAL the expression
    // cases fail too — `Tuple.eval` refuses any count but two. So everything but a Control errors
    // here, and the three arms converge on one verdict for three different reasons. Using
    // `c.rejects` was an authoring bug, and `authoredEntry`'s fail-loud re-derivation caught it.
    val tupleEntries = evalCases.zipWithIndex.map { case (c, i) =>
      val tree = v3(c.body)
      val erroring = c.arm != Control
      val name = s"${c.slug}-${if (erroring) "errored" else "accept"}#$i"
      if (erroring) SpecExtract.authoredRejectEntry(Op, script(c), tree, name, dummyInput, V3)
      else SpecExtract.authoredEntry(Op, script(c), tree, name, dummyInput, V3)
    }
    // `(1) == (1)`: the one-item twin of `const_zero_item_type`, which proves the refusal is about
    // the TYPE's arity rather than about an empty tuple — "Unsupported tuple type (SInt$)".
    val eqOneItem = SpecExtract.authoredRejectEntry(Op,
      "sigmaProp(c == c) where c is a CONSTANT of type `(Int)`  // `Unsupported tuple type " +
      "(SInt$)`, where the `()` twin says `Unsupported tuple type ()` — the arity of the type is " +
      "what equality refuses, not its emptiness",
      v3(caseBySlug("const_zero_item_type").body.replace(ZeroItemConst, OneItemConst)),
      s"const_one_item_type_eq_self-errored#${evalCases.size}", dummyInput, V3)

    val serializeEntries = Seq(
      SpecExtract.authoredEntry(OpSerialize,
        "sigmaProp(Global.serialize(c) == Coll(0)) where c is a CONSTANT of type `(Int)`  // the " +
        "one place a one-item tuple WORKS: DataSerializer writes a tuple's items and no type, so " +
        "the write-side refusal that kills every box and transaction never fires",
        serializeOneItemTree, "serialize-one-item-tuple-accept#0", dummyInput, V3),
      SpecExtract.authoredEntry(OpSerialize,
        "sigmaProp(Global.serialize(c) == Coll(0)) where c is a `(Int,Int)` CONSTANT  // the " +
        "writable twin, and the cost the one-item row is read against",
        serializePairTree, "serialize-pair-control-accept#1", dummyInput, V3))

    def envelope(op: String, entries: Seq[Json]): Json =
      SpecExtract.authoredEnvelope(op, entries, Source)
        .mapObject(_.add("blessed_by", Json.fromString("jvm:sigma-state-6.0.7")))
    Map(
      Op          -> envelope(Op, tupleEntries :+ eqOneItem),
      OpSerialize -> envelope(OpSerialize, serializeEntries))
  }

  def writeVectors(outDir: java.nio.file.Path): Unit =
    SpecExtract.writeStaging("AuthoredEvalSmallTuples", extract(), outDir)
}
