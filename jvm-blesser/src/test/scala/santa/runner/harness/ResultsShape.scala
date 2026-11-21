package santa.runner.harness

import io.circe.{Json, JsonObject}

/** results.json checked field by field against SANTA's schema/santa-runner-results.schema.json (no JSON Schema library
  * on rudolph's classpath): exact key sets, types, the path and slice patterns, the totals' sums. */
object ResultsShape {
  private val Path = "^(eval|wire|transaction|block|chain|authds|nipopow)/(any|v4|v5|v6)/(spec|authored|vendored|captured)/[^/]+\\.json$"
  private val Slice = "^(eval|wire|transaction|block|chain|authds|nipopow)/(any|v4|v5|v6)/(spec|authored|vendored|captured)$"
  private val Dims = Set("value", "cost", "reject", "roundtrip", "valid", "post_digest", "proof", "digest", "accepted", "results", "interlinks")

  def check(r: Json): Unit = {
    def obj(j: Json, at: String): JsonObject = j.asObject.getOrElse(throw new AssertionError(s"$at: not an object"))
    def keys(o: JsonObject): Set[String] = o.keys.toSet
    def need(c: Boolean, what: => String): Unit = if (!c) throw new AssertionError(what)
    def count(j: Option[Json], at: String): Long = {
      val n = j.flatMap(_.asNumber).flatMap(_.toLong).getOrElse(throw new AssertionError(s"$at: not an integer"))
      need(n >= 0, s"$at: negative"); n
    }
    val o = obj(r, "results")
    need(keys(o) == Set("schema", "santa", "scope", "slices", "totals"), s"results keys ${keys(o)}")
    need(o("schema").contains(Json.fromString("santa-runner-results/v1")), "schema")
    need(o("santa").exists(s => s.isNull || s.asString.exists(_.matches("^[0-9a-f]{40}$"))), "santa")
    val sc = obj(o("scope").get, "scope")
    need(keys(sc).subsetOf(Set("version", "tiers", "cost", "kinds", "activation")) && Set("version", "tiers", "cost").subsetOf(keys(sc)), s"scope keys ${keys(sc)}")
    need(sc("version").flatMap(_.asString).exists(Set("v4", "v5", "v6")), "scope.version")
    need(sc("tiers").flatMap(_.asArray).exists(_.nonEmpty), "scope.tiers")
    need(sc("cost").exists(_.isBoolean), "scope.cost")
    var red = 0L
    var notImpl = 0L
    obj(o("slices").get, "slices").toIterable.foreach { case (k, sv) =>
      need(k.matches(Slice), s"slice key $k")
      val s = obj(sv, k)
      need(keys(s) == Set("files", "dims", "not_implemented", "panicked", "exceeded", "red"), s"$k keys ${keys(s)}")
      val files = s("files").flatMap(_.asArray).getOrElse(Vector.empty).map(_.asString.getOrElse(""))
      need(files.nonEmpty, s"$k: no files")
      files.foreach(f => need(f.matches(Path) && f.startsWith(s"$k/"), s"$k: file $f"))
      obj(s("dims").get, s"$k.dims").toIterable.foreach { case (d, dv) =>
        need(Dims(d), s"$k: dim $d")
        val g = obj(dv, s"$k.dims.$d")
        need(keys(g) == Set("graded", "nice"), s"$k.dims.$d keys")
        need(count(g("nice"), s"$k.dims.$d.nice") <= count(g("graded"), s"$k.dims.$d.graded"), s"$k.dims.$d: nice > graded")
      }
      notImpl += count(s("not_implemented"), s"$k.not_implemented")
      count(s("panicked"), s"$k.panicked"); count(s("exceeded"), s"$k.exceeded")
      val reds = s("red").flatMap(_.asArray).getOrElse(throw new AssertionError(s"$k.red: not a list"))
      reds.foreach { rv =>
        val rec = obj(rv, s"$k.red[]")
        need(Set("file", "entry", "dim", "expected", "got").subsetOf(keys(rec)) &&
          keys(rec).subsetOf(Set("file", "entry", "dim", "expected", "got", "note")), s"$k.red[] keys ${keys(rec)}")
        need(rec("file").flatMap(_.asString).exists(_.matches(Path)), s"$k.red[].file")
        Seq("entry", "expected", "got").foreach(x => need(rec(x).exists(_.isString), s"$k.red[].$x"))
        need(rec("dim").flatMap(_.asString).exists(d => Dims(d) || Set("not-implemented", "panicked", "exceeded")(d)), s"$k.red[].dim")
        rec("note").foreach(n => need(n.isString, s"$k.red[].note"))
      }
      red += reds.size
    }
    val t = obj(o("totals").get, "totals")
    need(keys(t) == Set("red", "not_implemented"), "totals keys")
    need(count(t("red"), "totals.red") == red && count(t("not_implemented"), "totals.not_implemented") == notImpl, "totals: not the slices' sums")
  }
}
