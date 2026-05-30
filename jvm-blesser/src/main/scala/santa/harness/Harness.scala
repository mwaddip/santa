package santa.harness

import io.circe.Json

/** SANTA harness.
  *
  * Compares a runner's ACTUALS against a vector's blessed expected and prints a
  * naughty/nice verdict. An entry whose actual `{ value, cost, error }` equals the
  * vector's `expected` is **nice**; any mismatch is a **lump of coal** and makes
  * the runner **naughty** on that vector.
  *
  *   harness <vector.json> <actuals.json> [<runner-label>]
  */
object Harness {
  def main(args: Array[String]): Unit = {
    if (args.length < 2) {
      System.err.println("usage: harness <vector.json> <actuals.json> [<runner-label>]")
      sys.exit(2)
    }
    val vec   = io.circe.parser.parse(scala.io.Source.fromFile(args(0)).mkString)
      .fold(e => sys.error(s"bad vector json: $e"), identity)
    val act   = io.circe.parser.parse(scala.io.Source.fromFile(args(1)).mkString)
      .fold(e => sys.error(s"bad actuals json: $e"), identity)
    val label = args.lift(2).getOrElse("Rudolph (jvm)")

    val op      = vec.hcursor.get[String]("op").toOption.getOrElse("?")
    val entries = vec.hcursor.downField("entries").values.getOrElse(Vector.empty)
    val actuals = act.asObject.getOrElse(io.circe.JsonObject.empty)

    val lumps = scala.collection.mutable.ListBuffer.empty[String]
    var nice  = 0
    for (e <- entries) {
      val c        = e.hcursor
      val name     = c.get[String]("name").toOption.getOrElse("?")
      val expected = c.downField("expected").focus.getOrElse(Json.Null)
      val actual   = actuals(name).getOrElse(Json.Null)
      if (expected == actual) nice += 1 else lumps += name
    }
    val total = entries.size

    if (lumps.isEmpty)
      println(s"$label   nice ✓   $nice/$total   $op")
    else {
      println(s"$label   naughty   $nice/$total   $op")
      lumps.foreach(n => println(s"    lump of coal: $n"))
    }
  }
}
