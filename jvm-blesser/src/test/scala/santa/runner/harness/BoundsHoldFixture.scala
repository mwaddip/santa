package santa.runner.harness

import java.nio.file.Paths

/** The interruption test's runner: `bash <script>` as a bounded child, held until this JVM is signalled. */
object BoundsHoldFixture {
  def main(args: Array[String]): Unit = {
    val script = Paths.get(args(0))
    Bounds.runChild(Seq("bash", script.toString), Bounds.Budget(1024, 60), script.resolveSibling("stderr"), None)
  }
}
