package santa.runner.harness

import io.circe.parser.parse
import java.nio.file.{Files, Path}
import scala.collection.JavaConverters._
import scala.util.Try

/** A vector file, by its path relative to `vectors/` and the slice it sits in. */
final case class VectorFile(rel: String, tier: String, version: String, provenance: String)

/** Selection (runner-integration §4.1): which vector files of a SANTA checkout a scope covers. */
object Selection {

  /** Every `*.json` under `vectors/` but the root `grading.json`, sorted; a file at another depth is refused. As the Rust
    * harness walks: a directory it cannot read is skipped, a symlinked directory is followed, and any other `*.json`
    * entry is listed (a dangling link among them fails loudly when it is read). */
  def discover(vectors: Path): Either[String, Seq[VectorFile]] = {
    def walk(d: Path, prefix: String): Vector[String] = {
      val names = Try { val s = Files.list(d); try s.iterator().asScala.map(_.getFileName.toString).toVector finally s.close() }
      names.getOrElse(Vector.empty).flatMap { n =>
        val rel = if (prefix.isEmpty) n else s"$prefix/$n"
        if (Files.isDirectory(d.resolve(n))) walk(d.resolve(n), rel)
        else if (n.endsWith(".json")) Vector(rel)
        else Vector.empty
      }
    }
    val rels = walk(vectors, "").filter(_ != "grading.json").sorted
    rels.foldLeft[Either[String, Vector[VectorFile]]](Right(Vector.empty)) { (acc, rel) =>
      acc.flatMap { done =>
        rel.split('/') match {
          case Array(t, v, p, _) => Right(done :+ VectorFile(rel, t, v, p))
          case _                 => Left(s"vectors/$rel: not at <tier>/<version>/<provenance>/<op>.json")
        }
      }
    }
  }

  /** The files `scope` selects: its tiers; `any`, or a slot at or below its version; for a wire file, its one kind among
    * `kinds` (when declared) and, when `activation` is false, not an `activation_rule: true` envelope. */
  def select(files: Seq[VectorFile], vectors: Path, scope: Scope): Either[String, Seq[VectorFile]] = {
    val vmax = Scope.Versions.indexOf(scope.version)
    if (vmax < 0) return Left(s"unknown version ${q(scope.version)}")
    files.foldLeft[Either[String, Vector[VectorFile]]](Right(Vector.empty)) { (acc, f) =>
      acc.flatMap { done =>
        val i = Scope.Versions.indexOf(f.version)
        val slot = f.version == "any" || (i >= 0 && i <= vmax)
        if (!scope.tiers.contains(f.tier) || !slot) Right(done)
        else if (f.tier == "wire" && (scope.kinds.isDefined || !scope.activation)) {
          val text = new String(Files.readAllBytes(vectors.resolve(f.rel)), "UTF-8")
          parse(text) match {
            case Left(e) => Left(s"vectors/${f.rel}: $e")
            case Right(doc) =>
              val kinds = doc.hcursor.downField("entries").focus.flatMap(_.asArray).getOrElse(Vector.empty)
                .map(e => e.hcursor.downField("kind").as[String].getOrElse("")).distinct
              kinds match {
                case Vector(k) if k.nonEmpty =>
                  val kindOk = scope.kinds.forall(_.contains(k))
                  val ruled = doc.hcursor.downField("activation_rule").as[Boolean].getOrElse(false)
                  Right(if (kindOk && (scope.activation || !ruled)) done :+ f else done)
                case ks => Left(s"vectors/${f.rel}: a wire file holds one kind, this one ${ks.mkString("[", ", ", "]")}")
              }
          }
        } else Right(done :+ f)
      }
    }
  }
}
