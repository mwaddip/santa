package santa

import io.circe.Json
import scorex.util.bytesToId
import scorex.util.encode.Base16
import sigma.VersionContext
import sigma.ast._
import sigma.ast.syntax.SValue
import sigma.crypto.CryptoConstants
import sigma.data.ProveDlog
import sigma.serialization.SigmaSerializer

/** Surface verification for the full-context EvalCore seam (`evalFullContext`): build an
  * envelope with KNOWN context pieces and check each `CONTEXT.*` surface returns the real
  * value — proving `fullContext` wires every ErgoLikeContext field (vs the dummy path).
  * Self-constructed (no node): expected values are known by construction.
  *
  * Walker JVM-oracle workstream, 2026-06-14 (prompts/walker-jvm-oracle-santa.md). */
class EvalFullContextTest extends munit.FunSuite {

  private val V6: Byte = VersionContext.V6SoftForkVersion

  private val anyTree: ErgoTree =
    ErgoTree.fromSigmaBoolean(ProveDlog(CryptoConstants.dlogGroup.generator))

  private val treeHeaderV6: ErgoTree.HeaderType =
    ErgoTree.setSizeBit(ErgoTree.headerWithVersion(ErgoTree.ZeroHeader, V6))

  private def treeHex(root: SValue): String =
    Base16.encode(sigma.santa.LenientErgoTree.serialize(treeHeaderV6, root))

  private def box(value: Long, idByte: Byte): org.ergoplatform.ErgoBox =
    new org.ergoplatform.ErgoBox(
      value = value, ergoTree = anyTree,
      transactionId = bytesToId(Array.fill(32)(idByte)), index = 0.toShort, creationHeight = 0)

  private def boxHex(b: org.ergoplatform.ErgoBox): String = {
    val w = SigmaSerializer.startWriter()
    org.ergoplatform.ErgoBox.sigmaSerializer.serialize(b, w)
    Base16.encode(w.toBytes)
  }

  // Known envelope pieces.
  private val selfBox = box(999L, 1.toByte)
  private val input2  = box(500L, 2.toByte)
  private val outBox  = box(123L, 3.toByte)
  private val dataBox = box(777L, 4.toByte)

  private val inputsHex     = Seq(boxHex(selfBox), boxHex(input2))
  private val dataInputsHex = Seq(boxHex(dataBox))
  private val outputsHex    = Seq(boxHex(outBox))
  private val preHeaderHex  = PreHeaderCodec.encodeHex(PreHeaderCodec.Fields(
    version = 3.toByte, parentId = (0 until 32).map(_.toByte).toArray,
    timestamp = 9999999999999999L, nBits = 486604799L, height = 12345,
    minerPk = Array(0x02.toByte) ++ Array.fill(32)(0xaa.toByte), votes = Array(0, 0, 0).map(_.toByte)))
  private val extensionJson: Map[Int, Json] = Map(5 -> EvalCore.valueToJson(77))

  private def evalSurface(root: SValue): Json = {
    val (_, res) = EvalCore.evalFullContext(
      treeHex(root), selfIndex = 0, inputsHex, dataInputsHex, outputsHex,
      headersHex = Seq.empty, preHeaderHex, extensionJson,
      lastBlockUtxoRootHex = None, activated = V6)
    res.fold(err => fail(s"eval errored: $err"), { case (json, _cost) => json })
  }

  test("HEIGHT reads the real preHeader.height") {
    assertEquals(evalSurface(Height).noSpaces, """{"kind":"Int","value":12345}""")
  }

  test("SELF.value reads the self box at selfIndex") {
    assertEquals(evalSurface(ExtractAmount(Self)).noSpaces, """{"kind":"Long","value":"999"}""")
  }

  test("INPUTS.size reads boxesToSpend") {
    assertEquals(evalSurface(SizeOf(Inputs)).noSpaces, """{"kind":"Int","value":2}""")
  }

  test("OUTPUTS.size reads the spending-tx outputs") {
    assertEquals(evalSurface(SizeOf(Outputs)).noSpaces, """{"kind":"Int","value":1}""")
  }

  test("getVar(5) reads the SELF ContextExtension") {
    assertEquals(evalSurface(OptionGet(GetVar(5.toByte, SInt))).noSpaces, """{"kind":"Int","value":77}""")
  }

  // req 2: SELF.bytes is the EXACT retained input slice (parse-and-hold), so the eval
  // result round-trips byte-identically to the input hex — the on-chain id basis.
  test("SELF.bytes returns the exact retained input bytes (req 2 id basis)") {
    assertEquals(collOfByteHex(evalSurface(ExtractBytes(Self))), inputsHex(0))
  }

  private def collOfByteHex(j: Json): String = {
    val items = j.hcursor.downField("items").as[List[Json]].getOrElse(Nil)
    Base16.encode(items.map(_.hcursor.downField("value").as[Int].getOrElse(0).toByte).toArray)
  }
}
