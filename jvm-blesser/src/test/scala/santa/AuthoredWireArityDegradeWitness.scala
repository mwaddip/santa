package santa

// Authored wire vectors — telling a PARSED wrong-arity call apart from a DEGRADED one.
//
// `ErgoTree.method_call_arity` cannot do it. The JVM parses a wrong-arity call; an implementation
// whose parse fails DEGRADES instead (every tree above version 0 carries the size flag,
// `ErgoTree.scala:104`, so a parse failure is soft); and a degraded tree echoes the bytes it
// received — the same bytes the JVM produces by parsing and re-serializing. Byte round-trip alone
// is blind to the difference.
//
// These entries break the tie with the non-identity arm the corpus already has
// (`santa-wire/v1`'s optional `expected_bytes_hex`, shipped for `STypeVar.name_utf8_roundtrip`),
// so no schema change is needed. Each tree carries, beside the call, a `ValDef` whose type
// parameter is named with the invalid UTF-8 byte `ff`. The JVM lossy-decodes that name to U+FFFD
// and re-encodes it as three bytes, so the canonical form DIFFERS from the input:
//
//   parsed    the whole tree is re-encoded from structure, the name widens 1 -> 3 bytes,
//             the declared size grows with it, and the result matches expected_bytes_hex
//   degraded  the tree is echoed verbatim, the name stays one byte, and the entry goes RED
//
// `ff` is deliberate: it is the lossy byte every runner already agrees on
// (`STypeVar.name_utf8_roundtrip` is green on blitzen-mwaddip for all five of its cases,
// `eda080` included), so a red here is the arity degrade and not the name handling. The
// legal-arity control carries the same lossy name and must stay green, which is what rules the
// name out as the cause.
//
// WHY THE LENIENT PATH DOES NOT SPOIL THIS. A runner's ErgoTree arm parses leniently
// (sigma-rust's `sigma_parse_bytes_lenient`) so that a tree re-encodes from structure rather than
// echoing cached bytes. That does NOT make it parse a wrong-arity call: lenient is
// `parse_with(r, check_root_tpe = false)`, and the flag reaches only the root-type check (rule
// 1001) inside `sigma_parse_body`. The size-flagged degrade is handled in the `match` around that
// call and runs the same either way. Measured by the sigma-rust session on
// `0b10d801d601dc04090402020404040808d3`: Unparsed under both entry points at fork `65c94a03`,
// parsed under both at `49dcc4d1` (PR 63, which merged the arity rule).

import io.circe.Json
import scorex.util.encode.Base16
import sigma.VersionContext
import sigma.ast._
import sigma.data.TrivialProp

object AuthoredWireArityDegradeWitness {
  val V3: Byte = VersionContext.V6SoftForkVersion
  val Op     = "ErgoTree.method_call_arity_degrade_witness"
  val Source = "santa:authored-arity-degrade-witness"

  /** The invalid UTF-8 byte spliced into every type-parameter name. */
  private val LossyByte = "ff"

  private def b(h: String): Array[Byte] = Base16.decode(h).get

  /** The call each entry carries, beside the lossy-named ValDef. */
  private def callOf(shape: String): Value[SType] = {
    def int(n: Int): Value[SType] = IntConstant(n).asInstanceOf[Value[SType]]
    val coll: Value[SType] =
      ConcreteCollection(IndexedSeq(IntConstant(1), IntConstant(2)), SInt).asInstanceOf[Value[SType]]
    shape match {
      case "legal-arity-control" =>
        MethodCall(IntConstant(1), SIntMethods.getMethodByName("bitwiseOr"),
          IndexedSeq(int(2)), Map.empty)
      case "user-defined-invoke-extra-arg" =>
        MethodCall(IntConstant(1), SIntMethods.getMethodByName("bitwiseOr"),
          IndexedSeq(int(2), int(4)), Map.empty)
      case "reflective-extra-arg" =>
        MethodCall(coll, SCollectionMethods.getMethodByName("indexOf"),
          IndexedSeq(int(2), int(0), int(7)), Map.empty)
      case "property-call-of-arg-taking-method" =>
        MethodCall(coll, SCollectionMethods.getMethodByName("indexOf"),
          IndexedSeq.empty, Map.empty)
    }
  }

  /** BlockValue(ValDef with a type parameter, ValDef holding the call) -> sigmaProp(true).
    * The name is a same-length placeholder, spliced to the lossy byte afterwards.
    *
    * Constants stay INLINE (header 0x0b, no segregation): the call then serializes to exactly the
    * bytes `ErgoTree.method_call_arity` carries — e.g. `dc 0409 0402 02 0404 0408` for the surplus
    * argument — so this entry exercises the same call form the fork was measured degrading. A
    * segregated tree writes the object and arguments as ConstantPlaceholders instead. */
  private def treeOf(shape: String): String = VersionContext.withVersions(V3, V3) {
    val tv = STypeVar("a")
    val poly = FuncValue(IndexedSeq(2 -> tv), ValUse(2, tv))
    val root = BlockValue(
      IndexedSeq(ValDef(1, Seq(tv), poly), ValDef(3, IndexedSeq.empty, callOf(shape))),
      SigmaPropConstant(TrivialProp.TrueProp))
    val header = ErgoTree.setSizeBit(ErgoTree.headerWithVersion(ErgoTree.ZeroHeader, V3))
    val tree = new ErgoTree(header, IndexedSeq.empty,
      Right(root.asInstanceOf[sigma.ast.syntax.SigmaPropValue]))
    splice(Base16.encode(
      sigma.serialization.ErgoTreeSerializer.DefaultSerializer.serializeErgoTree(tree)), b(LossyByte))
  }

  /** Replace the name bytes of every `67 <len> <name>` region; same length keeps the layout. */
  private def splice(hexIn: String, invalid: Array[Byte]): String = {
    val L = invalid.length
    val out = b(hexIn).clone()
    var i = 0; var count = 0
    while (i < out.length - 1) {
      if ((out(i) & 0xff) == 0x67 && (out(i + 1) & 0xff) == L) {
        System.arraycopy(invalid, 0, out, i + 2, L); count += 1; i += 2 + L
      } else i += 1
    }
    require(count >= 1, s"no STypeVar name region of length $L in $hexIn")
    Base16.encode(out)
  }

  private val Shapes = Seq(
    ("legal-arity-control",
     "`1.bitwiseOr(2)` at the arity the method declares — the control that rules the lossy name " +
     "out as the cause of any red here. The JVM parses it and so does an implementation with no " +
     "arity gate at all, so both re-encode and both are green."),
    ("user-defined-invoke-extra-arg",
     "`1.bitwiseOr(2, 4)` — one argument more than the method takes."),
    ("reflective-extra-arg",
     "`Coll(1, 2).indexOf(2, 0, 7)` — one argument more than the method takes, on a method with " +
     "no userDefinedInvoke handler."),
    ("property-call-of-arg-taking-method",
     "`Coll(1, 2).indexOf` with no arguments at all, written as a PropertyCall."))

  private def version: Json =
    Json.obj("activated" -> Json.fromInt(V3.toInt), "ergoTree" -> Json.fromInt(V3.toInt))

  def extract(): Map[String, Json] = {
    val entries = Shapes.zipWithIndex.map { case ((shape, what), i) =>
      val in = treeOf(shape)
      val out = WireCanonicalize.canonicalize("ErgoTree", in, V3, V3)
      require(out != in,
        s"$shape: the whole point is a NON-identity round-trip — the JVM returned the input ($in)")
      require(WireCanonicalize.canonicalize("ErgoTree", out, V3, V3) == out,
        s"$shape: the JVM's own output must round-trip to itself ($out)")
      Json.obj(
        "name"               -> Json.fromString(s"arity-degrade-witness-$shape#$i"),
        "kind"               -> Json.fromString("ErgoTree"),
        "source"             -> Json.fromString(Source),
        "description"        -> Json.fromString(
          s"$what Beside the call the tree binds a ValDef whose type parameter is named with the " +
          "invalid UTF-8 byte ff. The JVM PARSES the tree, lossy-decodes that name to U+FFFD and " +
          "re-encodes it as three bytes, so the canonical form is LONGER than the input and the " +
          "declared size grows with it. An implementation that degrades this tree instead — which " +
          "is what a parse-time arity refusal does, since the size flag makes the failure soft — " +
          "echoes the bytes it received and does not reach expected_bytes_hex: RED. This is what " +
          "ErgoTree.method_call_arity cannot show, because there a degrade and a parse emit the " +
          "same bytes. Lenient parsing does not blunt it: the leniency flag reaches only the " +
          "root-type check, and the size-flagged degrade runs either way."),
        "bytes_hex"          -> Json.fromString(in),
        "expected_bytes_hex" -> Json.fromString(out),
        "version"            -> version)
    }
    Map(Op -> Json.obj(
      "schema"     -> Json.fromString("santa-wire/v1"),
      "op"         -> Json.fromString(Op),
      "blessed_by" -> Json.fromString("jvm:sigma-state-6.0.7"),
      "entries"    -> Json.arr(entries: _*)))
  }

  def writeVectors(outDir: java.nio.file.Path): Unit =
    SpecExtract.writeStaging("AuthoredWireArityDegradeWitness", extract(), outDir)
}
