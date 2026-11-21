package santa.runner.harness

import io.circe.Json
import io.circe.parser.parse
import java.nio.file.{Files, Path, Paths}
import scala.collection.JavaConverters._
import scala.util.Try

/** rudolph's verbs as a self-contained runner (runner-integration §3): `run` checks both oracles, selects, runs every
  * selected file (a bounded one as a child JVM under its budget), grades, writes `actuals/<rel>` and `results.json`;
  * `one` is a bounded file's child. */
object Main {

  /** The per-file function: a vector file's path and parsed document → its actuals by entry name, in entry order. */
  type RunFile = (Path, Json) => Seq[(String, Json)]

  /** The child's command for a bounded file: (budget, relpath, out-file) → argv. */
  type ChildCommand = (Bounds.Budget, String, Path) => Seq[String]

  private def readJson(p: Path): Either[String, Json] =
    Try(new String(Files.readAllBytes(p), "UTF-8")).toEither.left.map(e => s"$p: $e")
      .flatMap(t => parse(t).left.map(e => s"$p: $e"))

  private def writeJson(p: Path, v: Json): Either[String, Unit] =
    Try { Files.createDirectories(p.getParent); Files.write(p, v.spaces2.getBytes("UTF-8")); () }.toEither.left.map(e => s"$p: $e")

  private def names(doc: Json): Vector[String] =
    doc.hcursor.downField("entries").focus.flatMap(_.asArray).getOrElse(Vector.empty).map(_.hcursor.downField("name").as[String].getOrElse(""))

  private def eachEntry(doc: Json, a: Json): Map[String, Json] = names(doc).map(_ -> a).toMap

  private def runBounded(f: VectorFile, doc: Json, b: Bounds.Budget, dir: Path, n: Int, child: ChildCommand): Map[String, Json] = {
    val outFile = dir.resolve(s"$n.json")
    val peak = Paths.get(outFile.toString + ".peak")
    Bounds.runChild(child(b, f.rel, outFile), b, dir.resolve(s"$n.stderr"), Some(peak)) match {
      case Bounds.Finished =>
        readJson(outFile).toOption.flatMap(_.asObject).map(_.toMap)
          .getOrElse(eachEntry(doc, Json.obj("error" -> Json.fromString("panicked"), "note" -> Json.fromString("the bounded child wrote no actuals"))))
      case Bounds.Exceeded(limit) => eachEntry(doc, Json.obj("error" -> Json.fromString("exceeded"), "limit" -> Json.fromString(limit)))
      case Bounds.Crashed(note)   => eachEntry(doc, Json.obj("error" -> Json.fromString("panicked"), "note" -> Json.fromString(note)))
    }
  }

  /** Removes a file or a directory tree (a link itself, never its target); absent is fine. */
  private def clear(p: Path): Either[String, Unit] =
    Try {
      if (Files.isDirectory(p, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
        val s = Files.walk(p)
        try s.iterator().asScala.toVector.reverse.foreach(x => Files.deleteIfExists(x)) finally s.close()
      } else Files.deleteIfExists(p)
      ()
    }.toEither.left.map(e => s"$p: $e")

  def run(runnerJson: Path, santa: Path, out: Path, runFile: RunFile, child: ChildCommand): Either[String, Unit] = {
    val linux = sys.props.get("os.name").contains("Linux")
    for {
      // before anything else: a run that fails leaves no earlier run's results behind (runner-integration §4.3)
      _ <- clear(out.resolve("results.json"))
      _ <- clear(out.resolve("actuals"))
      scope <- Scope.read(runnerJson)
      gdoc <- readJson(santa.resolve("vectors").resolve("grading.json"))
      grading <- Grading.parse(gdoc).left.map(e => s"vectors/grading.json: $e")
      _ <- Oracle.check(santa, grading)
      vectors = santa.resolve("vectors")
      files <- Selection.discover(vectors).flatMap(Selection.select(_, vectors, scope))
      children = out.resolve(".children")
      _ <- Try(Files.createDirectories(children)).toEither.left.map(e => s"$children: $e")
      tally = new Tally
      _ <- files.zipWithIndex.foldLeft[Either[String, Unit]](Right(())) { case (acc, (f, n)) =>
        acc.flatMap { _ =>
          val path = vectors.resolve(f.rel)
          for {
            doc <- readJson(path)
            budget <- Bounds.budget(doc).left.map(e => s"vectors/${f.rel}: $e")
            actuals <- budget match {
              case Some(_) if !linux => Left(s"vectors/${f.rel}: a bounded file needs Linux (the watchdog reads /proc); this platform cannot bound it")
              case Some(b)           => Right(runBounded(f, doc, b, children, n, child))
              case None              => Right(runFile(path, doc).toMap)
            }
            ns = names(doc)
            known = ns.toSet
            ordered = Json.fromFields(ns.flatMap(k => actuals.get(k).map(k -> _)) ++ actuals.filterKeys(k => !known(k)).toSeq)
            _ <- writeJson(out.resolve("actuals").resolve(f.rel), ordered)
            label <- grading.labelFor(f.tier, doc)
            _ = tally.addFile(f)
            _ <- doc.hcursor.downField("entries").focus.flatMap(_.asArray).getOrElse(Vector.empty).foldLeft[Either[String, Unit]](Right(())) { (a, e) =>
              a.flatMap { _ =>
                val actual = e.hcursor.downField("name").as[String].toOption.flatMap(actuals.get)
                grading.grade(label, e, actual, doc, scope.cost).map(v => tally.addVerdict(f, e, actual, v)).left.map(err => s"vectors/${f.rel}: $err")
              }
            }
          } yield ()
        }
      }
      _ = Try { val s = Files.walk(children); try s.iterator().asScala.toVector.reverse.foreach(p => Files.deleteIfExists(p)) finally s.close() }
      _ <- writeJson(out.resolve("results.json"), tally.resultsJson(Scope.santaCommit(santa), scope))
    } yield tally.summary.foreach(println)
  }

  /** A bounded file's child: run the one file, write its actuals, report its own peak (VmHWM, KiB) to `<out>.peak`. */
  def one(santa: Path, rel: String, outFile: Path, runFile: RunFile): Either[String, Unit] = {
    val path = santa.resolve("vectors").resolve(rel)
    for {
      doc <- readJson(path)
      _ <- writeJson(outFile, Json.fromFields(runFile(path, doc)))
      _ <- Try {
        val hwm = new String(Files.readAllBytes(Paths.get("/proc/self/status")), "UTF-8").split("\n")
          .find(_.startsWith("VmHWM:")).map(_.split("\\s+")(1))
        hwm.foreach(k => Files.write(Paths.get(outFile.toString + ".peak"), k.getBytes("UTF-8")))
      }.toEither.left.map(e => s"$outFile: $e")
    } yield ()
  }
}
