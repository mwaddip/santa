package santa

import io.circe.Json

import sigma.ast.{BoolToSigmaProp, Constant, FixedCost, PerItemCost, XorOf}

/** Anchors the activation-gate families. `SpecExtract` already fails loud if an accept does not evaluate or a reject
  * does; pinned here is what would rot silently while still grading green:
  *
  *  - each file's activated version, its tree versions (v0 then v1), and its rows in order;
  *  - the request's fifteen trees, by body, at every version pair;
  *  - every xorOf value is the exclusive or, at the cost read from the jar (Constant + XorOf's PerItemCost);
  *  - `sigmaProp(true)` costs Constant + BoolToSigmaProp, from the jar; every other operand is refused;
  *  - THE ROWS SEPARATE: the same trees at activated 1 take the 4.x reading through the same oracle, so a SEPARATING
  *    row is exactly one whose outcome below the activation differs, and a CONTROL one whose outcome does not.
  */
class AuthoredEvalActivationGatesTest extends munit.FunSuite {
  private val B = AuthoredEvalActivationGates
  private lazy val v = B.extract()
  private val ops = Seq(B.XorV5, B.XorV6, B.B2spV5, B.B2spV6)
  private def entries(op: String): List[Json] =
    v(op).hcursor.downField("entries").as[List[Json]].fold(e => fail(s"entries: $e"), identity)
  private def str(e: Json, f: String): String = e.hcursor.get[String](f).toOption.getOrElse(fail(f))
  private def id(e: Json): String = str(e, "script").takeWhile(_ != ']').drop(1)
  private def ver(e: Json, f: String): Int = e.hcursor.downField("version").get[Int](f).toOption.getOrElse(fail(f))
  private def at(op: String, t: Int): List[Json] = entries(op).filter(e => ver(e, "ergoTree") == t)
  private def row(op: String, t: Int, i: String): Json = at(op, t).find(id(_) == i).getOrElse(fail(s"no row $i"))
  private def err(e: Json): Option[String] = e.hcursor.downField("expected").get[Option[String]]("error").toOption.flatten
  private def cost(e: Json): Long = e.hcursor.downField("expected").get[Long]("cost").toOption.getOrElse(fail("cost"))
  private def value(e: Json): Json = e.hcursor.downField("expected").downField("value").focus.getOrElse(fail("value"))
  private def isXor(op: String) = op.startsWith("XorOf")
  private def ids(op: String): List[String] = if (isXor(op)) B.XorRows.map(_.id).toList else B.B2spRows.map(_.id).toList

  private val G = "0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798"
  private val bodies: Map[String, String] = Map(
    "tft" -> "ff0d0305", "tt" -> "ff0d0203", "tf" -> "ff0d0201", "ttf" -> "ff0d0303", "ttt" -> "ff0d0307",
    "ff" -> "ff0d0200", "t" -> "ff0d0101", "f" -> "ff0d0100", "empty" -> "ff0d00",
    "true" -> "d10101", "sp-const" -> s"d108cd$G", "provedlog" -> s"d1cd07$G", "nested" -> s"d1d108cd$G",
    "int" -> "d10402", "sp-throws" -> "d1b2830008040000")

  test("v5 at activated 2, v6 at activated 3; tree v0 then v1; xorOf 9 + 9, BoolToSigmaProp 6 + 6") {
    assertEquals(Map(B.XorV5 -> 2, B.B2spV5 -> 2, B.XorV6 -> 3, B.B2spV6 -> 3), B.Activated.map { case (k, a) => k -> a.toInt })
    ops.foreach { op =>
      val es = entries(op)
      assertEquals(es.size, if (isXor(op)) 18 else 12, op)
      es.foreach { e =>
        assertEquals(ver(e, "activated"), B.Activated(op).toInt, str(e, "name"))
        assertEquals(e.hcursor.downField("input").focus, Some(B.IntInput), str(e, "name"))
      }
      assertEquals(es.map(ver(_, "ergoTree")), List.fill(es.size / 2)(0) ++ List.fill(es.size / 2)(1), op)
      B.TreeVersions.foreach(t => assertEquals(at(op, t).map(id), ids(op), s"$op t$t"))
    }
  }

  test("the request's fifteen trees, by body, at every version pair") {
    assertEquals(bodies.keySet, (B.XorRows.map(_.id) ++ B.B2spRows.map(_.id)).toSet)
    ops.foreach(op => B.TreeVersions.foreach { t =>
      ids(op).foreach { i =>
        val bd = bodies(i)
        val want = if (t == 0) "00" + bd else "09" + f"${bd.length / 2}%02x" + bd
        assertEquals(str(row(op, t, i), "tree_bytes_hex"), want, s"$op $i t$t")
      }
    })
  }

  private def boolJ(b: Boolean) = Json.obj("kind" -> Json.fromString("Boolean"), "value" -> Json.fromBoolean(b))
  private def fixed(c: Any): Long = c match { case f: FixedCost => f.cost.value.toLong; case o => fail(s"not fixed: $o") }

  test("xorOf: the exclusive or at Constant + XorOf's PerItemCost, from the jar; 4 SEPARATING inputs of 9") {
    val perItem = XorOf.costKind match { case p: PerItemCost => p; case o => fail(s"not per-item: $o") }
    assertEquals(B.XorRows.filter(_.separating).map(_.id), Seq("tft", "ttf", "ttt", "t"))
    Seq(B.XorV5, B.XorV6).foreach(op => B.TreeVersions.foreach { t =>
      B.XorRows.foreach { r =>
        val e = row(op, t, r.id)
        assertEquals(err(e), None, s"$op ${r.id} t$t")
        assertEquals(value(e), boolJ(B.xorReading(r.items)), s"$op ${r.id} t$t")
        assertEquals(cost(e), fixed(Constant.costKind) + perItem.cost(r.items.size).value.toLong, s"$op ${r.id} t$t")
        assert(str(e, "script").contains(if (r.separating) "SEPARATING" else "CONTROL"), s"$op ${r.id}")
      }
    })
  }

  test("BoolToSigmaProp: sigmaProp(true) is TrueProp at Constant + BoolToSigmaProp, from the jar; the rest refused") {
    Seq(B.B2spV5, B.B2spV6).foreach(op => B.TreeVersions.foreach { t =>
      val tr = row(op, t, "true")
      assertEquals(value(tr), Json.obj("kind" -> Json.fromString("SigmaProp"), "raw_hex" -> Json.fromString("d3")))
      assertEquals(cost(tr), fixed(Constant.costKind) + fixed(BoolToSigmaProp.costKind))
      B.B2spRows.filterNot(_.accepts).foreach(r => assertEquals(err(row(op, t, r.id)), Some("errored"), s"$op ${r.id} t$t"))
    })
    assertEquals(B.B2spRows.filter(_.separating).map(_.id), Seq("sp-const", "provedlog", "nested"))
  }

  /** The oracle's outcome below the activation: Right(value) or Left(()) for a refusal. */
  private def below(hex: String): Either[Unit, Json] =
    EvalCore.evalApplied(hex, B.IntInput, activated = 1.toByte)._2.fold(_ => Left(()), { case (vj, _) => Right(vj) })
  private def graded(e: Json): Either[Unit, Json] = if (err(e).isDefined) Left(()) else Right(value(e))

  test("THE ROWS SEPARATE: at activated 1 the same trees take the 4.x reading, on exactly the SEPARATING rows") {
    ops.foreach(op => B.TreeVersions.foreach { t =>
      ids(op).foreach { i =>
        val e = row(op, t, i)
        val sep = if (isXor(op)) B.XorRows.find(_.id == i).get.separating else B.B2spRows.find(_.id == i).get.separating
        val lo = below(str(e, "tree_bytes_hex"))
        assertEquals(lo != graded(e), sep, s"$op $i t$t: below = $lo, graded = ${graded(e)}")
      }
      if (isXor(op)) B.XorRows.foreach { r =>
        assertEquals(below(str(row(op, t, r.id), "tree_bytes_hex")), Right(boolJ(B.v4Reading(r.items))), s"$op ${r.id} t$t")
      } else Seq("sp-const", "provedlog", "nested").foreach { i =>
        val pass = Json.obj("kind" -> Json.fromString("SigmaProp"), "raw_hex" -> Json.fromString(s"cd$G"))
        assertEquals(below(str(row(op, t, i), "tree_bytes_hex")), Right(pass), s"$op $i t$t: the SigmaProp passes through")
      }
    })
  }

  // ── v4: below the JIT activation ───────────────────────────────────────────────────────────────────────────────
  private val v4ops = Seq(B.XorV4, B.B2spV4)
  private val readable: Seq[(Int, Int)] = B.V4Combos.map { case (a, t) => (a.toInt, t) }
  private def atAT(op: String, a: Int, t: Int): List[Json] =
    entries(op).filter(e => ver(e, "activated") == a && ver(e, "ergoTree") == t)
  private def rowAT(op: String, a: Int, t: Int, i: String): Json =
    atAT(op, a, t).find(id(_) == i).getOrElse(fail(s"no row $i at ($a, $t)"))
  private def evalAt(hex: String, a: Int): Either[String, Json] =
    EvalCore.evalApplied(hex, B.IntInput, activated = a.toByte)._2.fold(Left(_), { case (vj, _) => Right(vj) })
  private def sigmaProp(raw: String) = Json.obj("kind" -> Json.fromString("SigmaProp"), "raw_hex" -> Json.fromString(raw))

  test("v4: (activated 0, tree 0), (1, 0), (1, 1); every row at every pair, on the request's bodies; no tree above") {
    assertEquals(readable, Seq((0, 0), (1, 0), (1, 1)))
    v4ops.foreach { op =>
      val es = entries(op)
      assertEquals(es.size, if (isXor(op)) 27 else 18, op)
      es.foreach(e => assert(ver(e, "ergoTree") <= ver(e, "activated"), s"${str(e, "name")}: tree above activated"))
      assertEquals(es.map(e => (ver(e, "activated"), ver(e, "ergoTree"))).distinct, B.V4Combos.map { case (a, t) => (a.toInt, t) }.toList)
      es.foreach(e => assertEquals(e.hcursor.downField("input").focus, Some(B.IntInput), str(e, "name")))
      B.V4Combos.foreach { case (a, t) =>
        assertEquals(atAT(op, a, t).map(id), ids(op), s"$op ($a, $t)")
        ids(op).foreach { i =>
          val bd = bodies(i)
          assertEquals(str(rowAT(op, a, t, i), "tree_bytes_hex"), if (t == 0) "00" + bd else "09" + f"${bd.length / 2}%02x" + bd)
        }
      }
    }
  }

  test("v4 below the activation: xorOf the 4.x reading at 30; a SigmaProp operand passes through at 20 / 30 / 35") {
    val perItem = XorOf.costKind match { case p: PerItemCost => p; case o => fail(s"not per-item: $o") }
    val pass = sigmaProp(s"cd$G")
    val b2sp: Map[String, Option[(Json, Long)]] = Map(
      "true" -> Some((sigmaProp("d3"), 20L)), "sp-const" -> Some((pass, 20L)), "provedlog" -> Some((pass, 30L)),
      "nested" -> Some((pass, 35L)), "int" -> None, "sp-throws" -> None)
    assertEquals(b2sp.keySet, B.B2spRows.map(_.id).toSet)
    assertEquals(B.B2spRows.filter(_.acceptsBelow).map(_.id).toSet, b2sp.collect { case (i, Some(_)) => i }.toSet)
    readable.foreach { case (a, t) =>
      B.XorRows.foreach { r =>
        val e = rowAT(B.XorV4, a, t, r.id)
        assertEquals(err(e), None, s"${r.id} ($a, $t)")
        assertEquals(value(e), boolJ(B.v4Reading(r.items)), s"${r.id} ($a, $t)")
        assertEquals(cost(e), fixed(Constant.costKind) + perItem.cost(r.items.size).value.toLong, s"${r.id} ($a, $t)")
        assert(str(e, "script").contains(if (r.separating) "SEPARATING" else "CONTROL"), r.id)
      }
      b2sp.foreach { case (i, want) =>
        val e = rowAT(B.B2spV4, a, t, i)
        want match {
          case Some((vj, c)) => assertEquals(value(e), vj, s"$i ($a, $t)"); assertEquals(cost(e), c, s"$i ($a, $t)")
          case None          => assertEquals(err(e), Some("errored"), s"$i ($a, $t)")
        }
      }
    }
  }

  test("v4 THE ROWS SEPARATE: at activated 2 the same trees take the 5.x reading, on exactly the SEPARATING rows") {
    v4ops.foreach(op => readable.foreach { case (a, t) =>
      ids(op).foreach { i =>
        val e = rowAT(op, a, t, i)
        val sep = if (isXor(op)) B.XorRows.find(_.id == i).get.separating else B.B2spRows.find(_.id == i).get.separating
        val above = evalAt(str(e, "tree_bytes_hex"), 2).left.map(_ => ())
        assertEquals(above != graded(e), sep, s"$op $i ($a, $t): above = $above, graded = ${graded(e)}")
        assert(str(e, "script").contains(if (sep) "SEPARATING" else "CONTROL"), s"$op $i")
      }
    })
  }

  test("envelope + write staging files") {
    (ops ++ v4ops).foreach { op =>
      assertEquals(v(op).hcursor.get[String]("blessed_by").toOption, Some(B.Stamp))
      assertEquals(v(op).hcursor.get[String]("source").toOption, Some(B.Source))
      assertEquals(v(op).hcursor.get[String]("schema").toOption, Some("santa-eval/v2"))
    }
    val outDir = java.nio.file.Paths.get("target", "eval-authored")
    B.writeVectors(outDir)
    (ops ++ v4ops).foreach(op => assert(java.nio.file.Files.exists(outDir.resolve(s"$op.json"))))
  }
}
