package santa.runner

import io.circe.Json

import santa.EvalCore

/** JVM reference runner — Rudolph.
  *
  * Consumes a `santa-eval` vector and emits, per entry, its ACTUAL
  * `{ value, cost, error }` — JSON keyed by entry `name`. Each entry is evaluated
  * under the version the vector records. (This is the blesser in consume-mode, so
  * it matches by construction — it proves the harness mechanics and is the Scala
  * runner scaffold, not yet an independent conformance check.)
  *
  *   runner <vector.json> [<actuals-out.json>]
  *
  * With an output path it writes the actuals; without, it prints them to stdout.
  */
object Runner {
  def main(args: Array[String]): Unit = {
    val vecPath = args.headOption.getOrElse {
      System.err.println("usage: runner <vector.json> [<actuals-out.json>]")
      sys.exit(2)
    }
    val outPath = args.lift(1)

    val doc     = io.circe.parser.parse(scala.io.Source.fromFile(vecPath).mkString)
      .fold(e => sys.error(s"bad json: $e"), identity)
    val entries = doc.hcursor.downField("entries").values.getOrElse(Vector.empty)

    val results: Vector[(String, Json)] = entries.toVector.map { e =>
      val c    = e.hcursor
      val name = c.get[String]("name").toOption.getOrElse("?")
      val hex  = c.get[String]("tree_bytes_hex").toOption.getOrElse("")
      val activated = c.downField("version").get[Int]("activated").toOption
        .map(_.toByte).getOrElse(sigma.VersionContext.MaxSupportedScriptVersion)

      val (_, outcome) = EvalCore.evalEntry(hex, activated)
      val actual = outcome match {
        case Right((value, cost)) =>
          Json.obj("value" -> value, "cost" -> Json.fromLong(cost), "error" -> Json.Null)
        case Left(_) =>
          Json.obj("value" -> Json.Null, "cost" -> Json.Null, "error" -> Json.fromString("errored"))
      }
      name -> actual
    }

    val out = Json.obj(results: _*).spaces2
    outPath match {
      case Some(p) =>
        java.nio.file.Files.write(java.nio.file.Paths.get(p),
          out.getBytes(java.nio.charset.StandardCharsets.UTF_8))
        System.err.println(s"actuals → $p")
      case None =>
        println(out)
    }
  }
}
