package santa

// ─────────────────────────────────────────────────────────────────────────────
// Authored ADVERSARIAL Box.getReg-adjacent MethodCall reject vectors
// (v6/authored) — ergots vector request P7a-4.
//
// Two adversarial constructions around SBoxMethods (typeId=99):
//
// (a) MethodCall 99:7 — `getRegMethodV5`, name "getRegV5" (methods.scala:1329):
//       SMethod(this, "getRegV5", SFunc(Array(SBox, SInt), SOption(tT), Array(paramT)),
//               7, ExtractRegisterAs.costKind)      // .withInfo(ExtractRegisterAs, …)
//     PREMISE VERIFIED IN SOURCE (sigma-state 6.0.3):
//       • version-agnostic at DESERIALIZE: getRegMethodV5 sits in `commonBoxMethods`,
//         hence in BOTH `v5Methods` and `v6Methods` → `SMethod.fromIds(99, 7)` resolves
//         under EVERY tree version (the version-gated map fork in
//         MethodsContainer._methodsMap cannot exclude it).
//       • ALWAYS throws at EVAL: the descriptor carries docInfo only (`withInfo` sets no
//         irInfo) — no MethodCallIrBuilder, no bound javaMethod, no `getRegV5_eval`. Its
//         costKind is FixedCost (ExtractRegisterAs's JitCost(50)), so MethodCall.eval
//         takes the `invokeFixed` branch, which forces `SMethod.javaMethod` — a
//         reflection lookup of "getRegV5"(Int) on `classOf[sigma.Box]`. No such Java
//         method exists (the name appears nowhere outside the descriptor) → throws on
//         the live path at every version. The frontend never emits 99:7 (`box.getReg`
//         lowers to ExtractRegisterAs; the v6 path is 99:19), so only a manually built
//         tree carries the node — the adversarial gap these vectors pin.
//     Two arms (the dead/live bracketing mirrors AuthoredDeserializeContext):
//       • live-reject: root `SELF.getRegV5(getVar[Int](1).get)` → errored.
//       • dead-accept: `if (true) true else SELF.getRegV5(getVar[Int](1).get).isDefined`
//         — the 99:7 node parses; lazy If never evaluates it → true.
//     getRegV5 has NO explicitTypeArgs, so no type argument is serialized and the parsed
//     node's type is SOption(tT) with tT free; `.getRegV5(…)` in the script strings is
//     pseudo-syntax (ErgoScript has no surface form for this method).
//
// (b) MethodCall 99:19 — `getRegMethodV6` ("getReg", v6-only, P7a-2's subject) embedded
//     in an ErgoTree whose HEADER says version 2.
//     PREMISE VERIFIED IN SOURCE: ErgoTreeSerializer.deserializeErgoTree parses the body
//     under VersionContext(activated, treeVersion = header version bits) — with
//     treeVersion=2, `isV3OrLaterErgoTreeVersion` is false → SBoxMethods exposes only
//     v5Methods → method id 19 missing → ValidationException from CheckAndGetMethod
//     (method-validity IS tree-version-gated at deserialize). NUANCE: with the size bit
//     set the serializer does not propagate the throw — it returns
//     ErgoTree(DefaultHeader, …, Left(UnparsedErgoTree(bytes, ve))) (soft-fork
//     tolerance) and the stored ValidationException is rethrown on first USE of the
//     tree (`toProposition`). Outcome-wise a v2 tree containing 99:19 can never
//     evaluate → errored; the failure ORIGIN is deserialize-time method-id validation.
//
//     BYTES (serializer-emitted, no hand-patch needed): MethodCallSerializer has no
//     version gate on SERIALIZE (only parse does the gated lookup), so serializing the
//     P7a-2 dynamic-index Long tree under a version-2 header emits byte-for-byte the
//     committed Box.getReg_dynamic_index.json accept tree with ONLY the header's
//     version bits changed: 0x1b (const-seg|size|v3) → 0x1a (const-seg|size|v2). The
//     test locks both the equality with the committed v3 baseline and the
//     single-byte-diff relationship.
//
// Version stamping: arms (a) are full v6 {activated:3, ergoTree:3}; arm (b) is
// {activated:3, ergoTree:2} — the eval path guard (tools/validate path_envelope_guard)
// pins only version.activated=3 under vectors/eval/v6/, and the vector schema allows
// ergoTree 0..127, so the mixed stamp is legal (verified before authoring).
// Schema: santa-eval/v2 (input bound at context var 1) — minimal envelope: no arm needs
// SELF registers (the live arm throws on reflection lookup before any register read;
// the dead arm and the v2 arm never reach the call).
// Arm (b)'s entry is built locally with the same gates as
// SpecExtract.authoredRejectEntry, because it stamps ergoTree=2 ≠ activated, which the
// shared helper cannot express (it always stamps ergoTree = activated).
// ─────────────────────────────────────────────────────────────────────────────

import scorex.util.encode.Base16

import io.circe.Json

import sigma.VersionContext
import sigma.ast.{
  ErgoTree, GetVar, If, MethodCall, OptionGet, OptionIsDefined, SBoxMethods, SInt, SLong,
  SOption, SType, Self, TrueLeaf, Value
}
import sigma.ast.ErgoTree.{HeaderType, ZeroHeader}
import sigma.ast.SType.tT

object AuthoredGetRegAdversarial {

  /** Pinned target version: full v6 (activated=3, ergoTree=3) for the 99:7 arms. */
  val V3: Byte = VersionContext.V6SoftForkVersion
  /** The adversarial HEADER version for arm (b): ErgoTree v2 (the v5.x consensus era). */
  val V2: Byte = 2

  val Source = "santa:authored-get-reg-adversarial"
  val Op     = "Box.getReg adversarial MethodCall rejects"

  /** Dynamic register index — `getVar[Int](1).get` (var 1 = the santa-eval/v2 input). */
  private def idxExpr: Value[SInt.type] = OptionGet(GetVar(1.toByte, SOption(SInt)))

  /** The 99:7 call `SELF.getRegV5(<idx>)`. No explicitTypeArgs → empty typeSubst; the
    * node's type is SOption(tT) with tT free (nothing type-ish is serialized). */
  private[santa] def getRegV5Call: Value[SOption[SType]] =
    MethodCall.typed[Value[SOption[SType]]](
      Self, SBoxMethods.getRegMethodV5, IndexedSeq(idxExpr), Map.empty)

  /** The 99:19 call `SELF.getReg[Long](<idx>)` — same construction as
    * AuthoredGetRegDynamic's Long tree (P7a-2). */
  private[santa] def getRegV6Call: Value[SOption[SType]] =
    MethodCall.typed[Value[SOption[SType]]](
      Self, SBoxMethods.getRegMethodV6, IndexedSeq(idxExpr), Map(tT -> SLong))

  /** Serialize `root` into an ErgoTree with the given HEADER version. Authoring always
    * runs under the full-v6 VersionContext — LenientErgoTree.fromExpr's internal
    * placeholder round-trip must resolve v6-only method ids — while the header argument
    * alone decides the emitted version bits (serializeErgoTree re-enters
    * withVersions(max(activated, treeVersion), treeVersion) internally). That asymmetry
    * (parse is version-gated, serialize is not) is what makes arm (b)'s
    * v6-method-under-v2-header bytes producible by the serializer itself. */
  private[santa] def serializeAt(headerVersion: Byte, root: Value[SType]): String =
    VersionContext.withVersions(V3, V3) {
      val h0: HeaderType     = ErgoTree.headerWithVersion(ZeroHeader, headerVersion)
      val header: HeaderType = if (headerVersion > 0) ErgoTree.setSizeBit(h0) else h0
      Base16.encode(sigma.santa.LenientErgoTree.serialize(header, root))
    }

  /** SValue JSON for an Int (the register index bound at var 1). */
  private def intJson(v: Int): Json =
    Json.obj("kind" -> Json.fromString("Int"), "value" -> Json.fromInt(v))

  /** Arm (b)'s reject entry: same input gates + REQUIRED-reject as
    * SpecExtract.authoredRejectEntry, but stamps version {activated: 3, ergoTree: 2}. */
  private def v2HeaderRejectEntry(script: String, treeBytesHex: String, name: String,
                                  inputJson: Json): Json = {
    val decoded =
      try EvalCore.decodeInputConstant(inputJson)
      catch { case t: Throwable =>
        sys.error(s"v2HeaderRejectEntry: input not decodable for '$Op': ${EvalCore.errClass(t)} — ${inputJson.noSpaces}") }
    if (!EvalCore.isWireEncodable(decoded, V3))
      sys.error(s"v2HeaderRejectEntry: input ${decoded.tpe} not wire-encodable at ErgoTree v$V3 for '$Op' — ${inputJson.noSpaces}")

    val (_, outcome) = EvalCore.evalApplied(treeBytesHex, inputJson, activated = V3)
    outcome match {
      case Left(_)   => // expected: a v2-headed tree carrying 99:19 can never evaluate
      case Right(vc) =>
        sys.error(s"v2HeaderRejectEntry: MAJOR FINDING — oracle ACCEPTED the v6-method-in-v2-tree " +
          s"'$Op' ($script): $vc — premise wrong, do NOT commit")
    }
    Json.obj(
      "name"           -> Json.fromString(name),
      "script"         -> Json.fromString(script),
      "tree_bytes_hex" -> Json.fromString(treeBytesHex),
      "input"          -> inputJson,
      "version"        -> Json.obj("activated" -> Json.fromInt(V3.toInt),
                                   "ergoTree"  -> Json.fromInt(V2.toInt)),
      "expected"       -> Json.obj("value" -> Json.Null,
                                   "cost"  -> Json.Null,
                                   "error" -> Json.fromString("errored")))
  }

  /** All three arms under one op key (mixed accept/reject, mirrors DeserializeContext). */
  def extract(): Map[String, Json] = {
    val liveHex = serializeAt(V3, getRegV5Call)
    val deadHex = serializeAt(V3, If(TrueLeaf, TrueLeaf, OptionIsDefined(getRegV5Call)))
    val v2Hex   = serializeAt(V2, getRegV6Call)

    // `.getRegV5(…)` is pseudo-syntax: MethodCall 99:7 has no ErgoScript surface form and
    // no encoded type argument (the parsed node's type is SOption(tT), tT free).
    val liveScript = "{ SELF.getRegV5(getVar[Int](1).get) }"
    val deadScript = "{ if (true) true else SELF.getRegV5(getVar[Int](1).get).isDefined }"
    val v2Script   = "{ SELF.getReg[Long](getVar[Int](1).get) } /* ErgoTree header v2 */"

    val entries = Seq(
      SpecExtract.authoredRejectEntry(Op, liveScript, liveHex, "getRegV5-live-reject#0", intJson(4), V3),
      SpecExtract.authoredEntry(Op, deadScript, deadHex, "getRegV5-dead-branch-accept#1", intJson(4), V3),
      v2HeaderRejectEntry(v2Script, v2Hex, "getReg-v6-method-in-v2-tree-reject#2", intJson(4)))
    Map(Op -> SpecExtract.authoredEnvelope(Op, entries, Source))
  }

  /** Persist authored vectors to a staging dir (build artifact — copied into
    * vectors/eval/v6/authored/ once inspected). Fails loud on a slug collision. */
  def writeVectors(vectors: Map[String, Json], outDir: java.nio.file.Path): Unit = {
    java.nio.file.Files.createDirectories(outDir)
    val collisions = vectors.keys.groupBy(SpecExtract.slug).filter(_._2.size > 1)
    if (collisions.nonEmpty)
      sys.error("AuthoredGetRegAdversarial.writeVectors: slug collision — " +
        collisions.map { case (stem, ops) => s"'$stem.json' ← ${ops.mkString(" / ")}" }.mkString("; "))
    vectors.foreach { case (op, json) =>
      val path = outDir.resolve(s"${SpecExtract.slug(op)}.json")
      java.nio.file.Files.write(path, json.spaces2.getBytes(java.nio.charset.StandardCharsets.UTF_8))
    }
  }
}
