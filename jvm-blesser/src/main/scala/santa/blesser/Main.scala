package santa.blesser

import io.circe.Json
import sigma.VersionContext

import santa.EvalCore

/** SANTA JVM blesser (Rudolph, produce-mode).
  *
  * Reads an input fixture (the `fixture-gen` shape — entries with
  * `{ name, tree_bytes_hex }`), blesses each through the canonical reference
  * interpreter, and emits a canonical `santa-eval/v1` vector — the nice list.
  *
  *   run <input-fixture.json> [<output-vector.json>]
  *
  * With an output path it writes the vector; without, it prints it to stdout.
  */
object Main {
  def main(args: Array[String]): Unit = {
    val inPath = args.headOption.getOrElse {
      System.err.println("usage: run <input-fixture.json> [<output-vector.json>]")
      sys.exit(2)
    }
    val outPath = args.lift(1)

    val doc    = io.circe.parser.parse(scala.io.Source.fromFile(inPath).mkString)
      .fold(e => sys.error(s"bad json: $e"), identity)
    val corpus = doc.hcursor.get[String]("corpus").toOption.getOrElse("op")
    val op     = corpus.stripPrefix("eval_")
    val inputs = doc.hcursor.downField("entries").values.getOrElse(Vector.empty)
    val activated = VersionContext.MaxSupportedScriptVersion

    println(s"blessing $corpus (${inputs.size} entries) with sigma-state 6.0.3")

    val entries: Vector[Json] = inputs.toVector.map { e =>
      val c    = e.hcursor
      val name = c.get[String]("name").toOption.getOrElse("?")
      val hex  = c.get[String]("tree_bytes_hex").toOption.getOrElse("")

      val (treeVer, outcome) = EvalCore.evalEntry(hex, activated)
      val version = Json.obj(
        "activated" -> Json.fromInt(activated.toInt),
        "ergoTree"  -> Json.fromInt(treeVer.toInt))
      val expected = outcome match {
        case Right((value, cost)) =>
          println(s"  ok       $name  (cost=$cost)")
          Json.obj("value" -> value, "cost" -> Json.fromLong(cost), "error" -> Json.Null)
        case Left(detail) =>
          println(s"  errored  $name  ($detail)")
          Json.obj("value" -> Json.Null, "cost" -> Json.Null, "error" -> Json.fromString("errored"))
      }
      Json.obj(
        "name"           -> Json.fromString(name),
        "tree_bytes_hex" -> Json.fromString(hex),
        "version"        -> version,
        "expected"       -> expected)
    }

    val vector = Json.obj(
      "schema"     -> Json.fromString("santa-eval/v1"),
      "op"         -> Json.fromString(op),
      "blessed_by" -> Json.fromString("jvm:sigma-state-6.0.3"),
      "entries"    -> Json.arr(entries: _*))

    outPath match {
      case Some(p) =>
        val path = java.nio.file.Paths.get(p)
        Option(path.getParent).foreach(java.nio.file.Files.createDirectories(_))
        java.nio.file.Files.write(path, vector.spaces2.getBytes(java.nio.charset.StandardCharsets.UTF_8))
        println(s"\nwrote ${entries.size} entries → $p")
      case None =>
        println(vector.spaces2)
    }
  }
}
