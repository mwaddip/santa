package santa

import io.circe.Json
import scorex.util.encode.Base16

import sigma.VersionContext
import sigma.ast._

/** Anchors the tuple binding-provenance family. `SpecExtract` already fails loud if an accept does
  * not evaluate; pinned here is what would rot silently while still grading green:
  *
  *  - every X row's binding IS an expression and every C row's a constant (the bytes' lead byte),
  *    and each X shares its C twin's tree — or the pairs would not isolate the producer;
  *  - the twelve numbers, among them the two places the producer flips a verdict, in OPPOSITE
  *    directions (X5 false / C5 true; C6 false / X6 true);
  *  - the item types that explain both flips, read from the oracle;
  *  - that the same collection built IN-SCRIPT throws, which is what makes X4/C4 a provenance pair;
  *  - that the JSON form of the collection now binds the DATA route (C5's answer), since the
  *    blesser routes every binding through the reader (EvalCore.offTheWire).
  */
class AuthoredEvalTupleBindingProvenanceTest extends munit.FunSuite {
  private val B = AuthoredEvalTupleBindingProvenance
  private lazy val v = B.extract()
  private def entries(op: String): List[Json] =
    v(op).hcursor.downField("entries").as[List[Json]].fold(e => fail(s"$op entries: $e"), identity)
  private lazy val all: List[Json] = List(B.OpVar, B.OpInputVar, B.OpRegister).flatMap(entries)
  private def str(e: Json, f: String): String = e.hcursor.get[String](f).toOption.getOrElse(fail(f))
  private def id(e: Json): String = str(e, "script").takeWhile(_ != ']').drop(1)
  private def row(i: String): Json = all.find(id(_) == i).getOrElse(fail(s"no row $i"))
  private def verdict(e: Json): (String, Long) = {
    val x = e.hcursor.downField("expected")
    (x.downField("value").focus.map(_.noSpaces).getOrElse(fail("value")), x.get[Long]("cost").toOption.getOrElse(fail("cost")))
  }
  private def bindingHex(e: Json): String = {
    val c = e.hcursor
    val j = c.downField("input").focus.filter(_ => id(e).matches("[CX]1"))
      .orElse(c.downField("inputs").downN(0).downField("extension").downField("1").focus)
      .orElse(c.downField("selfRegisters").downField("4").focus)
      .getOrElse(fail(s"${id(e)}: no binding"))
    assertEquals(j.hcursor.get[String]("kind").toOption, Some("Value"), s"${id(e)} must bind a Value")
    j.hcursor.get[String]("bytes_hex").toOption.getOrElse(fail("bytes_hex"))
  }
  private val int3 = """{"kind":"Int","value":3}"""

  test("2 + 2 + 8 rows; X bindings are EXPRESSIONS, C bindings CONSTANTS, each X on its C twin's tree") {
    assertEquals((entries(B.OpVar).size, entries(B.OpInputVar).size, entries(B.OpRegister).size), (2, 2, 8))
    all.foreach { e =>
      val lead = Integer.parseInt(bindingHex(e).take(2), 16)
      if (id(e).startsWith("X")) assert(lead == 0x86 || lead == 0x83, s"${id(e)}: lead byte $lead is not an expression")
      else assert(lead <= 0x70, s"${id(e)}: lead byte $lead is not a constant's type code")
    }
    (1 to 6).foreach { n =>
      assertEquals(str(row(s"X$n"), "tree_bytes_hex"), str(row(s"C$n"), "tree_bytes_hex"), s"X$n vs C$n tree")
    }
  }

  test("THE NUMBERS: X1-X4 equal their constant twins; the producer flips X5/C5 and C6/X6, in opposite directions") {
    Seq("1" -> 39L, "2" -> 54L, "3" -> 89L, "4" -> 124L).foreach { case (n, cost) =>
      assertEquals(verdict(row(s"C$n")), (int3, cost), s"C$n")
      assertEquals(verdict(row(s"X$n")), (int3, cost), s"X$n must answer exactly as C$n")
    }
    assertEquals(verdict(row("C5")), ("""{"kind":"Boolean","value":true}""", 119L))
    assertEquals(verdict(row("X5")), ("""{"kind":"Boolean","value":false}""", 81L))
    assertEquals(verdict(row("C6")), ("""{"kind":"Boolean","value":false}""", 101L))
    assertEquals(verdict(row("X6")), ("""{"kind":"Boolean","value":true}""", 146L))
  }

  private def boundItemType(v: Value[_ <: SType]): sigma.data.RType[_] =
    EvalCore.offTheWire(EvalCore.decodeBinding(B.binding(v)), B.V3).value.asInstanceOf[sigma.Coll[Any]].tItem

  test("THE MECHANISM: the item types behind both flips, from the oracle") {
    assertEquals[Any, Any](boundItemType(B.CollData), sigma.collRType(sigma.AnyType), "the data decoder's Coll[Any]")
    assertEquals(boundItemType(B.CollExpr).getClass.getSimpleName, "TupleType", "the expression's stypeToRType")
    // the tree's pair constant, as the tree reader builds it: the nested tuple is a TupleType
    VersionContext.withVersions(B.V3, B.V3) {
      val t = sigma.santa.LenientErgoTree.deserialize(Base16.decode(B.TreeRegZipEq).get)
      val consts = scala.collection.mutable.ArrayBuffer[sigma.Coll[_]]()
      def walk(x: Any): Unit = x match {
        case c: Constant[_] if c.tpe.isInstanceOf[SCollection[_]] => consts += c.value.asInstanceOf[sigma.Coll[_]]
        case p: Product => p.productIterator.foreach(walk)
        case _ =>
      }
      walk(t.toProposition(false))
      val pairType = consts.map(_.tItem).collectFirst { case p: sigma.data.PairType[_, _] => p }
        .getOrElse(fail("no pair constant in C6's tree"))
      assertEquals(pairType.tFst.getClass.getSimpleName, "TupleType", "the nested tuple's item type")
    }
  }

  test("the SAME collection built IN-SCRIPT throws: X4/C4 differ from it only by provenance") {
    val inScript = B.tree(SizeOf(ByIndex(ConcreteCollection(Array[Value[SType]](B.TupleConst, B.TupleConst), B.Tt),
      IntConstant(0), None).asInstanceOf[Value[SCollection[SType]]]).asInstanceOf[Value[SType]])
    EvalCore.evalEntry(inScript, B.V3)._2 match {
      case Left(err) => assert(err.contains("Unsupported tuple type"), err)
      case Right(r)  => fail(s"the in-script collection must throw, got $r")
    }
  }

  test("the JSON form of the collection binds the DATA route: C5's answer") {
    val t3 = Json.obj("kind" -> Json.fromString("Tuple"), "items" -> Json.arr(
      (1 to 3).map(i => Json.obj("kind" -> Json.fromString("Int"), "value" -> Json.fromInt(i))): _*))
    val json = Json.obj("kind" -> Json.fromString("Coll"), "elem" -> EvalCore.stypeToJson(B.Tt),
      "items" -> Json.arr(t3))
    val r = EvalCore.evalWithSelfRegistersAndVar1(B.TreeRegEq, Map(4 -> json),
      Json.obj("kind" -> Json.fromString("Int"), "value" -> Json.fromInt(0)), B.V3)._2
    assertEquals(r.map { case (value, cost) => (value.noSpaces, cost) }, Right(verdict(row("C5"))))
  }

  test("every row states its role: C rows are CONTROLS, X rows SEPARATING") {
    all.foreach { e =>
      val d = str(e, "script")
      if (id(e).startsWith("C")) assert(d.contains("CONTROL"), s"${id(e)}: $d")
      else assert(d.contains("SEPARATING"), s"${id(e)} must say what it separates: $d")
    }
  }

  test("envelopes + write staging files") {
    List(B.OpVar -> "santa-eval/v2", B.OpInputVar -> "santa-eval/v3", B.OpRegister -> "santa-eval/v4").foreach {
      case (op, schema) =>
        val c = v(op).hcursor
        assertEquals(c.get[String]("schema").toOption, Some(schema), op)
        assertEquals(c.get[String]("blessed_by").toOption, Some(B.Stamp), op)
        assertEquals(c.get[String]("source").toOption, Some(B.Source), op)
    }
    val outDir = java.nio.file.Paths.get("target", "eval-authored")
    B.writeVectors(outDir)
    List(B.OpVar, B.OpInputVar, B.OpRegister).foreach(op =>
      assert(java.nio.file.Files.exists(outDir.resolve(s"$op.json")), op))
  }
}
