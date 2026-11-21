package santa.runner.harness

import java.nio.file.{Files, Path}
import scala.collection.JavaConverters._

/** A suite's scratch directories, removed after the suite. */
trait Scratch extends munit.FunSuite {
  private val made = scala.collection.mutable.ListBuffer.empty[Path]

  def scratch(tag: String): Path = {
    val d = Files.createTempDirectory(s"rudolph-$tag-")
    made.synchronized(made += d)
    d
  }

  override def afterAll(): Unit = {
    made.synchronized(made.toList).foreach { d =>
      if (Files.exists(d)) {
        val s = Files.walk(d)
        try s.iterator().asScala.toVector.reverse.foreach(p => Files.deleteIfExists(p)) finally s.close()
      }
    }
    super.afterAll()
  }
}
