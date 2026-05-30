package santa.blesser

import sigma.VersionContext

/** SANTA JVM blesser CLI (single fixture). `run <input.json> [<output.json>]`.
  * With an output path it writes the vector; without, prints to stdout. */
object Main {
  def main(args: Array[String]): Unit = {
    val inPath = args.headOption.getOrElse {
      System.err.println("usage: run <input-fixture.json> [<output-vector.json>]")
      sys.exit(2)
    }
    val activated = VersionContext.MaxSupportedScriptVersion
    val vector    = Blesser.blessFixture(inPath, activated)
    args.lift(1) match {
      case Some(p) =>
        val path = java.nio.file.Paths.get(p)
        Option(path.getParent).foreach(java.nio.file.Files.createDirectories(_))
        java.nio.file.Files.write(path, vector.spaces2.getBytes(java.nio.charset.StandardCharsets.UTF_8))
        println(s"wrote ${Blesser.opFromPath(inPath)} → $p")
      case None => println(vector.spaces2)
    }
  }
}
