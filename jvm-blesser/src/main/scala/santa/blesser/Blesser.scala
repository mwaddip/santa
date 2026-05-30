package santa.blesser

import io.circe.Json
import santa.EvalCore

/** Shared bless logic: a fixture file → one `santa-eval/v1` vector Json.
  * CLI front-ends (`Main`, `Batch`) are thin wrappers over this. */
object Blesser {

  /** Canonical op name = filename stem with hyphens normalized to underscores
    * (`sigma-or.json` → `sigma_or`). Authoritative; ignores the `corpus` field. */
  def opFromPath(path: String): String =
    new java.io.File(path).getName.stripSuffix(".json").replace('-', '_')

  /** Bless one fixture file under `activated`, returning the vector Json. */
  def blessFixture(inPath: String, activated: Byte): Json = {
    val doc = io.circe.parser.parse(scala.io.Source.fromFile(inPath).mkString)
      .fold(e => sys.error(s"bad json: $e"), identity)
    val op     = opFromPath(inPath)
    val inputs = doc.hcursor.downField("entries").values.getOrElse(Vector.empty)

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
          Json.obj("value" -> value, "cost" -> Json.fromLong(cost), "error" -> Json.Null)
        case Left(_) =>
          Json.obj("value" -> Json.Null, "cost" -> Json.Null, "error" -> Json.fromString("errored"))
      }
      Json.obj(
        "name"           -> Json.fromString(name),
        "tree_bytes_hex" -> Json.fromString(hex),
        "version"        -> version,
        "expected"       -> expected)
    }

    Json.obj(
      "schema"     -> Json.fromString("santa-eval/v1"),
      "op"         -> Json.fromString(op),
      "blessed_by" -> Json.fromString("jvm:sigma-state-6.0.3"),
      "entries"    -> Json.arr(entries: _*))
  }
}
