package santa.runner.harness

import io.circe.Json
import io.circe.parser.parse
import java.nio.file.{Files, Path, Paths}
import scala.sys.process._

/** Main.run end to end over SANTA (`..`), with an honest per-file function standing in for rudolph's engines. A chain
  * scope at v4 selects no bounded file; the board run covers the child path. */
class HarnessRunTest extends munit.FunSuite {
  val santa: Path = Paths.get("..").toRealPath()

  /** A chain answer: the entry's `expected`, with `error: null` when it has none. */
  val honest: Main.RunFile = (_, doc) =>
    doc.hcursor.downField("entries").focus.flatMap(_.asArray).getOrElse(Vector.empty).map { e =>
      val exp = e.hcursor.downField("expected").focus.flatMap(_.asObject).getOrElse(io.circe.JsonObject.empty)
      e.hcursor.downField("name").as[String].getOrElse("") -> Json.fromJsonObject(if (exp.contains("error")) exp else exp.add("error", Json.Null))
    }

  def read(p: Path): Json = parse(new String(Files.readAllBytes(p), "UTF-8")).fold(e => fail(e.toString), identity)

  test("an honest run writes results.json and one actuals file per selected file, with no reds") {
    val d = Files.createTempDirectory("rudolph-run")
    val rj = d.resolve("runner.json")
    Files.write(rj, """{"name": "t", "version": "v4", "tiers": ["chain"]}""".getBytes("UTF-8"))
    val out = d.resolve("out")
    assertEquals(Main.run(rj, santa, out, honest, (_, _, _) => Seq("false")), Right(()))
    val r = read(out.resolve("results.json"))
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
}
