package santa.runner.harness

import io.circe.{Json, JsonNumber, JsonObject}

/** The structural equality of a grading comparison (docs/contract/grading.md §3): objects key-order-free, arrays in
  * order, numbers numerically (5 == 5.0), strings exactly, null only null, a bool only a bool. rudolph's own reading,
  * independent of SANTA's santa-check: the canary compares the two. */
object Equal {
  /** An integer literal (no fraction, no exponent): what serde_json, under santa-check, stores as an integer. */
  private[harness] def integralLiteral(x: JsonNumber): Boolean = !x.toString.exists(c => c == '.' || c == 'e' || c == 'E')
  private val U64 = BigInt(1) << 64
  private def i64(x: JsonNumber): Option[Long] = if (integralLiteral(x)) x.toLong else None
  private def u64(x: JsonNumber): Option[BigInt] = if (integralLiteral(x)) x.toBigInt.filter(b => b >= 0 && b < U64) else None

  /** As santa-check compares numbers: two 64-bit signed integers exactly, else two unsigned exactly, else as doubles (an
    * integer literal beyond both ranges is a double there too). */
  private def numEq(x: JsonNumber, y: JsonNumber): Boolean = (i64(x), i64(y)) match {
    case (Some(a), Some(b)) => a == b
    case _ =>
      (u64(x), u64(y)) match {
        case (Some(a), Some(b)) => a == b
        case _                  => x.toDouble == y.toDouble
      }
  }

  def structural(a: Json, b: Json): Boolean =
    (a.asBoolean, b.asBoolean) match {
      case (Some(x), Some(y)) => x == y
      case (Some(_), None) | (None, Some(_)) => false
      case _ =>
        if (a.isNull || b.isNull) a.isNull && b.isNull
        else (a.asNumber, b.asNumber) match {
          case (Some(x), Some(y)) => numEq(x, y)
          case (Some(_), None) | (None, Some(_)) => false
          case _ =>
            (a.asString, b.asString) match {
              case (Some(x), Some(y)) => x == y
              case (Some(_), None) | (None, Some(_)) => false
              case _ =>
                (a.asArray, b.asArray) match {
                  case (Some(x), Some(y)) => x.length == y.length && x.zip(y).forall { case (p, q) => structural(p, q) }
                  case (Some(_), None) | (None, Some(_)) => false
                  case _ =>
                    (a.asObject, b.asObject) match {
                      case (Some(x), Some(y)) =>
                        x.size == y.size && x.toIterable.forall { case (k, v) => y(k).exists(w => structural(v, w)) }
                      case _ => false
                    }
                }
            }
        }
    }
}

/** A path's root: the actual, the entry's `expected`, the entry, or the vector file's envelope. */
sealed trait Root
object Root {
  case object Actual extends Root
  case object Expected extends Root
  case object Entry extends Root
  case object File extends Root
}

/** What one entry is graded against. */
final case class Ctx(actual: Json, entry: Json, file: Json)

final case class JPath(root: Root, keys: Vector[String]) {
  def resolve(ctx: Ctx): Option[Json] = {
    val start: Option[Json] = root match {
      case Root.Actual   => Some(ctx.actual)
      case Root.Expected => ctx.entry.asObject.flatMap(_("expected"))
      case Root.Entry    => Some(ctx.entry)
      case Root.File     => Some(ctx.file)
    }
    keys.foldLeft(start)((v, k) => v.flatMap(_.asObject).flatMap(_(k)))
  }
}

sealed trait Operand {
  def eval(ctx: Ctx): Json = this match {
    case Operand.PathOp(p)      => p.resolve(ctx).getOrElse(Json.Null)
    case Operand.Lit(v)         => v
    case Operand.First(ps)      => ps.view.flatMap(_.resolve(ctx)).headOption.getOrElse(Json.Null)
    case Operand.Pluck(list, f) =>
      list.resolve(ctx).flatMap(_.asArray) match {
        case Some(items) => Json.fromValues(items.map(i => i.asObject.flatMap(_(f)).getOrElse(Json.Null)))
        case None        => Json.Null
      }
  }
}
object Operand {
  final case class PathOp(p: JPath) extends Operand
  final case class Lit(v: Json) extends Operand
  final case class First(ps: Vector[JPath]) extends Operand
  final case class Pluck(list: JPath, field: String) extends Operand
}

final case class Comparison(a: Operand, b: Operand, caseInsensitive: Boolean, nonempty: Boolean) {
  private def empty(v: Json): Boolean =
    v.isNull || v.asString.exists(_.isEmpty) || v.asArray.exists(_.isEmpty) || v.asObject.exists(_.isEmpty)

  def holds(ctx: Ctx): Boolean = {
    val (x, y) = (a.eval(ctx), b.eval(ctx))
    if (nonempty && (empty(x) || empty(y))) false
    else if (caseInsensitive) (x.asString, y.asString) match {
      case (Some(p), Some(q)) => asciiFold(p) == asciiFold(q)
      case _                  => false
    }
    else Equal.structural(x, y)
  }

  /** ASCII-only case folding (as Rust's eq_ignore_ascii_case): non-ASCII letters must match exactly. */
  private def asciiFold(s: String): String = s.map(c => if (c >= 'A' && c <= 'Z') (c + 32).toChar else c)
}

final case class Dim(name: String, compare: Vector[Comparison], requires: Vector[String], claimed: Boolean, clean: Boolean)
final case class Branch(when: Vector[(JPath, Json)], dims: Vector[Dim])
final case class GType(branches: Vector[Branch], unmatched: Vector[String])

/** One dimension's grade; `NotApplicable` is not graded (not counted). */
sealed trait Grade { def str: String }
object Grade {
  case object Nice extends Grade { val str = "nice" }
  case object Red extends Grade { val str = "red" }
  case object NotApplicable extends Grade { val str = "n/a" }
}

/** An entry's verdict: a global outcome, or its dimensions in the branch's order. */
sealed trait Verdict {
  def toJson: Json = this match {
    case Verdict.Exceeded(limit) => Json.obj("outcome" -> Json.fromString("exceeded"), "limit" -> limit)
    case Verdict.Panicked        => Json.obj("outcome" -> Json.fromString("panicked"))
    case Verdict.NotImplemented  => Json.obj("outcome" -> Json.fromString("not-implemented"))
    case Verdict.Graded(dims) =>
      Json.obj(
        "outcome" -> Json.fromString("graded"),
        "dims" -> Json.fromFields(dims.collect { case (n, g) if g != Grade.NotApplicable => n -> Json.fromString(g.str) }))
  }
}
object Verdict {
  final case class Exceeded(limit: Json) extends Verdict
  case object Panicked extends Verdict
  case object NotImplemented extends Verdict
  final case class Graded(dims: Seq[(String, Grade)]) extends Verdict
}

/** A parsed `vectors/grading.json` (santa-grading/v1, docs/contract/grading.md): each tier's default label and the
  * grading types by label. */
final class Grading(val tiers: Map[String, String], val types: Map[String, GType]) {

  /** The label an entry of `tier` in the vector file `file` is graded by: the file's own `grading`, else the tier's. */
  def labelFor(tier: String, file: Json): Either[String, String] =
    file.asObject.flatMap(_("grading")) match {
      case Some(g) =>
        g.asString match {
          case Some(l) if types.contains(l) => Right(l)
          case Some(l)                      => Left(s"grading: no grading type ${q(l)}")
          case None                         => Left("grading: a vector file's grading is a string")
        }
      case None => tiers.get(tier).toRight(s"grading: the tier ${q(tier)} has no default label")
    }

  /** Grades one entry by the type `label`: the global outcomes first, then the first branch whose `when` holds. */
  def grade(label: String, entry: Json, actual: Option[Json], file: Json, claimsCost: Boolean): Either[String, Verdict] =
    types.get(label) match {
      case None => Left(s"grading: no grading type ${q(label)}")
      case Some(t) =>
        val a = actual.getOrElse(Json.obj())
        val ctx = Ctx(a, entry, file)
        val error = a.asObject.flatMap(_("error")).flatMap(_.asString)
        error match {
          case Some("exceeded") => Right(Verdict.Exceeded(a.asObject.flatMap(_("limit")).getOrElse(Json.Null)))
          case Some("panicked") => Right(Verdict.Panicked)
          case Some("not-implemented") => Right(Verdict.NotImplemented)
          case _ =>
            val holds = (b: Branch) => b.when.forall { case (p, lit) => Equal.structural(p.resolve(ctx).getOrElse(Json.Null), lit) }
            t.branches.find(holds) match {
              case None if t.unmatched.isEmpty => Left(s"grading: no branch of ${q(label)} matches the entry")
              case None                        => Right(Verdict.Graded(t.unmatched.map(_ -> (Grade.Red: Grade))))
              case Some(branch) =>
                val clean = a.asObject.flatMap(_("error")).forall(_.isNull)
                val dims = branch.dims.foldLeft(Vector.empty[(String, Grade)]) { (done, d) =>
                  val required = d.requires.forall(r => done.exists { case (n, g) => n == r && g == Grade.Nice })
                  val claimed = !d.claimed || (claimsCost && !d.compare.head.b.eval(ctx).isNull)
                  val grade: Grade =
                    if (!required || !claimed) Grade.NotApplicable
                    else if ((!d.clean || clean) && d.compare.forall(_.holds(ctx))) Grade.Nice
                    else Grade.Red
                  done :+ (d.name -> grade)
                }
                Right(Verdict.Graded(dims))
            }
        }
    }
}

object Grading {
  val Schema = "santa-grading/v1"

  private final class Refused(msg: String) extends Exception(msg)
  private def refuse(at: String, why: String): Nothing = throw new Refused(s"grading: $at: $why")

  private def obj(v: Json, at: String, allowed: Set[String]): JsonObject = {
    val m = v.asObject.getOrElse(refuse(at, "not an object"))
    m.keys.find(k => !allowed.contains(k)).foreach(k => throw new Refused(s"grading: unknown key ${q(k)} at $at"))
    m
  }

  private def flag(m: JsonObject, key: String, default: Boolean, at: String): Boolean =
    m(key) match {
      case None    => default
      case Some(v) => v.asBoolean.getOrElse(refuse(s"$at.$key", "not a boolean"))
    }

  private def strings(v: Option[Json], at: String): Vector[String] = v match {
    case None => Vector.empty
    case Some(j) =>
      j.asArray.getOrElse(refuse(at, "not a list of strings")).map(_.asString.getOrElse(refuse(at, "not a list of strings")))
  }

  private def path(s: String, at: String): JPath = {
    val parts = s.split("\\.", -1).toVector
    val root = parts.head match {
      case "actual"   => Root.Actual
      case "expected" => Root.Expected
      case "entry"    => Root.Entry
      case "file"     => Root.File
      case _          => refuse(at, s"path ${q(s)} does not start with actual, expected, entry or file")
    }
    val keys = parts.tail
    if (keys.exists(_.isEmpty)) refuse(at, s"path ${q(s)} has an empty segment")
    JPath(root, keys)
  }

  private def operand(v: Json, at: String): Operand = {
    val m = obj(v, at, Set("path", "lit", "first", "pluck", "field"))
    val forms = Seq("path", "lit", "first", "pluck").count(m.contains)
    if (forms != 1) refuse(at, "an operand has exactly one of path, lit, first, pluck")
    if (m.contains("field") != m.contains("pluck")) refuse(at, "field goes with pluck, and only with it")
    def p(j: Json): JPath = path(j.asString.getOrElse(refuse(at, "a path is a string")), at)
    if (m.contains("path")) Operand.PathOp(p(m("path").get))
    else if (m.contains("lit")) Operand.Lit(m("lit").get)
    else if (m.contains("first")) {
      val list = m("first").get.asArray.filter(_.nonEmpty).getOrElse(refuse(at, "first is a list of paths"))
      Operand.First(list.map(p))
    } else {
      val field = m("field").get.asString.getOrElse(refuse(at, "field is a string"))
      Operand.Pluck(p(m("pluck").get), field)
    }
  }

  private def comparison(v: Json, at: String): Comparison = {
    val m = obj(v, at, Set("a", "b", "case_insensitive", "nonempty"))
    def op(k: String) = operand(m(k).getOrElse(refuse(at, s"missing $k")), s"$at.$k")
    Comparison(op("a"), op("b"), flag(m, "case_insensitive", default = false, at), flag(m, "nonempty", default = false, at))
  }

  private def dim(v: Json, at: String, earlier: Vector[Dim]): Dim = {
    val m = obj(v, at, Set("name", "compare", "requires", "claimed", "clean"))
    val name = m("name").flatMap(_.asString).getOrElse(refuse(at, "a dimension needs a name"))
    if (earlier.exists(_.name == name)) refuse(at, s"the dimension ${q(name)} is listed twice")
    val compare = m("compare").flatMap(_.asArray).filter(_.nonEmpty).getOrElse(refuse(at, "compare is a non-empty list"))
      .zipWithIndex.map { case (c, i) => comparison(c, s"$at.compare[$i]") }
    val requires = strings(m("requires"), s"$at.requires")
    requires.find(r => !earlier.exists(_.name == r))
      .foreach(r => refuse(at, s"requires ${q(r)}, which is not an earlier dimension of the branch"))
    Dim(name, compare, requires, flag(m, "claimed", default = false, at), flag(m, "clean", default = true, at))
  }

  private def branch(v: Json, at: String): Branch = {
    val m = obj(v, at, Set("when", "dims"))
    val when = m("when") match {
      case None => Vector.empty
      case Some(w) =>
        val pairs = w.asObject.getOrElse(refuse(s"$at.when", "when is an object of path: literal"))
        pairs.toVector.map { case (k, lit) => path(k, s"$at.when") -> lit }
    }
    val raw = m("dims").flatMap(_.asArray).filter(_.nonEmpty).getOrElse(refuse(at, "dims is a non-empty list"))
    val dims = raw.zipWithIndex.foldLeft(Vector.empty[Dim]) { case (done, (d, i)) => done :+ dim(d, s"$at.dims[$i]", done) }
    Branch(when, dims)
  }

  private def gtype(v: Json, at: String): GType = {
    val m = obj(v, at, Set("branches", "unmatched"))
    val branches = m("branches").flatMap(_.asArray).filter(_.nonEmpty).getOrElse(refuse(at, "branches is a non-empty list"))
      .zipWithIndex.map { case (b, i) => branch(b, s"$at.branches[$i]") }
    GType(branches, strings(m("unmatched"), s"$at.unmatched"))
  }

  /** Parses a `vectors/grading.json` document, refusing anything this version does not know. */
  def parse(doc: Json): Either[String, Grading] =
    try {
      val m = obj(doc, "grading", Set("schema", "tiers", "types"))
      m("schema").flatMap(_.asString) match {
        case Some(Schema) =>
        case Some(other)  => throw new Refused(s"grading: unknown schema ${q(other)} (this reads $Schema)")
        case None         => refuse("grading", "missing schema")
      }
      val typesObj = m("types").flatMap(_.asObject).getOrElse(refuse("grading", "types is an object"))
      val types = typesObj.toVector.map { case (label, t) => label -> gtype(t, s"types.$label") }.toMap
      val tiersObj = m("tiers").flatMap(_.asObject).getOrElse(refuse("grading", "tiers is an object"))
      val tiers = tiersObj.toVector.map { case (tier, l) =>
        val label = l.asString.getOrElse(refuse(s"tiers.$tier", "a label is a string"))
        if (!types.contains(label)) refuse(s"tiers.$tier", s"names no grading type ${q(label)}")
        tier -> label
      }.toMap
      Right(new Grading(tiers, types))
    } catch { case r: Refused => Left(r.getMessage) }
}
