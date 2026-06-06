package santa

import java.nio.file.Files

/** Gates for the standing coverage manifest (docs/coverage/eval-coverage.json).
  *
  * 1. Structural invariants on a fresh build (totals consistent with families,
  *    known anchors present).
  * 2. Currency: the committed artifact must equal a fresh build — corpus changes
  *    without a regenerated manifest are a RED gate. Regenerate via
  *    `SANTA_WRITE_COVERAGE=1 sbt test` (writes the file, then asserts).
  */
class CoverageManifestTest extends munit.FunSuite {

  test("manifest builds with consistent totals + known anchors") {
    val m = CoverageManifest.build()
    val c = m.hcursor

    def total(field: String): Int =
      c.downField("totals").get[Int](field)
        .fold(e => fail(s"totals.$field missing: $e"), identity)

    val familyKeys = c.downField("families").keys
      .getOrElse(fail("families missing")).toSeq
    assertEquals(familyKeys.size, total("files"), "totals.files != families count")
    assert(total("files") >= 155, s"corpus shrank? ${total("files")} files")

    // totals are the sums over families
    var entries, accepts, rejects, unwalked = 0
    familyKeys.foreach { k =>
      val fc = c.downField("families").downField(k)
      def fi(f: String): Int = fc.get[Int](f).fold(e => fail(s"$k.$f missing: $e"), identity)
      entries += fi("entries"); accepts += fi("accepts")
      rejects += fi("rejects"); unwalked += fi("unwalked")
      assertEquals(fi("accepts") + fi("rejects"), fi("entries"), s"$k: arms don't sum to entries")
      assert(fi("unwalked") <= fi("entries"), s"$k: unwalked exceeds entries")
    }
    assertEquals(entries, total("entries"))
    assertEquals(accepts, total("accepts"))
    assertEquals(rejects, total("rejects"))
    assertEquals(unwalked, total("unwalked"))

    // every family key is <version>/<provenance>/<file>.json
    val keyShape = "^v[0-9]+/(spec|authored|vendored|captured)/.+\\.json$".r
    familyKeys.foreach { k =>
      assert(keyShape.findFirstIn(k).isDefined, s"unexpected family key shape: $k")
    }

    // anchors: Box.getReg (99:19) is in the method index with a sane name
    val mi = c.downField("method_index")
    val miKeys = mi.keys.getOrElse(fail("method_index missing")).toSet
    assert(miKeys.contains("99:19"), "Box.getReg (99:19) missing from method_index")
    val getRegName = mi.downField("99:19").get[String]("name")
      .fold(e => fail(s"99:19 name missing: $e"), identity)
    assert(getRegName.endsWith(".getReg"), s"99:19 name unexpected: $getRegName")

    // anchor: the substConstants families are present
    assert(familyKeys.exists(_.toLowerCase.contains("substconstants")),
      "substConstants family missing")

    println(s"\nCoverageManifest: ${total("files")} families · ${total("entries")} entries " +
      s"(${total("accepts")} accept / ${total("rejects")} reject · ${total("unwalked")} unwalked) · " +
      s"${total("distinct_ops")} distinct ops · ${total("distinct_methods")} distinct methods\n")
  }

  test("committed manifest is current (regenerate: SANTA_WRITE_COVERAGE=1 sbt test)") {
    if (sys.env.get("SANTA_WRITE_COVERAGE").contains("1")) {
      CoverageManifest.write()
      println(s"CoverageManifest: wrote ${CoverageManifest.manifestPath.normalize}")
    }
    assert(Files.exists(CoverageManifest.manifestPath),
      s"${CoverageManifest.manifestPath} missing — generate with SANTA_WRITE_COVERAGE=1 sbt test")
    val onDisk = new String(Files.readAllBytes(CoverageManifest.manifestPath), "UTF-8")
    val built  = CoverageManifest.render(CoverageManifest.build())
    // plain assert (not assertEquals): a diff dump of a ~100KB artifact is noise
    assert(onDisk == built,
      "docs/coverage/eval-coverage.json is STALE — regenerate with SANTA_WRITE_COVERAGE=1 sbt test")
  }
}
