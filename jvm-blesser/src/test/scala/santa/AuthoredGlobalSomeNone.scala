package santa

// ─────────────────────────────────────────────────────────────────────────────
// Authored `Global.some` (106:9) + `Global.none` (106:10) vectors (v6/authored).
//
// Both methods are V3-gated — SGlobalMethods.getMethods() returns them ONLY when
// VersionContext.current.isV3OrLaterErgoTreeVersion. Calling
// SGlobalMethods.getMethodByName("some") outside a VersionContext.withVersions(3,3)
// block will throw (probe-verified this session). The VersionContext wrap must
// cover BOTH the method lookup AND the tree construction.
//
// someMethod (methodId=9):  SFunc(Array(SGlobal, tT), SOption(tT), Array(paramT))
//   FixedCost(JitCost(5))   — type-var key in the Map is STypeVar("T")
// noneMethod (methodId=10): SFunc(Array(SGlobal), SOption(tT), Array(paramT))
//   FixedCost(JitCost(5))   — PropertyCall-shaped (no args, explicit T in type map)
//
// OptionIsDefined (opCode 118): FixedCost(JitCost(10))
// The isDefined entries compose the MethodCall result directly — no separate
// script-level wiring needed; the tree is closed so oracle sees it as constant.
//
// 4 CLOSED entries, single op / single output file. Dummy Int-0 input at var 1
// (authoredEntry requires one; closed trees never read it).
// ─────────────────────────────────────────────────────────────────────────────

import io.circe.Json
import scorex.util.encode.Base16
import sigma.VersionContext
import sigma.ast.{ErgoTree, Global, IntConstant, MethodCall, OptionIsDefined, SGlobalMethods,
  SInt, SOption, SType, STypeVar, Value}
import sigma.ast.ErgoTree.{HeaderType, ZeroHeader}

object AuthoredGlobalSomeNone {

  /** Pinned target version: full v6 (activated=3, ergoTree=3).
    * Global.some / Global.none are V3-gated. */
  val V3: Byte = VersionContext.V6SoftForkVersion

  val Op     = "Global.some_none"
  val Source = "santa:authored-global-some-none"

  /** Ignored dummy input at var 1 — closed trees; authoredEntry requires one. */
  private val dummyInput: Json =
    Json.obj("kind" -> Json.fromString("Int"), "value" -> Json.fromInt(0))

  /** Serialize a closed root value at v6 via the lenient (non-SigmaProp-root) encoder. */
  private def hex(root: Value[_ <: SType]): String = {
    val header: HeaderType = ErgoTree.setSizeBit(ErgoTree.headerWithVersion(ZeroHeader, V3))
    Base16.encode(sigma.santa.LenientErgoTree.serialize(header, root))
  }

  // SGlobalMethods' method list is version-context-dependent; getMethodByName("some") throws
  // outside withVersions(3,3). The VersionContext wrap covers method lookup +
  // tree construction + serialization to ensure the constants are encoded at v6.

  private lazy val (someIntTree, noneIntTree, someIsDefTree, noneIsDefTree) =
    VersionContext.withVersions(V3, V3) {
      val someMethod = SGlobalMethods.getMethodByName("some")
      val noneMethod = SGlobalMethods.getMethodByName("none")

      // Global.some[Int](5) → Option(5)
      val someInt: Value[SOption[SInt.type]] =
        MethodCall.typed[Value[SOption[SInt.type]]](
          Global,
          someMethod,
          IndexedSeq(IntConstant(5)),
          Map(STypeVar("T") -> SInt)
        )

      // Global.none[Int]() → None — PropertyCall-shaped (no args), explicit T in type map
      val noneInt: Value[SOption[SInt.type]] =
        MethodCall.typed[Value[SOption[SInt.type]]](
          Global,
          noneMethod,
          IndexedSeq.empty,
          Map(STypeVar("T") -> SInt)
        )

      // Global.some[Int](5).isDefined → true
      val someIsDef: Value[sigma.ast.SBoolean.type] =
        OptionIsDefined(someInt)

      // Global.none[Int].isDefined → false
      val noneIsDef: Value[sigma.ast.SBoolean.type] =
        OptionIsDefined(noneInt)

      (hex(someInt), hex(noneInt), hex(someIsDef), hex(noneIsDef))
    }

  def extract(): Map[String, Json] = {
    val entries = Seq(
      ("some#int",
       "{ Global.some[Int](5) }",
       someIntTree),
      ("none#int",
       "{ Global.none[Int] }",
       noneIntTree),
      ("some#isDefined",
       "{ Global.some[Int](5).isDefined }",
       someIsDefTree),
      ("none#isDefined",
       "{ Global.none[Int].isDefined }",
       noneIsDefTree)
    ).map { case (name, script, treeHex) =>
      SpecExtract.authoredEntry(Op, script, treeHex, name, dummyInput, V3)
    }
    Map(Op -> SpecExtract.authoredEnvelope(Op, entries, Source))
  }

  def writeVectors(outDir: java.nio.file.Path): Unit =
    SpecExtract.writeStaging("AuthoredGlobalSomeNone", extract(), outDir)
}
