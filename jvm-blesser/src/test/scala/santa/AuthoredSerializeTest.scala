package santa

/** Guard + smoke test for the authored Global.serialize[T] vectors (delegated +
  * composite types absent from LanguageSpecificationV6). Asserts every target op is
  * authored and well-formed (serialize ⇒ Coll[Byte], positive cost, no error), prints
  * the blessed costs, and writes the staging vectors. Exact-cost anchors are added
  * below once the blessed numbers are observed (regression baseline). */
class AuthoredSerializeTest extends munit.FunSuite {

  lazy val vectors: Map[String, io.circe.Json] = AuthoredSerialize.extract()

  private val expectedOps = Set(
    "Global.serialize[GroupElement]", "Global.serialize[SigmaProp]",
    "Global.serialize[UnsignedBigInt]", "Global.serialize[AvlTree]",
    "Global.serialize[Box]", "Global.serialize[Header]",
    "Global.serialize[Coll[GroupElement]]", "Global.serialize[Option[BigInt]]",
    "Global.serialize[(Box, Int)]")

  test("all delegated/composite serialize ops authored, each well-formed") {
    assertEquals(vectors.keySet, expectedOps,
      s"authored ops mismatch; got ${vectors.keys.toSeq.sorted}")

    vectors.foreach { case (op, env) =>
      val c = env.hcursor
      assertEquals(c.get[String]("schema").toOption, Some("santa-eval/v2"), s"$op schema")
      assertEquals(c.get[String]("op").toOption, Some(op), s"$op op field")
      assertEquals(c.get[String]("blessed_by").toOption, Some("jvm:sigma-state-6.0.3"), s"$op blessed_by")
      assertEquals(c.get[String]("source").toOption, Some("santa:authored-serialize"), s"$op source")

      val entries = c.downField("entries").as[List[io.circe.Json]]
        .fold(e => fail(s"$op entries missing/invalid: $e"), identity)
      assert(entries.nonEmpty, s"$op has no entries")

      entries.foreach { e =>
        val ec = e.hcursor
        assert(ec.get[String]("script").toOption.exists(_.contains("serialize")), s"$op entry script")
        // serialize always yields a Coll[Byte]; cost is the raw JIT eval cost; no error.
        assertEquals(ec.downField("expected").downField("value").get[String]("kind").toOption,
          Some("Coll"), s"$op expected.value kind")
        assertEquals(ec.downField("expected").downField("value").downField("elem").get[String]("tag").toOption,
          Some("SByte"), s"$op expected.value elem")
        val cost = ec.downField("expected").get[Long]("cost").toOption.getOrElse(-1L)
        assert(cost > 0, s"$op cost must be positive, got $cost")
        assertEquals(ec.downField("expected").downField("error").focus.map(_.noSpaces), Some("null"), s"$op error")
      }
    }
  }

  test("summary + write staging vectors") {
    val sb = new StringBuilder("\n==================== authored serialize vectors ====================\n")
    vectors.toSeq.sortBy(_._1).foreach { case (op, env) =>
      val entries = env.hcursor.downField("entries").as[List[io.circe.Json]].getOrElse(Nil)
      val costs = entries.map { e =>
        val n = e.hcursor.get[String]("name").getOrElse("?")
        val cost = e.hcursor.downField("expected").get[Long]("cost").getOrElse(-1L)
        val len = e.hcursor.downField("expected").downField("value").downField("items")
          .as[List[io.circe.Json]].map(_.size).getOrElse(-1)
        s"$n: cost=$cost len=$len"
      }
      sb.append(s"  $op\n      ${costs.mkString(" | ")}\n")
    }
    sb.append("====================================================================\n")
    println(sb.toString)

    val outDir = java.nio.file.Paths.get("target", "authored-vectors")
    AuthoredSerialize.writeVectors(vectors, outDir)
    assert(java.nio.file.Files.exists(outDir.resolve("Global.serialize_GroupElement.json")),
      "staging vector for Global.serialize[GroupElement] was not written")
  }

  // ── regression baseline: exact blessed costs, locked after the first run. A change
  //    here means the JVM cost model or an input moved — investigate, do not blindly
  //    re-bless (these costs are what sigma-rust verifies its serialize accounting against).
  private val expectedCosts: Map[String, Seq[(String, Long)]] = Map(
    "Global.serialize[GroupElement]"       -> Seq("generator#0" -> 125L),
    "Global.serialize[SigmaProp]"          -> Seq("proveDlog(generator)#0" -> 126L),
    "Global.serialize[UnsignedBigInt]"     -> Seq("0#0" -> 95L, "255#1" -> 96L, "2^255#2" -> 127L),
    "Global.serialize[AvlTree]"            -> Seq("dummy#0" -> 127L, "withValueLen#1" -> 127L),
    "Global.serialize[Box]"                -> Seq("minimal#0" -> 139L, "withR4#1" -> 143L),
    "Global.serialize[Header]"             -> Seq("specFixture#0" -> 333L),
    "Global.serialize[Coll[GroupElement]]" -> Seq("empty#0" -> 92L, "one#1" -> 128L, "two#2" -> 164L),
    "Global.serialize[Option[BigInt]]"     -> Seq("Some(0)#0" -> 97L, "Some(2^200)#1" -> 122L),
    "Global.serialize[(Box, Int)]"         -> Seq("(minimal,0)#0" -> 142L, "(withR4,42)#1" -> 146L))

  test("blessed costs match the recorded baseline") {
    expectedCosts.foreach { case (op, expected) =>
      val env = vectors.getOrElse(op, fail(s"no vector for $op"))
      val entries = env.hcursor.downField("entries").as[List[io.circe.Json]]
        .fold(e => fail(s"$op entries: $e"), identity)
      expected.foreach { case (name, cost) =>
        val e = entries.find(_.hcursor.get[String]("name").toOption.contains(name))
          .getOrElse(fail(s"$op: no entry named '$name'; have " +
            s"${entries.flatMap(_.hcursor.get[String]("name").toOption)}"))
        assertEquals(e.hcursor.downField("expected").get[Long]("cost").toOption, Some(cost),
          s"$op[$name] cost drifted")
      }
    }
  }

  test("GroupElement serialize tree + value anchored (deep regression)") {
    val e = vectors("Global.serialize[GroupElement]").hcursor
      .downField("entries").as[List[io.circe.Json]].toOption.get.head.hcursor
    assertEquals(e.get[String]("tree_bytes_hex").toOption,
      Some("1b1200dad9010107dc6a03dd01720101e4e30107"))
    // serialize(generator) == the 33 raw compressed-point bytes, as a Coll[Byte].
    val items = e.downField("expected").downField("value").downField("items")
      .as[List[io.circe.Json]].toOption.get
    assertEquals(items.size, 33)
    assertEquals(items.head.hcursor.get[Int]("value").toOption, Some(2)) // 0x02 point prefix
  }
}
