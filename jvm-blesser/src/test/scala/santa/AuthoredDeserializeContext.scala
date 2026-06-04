package santa

// ─────────────────────────────────────────────────────────────────────────────
// Authored `DeserializeContext` leniency vectors (v6/authored) — sigma-rust #879.
//
// `DeserializeContext(id, tpe)` is resolved by a whole-tree substitution pre-pass
// (`Interpreter.applyDeserializeContext` → `everywherebu(substDeserialize)`; sigma-rust's
// `Expr::substitute_deserialize`). The JVM's `substDeserialize` returns `None` — leaving the
// node in place — when the context-extension var `id` is ABSENT or present but NOT a
// `Coll[Byte]`; a leftover node only fails if the LIVE reduction actually evaluates it.
// sigma-rust eagerly ERRORED on absent / wrong-typed `id` even on a DEAD branch, forking the
// node off testnet block 111,927. Fixed: PR #879 / eni `46df20c0`.
//
// These distilled vectors exercise that leniency at the eval tier:
//   • dead-branch ACCEPT — `if (true) true else deserializeContext[Boolean](id)`: the DC node
//     sits on the never-taken branch; the JVM accepts (substDeserialize → None, then if(true)
//     skips it). A buggy eager-substitution errors here.
//   • live-path REJECT — root `deserializeContext[Boolean](id)`: a leftover node on the live
//     path errors at eval ("cannot be evaluated"). Guards against an over-fix that makes an
//     absent-var DeserializeContext wrongly succeed.
//
// EvalCore needs NO change to bless these: for absent/wrong-typed vars substitution returns
// None and leaves the node, so the JVM result is identical whether or not the pass runs
// (dead-branch → if(true) skips the DC; live-path → leftover DC errors). The substitution
// change lives in the RUNNERS (try_eval_with_deserialize), which is what makes them exercise
// the pass; the control already blesses/grades these correctly.
//
// Var convention: SANTA binds the entry input at ContextExtension var 1. The ABSENT cases read
// var 0 (never bound → absent); the WRONG-TYPE cases read var 1 (= the Int input → present, not
// Coll[Byte]). Results are Boolean (ACCEPT) / errored (REJECT) — trivially encodable. Mirrors
// AuthoredPowHitHof; reject entries via SpecExtract.authoredRejectEntry.
// ─────────────────────────────────────────────────────────────────────────────

import scorex.util.encode.Base16

import io.circe.Json

import sigma.VersionContext
import sigma.ast.{DeserializeContext, ErgoTree, If, SBoolean, SType, TrueLeaf, Value}
import sigma.ast.ErgoTree.{HeaderType, ZeroHeader}

object AuthoredDeserializeContext {

  /** Pinned target version: full v6 (activated=3, ergoTree=3). */
  val V3: Byte = VersionContext.V6SoftForkVersion

  val Source = "santa:authored-deserialize-context"
  val Op     = "DeserializeContext over absent/wrong-typed var"

  private val AbsentId: Byte = 0   // DeserializeContext over var 0 (never bound → absent)
  private val WrongId:  Byte = 1   // DeserializeContext over var 1 (= the Int input → wrong type)

  private def hex(script: String, root: Value[SType]): (String, String) =
    VersionContext.withVersions(V3, V3) {
      val header: HeaderType = ErgoTree.setSizeBit(ErgoTree.headerWithVersion(ZeroHeader, V3))
      (script, Base16.encode(sigma.santa.LenientErgoTree.serialize(header, root)))
    }

  /** Input bound at var 1: a plain Int. For the wrong-type cases it IS the wrong-typed var the
    * DeserializeContext reads; for the absent cases it's an unused placeholder (the tree reads
    * var 0). A plain Int is decodable + wire-encodable by every runner. */
  private def intInput: Json = Json.obj("kind" -> Json.fromString("Int"), "value" -> Json.fromInt(0))

  def extract(): Map[String, Json] = {
    val (deadAbsentS, deadAbsentH) = hex(
      "{ if (true) true else deserializeContext[Boolean](0) }",
      If(TrueLeaf, TrueLeaf, DeserializeContext(AbsentId, SBoolean)))
    val (deadWrongS, deadWrongH) = hex(
      "{ if (true) true else deserializeContext[Boolean](1) }",   // var 1 present as Int (wrong type)
      If(TrueLeaf, TrueLeaf, DeserializeContext(WrongId, SBoolean)))
    val (liveAbsentS, liveAbsentH) = hex(
      "{ deserializeContext[Boolean](0) }",
      DeserializeContext(AbsentId, SBoolean))
    val (liveWrongS, liveWrongH) = hex(
      "{ deserializeContext[Boolean](1) }",   // var 1 present as Int (wrong type)
      DeserializeContext(WrongId, SBoolean))

    val entries = Seq(
      SpecExtract.authoredEntry(Op, deadAbsentS, deadAbsentH, "dead-branch-absent#0", intInput, V3),
      SpecExtract.authoredEntry(Op, deadWrongS, deadWrongH, "dead-branch-wrong-type#1", intInput, V3),
      SpecExtract.authoredRejectEntry(Op, liveAbsentS, liveAbsentH, "live-absent#2", intInput, V3),
      SpecExtract.authoredRejectEntry(Op, liveWrongS, liveWrongH, "live-wrong-type#3", intInput, V3))
    Map(Op -> SpecExtract.authoredEnvelope(Op, entries, Source))
  }

  /** Persist authored vectors to a staging dir (build artifact — copied into
    * vectors/eval/v6/authored/ once inspected). Fails loud on a slug collision. */
  def writeVectors(vectors: Map[String, Json], outDir: java.nio.file.Path): Unit = {
    java.nio.file.Files.createDirectories(outDir)
    val collisions = vectors.keys.groupBy(SpecExtract.slug).filter(_._2.size > 1)
    if (collisions.nonEmpty)
      sys.error("AuthoredDeserializeContext.writeVectors: slug collision would silently drop entries — " +
        collisions.map { case (stem, ops) => s"'$stem.json' ← ${ops.mkString(" / ")}" }.mkString("; "))
    vectors.foreach { case (op, json) =>
      val path = outDir.resolve(s"${SpecExtract.slug(op)}.json")
      java.nio.file.Files.write(path, json.spaces2.getBytes(java.nio.charset.StandardCharsets.UTF_8))
    }
  }
}
