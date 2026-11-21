package santa.runner.harness

import io.circe.Json
import io.circe.parser.parse
import java.nio.file.{Files, Path, Paths}
import scala.sys.process._

/** Main.run end to end over SANTA (`..`), with an honest per-file function standing in for rudolph's engines. A chain
  * scope at v4 selects no bounded file; the board run covers the child path. */
class HarnessRunTest extends munit.FunSuite with Scratch {
  val santa: Path = Paths.get("..").toRealPath()

  /** A chain answer: the entry's `expected`, with `error: null` when it has none. */
  val honest: Main.RunFile = (_, doc) =>
    doc.hcursor.downField("entries").focus.flatMap(_.asArray).getOrElse(Vector.empty).map { e =>
      val exp = e.hcursor.downField("expected").focus.flatMap(_.asObject).getOrElse(io.circe.JsonObject.empty)
      e.hcursor.downField("name").as[String].getOrElse("") -> Json.fromJsonObject(if (exp.contains("error")) exp else exp.add("error", Json.Null))
    }

  def read(p: Path): Json = parse(new String(Files.readAllBytes(p), "UTF-8")).fold(e => fail(e.toString), identity)

  test("an honest run writes results.json and one actuals file per selected file, with no reds") {
    val d = scratch("run")
    val rj = d.resolve("runner.json")
    Files.write(rj, """{"name": "t", "version": "v4", "tiers": ["chain"]}""".getBytes("UTF-8"))
    val out = d.resolve("out")
    assertEquals(Main.run(rj, santa, out, honest, (_, _, _) => Seq("false")), Right(()))
    val r = read(out.resolve("results.json"))
    ResultsShape.check(r)
    val c = r.hcursor
    assertEquals(c.downField("schema").as[String], Right("santa-runner-results/v1"))
    assertEquals(c.downField("santa").as[String], Right(Seq("git", "-C", santa.toString, "rev-parse", "HEAD").!!.trim))
    assertEquals(c.downField("totals").downField("red").as[Int], Right(0))
    assertEquals(c.downField("scope").downField("version").as[String], Right("v4"))
    val slices = c.downField("slices").focus.flatMap(_.asObject).get
    assert(slices.nonEmpty)
    slices.toIterable.foreach { case (k, s) =>
      assert(k.matches("chain/(any|v4)/[a-z]+"), k)
      val files = s.hcursor.downField("files").as[Vector[String]].toOption.get
      files.foreach(f => assert(Files.isRegularFile(out.resolve("actuals").resolve(f)), s"no actuals for $f"))
      assertEquals(s.hcursor.downField("red").as[Vector[Json]].map(_.size), Right(0))
    }
    assert(!Files.exists(out.resolve(".children")))
  }

  test("a run removes the previous results.json and actuals/ before anything else: a failed run leaves none") {
    val fake = scratch("fake-santa")
    Files.createDirectories(fake.resolve("vectors"))
    Files.createDirectories(fake.resolve("oracle"))
    Files.copy(santa.resolve("vectors/grading.json"), fake.resolve("vectors/grading.json"))
    Files.copy(santa.resolve("oracle/selection.json"), fake.resolve("oracle/selection.json"))
    val doc = read(santa.resolve("oracle/verdicts-grading.json"))
    val cases = doc.hcursor.downField("cases").focus.flatMap(_.asArray).get
    val wrong = doc.mapObject(_.add("cases", Json.fromValues(cases.head.mapObject(_.add("verdict", Json.obj("outcome" -> Json.fromString("panicked")))) +: cases.tail)))
    Files.write(fake.resolve("oracle/verdicts-grading.json"), wrong.noSpaces.getBytes("UTF-8"))
    val d = scratch("clears")
    val rj = d.resolve("runner.json")
    Files.write(rj, """{"name": "t", "version": "v4", "tiers": ["chain"]}""".getBytes("UTF-8"))
    val out = d.resolve("out")
    Files.createDirectories(out.resolve("actuals/chain/v4/spec"))
    Files.write(out.resolve("results.json"), "{}".getBytes("UTF-8"))
    Files.write(out.resolve("actuals/chain/v4/spec/old.json"), "{}".getBytes("UTF-8"))
    assert(Main.run(rj, fake, out, honest, (_, _, _) => Seq("false")).isLeft)
    assert(!Files.exists(out.resolve("results.json")), "an earlier results.json survived a failed run")
    assert(!Files.exists(out.resolve("actuals")), "earlier actuals survived a failed run")
  }

  test("a verb with the wrong arguments is a usage error, not a file to read") {
    Seq(Array("run", "runner.json"), Array("one", "santa"), Array("run", "a", "b")).foreach { a =>
      assert(_root_.santa.runner.Runner.verbError(a).exists(_.contains("usage: runner run <runner.json> <santa-dir> <out-dir>")), a.mkString(" "))
    }
    assertEquals(_root_.santa.runner.Runner.verbError(Array("run", "a", "b", "c")), None)
    assertEquals(_root_.santa.runner.Runner.verbError(Array("some/vector.json")), None)
  }
}
