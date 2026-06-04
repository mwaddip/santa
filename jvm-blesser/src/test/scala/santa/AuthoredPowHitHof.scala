package santa

// ─────────────────────────────────────────────────────────────────────────────
// Authored `Global.powHit` → Coll-HOF type-propagation vectors (v6/authored).
//
// `Global.powHit` (Autolykos-2 PoW hit value) returns SUnsignedBigInt (methods.scala
// powHitMethod, methodId 8, v6/V3). sigma-rust mis-declared it `t_range: SBoolean`, so
// `coll.map { x => powHit(..) }` resolved to Coll[Boolean]; a HOF whose predicate domain
// is UnsignedBigInt was then rejected at parse (`Exists::new`'s exact
// `t_dom[0] == elem_type` check → "Invalid condition tpe"), forking the node off testnet
// at block 28,474. Fixed: sigma-rust PR #877 / eni `96367193`. The real failing tree is a
// context guard → block-tier (docs/findings/testnet-powhit-return-type/); THIS is the
// distilled, standalone, eval-catchable form.
//
// Why the eval tier catches it (unlike the bigint-downcast-2666 seed): the bug is a
// method-table return type consulted at parse/proposition, and nothing in a runner's eval
// context overrides a method's return type — there is no field to pre-set, so no masking.
// The map's output element type is SCollection(mapper.tpe.tRange) = powHit's t_range, so a
// SBoolean-typed powHit makes the predicate domain mismatch and parse fails.
//
// Three entries cover the three HOFs (identical t_dom[0]==elem_type check at three call
// sites): exists/forall → Boolean, filter wrapped in .size → Int. Results are Boolean/Int
// only — no UnsignedBigInt crosses the SANTA bridge (no codec/schema change needed). The
// predicate threshold is a plain Int at context var 1 (decodable by every runner). Blessed
// value+cost by the JVM eval (EvalCore IS sigma-state 6.0.3 — the oracle). Honest provenance
// vectors/eval/v6/authored/. Manual AST, mirrors AuthoredGetVarFromInput / AuthoredSigmaPropEq.
// ─────────────────────────────────────────────────────────────────────────────

import scorex.util.encode.Base16

import io.circe.Json

import sigma.VersionContext
import sigma.ast.{ByteArrayConstant, ConcreteCollection, ErgoTree, Exists, Filter, ForAll,
  FuncValue, GetVar, GT, Global, IntConstant, MapCollection, MethodCall, OptionGet,
  SCollection, SGlobalMethods, SInt, SOption, SType, SUnsignedBigInt, SizeOf,
  Upcast, ValUse, Value}
import sigma.ast.SCollection.SByteArray
import sigma.ast.ErgoTree.{HeaderType, ZeroHeader}

object AuthoredPowHitHof {

  /** Pinned target version: full v6 (activated=3, ergoTree=3). `Global.powHit` is
    * v6/V3-gated (isV3OrLaterErgoTreeVersion) and returns SUnsignedBigInt. */
  val V3: Byte = VersionContext.V6SoftForkVersion

  val Source = "santa:authored-powhit-hof"
  val Op     = "Global.powHit feeding Coll-HOF"

  // powHit(k, msg, nonce, h, N) — Autolykos2PowValidation.hitForVersion2ForMessageWithChecks
  // requires k∈[2,32] and N≥16. The mapped element (the lambda var) is the NONCE; the other
  // args are tree constants. msg/h lengths only affect cost (deterministic; the JVM blesses it).
  private val K = 2
  private val N = 16
  private val Msg: Array[Byte]  = Array.tabulate(32)(_.toByte)   // 32-byte message digest
  private val HPad: Array[Byte] = Array[Byte](0, 0, 0, 1)        // 4-byte height padding
  private val Nonces: Seq[Array[Byte]] =                         // → a 2-element Coll[Coll[Byte]]
    Seq(Array.fill[Byte](8)(0), Array.tabulate[Byte](8)(_.toByte))

  private val NonceArgId = 1   // mapper lambda arg: (nonce: Coll[Byte])
  private val UbiArgId   = 2   // predicate lambda arg: (u: UnsignedBigInt)

  /** mapper `(nonce: Coll[Byte]) => Global.powHit(K, Msg, nonce, HPad, N)` — the MethodCall
    * result type is read from powHitMethod.t_range (SUnsignedBigInt), so MapCollection.tpe
    * becomes Coll[UnsignedBigInt]. THE bug locus. */
  private def powHitMapper: FuncValue =
    FuncValue(IndexedSeq(NonceArgId -> SByteArray),
      MethodCall(Global, SGlobalMethods.powHitMethod,
        IndexedSeq(IntConstant(K), ByteArrayConstant(Msg), ValUse(NonceArgId, SByteArray),
                   ByteArrayConstant(HPad), IntConstant(N)),
        Map()))

  /** `Coll(nonce0, nonce1).map(powHit)` : Coll[UnsignedBigInt] — what each HOF consumes. */
  private def mappedColl: Value[SCollection[SType]] = {
    val coll = ConcreteCollection(Nonces.map(b => ByteArrayConstant(b)), SByteArray)
    MapCollection(coll, powHitMapper).asInstanceOf[Value[SCollection[SType]]]
  }

  /** predicate `(u: UnsignedBigInt) => u > upcast[UnsignedBigInt](getVar[Int](1).get)`. The
    * predicate DOMAIN is UnsignedBigInt — the type that must match the mapped collection's
    * element type, the check that rejects a Coll[Boolean]. The threshold is read from context
    * var 1 (a plain Int → decodable by every runner) and upcast to UnsignedBigInt. */
  private def predicate: FuncValue =
    FuncValue(IndexedSeq(UbiArgId -> SUnsignedBigInt),
      GT(ValUse(UbiArgId, SUnsignedBigInt),
         Upcast(OptionGet(GetVar(1.toByte, SOption(SInt))), SUnsignedBigInt)))

  /** (name, script, treeHex) per HOF, serialized at v6 via the lenient (non-SigmaProp-root)
    * encoder — exists/forall roots are Boolean, the filter root is wrapped in .size → Int. */
  private def hofTrees: Seq[(String, String, String)] =
    VersionContext.withVersions(V3, V3) {
      val header: HeaderType = ErgoTree.setSizeBit(ErgoTree.headerWithVersion(ZeroHeader, V3))
      def hex(root: Value[SType]): String =
        Base16.encode(sigma.santa.LenientErgoTree.serialize(header, root))
      val mapPart = "Coll(n0,n1).map{x => Global.powHit(2,msg,x,h,16)}"
      val pred    = "u > upcast[UnsignedBigInt](getVar[Int](1).get)"
      Seq(
        ("exists",      s"{ $mapPart.exists{u => $pred} }",        hex(Exists(mappedColl, predicate))),
        ("forall",      s"{ $mapPart.forall{u => $pred} }",        hex(ForAll(mappedColl, predicate))),
        ("filter.size", s"{ $mapPart.filter{u => $pred}.size }",   hex(SizeOf(Filter(mappedColl, predicate))))
      )
    }

  /** Predicate-threshold input bound at context var 1: a plain Int (here 0, so every
    * positive powHit value passes the predicate). Decodable by every conformer. */
  private def thresholdInput: Json =
    Json.obj("kind" -> Json.fromString("Int"), "value" -> Json.fromInt(0))

  /** op -> v2 envelope: one entry per HOF, each its own tree, sharing the threshold input. */
  def extract(): Map[String, Json] = {
    val entries = hofTrees.zipWithIndex.map { case ((name, script, treeHex), i) =>
      SpecExtract.authoredEntry(Op, script, treeHex, s"$name#$i", thresholdInput, V3)
    }
    Map(Op -> SpecExtract.authoredEnvelope(Op, entries, Source))
  }

  /** Persist authored vectors to a staging dir (build artifact — copied into
    * vectors/eval/v6/authored/ once inspected). Fails loud on a slug collision. */
  def writeVectors(vectors: Map[String, Json], outDir: java.nio.file.Path): Unit = {
    java.nio.file.Files.createDirectories(outDir)
    val collisions = vectors.keys.groupBy(SpecExtract.slug).filter(_._2.size > 1)
    if (collisions.nonEmpty)
      sys.error("AuthoredPowHitHof.writeVectors: slug collision would silently drop entries — " +
        collisions.map { case (stem, ops) => s"'$stem.json' ← ${ops.mkString(" / ")}" }.mkString("; "))
    vectors.foreach { case (op, json) =>
      val path = outDir.resolve(s"${SpecExtract.slug(op)}.json")
      java.nio.file.Files.write(path, json.spaces2.getBytes(java.nio.charset.StandardCharsets.UTF_8))
    }
  }
}
