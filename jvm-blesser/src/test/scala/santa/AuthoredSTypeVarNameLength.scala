package santa

// ─────────────────────────────────────────────────────────────────────────────
// Authored STypeVar name-length domain witness (ergots version-signedness ask 23).
//
// The JVM `TypeSerializer.deserialize` reads `nameLength = r.getUByte()` (UNSIGNED,
// UNBOUNDED) then `STypeVar(new String(getBytes(nameLength)))` — so name length 0 yields
// `STypeVar("")` and 255 is accepted. Serialize emits the length via `putUByte`, so only
// > 255 is unrepresentable. ergots AND sigma-rust (via `BoundedVec<1, 254>`) enforced a
// `[1, 254]` bound — OVER-REJECTING both 0 and 255. This is the REVERSE direction of asks
// 21/22: convergence is by RELAXING the bound, not adding a reject.
//
// Oracle-confirmed (spike, sigma-state 6.0.3):
//   name length 0   (STypeVar(""))        -> accepts -> Int 5 @ cost 13
//   name length 255 (STypeVar of 255 'a') -> accepts -> Int 5 @ cost 13
//   name length 256                        -> serialize-side IllegalArgumentException
//                                             ("name is too long") — NOT a deserializable input
//                                             (no wire bytes), so it is document-only, not vectored.
//
// Construction mirrors AuthoredFunDefTypeVar's bound-never-applied accept floor (applying a
// type-var lambda throws; binding it is fine), varying only the type-var NAME. v6 (type-var
// serialization needs v3). Gradeable boundary = 0 + 255 ACCEPT (both red on a [1,254]-bounded impl).
// ─────────────────────────────────────────────────────────────────────────────

import scorex.util.encode.Base16

import io.circe.Json

import sigma.VersionContext
import sigma.ast.{BlockValue, ErgoTree, FuncValue, IntConstant, STypeVar, SType, ValDef, ValUse, Value}
import sigma.ast.ErgoTree.{HeaderType, ZeroHeader}

object AuthoredSTypeVarNameLength {

  /** Pinned target: full v6 (activated=3, ergoTree=3) — type-var serialization needs v3. */
  val V3: Byte = VersionContext.V6SoftForkVersion

  val Source = "santa:authored-stypevar-name-length"
  val Op     = "STypeVar.name_length_bound"

  /** Closed tree (bound-never-applied) → the dummy input is ignored. */
  private val dummyInput: Json = Json.obj("kind" -> Json.fromString("Int"), "value" -> Json.fromInt(0))

  /** `{ val f[T] = {(x: T) => x}; 5 }` with the type-var name = `name` — bound, never applied. */
  private def boundNeverApplied(name: String): Value[SType] = {
    val tv = STypeVar(name)
    val poly = FuncValue(IndexedSeq(2 -> tv), ValUse(2, tv))
    BlockValue(IndexedSeq(ValDef(1, Seq(tv), poly)), IntConstant(5))
  }

  private def hexV3(root: Value[SType]): String =
    VersionContext.withVersions(V3, V3) {
      val header: HeaderType = ErgoTree.setSizeBit(ErgoTree.headerWithVersion(ZeroHeader, V3))
      Base16.encode(sigma.santa.LenientErgoTree.serialize(header, root))
    }

  def extract(): Map[String, Json] = {
    val entries = Seq(
      SpecExtract.authoredEntry(Op,
        "{ val f[T] = {(x: T) => x}; 5 }  // STypeVar name length 0 (T = empty): JVM TypeSerializer reads nameLength via UNSIGNED getUByte (unbounded) -> STypeVar(\"\") accepts -> Int 5. ergots/sigma-rust enforced BoundedVec[1,254] and OVER-REJECTED length 0 (ask 23).",
        hexV3(boundNeverApplied("")), "name-length-0-accept#0", dummyInput, V3),
      SpecExtract.authoredEntry(Op,
        "{ val f[T] = {(x: T) => x}; 5 }  // STypeVar name length 255 (T = 255 chars): getUByte accepts the u8 max -> Int 5. ergots/sigma-rust OVER-REJECTED 255 (BoundedVec upper bound 254). Length 256 is serialize-side unrepresentable (no deserializable input) -> not vectored.",
        hexV3(boundNeverApplied("a" * 255)), "name-length-255-accept#1", dummyInput, V3))
    Map(Op -> SpecExtract.authoredEnvelope(Op, entries, Source))
  }

  def writeVectors(outDir: java.nio.file.Path): Unit =
    SpecExtract.writeStaging("AuthoredSTypeVarNameLength", extract(), outDir)
}
