package santa.blesser

import scorex.util.bytesToId
import scorex.util.encode.Base16

import org.ergoplatform.{ErgoBox, ErgoLikeContext, ErgoLikeTransaction}
import org.ergoplatform.validation.ValidationRules

import sigma.{Colls, GroupElement, Header, PreHeader, VersionContext}
import sigma.ast.{ErgoTree, JitCost}
import sigma.crypto.CryptoConstants
import sigma.data.AvlTreeData
import sigma.interpreter.ContextExtension
import sigma.serialization.{GroupElementSerializer, SigmaSerializer}
import sigma.util.Extensions.EcpOps

import sigmastate.eval._ // CPreHeader
import sigmastate.interpreter.{CErgoTreeEvaluator, CostAccumulator}
import sigmastate.interpreter.CErgoTreeEvaluator.DefaultEvalSettings

/** SANTA JVM blesser — spike.
  *
  * Reads an eval-tier vector file (the `fixture-gen` shape: entries of
  * `{ tree_bytes_hex, expected_value_json, expected_cost, expected_error_code }`),
  * evaluates each tree's root expression through the canonical reference
  * interpreter under an explicit, recorded version, and prints the JVM-blessed
  * `(value, cost)` next to the committed fork-blessed values so divergences show.
  *
  * The differential tells us (a) the blesser works end-to-end and (b) the real
  * JVM output shape — what the canonical vector format should be built around.
  */
object Main {

  // --- minimal dummy context (replicated from sigma's test-scoped
  //     ErgoLikeContextTesting.dummy, since that helper isn't in the published jar) ---

  private val dummyPubkey: Array[Byte] =
    GroupElementSerializer.toBytes(CryptoConstants.dlogGroup.generator)

  private def dummyPreHeader(height: Int): PreHeader = CPreHeader(
    version = 0,
    parentId = Colls.emptyColl[Byte],
    timestamp = 3L,
    nBits = 0L,
    height = height,
    minerPk = GroupElementSerializer.parse(SigmaSerializer.startReader(dummyPubkey)).toGroupElement,
    votes = Colls.emptyColl[Byte]
  )

  private def dummyContext(tree: ErgoTree, activatedVersion: Byte): ErgoLikeContext = {
    val treeVersion = tree.version
    val selfBox = new ErgoBox(
      value = 1000000L,
      ergoTree = tree,
      transactionId = bytesToId(Array.fill(32)(0: Byte)),
      index = 0.toShort,
      creationHeight = 0
    )
    new ErgoLikeContext(
      lastBlockUtxoRoot = AvlTreeData.dummy,
      headers = Colls.emptyColl[Header],
      preHeader = dummyPreHeader(0),
      dataBoxes = IndexedSeq.empty,
      boxesToSpend = IndexedSeq(selfBox),
      spendingTransaction = ErgoLikeTransaction(IndexedSeq(), IndexedSeq()),
      selfIndex = 0,
      extension = ContextExtension.empty,
      validationSettings = ValidationRules.currentSettings,
      costLimit = DefaultEvalSettings.scriptCostLimitInEvaluator,
      initCost = 0L,
      activatedScriptVersion = activatedVersion
    ).withErgoTreeVersion(treeVersion)
  }

  /** Bless one vector: deserialize the tree, evaluate its root expr under an
    * explicit, recorded version, and return
    * (encoded value, jit cost, activatedVersion, ergoTreeVersion) — or
    * Left(error-class) if eval throws. */
  private def bless(treeBytesHex: String): Either[String, (String, Long, Byte, Byte)] =
    try {
      val bytes = Base16.decode(treeBytesHex).get
      val tree  = sigma.santa.LenientErgoTree.deserialize(bytes)
      // Bless under the current mainnet activation (v6.0) and the tree's own
      // version, recorded per vector so each is self-describing. activated >=
      // JitActivationVersion (2), so the cost is the JIT cost the fork records.
      val activated: Byte = VersionContext.MaxSupportedScriptVersion // 3 (v6.0)
      val treeVer:   Byte = tree.version
      val (rawValue, jitCost) = VersionContext.withVersions(activated, treeVer) {
        val ctx = dummyContext(tree, activated)
        // Read RAW jit cost (matches the fork's jit_cost_value) by keeping our own
        // accumulator — the companion `eval` scales it to block cost (÷10) on
        // return. Block cost stays derivable downstream as jitCost / 10.
        val acc = new CostAccumulator(
          initialCost = JitCost.fromBlockCost(Math.toIntExact(ctx.initCost)),
          costLimit   = Some(JitCost.fromBlockCost(Math.toIntExact(ctx.costLimit))))
        val (v, _blockCost) = CErgoTreeEvaluator.eval(
          ctx.toSigmaContext(), acc, tree.constants,
          tree.toProposition(replaceConstants = false), DefaultEvalSettings)
        (v, acc.totalCost.value)
      }
      Right((encodeValue(rawValue), jitCost.toLong, activated, treeVer))
    } catch {
      case t: Throwable => Left(s"${t.getClass.getSimpleName}: ${Option(t.getMessage).getOrElse("")}")
    }

  private def encodeValue(v: Any): String = v match {
    case g: GroupElement => "GroupElement:" + Base16.encode(g.getEncoded.toArray)
    case other           => s"${other.getClass.getSimpleName}:$other"
  }

  def main(args: Array[String]): Unit = {
    val path = args.headOption.getOrElse {
      System.err.println("usage: run <path-to-eval-vector.json>")
      sys.exit(2)
    }

    val raw = scala.io.Source.fromFile(path).mkString
    val doc = io.circe.parser.parse(raw).fold(e => sys.error(s"bad json: $e"), identity)

    val corpus  = doc.hcursor.get[String]("corpus").toOption.getOrElse("?")
    val entries = doc.hcursor.downField("entries").values.getOrElse(Vector.empty)

    println(s"corpus: $corpus   (${entries.size} entries)   blesser: sigma-state 6.0.3")
    println("=" * 78)

    for (e <- entries) {
      val c       = e.hcursor
      val name    = c.get[String]("name").toOption.getOrElse("?")
      val hex     = c.get[String]("tree_bytes_hex").toOption.getOrElse("")
      val expCost = c.get[Long]("expected_cost").toOption
      val expErr  = c.get[String]("expected_error_code").toOption
      val expVal  = c.downField("expected_value_json").focus.map(_.noSpaces).getOrElse("null")

      println(s"\n• $name")
      bless(hex) match {
        case Right((value, cost, activated, treeVer)) =>
          println(s"    JVM   ok      cost=$cost   [blessed @ activated=$activated tree=$treeVer]")
          println(s"      value=$value")
          if (expErr.isDefined)
            println(s"    fork  ERROR   ${expErr.get}")
          else {
            println(s"    fork  ok      cost=${expCost.getOrElse("?")}")
            println(s"      value=$expVal")
          }
          if (expErr.isDefined)            println("    ⚠ DIVERGENCE — JVM accepted, fork rejected")
          else if (expCost.contains(cost)) println(s"    ✓ cost matches ($cost)")
          else                             println(s"    ⚠ cost differs — JVM=$cost  fork=${expCost.getOrElse("?")}")
        case Left(err) =>
          println(s"    JVM   ERROR   $err")
          if (expErr.isDefined) {
            println(s"    fork  ERROR   ${expErr.get}")
            println("    ✓ both errored (coarse error-class match)")
          } else {
            println(s"    fork  ok      cost=${expCost.getOrElse("?")}")
            println("    ⚠ DIVERGENCE — JVM rejected, fork accepted")
          }
      }
    }
    println()
  }
}
