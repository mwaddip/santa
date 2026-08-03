package santa

/** Fail-loud re-derive guard for the vendored avl_prove corpus. Every expectation
  * in the committed vector must be reproducible from the JVM prover RIGHT NOW —
  * a drift means scrypto moved or the vendored inputs were edited. */
class VendoredAuthdsAvlProveTest extends munit.FunSuite {

  private lazy val vectors = VendoredAuthdsAvlProve.extract()

  test("one op, ten entries, well-formed santa-authds/v1 envelope") {
    assertEquals(vectors.size, 1)
    val doc = vectors(VendoredAuthdsAvlProve.Op).hcursor
    assertEquals(doc.get[String]("schema").toOption, Some("santa-authds/v1"))
    assertEquals(doc.get[String]("blessed_by").toOption, Some("jvm:scrypto-3.0.0"))
    val entries = doc.downField("entries").values.get.toVector
    assertEquals(entries.size, 10)
    entries.foreach { e =>
      val c = e.hcursor
      assertEquals(c.get[String]("kind").toOption, Some("avl_prove"))
      assert(c.get[String]("source").toOption.exists(_.startsWith("ergots:")), "per-entry source")
    }
  }

  test("every expectation re-derives from the JVM prover") {
    val entries = vectors(VendoredAuthdsAvlProve.Op).hcursor.downField("entries").values.get.toVector
    entries.foreach { e =>
      val c = e.hcursor
      val name = c.get[String]("name").toOption.get
      val (proofs, digests) = VendoredAuthdsAvlProve.derive(e)
      assertEquals(c.downField("expected").get[List[String]]("proofs").toOption, Some(proofs),
        s"proofs drifted for $name")
      assertEquals(c.downField("expected").get[List[String]]("digests").toOption, Some(digests),
        s"digests drifted for $name")
    }
  }

  test("proofs and digests are parallel to gen_proof_after") {
    val entries = vectors(VendoredAuthdsAvlProve.Op).hcursor.downField("entries").values.get.toVector
    entries.foreach { e =>
      val c = e.hcursor
      val cycles = c.downField("payload").get[List[Int]]("gen_proof_after").toOption.get.size
      assertEquals(c.downField("expected").get[List[String]]("proofs").toOption.get.size, cycles)
      assertEquals(c.downField("expected").get[List[String]]("digests").toOption.get.size, cycles)
    }
  }

  test("optional settings are explicit nulls, never omitted keys") {
    // `settings.value_length` is required-and-nullable in santa-authds/v1: a runner
    // decoding `Option[Int]` must see the field, not infer absence.
    val entries = vectors(VendoredAuthdsAvlProve.Op).hcursor.downField("entries").values.get.toVector
    val unfixed = entries.filter(_.hcursor.downField("settings")
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

  /** THE DELIVERABLE. The vendored fixtures ship Rust-blessed expectations
    * (`ergo_avltree_rust`); the vector's expectations come from the JVM. This
    * compares the two WITHOUT letting the Rust values near the vector. A
    * divergence is a genuine Rust-vs-JVM prover finding — record it, do not
    * reconcile it (docs/specs/authds-tier.md "Predicted findings"). */
  test("JVM-derived expectations vs the fixtures' Rust-blessed values") {
    val seeds = VendoredAuthdsAvlProve.seeds
    val entries = vectors(VendoredAuthdsAvlProve.Op).hcursor.downField("entries").values.get.toVector
    assertEquals(seeds.size, entries.size, "seed <-> entry pairing is positional (both name-sorted)")

    val sb = new StringBuilder("\n===== authds avl_prove: JVM vs Rust-blessed (vendored fixtures) =====\n")
    val divergences = scala.collection.mutable.ArrayBuffer.empty[String]
    var matched = 0

    seeds.zip(entries).foreach { case (seed, entry) =>
      val sc = seed.hcursor
      val name = sc.get[String]("name").toOption.get
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

  test("write staging vector (cp into vectors/authds/any/vendored/)") {
    val outDir = java.nio.file.Paths.get("target", "authds-vectors")
    VendoredAuthdsAvlProve.writeVectors(vectors, outDir)
    assert(java.nio.file.Files.exists(outDir.resolve(s"${VendoredAuthdsAvlProve.Op}.json")),
      s"staging ${VendoredAuthdsAvlProve.Op}.json not written")
  }
}
