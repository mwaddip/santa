package santa

// ─────────────────────────────────────────────────────────────────────────────
// Authored vectors for sigma-rust's `santa-probe-numeric-v5-methods` (2026-11-07), which asked
// whether numeric method ids 6 (`toBytes`) and 7 (`toBits`) parse AND EVALUATE below tree version
// 3 — it expected yes, which would have been a spend-verdict divergence against sigma-rust's
// `min_version: V3` gate.
//
// MEASURED: NO. No numeric method call of ANY id parses below tree version 3. The probe's reading
// of `SNumericTypeMethods.v5Methods` was right about the LISTED table — `SIntMethods.methods` does
// return ids 1-7 below v3 — but the parser does not use that view. It uses `getMethodById`, via
// rule 1016 `CheckAndGetMethod`, and that returns None for every numeric id below v3:
//
//   etv=0  methods: 1:toByte 2:toShort 3:toInt 4:toLong 5:toBigInt 6:toBytes 7:toBits
//          getMethodById(1|5|6|7) = None
//   etv=3  getMethodById(6) = Some(toBytes)
//
// A runtime walk of every container shows the listed/by-id disagreement is confined to the six
// numeric containers, and there it is total: 7 listed, 0 parser-visible below v3. So sigma-rust's
// V3 gate for ids 1-7 matches the JVM, and there is nothing to fix.
//
// THE 0x6a TRAP. Below v3, building a numeric MethodCall AST and serializing it emits the owner
// type code 0x6a, because `getMethodByName` resolves to an `SNumericTypeMethods`-owned method
// there rather than an `SIntMethods`-owned one. 0x6a is **SGlobal** (106; our own coverage
// manifest has 106:1 = SigmaDslBuilder.groupGenerator). So below v3 the JVM WRITES a numeric
// method call it cannot READ BACK — it reparses as an SGlobal call. Only reachable by hand-built
// AST, since the compiler emits Upcast/Downcast for a numeric conversion, never a MethodCall.
//
// One consequence is a tree that parses at EVERY version, including 0: `db 6a 01` is SGlobal
// method 1 (`groupGenerator`) invoked on an Int receiver. Rule 1016 passes, because SGlobal really
// does have method 1 at every version, and it then dies in reflection
// ("object is not an instance of declaring class"). That is the only parseable-below-v3 tree in
// this area, and an implementation keying method lookup off the concrete receiver type refuses it.
//
// NOT RE-AUTHORED. `4:6` (Int.toBytes) and `4:7` (Int.toBits) are already exercised at v3 by
// `eval/v6/spec/Int_6.0_methods.json` — see docs/coverage/eval-coverage.json's method_index — so
// their v3 accepts would be duplication. `4:1` and `4:5` are not covered, and are here.
//
// Every tree below is a MEASURED hex rather than a re-serialized AST, precisely because the
// serializer's below-v3 output (0x6a) is not the encoding under test for the version gate.
// ─────────────────────────────────────────────────────────────────────────────

import io.circe.Json

object AuthoredNumericMethodCallGate {

  val V3: Byte = sigma.VersionContext.V6SoftForkVersion
  val OpEval = "Numeric.methodcall_ids"
  val OpWire = "ErgoTree.numeric_methodcall_version_gate"
  val Source = "santa:authored-numeric-methodcall-gate"

  private val input = Json.obj("kind" -> Json.fromString("Int"), "value" -> Json.fromInt(0))

  // Measured trees. Body `db <ownerTypeCode> <methodId> <obj>`, 0402 = IntConstant(1).
  val ToByteV3Sized    = "0b05db04010402"             // Int.toByte      id 1, owner 0x04
  val ToBigIntV3Sized  = "0b05db04050402"             // Int.toBigInt    id 5, owner 0x04
  val SGlobal1V3Sized  = "0b05db6a010402"             // SGlobal id 1 on an Int receiver
  val SGlobal1V0Unsized = "00db6a010402"
  val ToBytesV3Sized   = "0b0ad193b1db040604020408"   // sigmaProp(1.toBytes.size == 4)
  val ToBytesV0Sized   = "080ad193b1db040604020408"
  val ToBytesV0Unsized = "00d193b1db040604020408"
  val ToByteV0Unsized  = "00db04010402"

  // ── eval arm ────────────────────────────────────────────────────────────────

  private val NoHandler = "PARSES at tree version 3 and throws at eval: `%s` is in the " +
    "38-method parse-then-fail set — neither a FixedCost nor carrying a `%s_eval` handler, so " +
    "MethodCall.eval's non-FixedCost branch raises RuntimeException(\"Cannot find eval method\"). " +
    "sigma-rust refuses method id %d at PARSE instead, which the wire arm pins."

  def extractEval(): Map[String, Json] = {
    val entries = Seq(
      SpecExtract.authoredRejectEntryV(OpEval,
        "{ (1: Int).toByte }  // JVM: " + NoHandler.format("toByte", "toByte", 1),
        ToByteV3Sized, "int-tobyte-id1-errored#0", input, V3, 3),
      SpecExtract.authoredRejectEntryV(OpEval,
        "{ (1: Int).toBigInt }  // JVM: " + NoHandler.format("toBigInt", "toBigInt", 5) +
          " This is the id sigma-rust leaves out of its numeric table ALTOGETHER, so it refuses " +
          "this at parse at every version while the JVM parses it at v3 — a parse-side divergence, " +
          "the opposite direction to the spend one the probe expected.",
        ToBigIntV3Sized, "int-tobigint-id5-errored#1", input, V3, 3),
      SpecExtract.authoredRejectEntryV(OpEval,
        "{ SGlobal method 1 (groupGenerator) invoked on an Int receiver, db 6a 01 }  // JVM: " +
          "PARSES — 0x6a is SGlobal, which has method 1 at every tree version, so rule 1016 " +
          "passes — and then throws IllegalArgumentException \"object is not an instance of " +
          "declaring class\" in reflection, the receiver being an Integer and not a " +
          "SigmaDslBuilder. A DIFFERENT failure class from #0 and #1, and the tree the JVM's own " +
          "below-v3 serializer emits for a numeric method call.",
        SGlobal1V3Sized, "sglobal-id1-on-int-errored#2", input, V3, 3))
    Map(OpEval -> SpecExtract.authoredEnvelope(OpEval, entries, Source)
      .mapObject(_.add("blessed_by", Json.fromString("jvm:sigma-state-6.0.7"))))
  }

  // ── wire arm ────────────────────────────────────────────────────────────────

  private def version(etv: Int): Json =
    Json.obj("activated" -> Json.fromInt(V3.toInt), "ergoTree" -> Json.fromInt(etv))

  /** An identity round-trip entry; fails loud if the JVM does not return the input. */
  private def accept(name: String, description: String, in: String, etv: Int): Json = {
    val out = WireCanonicalize.canonicalize("ErgoTree", in, V3, etv.toByte)
    require(out == in, s"$name: an identity round-trip is the whole claim — in $in, out $out")
    Json.obj("name" -> Json.fromString(name), "kind" -> Json.fromString("ErgoTree"),
      "source" -> Json.fromString(Source), "description" -> Json.fromString(description),
      "bytes_hex" -> Json.fromString(in), "version" -> version(etv))
  }

  /** A reject entry; fails loud if the JVM parses it after all. */
  private def reject(name: String, description: String, in: String, etv: Int): Json = {
    val parsed = try { WireCanonicalize.canonicalize("ErgoTree", in, V3, etv.toByte); true }
      catch { case _: Throwable => false }
    require(!parsed, s"$name: expected the JVM to REJECT $in at ErgoTree v$etv, but it parsed")
    Json.obj("name" -> Json.fromString(name), "kind" -> Json.fromString("ErgoTree"),
      "source" -> Json.fromString(Source), "description" -> Json.fromString(description),
      "bytes_hex" -> Json.fromString(in), "error" -> Json.fromString("errored"),
      "version" -> version(etv))
  }

  private val Gate = "The version gate: rule 1016 CheckAndGetMethod resolves a method id through " +
    "getMethodById, which returns None for EVERY numeric id below tree version 3 — 7 ids listed in " +
    "SIntMethods.methods, 0 parser-visible. "

  def extractWire(): Map[String, Json] = {
    val entries = Seq(
      accept("tobytes-v3-sized#0",
        "`sigmaProp((1: Int).toBytes.size == 4)`, id 6 at tree version 3, where the method IS " +
        "parser-visible: parses and round-trips byte-identically (and evaluates to SigmaProp d3 at " +
        "cost 51 — already pinned at v3 by eval/v6/spec/Int_6.0_methods.json). The upper side of " +
        "the gate, and the control for the two entries below.",
        ToBytesV3Sized, 3),
      accept("tobytes-v0-sized-degrades#1",
        Gate + "At v0 this tree is size-flagged, so the failure is SOFT: the JVM degrades it to an " +
        "unparsed tree and ECHOES these bytes, which is why this is an identity entry and not a " +
        "reject. A byte round-trip cannot tell that degrade from a parse — #2 is what can.",
        ToBytesV0Sized, 0),
      reject("tobytes-v0-unsized-reject#2",
        Gate + "UNSIZED at v0, so the failure cannot degrade and the JVM REJECTS outright. This is " +
        "the entry that actually witnesses the gate. sigma-rust's min_version V3 makes it reject " +
        "here too, so this one AGREES — it is a regression guard for the gate, not a divergence.",
        ToBytesV0Unsized, 0),
      reject("tobyte-v0-unsized-reject#3",
        Gate + "The same gate on id 1 rather than id 6, unsized at v0: rejected. Together with #2 " +
        "this shows the gate is not specific to the two ids the probe asked about — it covers all " +
        "of 1-7.",
        ToByteV0Unsized, 0),
      accept("tobigint-v3-sized#4",
        "`(1: Int).toBigInt`, id 5 at tree version 3: the JVM PARSES it (identity) and then throws " +
        "at eval. sigma-rust omits id 5 from its numeric table at every version and so refuses it " +
        "at parse — making this the one genuine parse-side divergence of this round. The eval arm " +
        "carries the throw.",
        ToBigIntV3Sized, 3),
      accept("sglobal-id1-on-int-v0-unsized#5",
        "`db 6a 01` — SGlobal method 1 (groupGenerator) invoked on an Int receiver — UNSIZED at " +
        "tree version 0, and it PARSES: 0x6a is SGlobal, which has method 1 at every version, so " +
        "rule 1016 passes where every numeric id fails. Identity round-trip. The only " +
        "parseable-below-v3 tree in this area, and the shape the JVM's own serializer emits for a " +
        "below-v3 numeric method call — so the JVM writes a numeric call that reads back as an " +
        "SGlobal one. An implementation keying the lookup off the concrete receiver type rejects it.",
        SGlobal1V0Unsized, 0),
      accept("sglobal-id1-on-int-v3-sized#6",
        "The same `db 6a 01` tree at version 3, size-flagged: also parses, also identity. The pair " +
        "with #5 shows this one is NOT version-gated, unlike every numeric id — which is what makes " +
        "it the counterexample that keeps the gate claim honest.",
        SGlobal1V3Sized, 3))
    Map(OpWire -> Json.obj(
      "schema"     -> Json.fromString("santa-wire/v1"),
      "op"         -> Json.fromString(OpWire),
      "blessed_by" -> Json.fromString("jvm:sigma-state-6.0.7"),
      "entries"    -> Json.arr(entries: _*)))
  }

  def writeVectors(evalDir: java.nio.file.Path, wireDir: java.nio.file.Path): Unit = {
    SpecExtract.writeStaging("AuthoredNumericMethodCallGate-eval", extractEval(), evalDir)
    SpecExtract.writeStaging("AuthoredNumericMethodCallGate-wire", extractWire(), wireDir)
  }
}
