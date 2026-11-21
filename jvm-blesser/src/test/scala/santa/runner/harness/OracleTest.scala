package santa.runner.harness

import io.circe.Json
import io.circe.parser.parse
import java.nio.file.{Files, Path, Paths}
import scala.sys.process._

/** rudolph's harness against SANTA's oracles (`..` from jvm-blesser/): every verdict and selection case reproduced. */
class OracleTest extends munit.FunSuite {
  val santa: Path = Paths.get("..").toRealPath()

  def json(p: Path): Json = parse(new String(Files.readAllBytes(p), "UTF-8")).fold(e => fail(e.toString), identity)
  def grading: Grading = Grading.parse(json(santa.resolve("vectors/grading.json"))).fold(e => fail(e), identity)
  def temp(tag: String): Path = Files.createTempDirectory(s"rudolph-harness-$tag")

  test("every verdict and selection case of the oracle is reproduced") {
    assertEquals(Oracle.check(santa, grading), Right(()))
  }

  test("a wrong verdict is named") {
    val fake = temp("wrong")
    Files.createDirectories(fake.resolve("oracle"))
    Files.copy(santa.resolve("oracle/selection.json"), fake.resolve("oracle/selection.json"))
    val doc = json(santa.resolve("oracle/verdicts-grading.json"))
    val cases = doc.hcursor.downField("cases").focus.flatMap(_.asArray).get
    val first = cases.head
    val flipped = if (first.hcursor.downField("verdict").downField("outcome").as[String] == Right("panicked"))
      Json.obj("outcome" -> Json.fromString("not-implemented")) else Json.obj("outcome" -> Json.fromString("panicked"))
    val bad = doc.mapObject(_.add("cases", Json.fromValues(first.mapObject(_.add("verdict", flipped)) +: cases.tail)))
    Files.write(fake.resolve("oracle/verdicts-grading.json"), bad.noSpaces.getBytes("UTF-8"))
    val e = Oracle.check(fake, grading).left.getOrElse(fail("a flipped verdict passed"))
    assert(e.contains(first.hcursor.downField("name").as[String].toOption.get), e)
  }

  test("an oracle file without its cases or files is refused") {
    Seq("""{"schema": "santa-oracle-grading/v1"}""" -> "no cases",
        """{"schema": "santa-oracle-selection/v1", "cases": []}""" -> "no files",
        """{"schema": "santa-oracle-selection/v1", "files": []}""" -> "no cases").foreach { case (body, why) =>
      val fake = temp("empty")
      Files.createDirectories(fake.resolve("oracle"))
      Files.write(fake.resolve("oracle/x.json"), body.getBytes("UTF-8"))
      val e = Oracle.check(fake, grading).left.getOrElse(fail(s"$body passed"))
      assert(e.contains(why), e)
    }
  }

  test("discover walks as the Rust harness: symlinked directories followed, a dangling *.json listed, an unreadable directory skipped") {
    val root = temp("discover")
    val v = root.resolve("vectors")
    Files.createDirectories(v.resolve("eval/v6/authored"))
    Files.write(v.resolve("eval/v6/authored/A.json"), "{}".getBytes("UTF-8"))
    Files.createSymbolicLink(v.resolve("eval/v6/authored/B.json"), root.resolve("nowhere.json"))
    Files.createDirectories(root.resolve("elsewhere/spec"))
    Files.write(root.resolve("elsewhere/spec/C.json"), "{}".getBytes("UTF-8"))
    Files.createDirectories(v.resolve("wire"))
    Files.createSymbolicLink(v.resolve("wire/v6"), root.resolve("elsewhere"))
    val shut = v.resolve("chain/v6/spec")
    Files.createDirectories(shut)
    Files.write(shut.resolve("D.json"), "{}".getBytes("UTF-8"))
    shut.toFile.setReadable(false, false); shut.toFile.setExecutable(false, false)
    try assertEquals(Selection.discover(v).map(_.map(_.rel)), Right(Seq("eval/v6/authored/A.json", "eval/v6/authored/B.json", "wire/v6/spec/C.json")))
    finally { shut.toFile.setReadable(true, false); shut.toFile.setExecutable(true, false) }
  }

  test("an unknown oracle schema is refused") {
    val fake = temp("unknown")
    Files.createDirectories(fake.resolve("oracle"))
    Files.write(fake.resolve("oracle/x.json"), """{"schema": "santa-oracle-foo/v1", "cases": []}""".getBytes("UTF-8"))
    val e = Oracle.check(fake, grading).left.getOrElse(fail("an unknown oracle schema passed"))
    assert(e.contains("santa-oracle-foo/v1"), e)
  }

  test("the SANTA commit follows real paths") {
    val head = Seq("git", "-C", santa.toString, "rev-parse", "HEAD").!!.trim
    assertEquals(Scope.santaCommit(santa), Some(head))
    assertEquals(Scope.santaCommit(santa.resolve("vectors").resolve("..")), Some(head))
    assertEquals(Scope.santaCommit(temp("not-git")), None)
  }

  test("a scope is refused as conform refuses it") {
    val bad = Seq(
      """{"version": "v6", "tiers": []}""",
      """{"version": "v6", "tiers": ["evil"]}""",
      """{"version": "v6", "tiers": ["eval", "eval"]}""",
      """{"version": "v6", "tiers": ["wire"], "kinds": ["Box", "Box"]}""",
      """{"version": "v6", "tiers": ["eval"], "kinds": ["Box"]}""",
      """{"version": "any", "tiers": ["eval"]}""",
      """{"version": "v6", "tiers": ["wire"], "kinds": []}""",
      """{"version": "v6", "tiers": ["wire"], "kinds": ["Boxes"]}""",
      """{"version": "v6", "tiers": ["eval"], "activations": false}""")
    bad.foreach(s => assert(Scope.parse(parse(s).toOption.get).isLeft, s"accepted $s"))
    val ok = Scope.parse(parse("""{"name": "x", "impl": null, "version": "v6", "tiers": ["eval", "wire"]}""").toOption.get)
    assertEquals(ok, Right(Scope("v6", Seq("eval", "wire"), cost = true, kinds = None, activation = true)))
  }
}
