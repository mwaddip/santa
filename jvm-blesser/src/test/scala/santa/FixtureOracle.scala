package santa

import io.circe.Json

/** Loads a `fixture-gen` eval file and exposes its entries. Stage-1 tests
  * assert JVM-blessed output of each *context-free* entry against the
  * fixture's own expected_* fields (JVM and the chain-validated fork agree
  * on these), so this doubles as the regression oracle. */
object FixtureOracle {
  val fixtureDir: String = sys.env.getOrElse(
    "SANTA_FIXTURES",
    s"${sys.props("user.home")}/projects/ergots/packages/ergoscript/test/fixtures/eval")

  def entries(file: String): Vector[Json] = {
    val text = scala.io.Source.fromFile(s"$fixtureDir/$file").mkString
    io.circe.parser.parse(text)
      .fold(e => sys.error(s"bad json in $file: $e"), identity)
      .hcursor.downField("entries").values.getOrElse(Vector.empty).toVector
  }

  /** Stage 1 only blesses entries with no declared context. */
  def isContextFree(entry: Json): Boolean =
    entry.hcursor.downField("opts_json").focus.contains(Json.obj())
}
