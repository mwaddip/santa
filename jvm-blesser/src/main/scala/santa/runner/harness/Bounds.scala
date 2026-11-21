package santa.runner.harness

import io.circe.Json
import java.io.File
import java.nio.file.{Files, Path, Paths}
import scala.collection.JavaConverters._
import scala.util.Try

/** rudolph's bounded-file watchdog (runner-integration §3a). A bounded file's entry runs in a child process in a process
  * group of its own — through `bash -c 'set -m; ulimit -d …'`, since the JVM cannot call setpgid: bash's job control
  * puts the background child in its own group, and `ulimit -d` sets the RLIMIT_DATA backstop. The parent polls the
  * group's resident memory (VmRSS + VmSwap) and each process's peak (VmHWM) every 10 ms and kills the group past
  * `memory_mb` or `wall_s`; a child that finishes reports its own peak (never the parent's rusage, which on Linux
  * carries the parent's memory at the fork). */
object Bounds {
  final case class Budget(memoryMb: Long, wallS: Long)

  sealed trait Outcome
  case object Finished extends Outcome
  final case class Exceeded(limit: String) extends Outcome
  final case class Crashed(note: String) extends Outcome

  /** The envelope's `limits`: None for an ordinary file, Left for a `limits` that is not a positive whole memory_mb and
    * wall_s (never run such a file unbounded). Whole numbers as SANTA's validate reads them: an integer literal, never a
    * numeric string or a float. */
  def budget(doc: Json): Either[String, Option[Budget]] =
    doc.asObject.flatMap(_("limits")) match {
      case None => Right(None)
      case Some(l) =>
        def whole(k: String): Option[Long] =
          l.asObject.flatMap(_(k)).flatMap(_.asNumber).filter(Equal.integralLiteral).flatMap(_.toLong)
        (whole("memory_mb"), whole("wall_s")) match {
          case (Some(m), Some(w)) if m > 0 && w > 0 => Right(Some(Budget(m, w)))
          case _                                    => Left(s"a malformed `limits`: ${l.noSpaces}")
        }
    }

  /** The backstop under the watchdog: twice the budget, at least 4096 MiB. */
  private def backstopMb(b: Budget): Long = math.max(2 * b.memoryMb, 4096)

  /** The group `pgid`'s memory (KiB): (VmRSS + VmSwap summed over its processes, the highest VmHWM of any). */
  def groupMemory(pgid: Long): (Long, Long) = {
    var now = 0L
    var peak = 0L
    val procs = Try(Files.list(Paths.get("/proc"))).toOption
    procs.foreach { s =>
      try {
        s.iterator().asScala.foreach { p =>
          val name = p.getFileName.toString
          if (name.nonEmpty && name.forall(_.isDigit)) {
            Try(new String(Files.readAllBytes(p.resolve("stat")), "UTF-8")).toOption.foreach { stat =>
              // after the command's ")": state, ppid, pgrp
              val f = stat.substring(stat.lastIndexOf(')') + 1).trim.split("\\s+")
              if (f.length > 2 && f(2) == pgid.toString) {
                Try(new String(Files.readAllBytes(p.resolve("status")), "UTF-8")).toOption.foreach { status =>
                  status.split("\n").foreach { l =>
                    def kib = Try(l.split("\\s+")(1).toLong).getOrElse(0L)
                    if (l.startsWith("VmRSS:") || l.startsWith("VmSwap:")) now += kib
                    else if (l.startsWith("VmHWM:")) peak = math.max(peak, kib)
                  }
                }
              }
            }
          }
        }
      } finally s.close()
    }
    (now, peak)
  }

  private def killGroup(pgid: Long): Unit =
    Try(new ProcessBuilder("kill", "-KILL", "--", s"-$pgid").redirectErrorStream(true)
      .redirectOutput(ProcessBuilder.Redirect.DISCARD).start().waitFor())

  /** The out-of-memory signatures of the runtimes SANTA's runners use: the JVM's (an OutOfMemoryError, or a native
    * commit failure), Rust's allocation-failure abort, Node's heap exhaustion. */
  private def outOfMemory(code: Int, said: String): Boolean =
    (code == 3 && said.contains("OutOfMemoryError")) ||
      said.contains("os::commit_memory") || said.contains("insufficient memory for the Java Runtime Environment") ||
      (code == 134 && (said.contains("memory allocation of") || said.contains("heap out of memory") ||
        said.contains("Allocation failed")))

  /** Runs `cmd` as a bounded child under `b`, stderr to `stderr`, its own peak (KiB) read from `peakReport` after a
    * clean exit. */
  def runChild(cmd: Seq[String], b: Budget, stderr: Path, peakReport: Option[Path]): Outcome = {
    // after the child, bash kills what it left in its group (the group outlives its reaped leader while it has members)
    val script = "set -m; ulimit -d \"$1\"; shift; \"$@\" & c=$!; wait $c; s=$?; kill -KILL -- -$c 2>/dev/null; exit $s"
    val wrapper = Seq("bash", "-c", script, "bash", (backstopMb(b) * 1024).toString) ++ cmd
    val bash = Try(new ProcessBuilder(wrapper: _*)
      .redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")))
      .redirectOutput(ProcessBuilder.Redirect.DISCARD)
      .redirectError(stderr.toFile).start())
    if (bash.isFailure) return Crashed(s"cannot start the bounded child: ${bash.failed.get.getMessage}")
    val p = bash.get
    val deadline = System.nanoTime() + b.wallS * 1000000000L
    val over = (kib: Long) => kib > b.memoryMb * 1024
    // the child: bash's own child, the leader of its process group
    var child: Option[Long] = None
    def stop(o: Outcome): Outcome = {
      child.foreach(killGroup)
      p.destroyForcibly()
      p.waitFor()
      o
    }
    while (true) {
      if (child.isEmpty) child = p.toHandle.children().iterator().asScala.toSeq.headOption.map(_.pid())
      if (!p.isAlive) {
        val code = p.exitValue()
        if (code == 0) {
          val reported = peakReport.flatMap(f => Try(new String(Files.readAllBytes(f), "UTF-8").trim.toLong).toOption)
          return if (reported.exists(over)) Exceeded("memory") else Finished
        }
        val said = Try(new String(Files.readAllBytes(stderr), "UTF-8")).getOrElse("")
        if (outOfMemory(code, said)) return Exceeded("memory")
        return Crashed(if (code > 128) s"process killed by signal ${code - 128}" else s"process exit status $code")
      }
      child.foreach { pgid =>
        val (now, peak) = groupMemory(pgid)
        if (over(now) || over(peak)) return stop(Exceeded("memory"))
      }
      if (System.nanoTime() >= deadline) return stop(Exceeded("time"))
      Thread.sleep(10)
    }
    Crashed("unreachable")
  }
}
