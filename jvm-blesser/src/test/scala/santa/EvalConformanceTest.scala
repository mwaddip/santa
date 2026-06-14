package santa

import java.nio.file.{Files, Path, Paths}

import io.circe.Json
import io.circe.parser.parse

import sigma.ast.{EvaluatedValue, SType}

/** Cross-check / regression gate: every committed vector file under vectors/eval/ must
  * reproduce when re-evaluated through EvalCore.
  *
  * For v2 entries (santa-eval/v2): calls EvalCore.evalApplied(tree_bytes_hex, input,
  * version.activated) and asserts result matches expected (value JSON equal, cost equal,
  * error equal).
  *
  * For v1 entries (santa-eval/v1, closed-tree): calls EvalCore.evalEntry(tree_bytes_hex,
  * version.activated) — same assertion, no input.
  *
  * This is the committed regression gate — SANTA re-blessing reproduces every canonical
  * vector. A mismatch means a serialization / encode round-trip issue, which is a real
  * finding; it is reported loudly with op + entry name. */
class EvalConformanceTest extends munit.FunSuite {

  private val vectorsDir = Paths.get("../vectors/eval")

  private def vectorFiles: Seq[java.nio.file.Path] = {
    import scala.jdk.CollectionConverters._
    Files.walk(vectorsDir)
      .filter((p: Path) => p.toString.endsWith(".json"))
      .iterator()
      .asScala
      .toSeq
      .sortBy(_.toString)
  }

  private def parse(s: String): Json =
    io.circe.parser.parse(s).fold(e => sys.error(s"JSON parse failed: $e\n$s"), identity)

  /** Run the cross-check for a single vector file; returns (total, passed) counts. */
  private def checkFile(path: java.nio.file.Path): (Int, Int) = {
    val raw     = new String(Files.readAllBytes(path))
    val doc     = parse(raw)
    val c       = doc.hcursor
    val schema  = c.get[String]("schema").fold(e => sys.error(s"missing schema in $path: $e"), identity)
    val op      = c.get[String]("op").fold(e => sys.error(s"missing op in $path: $e"), identity)
    val entries = c.downField("entries").as[List[Json]]
      .fold(e => sys.error(s"missing/invalid entries in $path: $e"), identity)

    var total  = 0
    var passed = 0

    entries.foreach { entry =>
      val ec        = entry.hcursor
      val name      = ec.get[String]("name").fold(e => sys.error(s"missing name in entry: $e"), identity)
      val treeHex   = ec.get[String]("tree_bytes_hex").fold(e => sys.error(s"missing tree_bytes_hex: $e"), identity)
      val activated = ec.downField("version").get[Int]("activated")
        .fold(e => sys.error(s"missing version.activated: $e"), identity).toByte

      val expValue = ec.downField("expected").downField("value").focus
        .getOrElse(sys.error(s"missing expected.value in $op/$name"))
      // cost is null for error-expected entries; decode as Option[Long]
      val expCostOpt: Option[Long] = ec.downField("expected").downField("cost").focus
        .getOrElse(sys.error(s"missing expected.cost field in $op/$name")) match {
          case j if j.isNull => None
          case j             => Some(j.as[Long].fold(e => sys.error(s"expected.cost not Long in $op/$name: $e"), identity))
        }
      val expError = ec.downField("expected").downField("error").focus
        .getOrElse(sys.error(s"missing expected.error in $op/$name"))

      val (_, outcome) = schema match {
        case "santa-eval/v3" =>
          val inputs = ec.downField("inputs").values.getOrElse(Vector.empty).toVector.map { inp =>
            val ext = inp.hcursor.downField("extension")
            ext.keys.getOrElse(Iterable.empty).iterator.map { k =>
              k.toInt.toByte -> EvalCore.decodeInputConstant(
                ext.downField(k).focus.getOrElse(sys.error(s"v3 entry '$name': missing extension value for key $k")))
            }.toMap
          }
          EvalCore.evalWithInputExtensions(treeHex, inputs, activated)
        case "santa-eval/v4" =>
          // SELF box carries selfRegisters (id (0-based Int) -> SValue JSON) + var 1 = input.
          val regsObj = ec.downField("selfRegisters").focus
            .getOrElse(sys.error(s"missing selfRegisters in $op/$name"))
          val registersJson: Map[Int, Json] = regsObj.asObject
            .getOrElse(sys.error(s"selfRegisters must be an object in $op/$name"))
            .toMap.map { case (k, v) => k.toInt -> v }
          val inputJson = ec.downField("input").focus
            .getOrElse(sys.error(s"missing input in $op/$name"))
          EvalCore.evalWithSelfRegistersAndVar1(treeHex, registersJson, inputJson, activated)
        case "santa-eval/v2" =>
          val inputJson = ec.downField("input").focus
            .getOrElse(sys.error(s"missing input in $op/$name"))
          EvalCore.evalApplied(treeHex, inputJson, activated)
        case "santa-eval/v1" =>
          EvalCore.evalEntry(treeHex, activated)
        case "santa-eval/v5" =>
          val extensionJson: Map[Int, Json] = ec.downField("extension").focus
            .flatMap(_.asObject).map(_.toMap.map { case (k, v) => k.toInt -> v })
            .getOrElse(sys.error(s"missing/invalid extension in $op/$name"))
          EvalCore.evalWithTopExtension(treeHex, extensionJson, activated)
        case "santa-eval/v6-fullctx" =>
          // Full-context eval — reuse the walker oracle's envelope parser (same path as
          // runner/Runner.evalEntry's v6-fullctx arm: parse context.* + EvalCore.evalFullContext).
          WalkerOracle.evalEnvelope(entry)
        case other =>
          sys.error(s"EvalConformanceTest: unknown schema '$other' in $path")
      }

      total += 1

      outcome match {
        case Right((valueJson, cost)) =>
          assertEquals(
            valueJson.noSpaces, expValue.noSpaces,
            s"value mismatch in op=$op name=$name")
          expCostOpt.foreach { expCost =>
            assertEquals(
              cost, expCost,
              s"cost mismatch in op=$op name=$name (got $cost, expected $expCost)")
          }
          assertEquals(
            expError, Json.Null,
            s"error mismatch in op=$op name=$name (expected error=${expError.noSpaces} but eval succeeded)")
          passed += 1

        case Left(errDetail) =>
          if (expError == Json.Null) {
            fail(s"eval failed in op=$op name=$name: $errDetail (expected success with value=${expValue.noSpaces})")
          } else {
            // error expected and error received — cross-check passes (error class not compared)
            passed += 1
          }
      }
    }

    (total, passed)
  }

  test("all committed eval vectors reproduce (cross-check)") {
    val files = vectorFiles
    assert(files.nonEmpty, s"no vector files found under $vectorsDir")

    var totalAll  = 0
    var passedAll = 0

    files.foreach { path =>
      val (t, p) = checkFile(path)
      totalAll  += t
      passedAll += p
    }

    println(
      s"\nEvalConformanceTest: $passedAll/$totalAll entries re-blessed across ${files.size} vector files\n")

    assertEquals(passedAll, totalAll,
      s"cross-check: $passedAll/$totalAll entries passed (${totalAll - passedAll} mismatches)")
  }
}
