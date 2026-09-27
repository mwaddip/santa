package santa

import io.circe.Json
import scorex.util.encode.Base16
import org.ergoplatform.modifiers.history.header.HeaderSerializer

/** Bless + lock the storage-rent transaction vectors. blessAll() fails loud unless the JVM agrees with each
  * arm for the arm's reason (rent rejects carry `Success((false,50))`; accept costs decompose exactly); this
  * pins the file/entry shape and the synthetic context, prints the blessed costs, and writes the vectors. */
class AuthoredTxStorageRentTest extends munit.FunSuite {
  private lazy val blessed: Seq[(String, Json)] = AuthoredTxStorageRent.blessAll()

  private def entries(rel: String): List[Json] =
    blessed.toMap.apply(rel).hcursor.downField("entries").as[List[Json]].fold(e => fail(s"entries: $e"), identity)
  private def name(e: Json): String = e.hcursor.get[String]("name").toOption.getOrElse(fail("name"))
  private def valid(e: Json): Boolean =
    e.hcursor.downField("expected").get[Boolean]("valid").toOption.getOrElse(fail("expected.valid"))
  private def all: List[Json] = blessed.map(_._1).toList.flatMap(entries)

  test("six files; every verdict matches its name (…-accept valid, …-reject invalid)") {
    assertEquals(blessed.map(_._1).toSet, Set(
      AuthoredTxStorageRent.RecreationPath, AuthoredTxStorageRent.FallbackPath, AuthoredTxStorageRent.GatePath,
      AuthoredTxStorageRent.MixedPath, AuthoredTxStorageRent.DustPath, AuthoredTxStorageRent.FeeWrapPath))
    assertEquals(all.map(name).distinct.size, all.size, "entry names must be unique")
    all.foreach { e =>
      val n = name(e)
      assert(n.endsWith("-accept") || n.endsWith("-reject"), s"$n: name must end in -accept or -reject")
      assertEquals(valid(e), n.endsWith("-accept"), s"$n: verdict")
    }
    assertEquals(all.size, 21)
  }

  test("rejects are rent verdicts; accepts carry a cost") {
    all.foreach { e =>
      val exp = e.hcursor.downField("expected")
      if (valid(e)) assert(exp.get[Long]("cost").isRight, s"${name(e)}: accept without cost")
      else assert(exp.get[String]("reason").toOption.exists(_.contains("=> Success((false,50))")),
        s"${name(e)}: reject must be the final rent verdict")
    }
  }

  test("one synthetic context: ten parent-linked headers below StoragePeriod, preHeader on top") {
    val ctxs = all.map(e => (e.hcursor.downField("headers_hex").focus, e.hcursor.downField("preHeader").focus)).distinct
    assertEquals(ctxs.size, 1, "every entry must share the context")
    val e0 = all.head.hcursor
    val headers = e0.downField("headers_hex").as[List[String]].toOption.get
      .map(h => HeaderSerializer.parseBytes(Base16.decode(h).get))
    assertEquals(headers.map(_.height), (1 to 10).map(AuthoredTxStorageRent.H - _).toList, "NEWEST-first heights")
    headers.sliding(2).foreach { case List(newer, older) => assertEquals(newer.parentId, older.id) }
    assertEquals(e0.downField("preHeader").get[String]("parentId").toOption, Some(headers.head.id: String))
    assertEquals(e0.downField("preHeader").get[Int]("height").toOption, Some(AuthoredTxStorageRent.H))
    assertEquals(e0.downField("context").get[Int]("height").toOption, Some(AuthoredTxStorageRent.H))
  }

  test("print blessed verdicts + costs") {
    val sb = new StringBuilder(s"\n===== storage-rent vectors (script cost of sigmaProp(true) = ${AuthoredTxStorageRent.scriptCost}) =====\n")
    blessed.foreach { case (rel, _) =>
      sb.append(s"  [$rel]\n")
      entries(rel).foreach { e =>
        val exp = e.hcursor.downField("expected")
        sb.append(f"    ${name(e)}%-52s valid=${valid(e)}%-5s cost=${exp.get[Long]("cost").toOption.map(_.toString).getOrElse("-")}\n")
      }
    }
    println(sb.toString)
  }

  test("write step: files land at the committed vectors/transaction/v6/authored/ paths") {
    val vectorsRoot = java.nio.file.Paths.get("..", "vectors")
    AuthoredTxStorageRent.writeVectors(blessed, vectorsRoot)
    blessed.foreach { case (rel, _) =>
      assert(java.nio.file.Files.exists(vectorsRoot.resolve(rel)), s"not written: $rel")
    }
  }
}
