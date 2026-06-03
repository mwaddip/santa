package santa

import scorex.util.encode.Base16
import io.circe.Json
import sigma.VersionContext
import sigma.ast.{ByteConstant, Context, ErgoTree, MethodCall, SBoolean, SContextMethods, SType, ShortConstant}
import sigma.ast.ErgoTree.{HeaderType, ZeroHeader}

object AuthoredGetVarFromInput {
  val V3: Byte = VersionContext.V6SoftForkVersion
  val Source = "santa:authored-getvarfrominput"
  val Op = "Context.getVarFromInput"

  /** Standalone `getVarFromInput[Boolean](inputIdx, varId)` tree, serialized at v6. */
  private def tree(inputIdx: Short, varId: Byte): (String, String) =
    VersionContext.withVersions(V3, V3) {
      val mc = MethodCall(Context, SContextMethods.getVarFromInputMethod,
        IndexedSeq(ShortConstant(inputIdx), ByteConstant(varId)), Map(SType.tT -> SBoolean))
      val header: HeaderType = ErgoTree.setSizeBit(ErgoTree.headerWithVersion(ZeroHeader, V3))
      (s"{ getVarFromInput[Boolean]($inputIdx, $varId) }",
       Base16.encode(sigma.santa.LenientErgoTree.serialize(header, mc)))
    }

  private def boolJson(b: Boolean) = Json.obj("kind" -> Json.fromString("Boolean"), "value" -> Json.fromBoolean(b))
  private def intJson(n: Int)      = Json.obj("kind" -> Json.fromString("Int"), "value" -> Json.fromInt(n))

  /** op -> v3 envelope. Scenarios cover the getVarFromInput behaviors at input 0 / var 11. */
  def extract(): Map[String, Json] = {
    val (script, treeHex) = tree(0, 11)
    val scenarios: Seq[(String, Seq[Map[Byte, Json]])] = Seq(
      "present-true"  -> Seq(Map(11.toByte -> boolJson(true))),
      "present-false" -> Seq(Map(11.toByte -> boolJson(false))),
      "absent"        -> Seq(Map.empty[Byte, Json]),
      "wrong-type"    -> Seq(Map(11.toByte -> intJson(5)))
    )
    val entries = scenarios.zipWithIndex.map { case ((name, exts), i) =>
      SpecExtract.authoredV3Entry(Op, script, treeHex, s"$name#$i", exts, V3)
    }
    Map(Op -> SpecExtract.authoredV3Envelope(Op, entries, Source))
  }
}
