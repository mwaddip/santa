package santa

import java.nio.file.Files

/** Gates for the committed vendored avl_prove corpus
  * (`vectors/authds/any/vendored/AvlProve.ergots_corpus.json`) — the artifact
  * every conformer is graded against.
  *
  * The structural + re-derive gates all read the file FROM DISK, never the
  * in-memory build: a hand-edited hex byte in the committed vector must fail a
  * test, and `validate` cannot catch that (it checks schema, path, source prefix
  * and array lengths — never the values).
  *
  * 1. Structural invariants on the committed doc (envelope, parallel arrays,
  *    explicit nulls).
  * 2. Re-derive: every committed `expected` value must come back out of a
  *    freshly-run JVM prover driven by that entry's OWN settings/payload.
  * 3. Currency: the committed artifact must equal a fresh build, so an edited
  *    input fixture or a blesser change that was never re-emitted is a RED gate.
  *    Regenerate via `SANTA_WRITE_AUTHDS=1 sbt test` (writes the file, then asserts).
  * 4. The JVM-vs-Rust comparison — a different question (are the two prover
  *    implementations equivalent), deliberately kept separate.
  */
class VendoredAuthdsAvlProveTest extends munit.FunSuite {

  /** Freshly built from the vendored fixtures — the currency reference. */
  private lazy val built = VendoredAuthdsAvlProve.extract()

  /** The COMMITTED artifact, parsed from disk. Everything that claims to defend
    * the vector reads this, not `built`. */
  private lazy val committed: io.circe.Json = {
    val p = VendoredAuthdsAvlProve.VectorPath
    assert(Files.exists(p),
      s"${p.normalize} missing — generate with SANTA_WRITE_AUTHDS=1 sbt test")
    io.circe.parser.parse(new String(Files.readAllBytes(p), "UTF-8"))
      .fold(e => fail(s"committed vector is not valid JSON: $e"), identity)
  }

  private def committedEntries: Vector[io.circe.Json] =
    committed.hcursor.downField("entries").values
      .getOrElse(fail("committed vector has no entries")).toVector

  test("committed vector: one op, ten entries, well-formed santa-authds/v1 envelope") {
    assertEquals(built.size, 1)
    val doc = committed.hcursor
    assertEquals(doc.get[String]("schema").toOption, Some("santa-authds/v1"))
    assertEquals(doc.get[String]("blessed_by").toOption, Some("jvm:scrypto-3.0.0"))
    val entries = committedEntries
    assertEquals(entries.size, 10)
    entries.foreach { e =>
      val c = e.hcursor
      assertEquals(c.get[String]("kind").toOption, Some("avl_prove"))
      assert(c.get[String]("source").toOption.exists(_.startsWith("ergots:")), "per-entry source")
    }
  }

  /** THE RE-DERIVE GUARD. Reads the COMMITTED file and re-runs the JVM prover
    * from each entry's own settings/payload. Fails on: a hand-edited expectation,
    * a scrypto version bump that moves prover output, or any drift between the
    * committed inputs and the committed expectations. */
  test("every committed expectation re-derives from a fresh JVM prover run") {
    val entries = committedEntries
    assert(entries.nonEmpty, "committed vector has no entries to guard")
    entries.foreach { e =>
      val c = e.hcursor
      val name = c.get[String]("name").toOption.getOrElse(fail("committed entry has no name"))
      // driven by what is ON DISK — the vendored fixtures are not consulted here
      val (proofs, digests) = VendoredAuthdsAvlProve.derive(e)
      assertEquals(c.downField("expected").get[List[String]]("proofs").toOption, Some(proofs),
        s"COMMITTED VECTOR DRIFTED — proofs for '$name' do not re-derive from the JVM prover")
      assertEquals(c.downField("expected").get[List[String]]("digests").toOption, Some(digests),
        s"COMMITTED VECTOR DRIFTED — digests for '$name' do not re-derive from the JVM prover")
    }
  }

  test("committed vector is current (regenerate: SANTA_WRITE_AUTHDS=1 sbt test)") {
    if (sys.env.get("SANTA_WRITE_AUTHDS").contains("1")) {
      VendoredAuthdsAvlProve.writeVectors(built, VendoredAuthdsAvlProve.VectorPath.getParent)
      println(s"VendoredAuthdsAvlProve: wrote ${VendoredAuthdsAvlProve.VectorPath.normalize}")
    }
    val p = VendoredAuthdsAvlProve.VectorPath
    assert(Files.exists(p), s"${p.normalize} missing — generate with SANTA_WRITE_AUTHDS=1 sbt test")
    val onDisk = new String(Files.readAllBytes(p), "UTF-8")
    val fresh  = VendoredAuthdsAvlProve.render(built(VendoredAuthdsAvlProve.Op))
    // plain assert, not assertEquals: a diff dump of the whole vector is noise
    assert(onDisk == fresh,
      s"${p.normalize} is STALE — the committed vector no longer equals a fresh build " +
        "(edited inputs, edited output, or a blesser change that was never re-emitted). " +
        "Regenerate with SANTA_WRITE_AUTHDS=1 sbt test")
  }

  test("committed vector: proofs and digests are parallel to gen_proof_after") {
    committedEntries.foreach { e =>
      val c = e.hcursor
      val cycles = c.downField("payload").get[List[Int]]("gen_proof_after").toOption.get.size
      assertEquals(c.downField("expected").get[List[String]]("proofs").toOption.get.size, cycles)
      assertEquals(c.downField("expected").get[List[String]]("digests").toOption.get.size, cycles)
    }
  }

  test("committed vector: optional settings are explicit nulls, never omitted keys") {
    // `settings.value_length` is required-and-nullable in santa-authds/v1: a runner
    // decoding `Option[Int]` must see the field, not infer absence.
    val unfixed = committedEntries.filter(_.hcursor.downField("settings")
      .get[Option[Int]]("value_length").toOption.flatten.isEmpty)
    assert(unfixed.nonEmpty, "corpus must contain at least one variable-length-value fixture")
    unfixed.foreach { e =>
      val name = e.hcursor.get[String]("name").toOption.get
      val keys = e.hcursor.downField("settings").keys.get.toList
      assert(keys.contains("value_length"), s"$name: value_length key omitted, must be explicit null")
      assertEquals(e.hcursor.downField("settings").downField("value_length").focus,
        Some(io.circe.Json.Null), s"$name: value_length must be an explicit JSON null")
    }
  }

  test("every entry's source names the fixture file it was built from") {
    val byName = VendoredAuthdsAvlProve.seeds.map(s => s.name -> s).toMap
    committedEntries.foreach { e =>
      val c = e.hcursor
      val name = c.get[String]("name").toOption.get
      val seed = byName.getOrElse(name, fail(s"committed entry '$name' has no vendored fixture"))
      assertEquals(c.get[String]("source").toOption,
        Some(s"${VendoredAuthdsAvlProve.Source}/${seed.file}"),
        s"$name: source must name the fixture FILE, not the fixture's internal name")
    }
  }

  /** THE DELIVERABLE. The vendored fixtures ship Rust-blessed expectations
    * (`ergo_avltree_rust`); the vector's expectations come from the JVM. This
    * compares the two WITHOUT letting the Rust values near the vector. A
    * divergence is a genuine Rust-vs-JVM prover finding — record it, do not
    * reconcile it (docs/specs/authds-tier.md "Predicted findings"). */
  test("JVM-derived expectations vs the fixtures' Rust-blessed values") {
    val seeds = VendoredAuthdsAvlProve.seeds
    val entries = built(VendoredAuthdsAvlProve.Op).hcursor.downField("entries").values.get.toVector
    assertEquals(seeds.size, entries.size, "one entry per vendored fixture")
    // paired BY NAME, not positionally
    val entryByName = entries.map(e => e.hcursor.get[String]("name").toOption.get -> e).toMap

    val sb = new StringBuilder("\n===== authds avl_prove: JVM vs Rust-blessed (vendored fixtures) =====\n")
    val divergences = scala.collection.mutable.ArrayBuffer.empty[String]
    var matched = 0

    seeds.foreach { seed =>
      val name = seed.name
      val sc = seed.json.hcursor
      val entry = entryByName.getOrElse(name, fail(s"no built entry for fixture '$name'"))
      val rustProofs  = sc.get[List[String]]("expectedProofs").toOption.getOrElse(Nil)
      val rustDigests = sc.get[List[String]]("expectedDigests").toOption.getOrElse(Nil)
      val jvmProofs   = entry.hcursor.downField("expected").get[List[String]]("proofs").toOption.get
      val jvmDigests  = entry.hcursor.downField("expected").get[List[String]]("digests").toOption.get

      val issues = compare(name, "proof", jvmProofs, rustProofs) ++
                   compare(name, "digest", jvmDigests, rustDigests)
      if (issues.isEmpty) {
        matched += 1
        sb.append(f"  $name%-42s MATCH   (${jvmProofs.size} cycle(s))\n")
      } else {
        sb.append(f"  $name%-42s DIVERGE\n")
        issues.foreach { i => sb.append(s"      $i\n"); divergences += i }
      }
    }
    sb.append(s"  $matched/${seeds.size} fixtures match the JVM on every cycle " +
      s"(proof AND digest)\n")
    sb.append("====================================================================\n")
    println(sb.toString)

    assert(divergences.isEmpty,
      "Rust-vs-JVM PROVER DIVERGENCE — this is a finding, not a vector to fix. Record it in " +
      s"docs/findings/ and report it before touching anything:\n${divergences.mkString("\n")}")
  }

  /** Per-cycle hex comparison; reports the first differing byte offset. */
  private def compare(name: String, dim: String,
                      jvm: List[String], rust: List[String]): Seq[String] =
    if (jvm.size != rust.size)
      Seq(s"$name $dim: cycle-count differs — jvm=${jvm.size} rust=${rust.size}")
    else
      jvm.zip(rust).zipWithIndex.collect { case ((j, r), i) if j != r =>
        s"$name $dim cycle $i: first differing byte offset ${firstDiffByte(j, r)}; " +
          s"jvm=$j rust=$r"
      }

  /** Byte offset of the first differing hex byte (== the shorter length in bytes
    * when one is a strict prefix of the other). */
  private def firstDiffByte(a: String, b: String): Int = {
    val n = math.min(a.length, b.length) / 2
    var i = 0
    while (i < n && a.substring(2 * i, 2 * i + 2) == b.substring(2 * i, 2 * i + 2)) i += 1
    i
  }
}
