package santa

import java.nio.file.Files

/** Guard for the box bytes-basis witnesses (sigma-rust Ask 2). The load-bearing facts:
  * `.bytes` of the garbage twin RETAINS the spliced register (32× 0xaa present, 80
  * bytes), `.bytesWithoutRef` NORMALIZES it away (47 bytes, byte-identical across the
  * twins — the asymmetry), and the twins' ids differ. All blessed live @ cost 13. */
class AuthoredBoxBytesBasisTest extends munit.FunSuite {

  private lazy val vectors = AuthoredBoxBytesBasis.extract()
  private lazy val entries: Vector[io.circe.Json] =
    vectors(AuthoredBoxBytesBasis.Op).hcursor.downField("entries").values.get.toVector
  private def byName(name: String): io.circe.Json =
    entries.find(_.hcursor.get[String]("name").toOption.contains(name))
      .getOrElse(fail(s"missing entry $name"))
  private def bytesOf(name: String): Vector[Int] =
    byName(name).hcursor.downField("expected").downField("value").downField("items")
      .values.get.toVector.map(_.hcursor.get[Int]("value").toOption.get)
  private def costOf(name: String): Long =
    byName(name).hcursor.downField("expected").get[Long]("cost").toOption.get

  test("one family, six entries, all cost 13") {
    assertEquals(vectors.size, 1)
    assertEquals(entries.size, 6)
    entries.foreach { e =>
      val name = e.hcursor.get[String]("name").toOption.get
      assertEquals(costOf(name), 13L, name)
    }
  }

  test(".bytes: garbage twin retains the spliced register; canonical control differs") {
    val garbage = bytesOf("bytes-garbage-retained#0")
    val canon   = bytesOf("bytes-canonical-control#1")
    assertEquals(garbage.size, 80)
    assertEquals(canon.size, 80)
    assertEquals(garbage.count(_ == -86), 32, "0xaa×32 must survive in the retained slice")
    assertEquals(canon.count(_ == -86), 0)
    assert(garbage != canon, "the twins' .bytes must differ")
  }

  test(".bytesWithoutRef: canonical re-serialization — twins byte-IDENTICAL, garbage gone") {
    val garbage = bytesOf("bytesnoref-garbage-canonical#2")
    val canon   = bytesOf("bytesnoref-canonical-control#3")
    assertEquals(garbage.size, 47)
    assertEquals(garbage.count(_ == -86), 0, "the garbage encoding must be normalized away")
    assertEquals(garbage, canon, "the twins must converge on this accessor (the asymmetry pin)")
  }

  test(".id: 32-byte ids, distinct across the twins (blake2b over the retained slice)") {
    val gid = bytesOf("id-garbage#4")
    val cid = bytesOf("id-canonical#5")
    assertEquals(gid.size, 32)
    assertEquals(cid.size, 32)
    assert(gid != cid, "ids must differ — the byte basis")
  }

  test("version pinned at {activated 2, ergoTree 0}") {
    entries.foreach { e =>
      val v = e.hcursor.downField("version")
      assertEquals(v.get[Int]("activated").toOption, Some(2))
      assertEquals(v.get[Int]("ergoTree").toOption, Some(0))
    }
  }

  test("staging: writes the family file") {
    val out = java.nio.file.Paths.get("target/authored-staging")
    AuthoredBoxBytesBasis.writeVectors(out)
    assert(Files.exists(out.resolve("Box.bytes_byte_basis.json")))
  }
}
