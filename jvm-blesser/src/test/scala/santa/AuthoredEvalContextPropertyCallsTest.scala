package santa

import scala.util.Try

import io.circe.Json
import scorex.util.encode.Base16

import org.ergoplatform.ErgoBox
import sigma.serialization.SigmaSerializer
import sigma.ast.{Context, FixedCost, Height, Inputs, MethodCall, Outputs, PropertyCall, SContextMethods, Self}

/** Anchors the Context property-call family. `SpecExtract` already fails loud if an accept does not evaluate;
  * pinned here is what would rot silently while still grading green:
  *
  *  - each file's version pairs, and every entry an accept;
  *  - the probe's eight trees, by body, at every version pair;
  *  - every value and cost, identical at every version pair;
  *  - THE CALL IS THE NODE + 5: each method's cost kind is its node's, read from the jar, and
  *    call − node = PropertyCall's 4 + Context's 1, also read from the jar;
  *  - the bare SELF the calls return is the contract §2 box that carries the row's own tree, and a box a node
  *    can parse: the JVM's own ErgoBox parser takes every graded one, and refuses the v0 box left out.
  */
class AuthoredEvalContextPropertyCallsTest extends munit.FunSuite {
  private val B = AuthoredEvalContextPropertyCalls
  private lazy val v = B.extract()
  private def entries(op: String): List[Json] =
    v(op).hcursor.downField("entries").as[List[Json]].fold(e => fail(s"entries: $e"), identity)
  private def str(e: Json, f: String): String = e.hcursor.get[String](f).toOption.getOrElse(fail(f))
  private def id(e: Json): String = str(e, "script").takeWhile(_ != ']').drop(1)
  private def ver(e: Json, f: String): Int = e.hcursor.downField("version").get[Int](f).toOption.getOrElse(fail(f))
  private def at(op: String, a: Int, t: Int): List[Json] =
    entries(op).filter(e => ver(e, "activated") == a && ver(e, "ergoTree") == t)
  private def row(es: List[Json], i: String): Json = es.find(id(_) == i).getOrElse(fail(s"no row $i"))
  private def cost(e: Json): Long = e.hcursor.downField("expected").get[Long]("cost").toOption.getOrElse(fail("cost"))
  private def value(e: Json): Json = e.hcursor.downField("expected").downField("value").focus.getOrElse(fail("value"))
  private val pairs: Seq[(String, Int, Int)] = Seq((B.OpV5, 2, 0), (B.OpV6, 3, 3), (B.OpV6, 3, 0))

  private val boxRows = Set("Ib", "Ibc", "Sb", "Sbc")
  private def ids(t: Int): List[String] = B.Rows.map(_.id).filter(i => t != 0 || !boxRows(i)).toList

  test("v5 = (2, 0) 14 rows; v6 = (3, 3) 18 then (3, 0) 14: the box-valued rows at a sized tree only; all accepts") {
    assertEquals(entries(B.OpV5).size, 14)
    assertEquals(entries(B.OpV6).size, 32)
    assertEquals(B.Rows.filter(_.returnsBox).map(_.id).toSet, boxRows)
    pairs.foreach { case (op, a, t) => assertEquals(at(op, a, t).map(id), ids(t), s"$op ($a, $t)") }
    (entries(B.OpV5) ++ entries(B.OpV6)).foreach { e =>
      assertEquals(e.hcursor.downField("expected").get[Option[String]]("error").toOption.flatten, None, str(e, "name"))
      assertEquals(e.hcursor.downField("input").focus, Some(B.IntInput), str(e, "name"))
    }
  }

  test("the probe's eight trees, by body, at every version pair") {
    val bodies = Map("I" -> "b1db6504fe", "Ic" -> "b1a4", "O" -> "b1db6505fe", "Oc" -> "b1a5",
      "H" -> "db6506fe", "Hc" -> "a3", "S" -> "c1db6507fe", "Sc" -> "c1a7")
    pairs.foreach { case (op, a, t) =>
      bodies.foreach { case (i, bd) =>
        val want = if (t == 0) "00" + bd else "0b" + f"${bd.length / 2}%02x" + bd
        assertEquals(str(row(at(op, a, t), i), "tree_bytes_hex"), want, s"$i ($a, $t)")
      }
    }
  }

  test("every value and cost, identical at every version pair") {
    def I(n: Int) = Json.obj("kind" -> Json.fromString("Int"), "value" -> Json.fromInt(n))
    val L1m = Json.obj("kind" -> Json.fromString("Long"), "value" -> Json.fromString("1000000"))
    val T = Json.obj("kind" -> Json.fromString("Boolean"), "value" -> Json.True)
    val want: Map[String, (Option[Json], Long)] = Map(
      "I" -> (Some(I(1)), 29), "Ic" -> (Some(I(1)), 24), "O" -> (Some(I(0)), 29), "Oc" -> (Some(I(0)), 24),
      "H" -> (Some(I(0)), 31), "Hc" -> (Some(I(0)), 26), "S" -> (Some(L1m), 23), "Sc" -> (Some(L1m), 18),
      "Ib" -> (None, 15), "Ibc" -> (None, 10), "Ob" -> (None, 15), "Obc" -> (None, 10),
      "Sb" -> (None, 15), "Sbc" -> (None, 10),
      "I=" -> (Some(T), 46), "O=" -> (Some(T), 41), "H=" -> (Some(T), 60), "S=" -> (Some(T), 31))
    assertEquals(want.keySet, B.Rows.map(_.id).toSet)
    pairs.foreach { case (op, a, t) =>
      val es = at(op, a, t)
      want.filter { case (i, _) => ids(t).contains(i) }.foreach { case (i, (vj, c)) =>
        assertEquals(cost(row(es, i)), c, s"$i ($a, $t)")
        vj.foreach(j => assertEquals(value(row(es, i)), j, s"$i ($a, $t)"))
      }
      val empty = Json.obj("kind" -> Json.fromString("Coll"), "elem" -> Json.obj("tag" -> Json.fromString("SBox")),
        "items" -> Json.arr())
      assertEquals(value(row(es, "Ob")), empty); assertEquals(value(row(es, "Obc")), empty)
    }
  }

  test("THE CALL IS THE NODE + 5: the node's own cost kind, plus PropertyCall's 4 and Context's 1, from the jar") {
    def k(c: Any): Long = c match { case f: FixedCost => f.cost.value.toLong; case o => fail(s"not fixed: $o") }
    assertEquals(SContextMethods.inputsMethod.costKind, Inputs.costKind)
    assertEquals(SContextMethods.outputsMethod.costKind, Outputs.costKind)
    assertEquals(SContextMethods.heightMethod.costKind, Height.costKind)
    assertEquals(SContextMethods.selfMethod.costKind, Self.costKind)
    assertEquals(k(MethodCall.costKind), k(PropertyCall.costKind))
    val overhead = k(PropertyCall.costKind) + k(Context.costKind)
    assertEquals(overhead, 5L)
    pairs.foreach { case (op, a, t) =>
      val es = at(op, a, t)
      Seq("I" -> "Ic", "O" -> "Oc", "H" -> "Hc", "S" -> "Sc", "Ib" -> "Ibc", "Ob" -> "Obc", "Sb" -> "Sbc")
        .filter { case (call, _) => ids(t).contains(call) }.foreach {
          case (call, node) => assertEquals(cost(row(es, call)) - cost(row(es, node)), overhead, s"$call − $node ($a, $t)")
        }
      assertEquals(cost(row(es, "Obc")), k(Outputs.costKind)); assertEquals(cost(row(es, "Hc")), k(Height.costKind))
      if (t != 0) { assertEquals(cost(row(es, "Ibc")), k(Inputs.costKind)); assertEquals(cost(row(es, "Sbc")), k(Self.costKind)) }
    }
  }

  private def parses(boxHex: String): Boolean =
    Try(ErgoBox.sigmaSerializer.parse(SigmaSerializer.startReader(Base16.decode(boxHex).get)))
      .toOption.exists(bx => Base16.encode(bx.bytes).toLowerCase == boxHex)

  test("the bare SELF is the contract §2 box (value 1000000, the row's own tree, height 0, zero tx id), and parses") {
    def self(tree: String) = "c0843d" + tree + "00" * 36
    pairs.filter(_._3 != 0).foreach { case (op, a, t) =>
      val es = at(op, a, t)
      Seq("Ib", "Ibc").foreach { i =>
        val items = value(row(es, i)).hcursor.downField("items").as[List[Json]].fold(e => fail(s"$e"), identity)
        assertEquals(items.map(_.hcursor.get[String]("bytes_hex").toOption), List(Some(self(str(row(es, i), "tree_bytes_hex")))), i)
      }
      Seq("Sb", "Sbc").foreach { i =>
        assertEquals(value(row(es, i)).hcursor.get[String]("bytes_hex").toOption, Some(self(str(row(es, i), "tree_bytes_hex"))), i)
      }
      Seq("Ibc", "Sbc", "Ib", "Sb").foreach(i => assert(parses(self(str(row(es, i), "tree_bytes_hex"))), s"$i ($a, $t)"))
    }
    // the cut: at v0 the same box is one the JVM's own parser refuses (rule 1001 with no size bit)
    Seq("a4", "a7", "db6504fe", "db6507fe").foreach(bd => assert(!parses(self("00" + bd)), s"00$bd must be refused"))
  }

  test("envelope + write staging files") {
    List(B.OpV5, B.OpV6).foreach { op =>
      assertEquals(v(op).hcursor.get[String]("blessed_by").toOption, Some(B.Stamp))
      assertEquals(v(op).hcursor.get[String]("source").toOption, Some(B.Source))
      assertEquals(v(op).hcursor.get[String]("schema").toOption, Some("santa-eval/v2"))
    }
    val outDir = java.nio.file.Paths.get("target", "eval-authored")
    B.writeVectors(outDir)
    List(B.OpV5, B.OpV6).foreach(op => assert(java.nio.file.Files.exists(outDir.resolve(s"$op.json"))))
  }
}
