package santa

// ─────────────────────────────────────────────────────────────────────────────
// Authored `Global.serialize[T]` cost vectors for the DELEGATED-serializer types.
//
// `LanguageSpecificationV6` tests Global.serialize only for the DIRECT types
// (Byte/Short/Int/Long/Coll[Byte]/(Long,Long) + the BigInt roundtrip) — settled by
// reading the spec: its serialize features are exactly serializeByte/Short/Int/Long/
// CollByte/Pair, one-for-one with the 7 committed v6/spec/Global.serialize_* vectors.
// The delegated-serializer types are simply ABSENT from the spec, not gated, so they
// are AUTHORED here (requested by the sigma-rust v6 cost-parity work, which can't
// self-verify its delegated nested serializers without a JVM oracle):
//
//   delegated : GroupElement · SigmaProp · UnsignedBigInt · AvlTree · Box · Header
//   composite : Coll[GroupElement] · Option[BigInt] · (Box, Int)
//
// Mechanism: reuse the spec's OWN `mkSerializeFeature[A](RType[A])` to compile the
// identical `{ (x: T) => serialize(x) }` tree it produces for the direct types, then
// bless value+cost from the JVM eval (EvalCore IS sigma-state 6.0.3 — the oracle; no
// spec-declared expected exists to cross-check, so the eval is canonical). The only
// authored parts are the type list and the chosen inputs. Honest provenance is
// vectors/eval/v6/authored/ (NOT v6/spec/), source "santa:authored-serialize".
// ─────────────────────────────────────────────────────────────────────────────

import scorex.util.encode.Base16

import io.circe.Json

import org.ergoplatform.ErgoBox

import sigma.{AvlTreeRType, BigIntRType, BoxRType, Colls, GroupElementRType, HeaderRType, IntType,
  SigmaPropRType, UnsignedBigIntRType, VersionContext, collRType}
import sigma.ast.{ErgoTree, IntConstant}
import sigma.ast.ErgoTree.{HeaderType, ZeroHeader}
import sigma.crypto.{CryptoConstants, EcPointType}
import sigma.data.{AvlTreeData, AvlTreeFlags, ProveDlog, RType, SigmaBoolean}
import sigma.serialization.{GroupElementSerializer, SigmaSerializer}

import sigma.LanguageSpecificationV6

object AuthoredSerialize {

  /** Pinned target version: full v6 (activated=3, ergoTree=3). */
  val V3: Byte = VersionContext.V6SoftForkVersion

  /** Provenance stamp (these are SANTA-authored, JVM-blessed — not spec-extracted). */
  val Source = "santa:authored-serialize"

  /** Tap into LanguageSpecificationV6 to reuse its `serialize` feature builder. Pinned
    * to V3 so the compiled tree's ErgoTree version is v6 (ergoTreeVersionInTests). */
  private final class Tap extends LanguageSpecificationV6 {
    override protected val activatedVersions: Seq[Byte] = Array(V3)
    override val ergoTreeVersions: Seq[Byte] = Array(V3)

    /** (script, treeHex) for `{ (x: T) => serialize(x) }` at v6 — the SAME compilation
      * path the spec uses for the direct types (mkSerializeFeature → newF.compiledTree,
      * serialized via the lenient (non-SigmaProp-root) encoder). */
    def serializeTree[A](rt: RType[A]): (String, String) =
      VersionContext.withVersions(V3, V3) {
        val f = mkSerializeFeature(rt)
        val compiledTree = f.newF.compiledTree
        // Stamp the ErgoTree at v6 (V3) EXPLICITLY. `ergoTreeVersionInTests` is only set to
        // 3 inside the spec's per-iteration version loop (forEachScriptAndErgoTreeVersion);
        // here mkSerializeFeature is called directly (outside that loop), so it would default
        // low and the v6 `serialize` method would fail eval's method-validity check (rule 1011).
        val header: HeaderType =
          ErgoTree.setSizeBit(ErgoTree.headerWithVersion(ZeroHeader, V3))
        (f.script, Base16.encode(sigma.santa.LenientErgoTree.serialize(header, compiledTree)))
      }
  }

  // ── input JSON builders (mirror EvalCore.valueToJson's shapes exactly) ─────────
  private def geJson(point: EcPointType): Json =
    Json.obj("kind" -> Json.fromString("GroupElement"),
             "bytes_hex" -> Json.fromString(Base16.encode(GroupElementSerializer.toBytes(point))))
  private def sigmaPropJson(point: EcPointType): Json =
    Json.obj("kind" -> Json.fromString("SigmaProp"),
             "raw_hex" -> Json.fromString(Base16.encode(SigmaBoolean.serializer.toBytes(ProveDlog(point)))))
  private def ubiJson(dec: String): Json =
    Json.obj("kind" -> Json.fromString("UnsignedBigInt"), "value" -> Json.fromString(dec))
  private def bigIntJson(dec: String): Json =
    Json.obj("kind" -> Json.fromString("BigInt"), "value" -> Json.fromString(dec))
  private def avlJson(data: AvlTreeData): Json =
    Json.obj("kind" -> Json.fromString("AvlTree"),
             "bytes_hex" -> Json.fromString(Base16.encode(AvlTreeData.serializer.toBytes(data))))
  private def boxJson(hex: String): Json =
    Json.obj("kind" -> Json.fromString("Box"), "bytes_hex" -> Json.fromString(hex))
  private def headerJson(hex: String): Json =
    Json.obj("kind" -> Json.fromString("Header"), "bytes_hex" -> Json.fromString(hex))
  private def intJson(n: Int): Json =
    Json.obj("kind" -> Json.fromString("Int"), "value" -> Json.fromInt(n))
  private def collJson(elemTag: String, items: Seq[Json]): Json =
    Json.obj("kind" -> Json.fromString("Coll"),
             "elem" -> Json.obj("tag" -> Json.fromString(elemTag)),
             "items" -> Json.arr(items: _*))
  private def optionJson(inner: Json): Json =
    Json.obj("kind" -> Json.fromString("Option"), "value" -> inner)
  private def tupleJson(a: Json, b: Json): Json =
    Json.obj("kind" -> Json.fromString("Tuple"), "items" -> Json.arr(a, b))
  private def byteJson(n: Int): Json =
    Json.obj("kind" -> Json.fromString("Byte"), "value" -> Json.fromInt(n))
  private def longJson(v: Long): Json =
    Json.obj("kind" -> Json.fromString("Long"), "value" -> Json.fromString(v.toString))
  private def stype(tag: String): Json = Json.obj("tag" -> Json.fromString(tag))
  /** Coll with an explicit element-SType JSON (nested elems collJson's tag-string can't express). */
  private def collJsonE(elem: Json, items: Seq[Json]): Json =
    Json.obj("kind" -> Json.fromString("Coll"), "elem" -> elem, "items" -> Json.arr(items: _*))

  // ── representative inputs ─────────────────────────────────────────────────────
  private val gen: EcPointType = CryptoConstants.dlogGroup.generator

  // The corpus's minimal box fixture (Box_properties_equivalence_new_features), reused as
  // the small case; a copy carrying an R4 register is the larger case (serialize cost is
  // structure/length-driven, so the two span the cost axis).
  private val MinBoxHex =
    "c0843d0b0208d3000000000000000000000000000000000000000000000000000000000000000000000000"
  private val RichBoxHex: String = {
    val minBox = ErgoBox.sigmaSerializer.parse(SigmaSerializer.startReader(Base16.decode(MinBoxHex).get))
    val regs: ErgoBox.AdditionalRegisters = Map(ErgoBox.R4 -> IntConstant(42))
    val rich = new ErgoBox(minBox.value, minBox.ergoTree, minBox.additionalTokens, regs,
      minBox.transactionId, minBox.index, minBox.creationHeight)
    Base16.encode(ErgoBox.sigmaSerializer.toBytes(rich))
  }

  // Fast-path register families (sigma-rust SANTA_SERIALIZE_FASTPATH_VECTORS_NEEDED): a box whose R4
  // holds a COMPOUND value whose SType serializes via an embedded-type combined type-code byte (the
  // fast path). The register value is built by reusing EvalCore.decodeInputConstant (the same decoder
  // grading uses), placed in R4, and the box serialized — so serialize[Box] exercises that type-code
  // byte; the JVM cost is the oracle for sigma-rust's +1 fast-path metering.
  private def boxWithR4Hex(regJson: Json): String = {
    val minBox = ErgoBox.sigmaSerializer.parse(SigmaSerializer.startReader(Base16.decode(MinBoxHex).get))
    val regs: ErgoBox.AdditionalRegisters = Map(ErgoBox.R4 -> EvalCore.decodeInputConstant(regJson))
    val box = new ErgoBox(minBox.value, minBox.ergoTree, minBox.additionalTokens, regs,
      minBox.transactionId, minBox.index, minBox.creationHeight)
    Base16.encode(ErgoBox.sigmaSerializer.toBytes(box))
  }
  /** serialize[Box] input for a box carrying `regJson` in R4. */
  private def r4Box(regJson: Json): Json = boxJson(boxWithR4Hex(regJson))

  // The corpus's v6 header fixture (Header_new_methods) — a structurally-complete header.
  // serialize is version-agnostic in its put sequence (HeaderWithoutPowSerializer writes the
  // same fields for v2 and v3; only the version byte value differs), so this fully exercises
  // the serialize[Header] cost path. (A real testnet v3 header can be added later if wanted.)
  private val HeaderHex =
    "02ac2101807f0000ca01ff0119db227f202201007f62000177a080005d440896d05d3f80dcff7f5e7f59007294" +
    "c180808d0158d1ff6ba10000f901c7f0ef87dcfff17fffacb6ff7f7f1180d2ff7f1e24ffffe1ff937f807f0797b9" +
    "ff6ebdae007e5c8c00b8403d3701557181c8df800001b6d5009e2201c6ff807d71808c00019780f087adb3fcdbc0" +
    "b3441480887f80007f4b01cf7f013ff1ffff564a0000b9a54f00770e807f41ff88c00240000080c0250000000003" +
    "bedaee069ff4829500b3c07c4d5fe6b3ea3d3bf76c5c28c1d4dcdb1bed0ade0c0000000000003105"

  private val twoPow255 = java.math.BigInteger.valueOf(2).pow(255).toString  // 256-bit unsigned
  private val twoPow200 = java.math.BigInteger.valueOf(2).pow(200).toString  // large signed BigInt

  private val avlDummy = AvlTreeData.dummy
  private val avlWithValueLen =
    AvlTreeData(Colls.fromArray(Array.fill(AvlTreeData.DigestSize)(1.toByte)),
                AvlTreeFlags.AllOperationsAllowed, keyLength = 32, valueLengthOpt = Some(8))

  /** Build one target's envelope: one serialize[T] tree, one entry per input. */
  private def target[A](tap: Tap, op: String, rt: RType[A], inputs: Seq[(String, Json)]): (String, Json) = {
    val (script, treeHex) = tap.serializeTree(rt)
    val entries = inputs.zipWithIndex.map { case ((name, in), i) =>
      SpecExtract.authoredEntry(op, script, treeHex, s"$name#$i", in, V3)
    }
    op -> SpecExtract.authoredEnvelope(op, entries, Source)
  }

  /** All authored serialize vectors, op -> v2 envelope. */
  def extract(): Map[String, Json] = {
    val tap = new Tap
    Seq(
      target(tap, "Global.serialize[GroupElement]", GroupElementRType,
        Seq("generator" -> geJson(gen))),
      target(tap, "Global.serialize[SigmaProp]", SigmaPropRType,
        Seq("proveDlog(generator)" -> sigmaPropJson(gen))),
      target(tap, "Global.serialize[UnsignedBigInt]", UnsignedBigIntRType,
        Seq("0" -> ubiJson("0"), "255" -> ubiJson("255"), "2^255" -> ubiJson(twoPow255))),
      target(tap, "Global.serialize[AvlTree]", AvlTreeRType,
        Seq("dummy" -> avlJson(avlDummy), "withValueLen" -> avlJson(avlWithValueLen))),
      target(tap, "Global.serialize[Box]", BoxRType,
        Seq("minimal" -> boxJson(MinBoxHex), "withR4" -> boxJson(RichBoxHex),
            // fast-path register families: R4 holds a compound type → embedded combined type-code byte
            "R4=Coll[Byte]"         -> r4Box(collJsonE(stype("SByte"), (0 until 32).map(byteJson))),
            "R4=Coll[Int]"          -> r4Box(collJsonE(stype("SInt"), Seq(intJson(1), intJson(2), intJson(3)))),
            // Option register values are NOT data-serializable (JVM rule 1009 rejects SOption.OptionTypeCode
            // since v5.0), so the OPTION+prim / OPTION_COLL fast-path families are un-authorable as a box
            // register — and unreachable via Global.serialize (an Option value can't be serialized at all).
            "R4=Coll[Coll[Byte]]"   -> r4Box(collJsonE(
                                         Json.obj("tag" -> Json.fromString("SColl"), "elem" -> stype("SByte")),
                                         Seq(collJsonE(stype("SByte"), Seq(byteJson(1), byteJson(2)))))),
            "R4=(Int,Int)"          -> r4Box(tupleJson(intJson(1), intJson(2))),
            "R4=(Int,Long)"         -> r4Box(tupleJson(intJson(1), longJson(2L))),
            // TUPLE_PAIR2: (non-prim, prim) — first elem Coll[Byte], a distinct fast-path tuple type code
            "R4=(Coll[Byte],Int)"   -> r4Box(tupleJson(collJsonE(stype("SByte"),
                                         Seq(byteJson(1), byteJson(2), byteJson(3), byteJson(4))), intJson(42))))),
      target(tap, "Global.serialize[Header]", HeaderRType,
        Seq("specFixture" -> headerJson(HeaderHex))),
      target(tap, "Global.serialize[Coll[GroupElement]]", collRType(GroupElementRType),
        Seq("empty" -> collJson("SGroupElement", Seq.empty),
            "one"   -> collJson("SGroupElement", Seq(geJson(gen))),
            "two"   -> collJson("SGroupElement", Seq(geJson(gen), geJson(gen))))),
      target(tap, "Global.serialize[Option[BigInt]]", RType.optionRType(BigIntRType),
        Seq("Some(0)"     -> optionJson(bigIntJson("0")),
            "Some(2^200)" -> optionJson(bigIntJson(twoPow200)))),
      target(tap, "Global.serialize[(Box, Int)]", RType.pairRType(BoxRType, IntType),
        Seq("(minimal,0)" -> tupleJson(boxJson(MinBoxHex), intJson(0)),
            "(withR4,42)"  -> tupleJson(boxJson(RichBoxHex), intJson(42))))
    ).toMap
  }

  /** Persist authored vectors to a staging dir (build artifact — copied into
    * vectors/eval/v6/authored/ once inspected). Fails loud on a slug collision. */
  def writeVectors(vectors: Map[String, Json], outDir: java.nio.file.Path): Unit = {
    java.nio.file.Files.createDirectories(outDir)
    val collisions = vectors.keys.groupBy(SpecExtract.slug).filter(_._2.size > 1)
    if (collisions.nonEmpty)
      sys.error("AuthoredSerialize.writeVectors: slug collision would silently drop entries — " +
        collisions.map { case (stem, ops) => s"'$stem.json' ← ${ops.mkString(" / ")}" }.mkString("; "))
    vectors.foreach { case (op, json) =>
      val path = outDir.resolve(s"${SpecExtract.slug(op)}.json")
      java.nio.file.Files.write(path, json.spaces2.getBytes(java.nio.charset.StandardCharsets.UTF_8))
    }
  }
}
