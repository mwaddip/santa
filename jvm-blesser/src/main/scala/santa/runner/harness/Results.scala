package santa.runner.harness

import io.circe.Json
import scala.collection.immutable.TreeMap

/** What rudolph writes to `<out-dir>/results.json` (santa-runner-results/v1, runner-integration §4.3). */
final class Tally {
  private final class Slice {
    var files: Vector[String] = Vector.empty
    var dims: TreeMap[String, (Long, Long)] = TreeMap.empty // (graded, nice)
    var notImplemented = 0L
    var panicked = 0L
    var exceeded = 0L
    var red: Vector[Json] = Vector.empty
  }
  private var slices: TreeMap[String, Slice] = TreeMap.empty

  private def slice(f: VectorFile): Slice = {
    val k = s"${f.tier}/${f.version}/${f.provenance}"
    slices.getOrElse(k, { val s = new Slice; slices += k -> s; s })
  }

  /** A short rendering for people (never compared): the compact JSON, cut at 200 characters. */
  private def brief(v: Json): String = { val s = v.noSpaces; if (s.length > 200) s.take(200) + "…" else s }

  private def expectedOf(entry: Json): String = {
    val o = entry.asObject
    brief(o.flatMap(_("expected")).orElse(o.flatMap(_("expected_bytes_hex"))).orElse(o.flatMap(_("bytes_hex"))).getOrElse(Json.Null))
  }

  def addFile(f: VectorFile): Unit = { val s = slice(f); s.files :+= f.rel }

  def addVerdict(f: VectorFile, entry: Json, actual: Option[Json], v: Verdict): Unit = {
    val s = slice(f)
    val name = entry.hcursor.downField("name").as[String].getOrElse("")
    val got = actual.map(brief).getOrElse("missing")
    def record(dim: String, note: Option[Json] = None): Json = {
      val base = Json.obj("file" -> Json.fromString(f.rel), "entry" -> Json.fromString(name), "dim" -> Json.fromString(dim),
        "expected" -> Json.fromString(expectedOf(entry)), "got" -> Json.fromString(got))
      note.flatMap(_.asString).fold(base)(n => base.mapObject(_.add("note", Json.fromString(n))))
    }
    v match {
      case Verdict.Exceeded(_)    => s.exceeded += 1; s.red :+= record("exceeded")
      case Verdict.Panicked       => s.panicked += 1; s.red :+= record("panicked", actual.flatMap(_.asObject).flatMap(_("note")))
      case Verdict.NotImplemented => s.notImplemented += 1; s.red :+= record("not-implemented")
      case Verdict.Graded(dims) =>
        dims.foreach {
          case (_, Grade.NotApplicable) =>
          case (dim, g) =>
            val (graded, nice) = s.dims.getOrElse(dim, (0L, 0L))
            s.dims += dim -> (graded + 1, nice + (if (g == Grade.Nice) 1 else 0))
            if (g == Grade.Red) s.red :+= record(dim)
        }
    }
  }

  def summary: Seq[String] = slices.toSeq.map { case (k, s) =>
    s"$k: ${s.dims.map { case (d, (g, n)) => s"$d $n/$g" }.mkString(" · ")} · ${s.red.size} red"
  }

  def resultsJson(santa: Option[String], scope: Scope): Json = {
    val red = slices.values.map(_.red.size.toLong).sum
    val notImpl = slices.values.map(_.notImplemented).sum
    val sc = Json.obj("version" -> Json.fromString(scope.version), "tiers" -> Json.fromValues(scope.tiers.map(Json.fromString)),
      "cost" -> Json.fromBoolean(scope.cost))
    val sc2 = scope.kinds.fold(sc)(k => sc.mapObject(_.add("kinds", Json.fromValues(k.map(Json.fromString)))))
    val sc3 = if (scope.activation) sc2 else sc2.mapObject(_.add("activation", Json.False))
    Json.obj(
      "schema" -> Json.fromString("santa-runner-results/v1"),
      "santa" -> santa.fold(Json.Null)(Json.fromString),
      "scope" -> sc3,
      "slices" -> Json.fromFields(slices.toSeq.map { case (k, s) =>
        k -> Json.obj(
          "files" -> Json.fromValues(s.files.map(Json.fromString)),
          "dims" -> Json.fromFields(s.dims.toSeq.map { case (d, (g, n)) => d -> Json.obj("graded" -> Json.fromLong(g), "nice" -> Json.fromLong(n)) }),
          "not_implemented" -> Json.fromLong(s.notImplemented), "panicked" -> Json.fromLong(s.panicked),
          "exceeded" -> Json.fromLong(s.exceeded), "red" -> Json.fromValues(s.red))
      }),
      "totals" -> Json.obj("red" -> Json.fromLong(red), "not_implemented" -> Json.fromLong(notImpl)))
  }
}
