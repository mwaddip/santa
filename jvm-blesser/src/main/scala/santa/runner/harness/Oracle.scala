package santa.runner.harness

import io.circe.Json
import io.circe.parser.parse
import java.nio.file.{Files, Path}
import java.util.concurrent.atomic.AtomicLong
import scala.collection.JavaConverters._

/** The self-checks rudolph makes before it grades anything (runner-integration §4.1, §4.2): every verdict case and every
  * selection case of the SANTA checkout's `oracle/` reproduced; an oracle schema it does not know is refused. */
object Oracle {
  private val seq = new AtomicLong(0)

  private def read(p: Path): Either[String, Json] =
    parse(new String(Files.readAllBytes(p), "UTF-8")).left.map(e => s"$p: $e")

  def check(santa: Path, g: Grading): Either[String, Unit] = {
    val dir = santa.resolve("oracle")
    val files = if (!Files.isDirectory(dir)) Vector.empty[Path] else {
      val s = Files.list(dir)
      try s.iterator().asScala.filter(_.toString.endsWith(".json")).toVector.sortBy(_.toString) finally s.close()
    }
    if (files.isEmpty) return Left(s"$dir: no oracle files")
    files.foldLeft[Either[String, Unit]](Right(())) { (acc, p) =>
      acc.flatMap { _ =>
        val name = s"oracle/${p.getFileName}"
        read(p).flatMap { doc =>
          doc.hcursor.downField("schema").as[String].getOrElse("") match {
            case "santa-oracle-grading/v1"   => verdicts(doc, g).left.map(e => s"$name: $e")
            case "santa-oracle-selection/v1" => selections(doc).left.map(e => s"$name: $e")
            case s                           => Left(s"$name: unknown oracle schema ${q(s)}")
          }
        }
      }
    }
  }

  /** The file's list under `key`, refused when absent: an oracle without cases would pass vacuously. */
  private def list(doc: Json, key: String): Either[String, Vector[Json]] =
    doc.hcursor.downField(key).focus.flatMap(_.asArray).toRight(s"no $key")

  /** One verdict case: its label, entry, file (default {}), actual (null = missing) and cost claim (default false). */
  private def verdicts(doc: Json, g: Grading): Either[String, Unit] =
    list(doc, "cases").flatMap(_.foldLeft[Either[String, Unit]](Right(())) { (acc, c) =>
      acc.flatMap { _ =>
        val o = c.asObject.get
        val actual = o("actual").filterNot(_.isNull)
        val got = o("label").flatMap(_.asString).toRight("no label").flatMap { label =>
          g.grade(label, o("entry").getOrElse(Json.Null), actual, o("file").getOrElse(Json.obj()),
            o("claims_cost").flatMap(_.asBoolean).getOrElse(false))
        }.fold(e => Json.obj("error" -> Json.fromString(e)), _.toJson)
        val want = o("verdict").getOrElse(Json.Null)
        if (Equal.structural(got, want)) Right(())
        else Left(s"case ${o("name").map(_.noSpaces).getOrElse("?")}: got ${got.noSpaces}, want ${want.noSpaces}")
      }
    })

  /** The selection cases over a fresh temporary tree built from `files` (runner-integration §4.1). */
  private def selections(doc: Json): Either[String, Unit] = {
    val files = list(doc, "files") match { case Right(f) => f; case Left(e) => return Left(e) }
    val cases = list(doc, "cases") match { case Right(c) => c; case Left(e) => return Left(e) }
    // a path the tree cannot hold (absolute, or climbing out with `..`) is refused before anything is written
    val leaves = (rel: String) => rel.isEmpty || rel.startsWith("/") || rel.split("/", -1).exists(p => p.isEmpty || p == "." || p == "..")
    files.map(_.hcursor.downField("path").as[String].getOrElse("")).find(leaves)
      .foreach(rel => return Left(s"a file path that leaves the tree: ${q(rel)}"))
    val root = Files.createTempDirectory(s"rudolph-selection-${seq.incrementAndGet()}-")
    try {
      val vectors = root.resolve("vectors")
      files.foreach { f =>
        val rel = f.hcursor.downField("path").as[String].getOrElse("")
        val path = vectors.resolve(rel)
        Files.createDirectories(path.getParent)
        val body = f.hcursor.downField("kind").focus match {
          case Some(kind) =>
            val base = Json.obj("entries" -> Json.arr(Json.obj("kind" -> kind)))
            f.hcursor.downField("activation_rule").focus.fold(base)(a => base.mapObject(_.add("activation_rule", a))).noSpaces
          case None if !rel.endsWith(".json") => "not a vector"
          case None if rel == "grading.json"  => "{}"
          case None                           => """{"entries": []}"""
        }
        Files.write(path, body.getBytes("UTF-8"))
      }
      Selection.discover(vectors).flatMap { all =>
        cases.foldLeft[Either[String, Unit]](Right(())) { (acc, c) =>
          acc.flatMap { _ =>
            val name = c.hcursor.downField("name").as[String].getOrElse("?")
            Scope.parse(c.hcursor.downField("scope").focus.getOrElse(Json.Null)).left.map(e => s"case $name: $e").flatMap { scope =>
              Selection.select(all, vectors, scope).flatMap { sel =>
                val got = sel.map(_.rel)
                val want = c.hcursor.downField("selects").as[Vector[String]].getOrElse(Vector.empty)
                if (got == want) Right(()) else Left(s"case $name: selects ${got.mkString(",")}, want ${want.mkString(",")}")
              }
            }
          }
        }
      }
    } finally {
      val s = Files.walk(root)
      try s.iterator().asScala.toVector.reverse.foreach(p => Files.deleteIfExists(p)) finally s.close()
    }
  }
}
