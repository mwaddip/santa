package santa

import java.nio.file.Files

import scorex.crypto.authds.avltree.batch.BatchAVLVerifier
import scorex.crypto.authds.{ADDigest, SerializedAdProof}
import scorex.crypto.hash.{Blake2b256, Digest32}
import scorex.util.encode.Base16

/** Gates for the committed vendored avl_verify corpus
  * (`vectors/authds/any/vendored/AvlVerify.ergots_corpus.json`) — the artifact
  * every conformer is graded against.
  *
  * The structural + re-derive gates all read the file FROM DISK, never the
  * in-memory build: a hand-edited hex byte in the committed vector must fail a
  * test, and `validate` cannot catch that (it checks schema, path, source prefix
  * and array arity — never the values).
  *
  * 1. Structural invariants on the committed doc (envelope, conditional arity,
  *    explicit nulls in `settings`, omitted-not-null optionals in `operations`).
  * 2. Re-derive: every committed `expected` value must come back out of a freshly
  *    run JVM verifier driven by that entry's OWN settings/payload.
  * 3. Currency: the committed artifact must equal a fresh build, so an edited
  *    input fixture or a blesser change that was never re-emitted is a RED gate.
  *    Regenerate via `SANTA_WRITE_AUTHDS=1 sbt test` (writes the file, then asserts).
  * 4. The three levels really are distinguished, not collapsed back into one null.
  * 5. The JVM-vs-Rust comparison — a different question (are the two verifier
  *    implementations equivalent), deliberately kept separate.
  */
class VendoredAuthdsAvlVerifyTest extends munit.FunSuite {

  /** Freshly built from the vendored fixtures — the currency reference. */
  private lazy val built = VendoredAuthdsAvlVerify.extract()

  /** The COMMITTED artifact, parsed from disk. Everything that claims to defend
    * the vector reads this, not `built`. */
  private lazy val committed: io.circe.Json = {
    val p = VendoredAuthdsAvlVerify.VectorPath
    assert(Files.exists(p),
      s"${p.normalize} missing — generate with SANTA_WRITE_AUTHDS=1 sbt test")
    io.circe.parser.parse(new String(Files.readAllBytes(p), "UTF-8"))
      .fold(e => fail(s"committed vector is not valid JSON: $e"), identity)
  }

  private def committedEntries: Vector[io.circe.Json] =
    committed.hcursor.downField("entries").values
      .getOrElse(fail("committed vector has no entries")).toVector

  private def committedByName(n: String): io.circe.HCursor =
    committedEntries.find(_.hcursor.get[String]("name").toOption.contains(n))
      .getOrElse(fail(s"committed entry '$n' not found")).hcursor

  test("committed vector: one op, fifty entries, well-formed santa-authds/v1 envelope") {
    assertEquals(built.size, 1)
    val doc = committed.hcursor
    assertEquals(doc.get[String]("schema").toOption, Some("santa-authds/v1"))
    assertEquals(doc.get[String]("op").toOption, Some("authds:vendored:avl-verify"))
    assertEquals(doc.get[String]("blessed_by").toOption, Some("jvm:scrypto-3.0.0"))
    val entries = committedEntries
    assertEquals(entries.size, 37)
    entries.foreach { e =>
      val c = e.hcursor
      assertEquals(c.get[String]("kind").toOption, Some("avl_verify"))
      assert(c.get[String]("source").toOption.exists(_.startsWith("ergots:")), "per-entry source")
    }
  }

  /** THE RE-DERIVE GUARD. Reads the COMMITTED file and re-runs the JVM verifier
    * from each entry's own settings/payload. Fails on: a hand-edited expectation,
    * a scrypto version bump that moves verifier behaviour, or any drift between
    * the committed inputs and the committed expectations. All three levels are
    * checked — a vector that agreed on `proof_accepted` while lying about a
    * per-operation value would be exactly the false-green this tier exists to
    * prevent. */
  test("every committed expectation re-derives from a fresh JVM verifier run") {
    val entries = committedEntries
    assert(entries.nonEmpty, "committed vector has no entries to guard")
    entries.foreach { e =>
      val c = e.hcursor
      val name = c.get[String]("name").toOption.getOrElse(fail("committed entry has no name"))
      // driven by what is ON DISK — the vendored fixtures are not consulted here
      val out = VendoredAuthdsAvlVerify.derive(e)
      val exp = c.downField("expected")
      assertEquals(exp.get[Boolean]("proof_accepted").toOption, Some(out.proofAccepted),
        s"COMMITTED VECTOR DRIFTED — proof_accepted for '$name' does not re-derive")
      assertEquals(exp.get[Option[String]]("new_digest_hex").toOption.flatten, out.newDigestHex,
        s"COMMITTED VECTOR DRIFTED — new_digest_hex for '$name' does not re-derive")
      val committedResults = exp.downField("results").values
        .getOrElse(fail(s"$name: expected.results missing")).toVector.map { r =>
          AvlVerifierBlesser.OpResult(
            r.hcursor.get[Boolean]("ok").toOption.getOrElse(fail(s"$name: result.ok missing")),
            r.hcursor.get[Option[String]]("value").toOption.flatten)
        }
      assertEquals(committedResults.toList, out.results,
        s"COMMITTED VECTOR DRIFTED — results for '$name' do not re-derive")
    }
  }

  test("committed vector is current (regenerate: SANTA_WRITE_AUTHDS=1 sbt test)") {
    if (sys.env.get("SANTA_WRITE_AUTHDS").contains("1")) {
      VendoredAuthdsAvlVerify.writeVectors(built, VendoredAuthdsAvlVerify.VectorPath.getParent)
      println(s"VendoredAuthdsAvlVerify: wrote ${VendoredAuthdsAvlVerify.VectorPath.normalize}")
    }
    val p = VendoredAuthdsAvlVerify.VectorPath
    assert(Files.exists(p), s"${p.normalize} missing — generate with SANTA_WRITE_AUTHDS=1 sbt test")
    val onDisk = new String(Files.readAllBytes(p), "UTF-8")
    val fresh  = VendoredAuthdsAvlVerify.render(built(VendoredAuthdsAvlVerify.Op))
    // plain assert, not assertEquals: a diff dump of the whole vector is noise
    assert(onDisk == fresh,
      s"${p.normalize} is STALE — the committed vector no longer equals a fresh build " +
        "(edited inputs, edited output, or a blesser change that was never re-emitted). " +
        "Regenerate with SANTA_WRITE_AUTHDS=1 sbt test")
  }

  /** The conditional arity invariant, on the committed doc. `validate` enforces
    * the same rule from the Rust side; duplicated here because this suite is what
    * runs when the blesser changes. */
  test("committed vector: results arity is conditional on proof_accepted") {
    committedEntries.foreach { e =>
      val c = e.hcursor
      val name = c.get[String]("name").toOption.get
      val ops = c.downField("payload").downField("operations").values.get.size
      val res = c.downField("expected").downField("results").values.get.size
      c.downField("expected").get[Boolean]("proof_accepted").toOption.get match {
        case true  => assertEquals(res, ops,
          s"$name: an accepted proof records one result per operation, even after poisoning")
        case false => assertEquals(res, 0,
          s"$name: a rejected proof attempts no operations, so results must be []")
      }
    }
  }

  test("committed vector: a rejected proof never carries a digest") {
    committedEntries
      .filter(!_.hcursor.downField("expected").get[Boolean]("proof_accepted").toOption.get)
      .foreach { e =>
        val name = e.hcursor.get[String]("name").toOption.get
        assertEquals(e.hcursor.downField("expected").downField("new_digest_hex").focus,
          Some(io.circe.Json.Null), s"$name: no tree was built, so there is no digest")
      }
  }

  test("committed vector: optional settings are explicit nulls, never omitted keys") {
    // All four `settings` fields are required-and-nullable in santa-authds/v1: a
    // runner decoding `Option[Int]` must see the field, not infer absence.
    val nullable = List("value_length", "max_num_operations", "max_deletes")
    val seenNull = scala.collection.mutable.Set.empty[String]
    committedEntries.foreach { e =>
      val name = e.hcursor.get[String]("name").toOption.get
      val s = e.hcursor.downField("settings")
      val keys = s.keys.get.toList
      assertEquals(keys.sorted, ("key_length" :: nullable).sorted,
        s"$name: settings must carry exactly the four santa-authds/v1 fields")
      nullable.foreach { f =>
        if (s.downField(f).focus.contains(io.circe.Json.Null)) seenNull += f
      }
    }
    assert(seenNull.contains("value_length"),
      "corpus must contain at least one variable-length-value fixture (explicit null)")
  }

  test("committed vector: per-op optionals are OMITTED, never null") {
    // op_item is `additionalProperties: false` with `value_hex`/`delta` optional
    // and NOT nullable — a `"delta": null` would fail schema validation outright.
    // The mirror image of the settings rule above, and the reason they differ.
    var withValue, withDelta = 0
    committedEntries.foreach { e =>
      val name = e.hcursor.get[String]("name").toOption.get
      e.hcursor.downField("payload").downField("operations").values.get.foreach { op =>
        val keys = op.hcursor.keys.get.toSet
        assert(keys.subsetOf(Set("tag", "key_hex", "value_hex", "delta")),
          s"$name: unexpected operation key(s) ${keys.diff(Set("tag", "key_hex", "value_hex", "delta"))}")
        assert(keys.contains("tag") && keys.contains("key_hex"), s"$name: tag + key_hex are required")
        List("value_hex", "delta").foreach { f =>
          assert(!op.hcursor.downField(f).focus.contains(io.circe.Json.Null),
            s"$name: '$f' present as null — the schema forbids it; omit the key instead")
        }
        if (keys.contains("value_hex")) withValue += 1
        if (keys.contains("delta")) withDelta += 1
      }
    }
    assert(withValue > 0, "corpus must exercise value-carrying operations")
    // `delta` is carried only by UpdateLongBy, which is unreachable from any
    // consensus path and retired to unreachable/authds/ (2026-08-04). Pinned at 0 so
    // reactivating it trips here: restore this count AND the Long-exactness guard
    // that used to live in the test below (the schema constrains `delta` to
    // ^-?[0-9]+$, but not that it round-trips through Long).
    assertEquals(withDelta, 0,
      "UpdateLongBy is retired to unreachable/authds/ — if it is reactivated, restore " +
        "this count and the deleted 'deltas are decimal STRINGS, Long-exact' test")
  }

  test("every entry's source names the fixture file it was built from") {
    val byName = VendoredAuthdsAvlVerify.seeds.map(s => s.name -> s).toMap
    committedEntries.foreach { e =>
      val c = e.hcursor
      val name = c.get[String]("name").toOption.get
      val seed = byName.getOrElse(name, fail(s"committed entry '$name' has no vendored fixture"))
      assertEquals(c.get[String]("source").toOption,
        Some(s"${VendoredAuthdsAvlVerify.Source}/${seed.file}"),
        s"$name: source must name the fixture FILE, not the fixture's internal name")
    }
  }

  // ── the enrichment the re-blessing exists for ─────────────────────────────

  test("the three levels are actually distinguished, not collapsed") {
    // A rejected proof: no operations attempted, no digest.
    val truncated = committedByName("adverse-truncated-proof").downField("expected")
    assertEquals(truncated.get[Boolean]("proof_accepted").toOption, Some(false))
    assertEquals(truncated.downField("results").values.get.size, 0)
    assertEquals(truncated.get[Option[String]]("new_digest_hex").toOption.flatten, None)

    // A fine proof whose operation fails: accepted, one failed result, no digest.
    val absent = committedByName("remove-3leaves-absent-fail").downField("expected")
    assertEquals(absent.get[Boolean]("proof_accepted").toOption, Some(true),
      "the proof is well-formed — only the operation fails")
    assertEquals(absent.downField("results").values.get.size, 1)
    assertEquals(absent.downField("results").downN(0).get[Boolean]("ok").toOption, Some(false))
    assertEquals(absent.get[Option[String]]("new_digest_hex").toOption.flatten, None)

    // Clean success: accepted, ok result, digest present.
    val clean = committedByName("lookup-3leaves-present").downField("expected")
    assertEquals(clean.get[Boolean]("proof_accepted").toOption, Some(true))
    assertEquals(clean.downField("results").downN(0).get[Boolean]("ok").toOption, Some(true))
    assert(clean.downField("results").downN(0).get[Option[String]]("value").toOption.flatten.isDefined,
      "a present-key lookup returns the value")
    assert(clean.get[Option[String]]("new_digest_hex").toOption.flatten.isDefined,
      "a clean batch yields a digest")

    // The three levels must all be populated across the corpus, or the enrichment
    // claim is theoretical.
    val (rejected, accepted) = committedEntries.partition(
      !_.hcursor.downField("expected").get[Boolean]("proof_accepted").toOption.get)
    val (failing, cleanly) = accepted.partition(_.hcursor.downField("expected")
      .downField("results").values.get.exists(_.hcursor.get[Boolean]("ok").toOption.contains(false)))
    assert(rejected.nonEmpty && failing.nonEmpty && cleanly.nonEmpty,
      s"levels: rejected=${rejected.size} op-failed=${failing.size} clean=${cleanly.size}")
    println(s"\n[authds avl_verify] three levels — proof-rejected=${rejected.size}, " +
      s"accepted-with-a-failing-op=${failing.size}, clean=${cleanly.size}\n")
  }

  test("at least four entries are proof-rejections — the reject arm exists") {
    val rejects = committedEntries.count(
      _.hcursor.downField("expected").get[Boolean]("proof_accepted").toOption.contains(false))
    assert(rejects >= 4, s"expected >= 4 proof-rejection entries, found $rejects")
  }

  // ── THE DELIVERABLE ───────────────────────────────────────────────────────

  /** Fixture expectations that the JVM genuinely CONTRADICTS, keyed
    * `<fixture>:<dimension>`. Each one would be a recorded Rust-vs-JVM finding, not
    * a vector to reconcile: the committed vector carries the JVM answer regardless.
    * Pinned as an exact set so a NEW divergence fails, and so a divergence that
    * silently disappears (upstream fix, scrypto bump) fails too.
    *
    * **EMPTY as of 2026-08-04 — the JVM and `ergo_avltree_rust` agree on the entire
    * reachable corpus.** It previously held 17 entries with a single root cause,
    * `UnknownModification`: scrypto's is a case OBJECT with a fixed ZERO-LENGTH key
    * that its own tree then rejects (`Key  is less than -inf`), where
    * `ergo_avltree_rust`'s carries the CALLER's key and behaves as a non-modifying
    * lookup. The two were not performing the same operation, so wherever the tag
    * appeared the JVM failed the op, poisoned the verifier, and every later op in
    * that batch failed too — 3 direct entries plus a 14-entry cascade.
    *
    * That divergence was never observable by consensus: `UnknownModification`,
    * `UpdateLongBy` and `RemoveIfExists` have ZERO fully-qualified references in
    * sigma-state 6.0.3 or ergo-core, so no ErgoScript or node path can construct
    * them. The 13 fixtures exercising them are retired to `unreachable/authds/`
    * (see that directory's README for the evidence and how to bring them back);
    * the finding is retained and reframed at
    * docs/findings/authds-unknownmodification-jvm-vs-rust.md.
    *
    * An empty set is the strongest form of this pin: ANY divergence now fails the
    * test as a new finding. Do not add to it without demonstrating the behaviour is
    * consensus-reachable — an unreachable one belongs in `unreachable/`, and a
    * reachable one belongs in `docs/findings/`. */
  private val KnownDivergences: Set[String] = Set.empty

  /** THE DELIVERABLE. The vendored fixtures ship Rust-blessed expectations
    * (`ergo_avltree_rust`); the vector's expectations come from the JVM. This
    * compares the two WITHOUT letting the Rust values near the vector.
    *
    * The comparison is not plain equality: a fixture carries ONE nullable
    * `expectedNewDigestHex` and one nullable hex per operation, where the vector
    * carries three separated levels. A fixture null that is consistent with the
    * JVM's finer answer is ENRICHMENT (reported, not scored); a fixture value the
    * JVM contradicts is a DIVERGENCE. */
  test("JVM-derived expectations vs the fixtures' Rust-blessed values") {
    val seeds = VendoredAuthdsAvlVerify.seeds
    val entries = built(VendoredAuthdsAvlVerify.Op).hcursor.downField("entries").values.get.toVector
    assertEquals(seeds.size, entries.size, "one entry per vendored fixture")
    // paired BY NAME, not positionally
    val entryByName = entries.map(e => e.hcursor.get[String]("name").toOption.get -> e).toMap

    val sb = new StringBuilder(
      "\n===== authds avl_verify: JVM vs Rust-blessed (37 vendored fixtures) =====\n")
    val divergences = scala.collection.mutable.ArrayBuffer.empty[String]
    var matched, enriched = 0

    seeds.foreach { seed =>
      val name = seed.name
      val sc = seed.json.hcursor
      val entry = entryByName.getOrElse(name, fail(s"no built entry for fixture '$name'")).hcursor
      val exp = entry.downField("expected")

      val rustDigest: Option[String] = sc.get[Option[String]]("expectedNewDigestHex").toOption.flatten
      val rustResults: List[Option[String]] =
        sc.get[List[Option[String]]]("expectedResultsHex").toOption.getOrElse(Nil)
      val accepted = exp.get[Boolean]("proof_accepted").toOption.get
      val jvmDigest = exp.get[Option[String]]("new_digest_hex").toOption.flatten
      val jvmResults = exp.downField("results").values.get.toList.map { r =>
        (r.hcursor.get[Boolean]("ok").toOption.get,
         r.hcursor.get[Option[String]]("value").toOption.flatten)
      }

      val issues = scala.collection.mutable.ArrayBuffer.empty[String]
      val notes = scala.collection.mutable.ArrayBuffer.empty[String]

      // ── digest ──
      (jvmDigest, rustDigest) match {
        case (Some(j), Some(r)) if j != r =>
          issues += s"$name:digest — jvm=$j rust=$r"
        case (Some(j), None) =>
          issues += s"$name:digest — jvm produced $j where rust reports none"
        case (None, Some(r)) =>
          issues += s"$name:digest — rust produced $r where jvm reports none " +
            (if (!accepted) "(jvm rejected the proof outright)" else "(jvm poisoned by a failed op)")
        case _ => ()
      }

      // ── results ──
      if (!accepted) {
        // The JVM chose level 1. The fixture still lists one slot per operation;
        // every one of them must be null, or rust ran operations the JVM would not.
        val nonNull = rustResults.zipWithIndex.collect { case (Some(v), i) => s"[$i]=$v" }
        if (nonNull.nonEmpty)
          issues += s"$name:results — jvm rejected the proof, rust returned values ${nonNull.mkString(",")}"
        else if (rustResults.nonEmpty)
          notes += s"proof REJECTED (level 1) — fixture's ${rustResults.size} null slot(s) are consistent"
      } else if (jvmResults.size != rustResults.size) {
        issues += s"$name:results — arity differs: jvm=${jvmResults.size} rust=${rustResults.size}"
      } else {
        jvmResults.zip(rustResults).zipWithIndex.foreach {
          case (((ok, jv), rv), i) => (jv, rv) match {
            case (Some(j), Some(r)) if j != r => issues += s"$name:results[$i] — jvm=$j rust=$r"
            case (Some(j), None)              => issues += s"$name:results[$i] — jvm=$j rust=null"
            case (None, Some(r))              =>
              issues += s"$name:results[$i] — rust=$r, jvm=" + (if (ok) "Success(None)" else "FAILED")
            case (None, None) if !ok          =>
              notes += s"results[$i] FAILED (level 2) — fixture's null is consistent"
            case _ => ()
          }
        }
      }

      if (issues.isEmpty && notes.isEmpty) {
        matched += 1
        sb.append(f"  $name%-38s MATCH\n")
      } else if (issues.isEmpty) {
        enriched += 1
        sb.append(f"  $name%-38s MATCH   (enriched: ${notes.distinct.mkString("; ")})\n")
      } else {
        sb.append(f"  $name%-38s DIVERGE\n")
        issues.foreach { i => sb.append(s"      $i\n"); divergences += i }
      }
    }

    val diverged = seeds.size - matched - enriched
    sb.append(s"  ${matched + enriched}/${seeds.size} fixtures agree with the JVM " +
      s"($matched exact, $enriched consistent-with-enrichment); $diverged diverge\n")
    sb.append("========================================================================\n")
    println(sb.toString)

    // The pin is only legitimate while it stays tied to its ONE root cause. A
    // divergence in a fixture that never mentions UnknownModification is a
    // different finding and must not be absorbed by this set — that is the
    // difference between recording a divergence and pin-greening one away.
    val unknownModFixtures = seeds
      .filter(_.json.hcursor.downField("operations").values.get
        .exists(_.hcursor.get[String]("tag").toOption.contains("UnknownModification")))
      .map(_.name).toSet
    val offRootCause = KnownDivergences.map(_.takeWhile(_ != ':')).diff(unknownModFixtures)
    assert(offRootCause.isEmpty,
      "KnownDivergences pins a fixture with no UnknownModification operation — that is a " +
        s"SEPARATE finding and may not ride this pin: ${offRootCause.mkString(", ")}")

    val keys = divergences.map(_.takeWhile(_ != ' ')).toSet
    val unexpected = keys.diff(KnownDivergences)
    val vanished = KnownDivergences.diff(keys)
    assert(unexpected.isEmpty,
      "NEW Rust-vs-JVM VERIFIER DIVERGENCE — this is a finding, not a vector to fix. Record it in " +
        s"docs/findings/ and report it before touching anything:\n${divergences.mkString("\n")}")
    assert(vanished.isEmpty,
      "a pinned Rust-vs-JVM divergence no longer reproduces — the pin is stale, confirm the " +
        s"upstream change before dropping it:\n${vanished.mkString("\n")}")
  }
}
