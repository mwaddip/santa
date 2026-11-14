package santa

// Authored eval + wire vectors: a METHOD CALL with NO arguments (`dc <type> <method> <obj> 00`) is read below tree
// v3 and refused from v3. From sigma-rust's vector backlog (2026-11-14, fork PR 91: "the method-call form with no
// arguments below tree version 3, dc650[4-7]fe00, a row we never asked for"); measured by
// santa.spike.NoArgMethodCallProbe (local).
//
// THE RULE, read in 6.0.7: `MethodCallSerializer.parse` reads the argument list and, only from tree v3,
// `assert(args.nonEmpty)` ("the same check we have in serializer since v3 trees", MethodCallSerializer.scala:52-55).
// Below v3 a zero-argument `MethodCall` is built, evaluates as the property it names (CONTEXT.INPUTS / OUTPUTS /
// HEIGHT / SELF, ids 4-7), at the PropertyCall form's exact cost, and is written back AS a PropertyCall
// (`MethodCall.companion` is PropertyCall when the arguments are empty, values.scala:1351): a non-identity
// round-trip. From v3 the assertion throws `AssertionError`, which is no ValidationException, so the size bit does
// not degrade it: a reject.
//
// Families: eval Context.method_call_no_args (activated 2, tree v0: values) and Context.method_call_no_args_v6
// (activated 3, tree v3: refused), each row beside its PropertyCall twin; wire ErgoTree.method_call_no_args in v5
// (unsized v0, `expected_bytes_hex` the PropertyCall form) and v6 (sized v3, rejected).

import io.circe.Json
import scorex.util.encode.Base16
import sigma.serialization.SigmaSerializer

object AuthoredMethodCallNoArgs {
  val Source = "santa:authored-method-call-no-args"
  val Stamp = "jvm:sigma-state-6.0.7"
  val Op5 = "Context.method_call_no_args"
  val Op6 = "Context.method_call_no_args_v6"
  val OpWire = "ErgoTree.method_call_no_args"
  val IntInput: Json = Json.obj("kind" -> Json.fromString("Int"), "value" -> Json.fromInt(0))
  private def hx(bs: Array[Byte]): String = Base16.encode(bs).toLowerCase
  private def vlq(n: Int): String = { val w = SigmaSerializer.startWriter(); w.putUInt(n.toLong); hx(w.toBytes) }

  /** (id, the expression, the method-call body, the property-call body) */
  val Rows: Seq[(String, String, String, String)] = Seq(
    ("I", "CONTEXT.INPUTS.size", "b1dc6504fe00", "b1db6504fe"),
    ("O", "CONTEXT.OUTPUTS.size", "b1dc6505fe00", "b1db6505fe"),
    ("H", "CONTEXT.HEIGHT", "dc6506fe00", "db6506fe"),
    ("S", "CONTEXT.SELF.value", "c1dc6507fe00", "c1db6507fe"))

  def evalExtract(): Map[String, Json] = {
    def family(op: String, v6: Boolean): Json = {
      val act: Byte = if (v6) 3 else 2
      val tv = if (v6) 3 else 0
      def tree(b: String) = if (v6) "0b" + vlq(b.length / 2) + b else "00" + b
      val entries = Rows.flatMap { case (id, e, mc, pc) => Seq((id, e, mc, true), (s"${id}p", e, pc, false)) }
        .zipWithIndex.map { case ((id, e, body, isMc), i) =>
          val s =
            if (!isMc) s"[$id] `$e` as a PROPERTY call (`db`). CONTROL: the form both versions read."
            else if (v6) s"[$id] `$e` as a METHOD call with NO arguments (`dc … 00`) at tree v3 — REFUSED AT PARSE " +
              "(`AssertionError`): from v3 the reader asserts the argument list is non-empty " +
              "(MethodCallSerializer.scala:52-55). SEPARATING: an implementation that reads it at v3 returns a value."
            else s"[$id] `$e` as a METHOD call with NO arguments (`dc … 00`) at tree v0 — READ below v3 and evaluated " +
              "as the property, at the property call's exact cost. SEPARATING: an implementation that refuses a " +
              "zero-argument method call at every version errors."
          val name = s"${id.toLowerCase}-${if (isMc) "method-call" else "property-call"}#$i"
          if (isMc && v6) SpecExtract.authoredRejectEntryV(op, s, tree(body), name, IntInput, act, ergoTree = tv)
          else SpecExtract.authoredEntryV(op, s, tree(body), name, IntInput, act, ergoTree = tv)
        }
      SpecExtract.authoredEnvelope(op, entries, Source).mapObject(_.add("blessed_by", Json.fromString(Stamp)))
    }
    Map(Op5 -> family(Op5, v6 = false), Op6 -> family(Op6, v6 = true))
  }

  /** sigmaProp(x == x) around each method-call body. */
  def wireExtract(v6: Boolean): Json = {
    val act: Byte = if (v6) 3 else 2
    val tv: Byte = if (v6) 3 else 0
    val entries = Rows.zipWithIndex.map { case ((id, e, mc, pc), i) =>
      val b = "d193" + mc + mc
      val in = if (v6) "0b" + vlq(b.length / 2) + b else "00" + b
      val o = scala.util.Try(WireCanonicalize.canonicalize("ErgoTree", in, act, tv))
      val extra: Seq[(String, Json)] = (v6, o) match {
        case (false, scala.util.Success(out)) =>
          require(out == "00d193" + pc + pc, s"$id v0: must re-write as the property call, got $out")
          Seq("expected_bytes_hex" -> Json.fromString(out))
        case (true, scala.util.Failure(t)) =>
          require(Iterator.iterate(t: Throwable)(_.getCause).takeWhile(_ != null).exists(_.isInstanceOf[AssertionError]),
            s"$id v3: refused by the wrong exception: $t")
          Seq("error" -> Json.fromString("errored"))
        case other => sys.error(s"$id v6=$v6: unexpected $other")
      }
      val what =
        if (v6) s"`sigmaProp($e == $e)`, both sides the zero-argument METHOD call, sized tree v3 — REJECTED " +
          "(`AssertionError`, not a ValidationException: the size bit does not degrade it)."
        else s"`sigmaProp($e == $e)`, both sides the zero-argument METHOD call, unsized tree v0 — read, and written " +
          "back as the PROPERTY call (`db`): the expected bytes differ from the input. SEPARATING: an implementation " +
          "that echoes the input, or refuses it, is red."
      Json.obj(Seq("name" -> Json.fromString(s"method-call-no-args-${id.toLowerCase}-${if (v6) "v3" else "v0"}#$i"),
        "kind" -> Json.fromString("ErgoTree"), "source" -> Json.fromString(Source),
        "description" -> Json.fromString(s"[$id] $what"), "bytes_hex" -> Json.fromString(in)) ++ extra ++
        Seq("version" -> Json.obj("activated" -> Json.fromInt(act.toInt), "ergoTree" -> Json.fromInt(tv.toInt))): _*)
    }
    Json.obj("schema" -> Json.fromString("santa-wire/v1"), "op" -> Json.fromString(OpWire),
      "blessed_by" -> Json.fromString(Stamp), "entries" -> Json.arr(entries: _*))
  }

  def writeVectors(evalDir: java.nio.file.Path, wireDir: java.nio.file.Path): Unit = {
    SpecExtract.writeStaging("AuthoredMethodCallNoArgs.eval", evalExtract(), evalDir)
    SpecExtract.writeStaging("AuthoredMethodCallNoArgs.v5", Map(OpWire -> wireExtract(v6 = false)), wireDir.resolve("v5"))
    SpecExtract.writeStaging("AuthoredMethodCallNoArgs.v6", Map(OpWire -> wireExtract(v6 = true)), wireDir.resolve("v6"))
  }
}
