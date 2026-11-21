package santa.runner.harness

import io.circe.parser.parse
import java.nio.file.{Files, Path}

/** rudolph's bounded-file watchdog (runner-integration §3a): the child in its own process group (through bash job
  * control), killed at its memory or wall budget, its crash described. Children run as `bash <script>`. */
class BoundsTest extends munit.FunSuite with Scratch {
  def script(tag: String, body: String): Path = {
    val d = scratch(s"bounds-$tag")
    val s = d.resolve("run.sh")
    Files.write(s, s"#!/usr/bin/env bash\n$body\n".getBytes("UTF-8"))
    s
  }
  def run(s: Path, memoryMb: Long, wallS: Long, peak: Option[Path] = None): Bounds.Outcome =
    Bounds.runChild(Seq("bash", s.toString), Bounds.Budget(memoryMb, wallS), s.resolveSibling("stderr"), peak)
  def alive(pid: String): Boolean = {
    val p = java.nio.file.Paths.get(s"/proc/${pid.trim}/stat")
    Files.exists(p) && !new String(Files.readAllBytes(p), "UTF-8").split("\\)").last.trim.startsWith("Z")
  }
  def read(p: Path): String = new String(Files.readAllBytes(p), "UTF-8").trim

  test("a child past its wall time is exceeded time") {
    val t0 = System.nanoTime()
    assertEquals(run(script("wall", "sleep 30"), 1024, 1), Bounds.Exceeded("time"))
    assert((System.nanoTime() - t0) / 1e9 < 4)
  }

  test("a child past its memory is exceeded memory") {
    val s = script("memory", "exec python3 -I -c 'a = []\nwhile True: a.append(bytearray(10**7))'")
    assertEquals(run(s, 64, 30), Bounds.Exceeded("memory"))
  }

  test("the child has a process group of its own, not the runner's") {
    val s = script("group", "echo \"$$ $(cut -d' ' -f5 /proc/$$/stat)\" > \"$(dirname \"$0\")/ids\"")
    assertEquals(run(s, 1024, 30), Bounds.Finished)
    val Array(pid, pgid) = read(s.resolveSibling("ids")).split(" ")
    assertEquals(pgid, pid)
    val mine = new String(Files.readAllBytes(java.nio.file.Paths.get("/proc/self/stat")), "UTF-8").split("\\)").last.trim.split(" ")(2)
    assertNotEquals(pgid, mine)
  }

  test("a child's grandchild dies with its group") {
    val s = script("grandchild", "sleep 30 &\necho $! > \"$(dirname \"$0\")/child.pid\"\nsleep 30")
    assertEquals(run(s, 1024, 1), Bounds.Exceeded("time"))
    Thread.sleep(200)
    assert(!alive(read(s.resolveSibling("child.pid"))), "the grandchild outlived its group")
  }

  test("a child that exits at once takes what it left in its group with it") {
    val s = script("prompt", "sleep 30 &\necho $! > \"$(dirname \"$0\")/child.pid\"")
    assertEquals(run(s, 1024, 30), Bounds.Finished)
    Thread.sleep(200)
    assert(!alive(read(s.resolveSibling("child.pid"))), "the grandchild outlived its group")
  }

  test("an interrupted runner kills its child's group (a JVM: its shutdown hook) and exits as the signal says") {
    val s = script("interrupted", "sleep 30 &\necho $! > \"$(dirname \"$0\")/child.pid\"\nsleep 30")
    val pidFile = s.resolveSibling("child.pid")
    val java = ProcessHandle.current().info().command().get
    val fixture = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), "santa.runner.harness.BoundsHoldFixture", s.toString)
      .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
    val t0 = System.nanoTime()
    while (!Files.exists(pidFile)) {
      assert(System.nanoTime() - t0 < 30e9.toLong, "the child never started")
      Thread.sleep(20)
    }
    Thread.sleep(50)
    assertEquals(new ProcessBuilder("kill", "-INT", fixture.pid().toString).start().waitFor(), 0)
    assertEquals(fixture.waitFor(), 130, "a JVM interrupted by SIGINT exits 128 + 2")
    Thread.sleep(200)
    assert(!alive(read(pidFile)), "the grandchild outlived its runner")
  }

  test("a crash is its exit status or its signal") {
    assertEquals(run(script("exit", "exit 101"), 1024, 30), Bounds.Crashed("process exit status 101"))
    // through bash, exit status 128 + N and signal N read the same: the note names both
    assertEquals(run(script("segv", "kill -SEGV $$"), 1024, 30), Bounds.Crashed("process exit status 139 (killed by signal 11?)"))
  }

  test("a reported peak past the budget is exceeded memory") {
    val s = script("reported",
      "exec python3 -I -c 'import sys\nx = b\"x\" * (100 << 20)\ndel x\nhwm = [l.split()[1] for l in open(\"/proc/self/status\") if l.startswith(\"VmHWM:\")][0]\nopen(sys.argv[1], \"w\").write(hwm)' \"$(dirname \"$0\")/peak\"")
    assertEquals(run(s, 64, 30, Some(s.resolveSibling("peak"))), Bounds.Exceeded("memory"))
  }

  test("the runner's own memory never counts against its child") {
    val held = Array.fill[Byte](128 << 20)(1)
    assertEquals(run(script("parent", "true"), 64, 30), Bounds.Finished)
    assertEquals(held.length, 128 << 20)
  }

  test("a JVM that ran out of memory is exceeded memory") {
    val s = script("oome", "echo 'Exception in thread \"main\" java.lang.OutOfMemoryError: Java heap space' >&2\nexit 3")
    assertEquals(run(s, 1024, 30), Bounds.Exceeded("memory"))
  }

  test("a budget is the envelope's limits; a malformed one is refused") {
    assertEquals(Bounds.budget(parse("""{"limits": {"memory_mb": 1280, "wall_s": 10}}""").toOption.get), Right(Some(Bounds.Budget(1280, 10))))
    assertEquals(Bounds.budget(parse("""{"entries": []}""").toOption.get), Right(None))
    assert(Bounds.budget(parse("""{"limits": {"memory_mb": "lots", "wall_s": 10}}""").toOption.get).isLeft)
    // whole numbers only, as SANTA's validate and the Rust harness read them (as_u64)
    assert(Bounds.budget(parse("""{"limits": {"memory_mb": "1280", "wall_s": 10}}""").toOption.get).isLeft, "a numeric string")
    assert(Bounds.budget(parse("""{"limits": {"memory_mb": 1280.0, "wall_s": 10}}""").toOption.get).isLeft, "a float")
  }
}
