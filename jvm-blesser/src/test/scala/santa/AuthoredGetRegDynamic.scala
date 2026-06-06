package santa

// ─────────────────────────────────────────────────────────────────────────────
// Authored `Box.getReg` MethodCall (typeId=99, methodId=19) vectors with a
// DYNAMIC register index — v6/authored — ergots vector request P7a-2.
//
// `getRegMethodV6` (SBoxMethods, id=19, V3-gated) is defined as:
//   SFunc(Array(SBox, SInt), SOption(tT), Array(paramT))
// .withIRInfo(MethodCallIrBuilder, ...)
//
// LanguageSpecificationV6.scala:1286 (spec line 1286) confirms the spec NEVER
// exercises this MethodCall path: it builds `ExtractRegisterAs(ValUse(1, SBox),
// ErgoBox.R0, SOption(SLong))` directly — constant-index ExtractRegisterAs, not a
// MethodCall node. A compiler-lowered tree would also produce ExtractRegisterAs; only
// a MANUALLY-CONSTRUCTED MethodCall tree carries the 99:19 node through
// serialize→deserialize→eval.
//
// Dynamic index: `OptionGet(GetVar(1, SOption(SInt)))` — the index comes from context
// var 1 at runtime, so no conformer can fold/constant-propagate the index away. The
// SELF box (in the eval context) carries the register data via additionalRegisters.
//
// Three arms, all using the same tree `{ SELF.getReg[Long](getVar[Int](1).get) }`:
//
//   Arm 1 — ACCEPT:   SELF has R4 = Long 7; var 1 = 4 → Some(7L), cost.
//   Arm 2 — REJECT:   SELF has R4 = Long 7; var 1 = 4, but type arg is SInt not SLong
//                      → InvalidType thrown → reject (errored, no cost).
//                      Uses a separate tree `{ SELF.getReg[Int](getVar[Int](1).get) }`.
//   Arm 3 — None arm: dynamic index resolves to an absent/out-of-range register.
//                      Sub-cases:
//                        3a: index = 5 (R5 absent) → CBox.getReg returns None
//                        3b: index = 10 (> maxRegisters=9) → CBox.getReg: i>=registers.length → None
//                      Both observed to return None (not error) — confirmed by oracle.
//
// Schema: santa-eval/v4 (SELF box carries selfRegisters, var 1 = the index).
// Provenance: vectors/eval/v6/authored/Box.getReg_dynamic_index.json.
// Mirrors AuthoredDeserializeContext (mixed accept+reject in one envelope).
// ─────────────────────────────────────────────────────────────────────────────

import scorex.util.encode.Base16

import io.circe.Json

import sigma.VersionContext
import sigma.ast.{
  ErgoTree, GetVar, MethodCall, OptionGet, SBoxMethods, SInt, SLong, SOption, SType, Self, Value
}
import sigma.ast.ErgoTree.{HeaderType, ZeroHeader}
import sigma.ast.SType.tT

object AuthoredGetRegDynamic {

  /** Pinned target version: full v6 (activated=3, ergoTree=3). getRegMethodV6 is V3-gated. */
  val V3: Byte = VersionContext.V6SoftForkVersion

  val Source = "santa:authored-get-reg-dynamic"
  val Op     = "Box.getReg dynamic index MethodCall"

  /** Build the serialized `{ SELF.getReg[T](getVar[Int](1).get) }` tree for the given type arg.
    * The index `getVar[Int](1).get` is the dynamic (non-constant) register index from context var 1.
    * Returns (script string, tree_bytes_hex). */
  private def getRegTree(typeName: String, typeArg: SType): (String, String) =
    VersionContext.withVersions(V3, V3) {
      val idxExpr: Value[SInt.type] = OptionGet(GetVar(1.toByte, SOption(SInt)))
      val mc = MethodCall.typed[Value[SOption[typeArg.type]]](
        Self, SBoxMethods.getRegMethodV6,
        IndexedSeq(idxExpr), Map(tT -> typeArg))
      val header: HeaderType = ErgoTree.setSizeBit(ErgoTree.headerWithVersion(ZeroHeader, V3))
      (s"{ SELF.getReg[$typeName](getVar[Int](1).get) }",
       Base16.encode(sigma.santa.LenientErgoTree.serialize(header, mc)))
    }

  /** SValue JSON for a Long. */
  private def longJson(v: Long): Json =
    Json.obj("kind" -> Json.fromString("Long"), "value" -> Json.fromString(v.toString))

  /** SValue JSON for an Int (the register index). */
  private def intJson(v: Int): Json =
    Json.obj("kind" -> Json.fromString("Int"), "value" -> Json.fromInt(v))

  /** Self registers for arm 1/2/3a: R4 = Long 7. Arm 3b uses the same registers (R4 set, R5 absent). */
  private val r4Long7Registers: Map[Int, Json] = Map(4 -> longJson(7L))

  /** All entries under one op key, mixed accept/reject (mirrors DeserializeContext). */
  def extract(): Map[String, Json] = {
    // Tree for arms 1, 3a, 3b: SELF.getReg[Long](getVar[Int](1).get)
    val (scriptLong, hexLong) = getRegTree("Long", SLong)
    // Tree for arm 2 (wrong type): SELF.getReg[Int](getVar[Int](1).get) — type arg is SInt, not SLong
    val (scriptInt, hexInt) = getRegTree("Int", SInt)

    val entries = Seq(
      // Arm 1: R4=Long(7), index=4 → Some(7L)
      SpecExtract.authoredV4Entry(Op, scriptLong, hexLong,
        "accept-r4-long#0", r4Long7Registers, intJson(4), V3),
      // Arm 2: R4=Long(7), index=4, but getReg[Int] → InvalidType → reject
      SpecExtract.authoredV4RejectEntry(Op, scriptInt, hexInt,
        "reject-wrong-type#1", r4Long7Registers, intJson(4), V3),
      // Arm 3a: R5 absent, index=5 → None (not error)
      SpecExtract.authoredV4Entry(Op, scriptLong, hexLong,
        "none-absent-r5#2", r4Long7Registers, intJson(5), V3),
      // Arm 3b: index=10 >= maxRegisters(10) → None (not error)
      SpecExtract.authoredV4Entry(Op, scriptLong, hexLong,
        "none-out-of-range-10#3", r4Long7Registers, intJson(10), V3)
    )
    Map(Op -> SpecExtract.authoredV4Envelope(Op, entries, Source))
  }

  /** Persist authored vectors to a staging dir (build artifact — copied into
    * vectors/eval/v6/authored/ once inspected). Fails loud on a slug collision. */
  def writeVectors(vectors: Map[String, Json], outDir: java.nio.file.Path): Unit = {
    java.nio.file.Files.createDirectories(outDir)
    val collisions = vectors.keys.groupBy(SpecExtract.slug).filter(_._2.size > 1)
    if (collisions.nonEmpty)
      sys.error("AuthoredGetRegDynamic.writeVectors: slug collision — " +
        collisions.map { case (stem, ops) => s"'$stem.json' ← ${ops.mkString(" / ")}" }.mkString("; "))
    vectors.foreach { case (op, json) =>
      val path = outDir.resolve(s"${SpecExtract.slug(op)}.json")
      java.nio.file.Files.write(path, json.spaces2.getBytes(java.nio.charset.StandardCharsets.UTF_8))
    }
  }
}
