package santa

// ─────────────────────────────────────────────────────────────────────────────
// Authored `SigmaProp == SigmaProp` JIT-cost vectors (v5/authored).
//
// LanguageSpecificationV5's NEQ "predefined types" features do NOT include SigmaProp
// (verified: vectors/eval/v5/spec carries zero SigmaProp under EQ/NEQ — the SigmaProp
// values present are all constructors / `&&` / `||` / propBytes / serialize), so the
// spec-extracted corpus never exercises the SigmaProp branch of the equality cost path
// (DataValueComparer.equalDataValues → equalSigmaBoolean → equalECPoint). That blind
// spot let sigma-rust ship a flat EQ_PRIM_COST (3) for SigmaProp equality where the JVM
// charges per-node MatchType + EQ_GroupElement (172) per EcPoint — a consensus-cost
// divergence (sigma-rust SANTA_SIGMAPROP_EQ_COST_VECTOR_NEEDED.md). We AUTHOR the vector:
// build `getVar[SigmaProp](1).get == getVar[SigmaProp](1).get` and bless value+cost from
// the JVM eval (EvalCore IS sigma-state 6.0.3 — the oracle; no spec-declared expected to
// cross-check, so the eval is canonical — the rebless philosophy).
//
// Three SigmaProp shapes span the structural cost classes: ProveDlog (1 EcPoint),
// ProveDHTuple (4), CAND (per-node recursion). The input is bound to context var 1 (the
// authored v2 convention; EvalCore.evalApplied); the tree reads it and compares to itself
// — a full structural walk (no short-circuit → deterministic max cost), nothing to
// constant-fold (the operands are context reads), and the comparer is the ONLY op that can
// diverge. Honest provenance ⇒ vectors/eval/v5/authored/. Mirrors AuthoredGetVarFromInput
// (manual AST) + AuthoredSerialize (authored-input bless).
// ─────────────────────────────────────────────────────────────────────────────

import scorex.util.encode.Base16

import io.circe.Json

import sigma.VersionContext
import sigma.ast.{EQ, ErgoTree, GetVar, OptionGet, SOption, SSigmaProp, SString, SType}
import sigma.ast.ErgoTree.{HeaderType, ZeroHeader}
import sigma.crypto.{CryptoConstants, EcPointType}
import sigma.data.{CAND, ProveDHTuple, ProveDlog, SigmaBoolean}

object AuthoredSigmaPropEq {

  /** Pinned target version: v5 (activated=2, ergoTree=2) = JitActivationVersion. SigmaProp
    * `==` is a v5/mainnet op and its eval cost is activation-invariant (the cross-version
    * finding), so v5 is the honest home — it groups with the v5 equality/sigma vectors. */
  val V2: Byte = VersionContext.JitActivationVersion

  val Source = "santa:authored-sigmaprop-eq"
  val Op     = "EQ of SigmaProp"

  /** `getVar[T](1).get == getVar[T](1).get`, serialized at v5. The input is bound to context
    * var 1 by EvalCore.evalApplied; reading it (twice, as two independent context reads) and
    * comparing exercises the equality comparer on a self-equal value. The getVar/OptionGet
    * overhead is identical across conformers (already-conformant ops), so the only thing that
    * can diverge in the total cost is the equality comparer — the divergence under test. */
  private def eqSelfTree(typeName: String, elemType: SType): (String, String) =
    VersionContext.withVersions(V2, V2) {
      val l = OptionGet(GetVar(1.toByte, SOption(elemType)))
      val r = OptionGet(GetVar(1.toByte, SOption(elemType)))
      val header: HeaderType = ErgoTree.setSizeBit(ErgoTree.headerWithVersion(ZeroHeader, V2))
      (s"{ getVar[$typeName](1).get == getVar[$typeName](1).get }",
       Base16.encode(sigma.santa.LenientErgoTree.serialize(header, EQ(l, r))))
    }

  /** SigmaProp input as the authored v2 SValue JSON (inverse of EvalCore's SigmaProp encode:
    * raw_hex = Base16(SigmaBoolean.serializer.toBytes(sigmaTree)) — the general serializer, so
    * every SigmaBoolean variant round-trips). */
  private def sigmaPropJson(sb: SigmaBoolean): Json =
    Json.obj("kind"    -> Json.fromString("SigmaProp"),
             "raw_hex" -> Json.fromString(Base16.encode(SigmaBoolean.serializer.toBytes(sb))))

  private val gen: EcPointType = CryptoConstants.dlogGroup.generator

  // The three SigmaProp structural shapes. Point values are irrelevant to EQ cost — it is
  // EcPoint-count / node-count driven, and `x == x` walks the whole tree regardless of the
  // operands being equal (equalECPoint charges per EcPoint compared, not conditionally).
  private val proveDlog    = ProveDlog(gen)                            // 1 EcPoint
  private val proveDHTuple = ProveDHTuple(gen, gen, gen, gen)          // 4 EcPoints (gv/hv/uv/vv)
  private val cand         = CAND(Seq(ProveDlog(gen), ProveDlog(gen))) // 2 children → per-node recursion

  /** op -> v2 envelope: one `==` tree, one entry per SigmaProp shape. */
  def extract(): Map[String, Json] = {
    val (script, treeHex) = eqSelfTree("SigmaProp", SSigmaProp)
    val inputs: Seq[(String, Json)] = Seq(
      "proveDlog"    -> sigmaPropJson(proveDlog),
      "proveDHTuple" -> sigmaPropJson(proveDHTuple),
      "CAND"         -> sigmaPropJson(cand))
    val entries = inputs.zipWithIndex.map { case ((name, in), i) =>
      SpecExtract.authoredEntry(Op, script, treeHex, s"$name#$i", in, V2)
    }
    Map(Op -> SpecExtract.authoredEnvelope(Op, entries, Source))
  }

  /** String-equality reachability probe (the prompt's secondary ask). SString has a
    * DataValueComparer arm and serializes via CoreDataSerializer, but no op/method produces a
    * runtime String and the typer only folds `"+"` on two String constants — so whether a
    * crafted (non-source) tree can carry String to EQ at *eval* is settled empirically here,
    * not by source-reading. Returns the raw evalApplied outcome for
    * `getVar[String](1).get == getVar[String](1).get` on a String input: Right((value,cost))
    * ⇒ reachable (a crafted-tree consensus concern → author it); Left(err) ⇒ eval rejects
    * SString ⇒ unreachable (document the verdict, no vector). */
  def stringEqProbe(): (String, Either[String, (Json, Long)]) = {
    val (script, treeHex) = eqSelfTree("String", SString)
    val input = Json.obj("kind" -> Json.fromString("String"), "value" -> Json.fromString("ab"))
    val (_, outcome) = EvalCore.evalApplied(treeHex, input, V2)
    (script, outcome)
  }

  /** Persist authored vectors to a staging dir (build artifact — copied into
    * vectors/eval/v5/authored/ once inspected). Fails loud on a slug collision. */
  def writeVectors(vectors: Map[String, Json], outDir: java.nio.file.Path): Unit = {
    java.nio.file.Files.createDirectories(outDir)
    val collisions = vectors.keys.groupBy(SpecExtract.slug).filter(_._2.size > 1)
    if (collisions.nonEmpty)
      sys.error("AuthoredSigmaPropEq.writeVectors: slug collision would silently drop entries — " +
        collisions.map { case (stem, ops) => s"'$stem.json' ← ${ops.mkString(" / ")}" }.mkString("; "))
    vectors.foreach { case (op, json) =>
      val path = outDir.resolve(s"${SpecExtract.slug(op)}.json")
      java.nio.file.Files.write(path, json.spaces2.getBytes(java.nio.charset.StandardCharsets.UTF_8))
    }
  }
}
