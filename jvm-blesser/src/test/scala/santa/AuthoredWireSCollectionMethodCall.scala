package santa

// ─────────────────────────────────────────────────────────────────────────────
// Authored wire vectors for sigma-rust's `santa-probe-scollection-methods-1-10` (2026-11-07):
// a MethodCall (PropertyCall at zero arguments) to SCollection method ids 1-10 PARSES on the JVM
// and round-trips byte-identically — at tree version 0 and 3, size-flagged and unsized alike.
//
// The JVM's v5 SCollection table holds ids 1-10 (size 1, getOrElse 2, map 3, exists 4, fold 5,
// forall 6, slice 7, filter 8, append 9, apply 10) and `CheckAndGetMethod` resolves every one.
// sigma-rust's table starts at id 14, so it refuses all ten at parse with `UnknownMethodId` —
// rule 1011's class, which means a size-flagged tree DEGRADES and an unsized one REJECTS. Both
// states are entries here, because they are the two ways an implementation fails this, and a
// degrade is invisible to a byte round-trip unless the unsized twin sits beside it.
//
// ONE OF THE FOUR COMBINATIONS DOES NOT EXIST. The probe asked for (tree version 0, 3) x
// (unsized, size-flagged). At version 3 the size bit is FORCED (`ErgoTree.scala:104` — every tree
// above version 0 carries it), so asking for an unsized v3 tree silently yields the size-flagged
// one: measured byte-identical, `0b0adb0c01...` both ways. The unsized form is therefore only
// representable at tree VERSION 0. That makes the version dimension load-bearing rather than
// decorative — not because method resolution varies with it (it does not), but because version 0
// is the only place the REJECT arm can be expressed at all. The same constraint shaped
// `wire/v6/authored/*.zero_width_collection.json` earlier the same day.
//
// So the layout is: ten at (v3, size-flagged), ten at (v0, unsized), and one (v0, size-flagged)
// control proving the version alone changes nothing — the size flag is what does.
//
// NOT REWRITTEN AT PARSE. `getOrElse` carries a real `.withIRInfo` that calls `builder.mkByIndex`,
// and `size`/`apply` carry `.withInfo(SizeOf, ...)` / `.withInfo(ByIndex, ...)`, so a plausible
// reading is that these nodes are rewritten into their dedicated forms when read. They are not:
// every entry here round-trips to ITSELF, and a rewrite would change the canonical bytes. The IR
// builders are on the compiler/builder path, not the deserializer's. That is what makes these
// identity entries rather than `expected_bytes_hex` ones.
//
// The eval half — only four of the ten actually evaluate — is
// `eval/v6/authored/Coll.methodcall_v5_ids.json`. The two arms separate the two failure modes:
// refusing the method id at PARSE goes red here; evaluating a method the JVM cannot goes red there.
// ─────────────────────────────────────────────────────────────────────────────

import io.circe.Json

object AuthoredWireSCollectionMethodCall {

  val V3: Byte = sigma.VersionContext.V6SoftForkVersion
  val V0: Byte = 0
  val Op     = "ErgoTree.scollection_methodcall_v5_ids"
  val Source = "santa:authored-wire-scollection-methodcall"

  import AuthoredEvalSCollectionMethodCall.{callAppend, callApply, callExists, callFilter,
    callFoldTwoArg, callForall, callGetOrElseOut, callMap, callSize, callSlice, treeHex}

  /** (id, method name, the call, what it is) — one representative call per method id. */
  private val Methods: Seq[(Int, String, sigma.ast.Value[sigma.ast.SType], String)] = Seq(
    (1,  "size",      callSize,         "`Coll(1, 2).size` — zero arguments, so the JVM writes a PropertyCall"),
    (2,  "getOrElse", callGetOrElseOut, "`Coll(1, 2).getOrElse(5, 9)`"),
    (3,  "map",       callMap,          "`Coll(1, 2).map({ (x: Int) => x + 1 })`"),
    (4,  "exists",    callExists,       "`Coll(1, 2).exists({ (x: Int) => x > 1 })`"),
    (5,  "fold",      callFoldTwoArg,   "`Coll(1, 2).fold(0, { (acc: Int, x: Int) => acc + x })` — the " +
                                        "two-argument operator FoldMethod declares"),
    (6,  "forall",    callForall,       "`Coll(1, 2).forall({ (x: Int) => x > 0 })`"),
    (7,  "slice",     callSlice,        "`Coll(1, 2, 3).slice(1, 3)`"),
    (8,  "filter",    callFilter,       "`Coll(1, 2).filter({ (x: Int) => x > 1 })`"),
    (9,  "append",    callAppend,       "`Coll(1).append(Coll(2))`"),
    (10, "apply",     callApply,        "`Coll(1, 2).apply(1)`"))

  private def version(etv: Byte): Json =
    Json.obj("activated" -> Json.fromInt(V3.toInt), "ergoTree" -> Json.fromInt(etv.toInt))

  /** An identity round-trip entry; fails loud if the JVM does not return the input. */
  private def accept(name: String, description: String, in: String, etv: Byte): Json = {
    val out = WireCanonicalize.canonicalize("ErgoTree", in, V3, etv)
    require(out == in,
      s"$name: an identity round-trip is the whole claim — in $in, out $out")
    Json.obj(
      "name"        -> Json.fromString(name),
      "kind"        -> Json.fromString("ErgoTree"),
      "source"      -> Json.fromString(Source),
      "description" -> Json.fromString(description),
      "bytes_hex"   -> Json.fromString(in),
      "version"     -> version(etv))
  }

  private val SizedNote = "Size-flagged (forced at this version): an implementation that refuses " +
    "method id %d at parse DEGRADES this tree to an unparsed one (rule 1011's class) and echoes " +
    "these bytes, which a byte round-trip cannot tell from a real parse — the v0 unsized twin is " +
    "what separates them."
  private val UnsizedNote = "UNSIZED, at tree version 0 because that is the only version where a " +
    "tree may omit the size flag (ErgoTree.scala:104 forces it above v0). With no size flag a " +
    "parse failure cannot degrade, so an implementation that refuses method id %d at parse REJECTS " +
    "the tree outright and goes red here. This is the entry that actually catches the missing id."

  def extract(): Map[String, Json] = {
    val base = (id: Int, name: String, what: String) =>
      s"id $id, `$name`. $what. The JVM resolves it through CheckAndGetMethod, parses the tree and " +
      "re-encodes it byte-identically; sigma-rust's SCollection table starts at id 14, so it " +
      "refuses all ten with UnknownMethodId."
    val sized = Methods.zipWithIndex.map { case ((id, name, call, what), i) =>
      accept(s"$name-v3-sized#$i", s"${base(id, name, what)} ${SizedNote.format(id)}",
        treeHex(call, V3, sized = true), V3)
    }
    val unsized = Methods.zipWithIndex.map { case ((id, name, call, what), i) =>
      accept(s"$name-v0-unsized#${i + Methods.length}",
        s"${base(id, name, what)} ${UnsizedNote.format(id)}",
        treeHex(call, V0, sized = false), V0)
    }
    val versionControl = Seq(
      accept(s"size-v0-sized#${2 * Methods.length}",
        "id 1 at tree version 0, SIZE-FLAGGED — the control that separates the two dimensions. It " +
        "shares the v0 header family with the unsized entries and the size flag with the v3 ones, " +
        "so a red here and green on `size-v3-sized#0` would mean the VERSION matters to method " +
        "resolution, while a red on the unsized entries alone means only the SIZE FLAG does. " +
        "Method resolution for ids 1-10 is in fact version-independent; this is what pins it.",
        treeHex(callSize, V0, sized = true), V0))
    Map(Op -> Json.obj(
      "schema"     -> Json.fromString("santa-wire/v1"),
      "op"         -> Json.fromString(Op),
      "blessed_by" -> Json.fromString("jvm:sigma-state-6.0.7"),
      "entries"    -> Json.arr((sized ++ unsized ++ versionControl): _*)))
  }

  def writeVectors(outDir: java.nio.file.Path): Unit =
    SpecExtract.writeStaging("AuthoredWireSCollectionMethodCall", extract(), outDir)
}
