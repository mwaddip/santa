package santa

import io.circe.Json
import scala.util.Try
import sigma.VersionContext
import sigma.ast._

/** Anchors the numeric method-call version gate, and the two source facts behind it.
  *
  * The gate claim is not a reading: it is that `getMethodById` — the view rule 1016 uses — returns
  * None for every numeric id below tree version 3, while the LISTED table returns seven. Both are
  * asserted at runtime, so if sigma-state reconciles the two views the vectors stop being valid
  * and this test says so. */
class AuthoredNumericMethodCallGateTest extends munit.FunSuite {
  import AuthoredNumericMethodCallGate._

  private lazy val evalVectors = extractEval()
  private lazy val wireVectors = extractWire()

  private def entries(v: Map[String, Json], op: String): List[Json] =
    v(op).hcursor.downField("entries").as[List[Json]].fold(e => fail(s"entries: $e"), identity)
  private def name(e: Json): String = e.hcursor.get[String]("name").toOption.getOrElse("?")
  private def bytes(e: Json): String = e.hcursor.get[String]("bytes_hex").toOption.getOrElse(fail("bytes"))
  private def etv(e: Json): Int = e.hcursor.downField("version").get[Int]("ergoTree").getOrElse(-1)
  private def isReject(e: Json): Boolean = e.hcursor.get[String]("error").toOption.contains("errored")

  test("eval: all three reject, with the trees as measured") {
    val es = entries(evalVectors, OpEval)
    def treeHexOf(e: Json): String =
      e.hcursor.get[String]("tree_bytes_hex").toOption.getOrElse(fail("tree_bytes_hex"))
    assertEquals(es.map(e => (name(e), treeHexOf(e))), List(
      ("int-tobyte-id1-errored#0", ToByteV3Sized),
      ("int-tobigint-id5-errored#1", ToBigIntV3Sized),
      ("sglobal-id1-on-int-errored#2", SGlobal1V3Sized)))
    es.foreach { e =>
      assertEquals(e.hcursor.downField("expected").get[String]("error").toOption, Some("errored"), name(e))
      assertEquals(etv(e), 3, name(e))
    }
  }

  test("wire: 7 entries — 5 identity accepts and 2 rejects, at the right versions") {
    val es = entries(wireVectors, OpWire)
    assertEquals(es.map(e => (name(e), isReject(e), etv(e))), List(
      ("tobytes-v3-sized#0", false, 3),
      ("tobytes-v0-sized-degrades#1", false, 0),
      ("tobytes-v0-unsized-reject#2", true, 0),
      ("tobyte-v0-unsized-reject#3", true, 0),
      ("tobigint-v3-sized#4", false, 3),
      ("sglobal-id1-on-int-v0-unsized#5", false, 0),
      ("sglobal-id1-on-int-v3-sized#6", false, 3)))
    es.filterNot(isReject).foreach(e =>
      assert(e.hcursor.downField("expected_bytes_hex").focus.isEmpty,
        s"${name(e)}: an identity entry carries no expected_bytes_hex"))
  }

  /** The three toBytes entries are ONE body under three headers. If that ever stops being true the
    * family is comparing different trees and the gate claim dissolves. */
  test("wire: the toBytes trio differs only in the header and declared size") {
    val body = ToBytesV0Unsized.drop(2)
    assertEquals(ToBytesV3Sized.drop(4), body, "v3 sized body")
    assertEquals(ToBytesV0Sized.drop(4), body, "v0 sized body")
    assertEquals(ToBytesV3Sized.take(2), "0b")
    assertEquals(ToBytesV0Sized.take(2), "08")
    assertEquals(ToBytesV0Unsized.take(2), "00")
  }

  /** The gate itself: the listed table says seven, the parser's view says none, below v3. */
  test("numeric ids are listed below v3 but not parser-visible") {
    Seq[Byte](0, 1, 2).foreach { v =>
      VersionContext.withVersions(V3, v) {
        assertEquals(Try(SIntMethods.methods).getOrElse(Nil).map(_.methodId.toInt).sorted,
          List(1, 2, 3, 4, 5, 6, 7), s"listed table at etv=$v")
        (1 to 7).foreach(id =>
          assertEquals(Try(SIntMethods.getMethodById(id.toByte)).toOption.flatten, None,
            s"getMethodById($id) must be None at etv=$v"))
      }
    }
    VersionContext.withVersions(V3, V3) {
      (1 to 7).foreach(id =>
        assert(Try(SIntMethods.getMethodById(id.toByte)).toOption.flatten.isDefined,
          s"getMethodById($id) must resolve at etv=3"))
    }
  }

  /** 0x6a is SGlobal, not a numeric container — which is why `db 6a 01` parses at every version. */
  test("SGlobal has method 1 at every tree version, unlike any numeric id") {
    Seq[Byte](0, 2, 3).foreach { v =>
      VersionContext.withVersions(V3, v) {
        assert(Try(SGlobalMethods.getMethodById(1.toByte)).toOption.flatten.isDefined,
          s"SGlobal method 1 must resolve at etv=$v")
      }
    }
  }

  test("envelopes: the 6.0.7 blessing") {
    assertEquals(evalVectors(OpEval).hcursor.get[String]("schema").toOption, Some("santa-eval/v2"))
    assertEquals(evalVectors(OpEval).hcursor.get[String]("blessed_by").toOption, Some("jvm:sigma-state-6.0.7"))
    assertEquals(wireVectors(OpWire).hcursor.get[String]("schema").toOption, Some("santa-wire/v1"))
    assertEquals(wireVectors(OpWire).hcursor.get[String]("blessed_by").toOption, Some("jvm:sigma-state-6.0.7"))
  }

  test("write staging files") {
    val evalDir = java.nio.file.Paths.get("target", "eval-authored")
    val wireDir = java.nio.file.Paths.get("target", "wire-authored")
    writeVectors(evalDir, wireDir)
    assert(java.nio.file.Files.exists(evalDir.resolve(s"${SpecExtract.slug(OpEval)}.json")), "eval staging")
    assert(java.nio.file.Files.exists(wireDir.resolve(s"${SpecExtract.slug(OpWire)}.json")), "wire staging")
  }
}
