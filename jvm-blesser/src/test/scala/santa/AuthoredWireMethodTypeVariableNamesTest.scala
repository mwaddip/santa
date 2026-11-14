package santa

import io.circe.Json

/** Anchors the wire family. `check` in the blesser already requires each accept to round-trip to itself and each
  * reject to fail on `ConstraintFailed`/`SameType2`. Pinned here is what would rot silently:
  *
  *   1. THE SPLIT: per version, which rows the reader refuses (v6: X1 X2 X5; v5: N2 N3), in all three carriers;
  *   2. the carriers actually hold the tree, at the version the row declares;
  *   3. the eval family holds the same expressions (so the two tiers stay twins).
  */
class AuthoredWireMethodTypeVariableNamesTest extends munit.FunSuite {
  private val B = AuthoredWireMethodTypeVariableNames
  private lazy val v6 = B.extract(v6 = true)
  private lazy val v5 = B.extract(v6 = false)
  private def entries(m: Map[String, Json], op: String): List[Json] = m(op)
    .hcursor.downField("entries").as[List[Json]].fold(e => fail(s"entries: $e"), identity)
  private def str(e: Json, f: String): String = e.hcursor.get[String](f).toOption.getOrElse(fail(f))
  private def errored(e: Json): Boolean = e.hcursor.get[String]("error").toOption.contains("errored")
  private def id(e: Json): String = str(e, "description").takeWhile(_ != ']').drop(1)
  private def ver(e: Json, f: String): Int = e.hcursor.downField("version").get[Int](f).toOption.getOrElse(fail(f))

  test("v6: 7 rows per carrier, X1/X2/X5 refused; v5: 5 rows per carrier, N2/N3 refused") {
    Seq((v6, 7, Set("X1", "X2", "X5"), 3, 3), (v5, 5, Set("N2", "N3"), 2, 0)).foreach {
      case (m, n, refused, act, tv) =>
        B.Ops.foreach { op =>
          val es = entries(m, op)
          assertEquals(es.size, n, op)
          assertEquals(es.filter(errored).map(id).toSet, refused, op)
          es.foreach { e =>
            assertEquals(ver(e, "activated"), act, str(e, "name"))
            assertEquals(ver(e, "ergoTree"), tv, str(e, "name"))
          }
        }
    }
  }

  test("each carrier holds its row's tree; the ErgoTree kind IS the tree") {
    Seq(v6, v5).foreach { m =>
      B.Rows.filter(r => entries(m, B.OpErgoTree).exists(id(_) == r.id)).foreach { r =>
        B.Ops.foreach { op =>
          val e = entries(m, op).find(id(_) == r.id).getOrElse(fail(s"$op ${r.id}"))
          if (op == B.OpErgoTree) assertEquals(str(e, "bytes_hex"), r.treeHex, r.id)
          else assert(str(e, "bytes_hex").contains(r.treeHex), s"$op ${r.id}: the carrier must hold the tree")
        }
      }
    }
  }

  test("the eval family holds the same expressions, with the same verdicts") {
    val ev = AuthoredEvalMethodTypeVariableNames.Rows.filter(r => r.id.startsWith("X") || r.id.startsWith("N"))
    B.Rows.foreach { r =>
      val twin = ev.find(e => e.id == r.id && e.v6 == r.v6).getOrElse(fail(s"no eval twin for ${r.id}"))
      assertEquals(twin.body, r.body, r.id)
      assertEquals(twin.accept, r.accept, r.id)
    }
  }

  test("envelopes + write staging files (wire/v6 and wire/v5)") {
    Seq(v6, v5).foreach(m => B.Ops.foreach { op =>
      assertEquals(m(op).hcursor.get[String]("op").toOption, Some(op))
      assertEquals(m(op).hcursor.get[String]("blessed_by").toOption, Some("jvm:sigma-state-6.0.7"))
    })
    val outDir = java.nio.file.Paths.get("target", "wire-authored", "method-type-variable-names")
    B.writeVectors(outDir)
    Seq("v6", "v5").foreach(d => B.Ops.foreach(op =>
      assert(java.nio.file.Files.exists(outDir.resolve(d).resolve(s"$op.json")), s"$d/$op")))
  }
}
