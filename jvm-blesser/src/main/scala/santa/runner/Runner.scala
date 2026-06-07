package santa.runner

import io.circe.Json

import sigma.ast.{EvaluatedValue, SType}

import santa.{EvalCore, WireCanonicalize}

/** JVM reference runner — Rudolph.
  *
  * Consumes a `santa-eval`, `santa-wire`, or `santa-transaction` vector and emits, per
  * entry, its ACTUAL — eval `{ value, cost, error }`, wire `{ bytes_hex, error }`, or tx
  * `{ valid, cost, error }` — JSON keyed by entry `name`. Each entry is processed under
  * the version the vector records.
  *
  * Dispatches by the vector's top-level `schema` field:
  *   - `santa-eval/v4` → EvalCore.evalWithSelfRegistersAndVar1 (SELF box registers + var 1 = index)
  *   - `santa-eval/v3` → EvalCore.evalWithInputExtensions (per-input extension map)
  *   - `santa-eval/v2` → EvalCore.evalApplied (reads the entry's `input` binding)
  *   - `santa-eval/v1` → EvalCore.evalEntry   (closed tree, no input)
  *   - `santa-wire/v1` → WireCanonicalize.canonicalize (parse + reserialize, round-trip)
  *   - `santa-transaction/v1` → TxEngine.txEntry (validateStateful) when this build
  *     carries the gated tx engine (SANTA_TX_BLESSER=1 + a publishLocal'd ergo-core);
  *     otherwise every tx entry is a faithful `not-implemented` (this build has no tx
  *     engine — a capability fact, not an excuse).
  *
  *   runner <vector.json> [<actuals-out.json>]
  *
  * With an output path it writes the actuals; without, it prints them to stdout.
  */
object Runner {

  /** Reflection seam to the gated [[santa.runner.TxEngine]] (compiled only under
    * SANTA_TX_BLESSER — its sources import ergo-core, which ungated builds don't carry,
    * so a static reference here would not compile). Absent ⇒ the not-implemented arm. */
  private lazy val txEntryFn: Option[Json => (String, Json)] =
    scala.util.Try {
      val clazz  = Class.forName("santa.runner.TxEngine$")
      val module = clazz.getField("MODULE$").get(null)
      val m      = clazz.getMethod("txEntry", classOf[Json])
      (e: Json) => m.invoke(module, e).asInstanceOf[(String, Json)]
    }.toOption

  /** Grade one transaction-tier entry — real verdicts via the gated engine, or the
    * faithful `not-implemented` outcome on a build without it. */
  def txEntry(e: Json): (String, Json) = txEntryFn match {
    case Some(f) => f(e)
    case None =>
      val name = e.hcursor.get[String]("name").toOption.getOrElse("?")
      name -> Json.obj(
        "valid" -> Json.Null,
        "cost"  -> Json.Null,
        "error" -> Json.fromString("not-implemented"))
  }

  /** Evaluate one vector entry and return the actuals JSON. */
  def evalEntry(schema: String, e: Json): (String, Json) = {
    val c    = e.hcursor
    val name = c.get[String]("name").toOption.getOrElse("?")
    try {
      val hex  = c.get[String]("tree_bytes_hex").toOption.getOrElse("")
      val activated = c.downField("version").get[Int]("activated").toOption
        .map(_.toByte).getOrElse(sigma.VersionContext.MaxSupportedScriptVersion)

      val (_, outcome) = schema match {
        case "santa-eval/v3" =>
          val inputs = c.downField("inputs").values.getOrElse(Vector.empty).toVector.map { inp =>
            val ext = inp.hcursor.downField("extension")
            ext.keys.getOrElse(Iterable.empty).iterator.map { k =>
              k.toInt.toByte -> EvalCore.decodeInputConstant(
                ext.downField(k).focus.getOrElse(sys.error(s"v3 entry '$name': missing extension value for key $k")))
            }.toMap
          }
          EvalCore.evalWithInputExtensions(hex, inputs, activated)
        case "santa-eval/v4" =>
          val regsObj = c.downField("selfRegisters").focus
            .getOrElse(sys.error(s"missing selfRegisters in v4 entry '$name'"))
          val registersJson: Map[Int, io.circe.Json] = regsObj.asObject
            .getOrElse(sys.error(s"selfRegisters must be an object in v4 entry '$name'"))
            .toMap.map { case (k, v) => k.toInt -> v }
          val inputJson = c.downField("input").focus
            .getOrElse(sys.error(s"missing input field in v4 entry '$name'"))
          EvalCore.evalWithSelfRegistersAndVar1(hex, registersJson, inputJson, activated)
        case "santa-eval/v2" =>
          val inputJson = c.downField("input").focus
            .getOrElse(sys.error(s"missing input field in v2 entry '${name}'"))
          EvalCore.evalApplied(hex, inputJson, activated)
        case _ =>
          EvalCore.evalEntry(hex, activated)
      }

      val actual = outcome match {
        case Right((value, cost)) =>
          Json.obj("value" -> value, "cost" -> Json.fromLong(cost), "error" -> Json.Null)
        case Left(_) =>
          Json.obj("value" -> Json.Null, "cost" -> Json.Null, "error" -> Json.fromString("errored"))
      }
      name -> actual
    } catch {
      // Never-panic (runner-contract §3): an uncaught internal error on one entry becomes the
      // `panicked` outcome (coal, message in note) so the run continues. NonFatal excludes
      // fatal errors (OOM, etc.).
      case scala.util.control.NonFatal(t) =>
        name -> Json.obj(
          "value" -> Json.Null,
          "cost"  -> Json.Null,
          "error" -> Json.fromString("panicked"),
          "note"  -> Json.fromString(s"${t.getClass.getName}: ${Option(t.getMessage).getOrElse("")}"))
    }
  }

  /** Round-trip one wire-tier entry: parse `bytes_hex` as `kind` under the entry's version and
    * reserialize. Rudolph IS the JVM canonicalizer, so this reproduces the blessed bytes — actuals
    * `{ bytes_hex, error }` (no value/cost). A recognized parse/reserialize failure is `errored`;
    * any other uncaught throw is `panicked` (note carries the message), per the wire result shape
    * (docs/specs/wire-tier.md). Finer outcome classification (e.g. not-implemented for an
    * unsupported `kind`) is deferred — for the control every vector's kind is supported by
    * construction (the same canonicalizer blessed it). */
  def wireEntry(e: Json): (String, Json) = {
    val c    = e.hcursor
    val name = c.get[String]("name").toOption.getOrElse("?")
    try {
      val kind = c.get[String]("kind").toOption
        .getOrElse(sys.error(s"wire entry '$name': missing kind"))
      val hex  = c.get[String]("bytes_hex").toOption
        .getOrElse(sys.error(s"wire entry '$name': missing bytes_hex"))
      val activated = c.downField("version").get[Int]("activated").toOption.map(_.toByte)
        .getOrElse(sys.error(s"wire entry '$name': missing version.activated"))
      val ergoTree  = c.downField("version").get[Int]("ergoTree").toOption.map(_.toByte)
        .getOrElse(sys.error(s"wire entry '$name': missing version.ergoTree"))
      scala.util.Try(WireCanonicalize.canonicalize(kind, hex, activated, ergoTree)) match {
        case scala.util.Success(bytes) =>
          name -> Json.obj("bytes_hex" -> Json.fromString(bytes), "error" -> Json.Null)
        case scala.util.Failure(scala.util.control.NonFatal(_)) =>
          name -> Json.obj("bytes_hex" -> Json.Null, "error" -> Json.fromString("errored"))
      }
    } catch {
      case scala.util.control.NonFatal(t) =>
        name -> Json.obj(
          "bytes_hex" -> Json.Null,
          "error"     -> Json.fromString("panicked"),
          "note"      -> Json.fromString(s"${t.getClass.getName}: ${Option(t.getMessage).getOrElse("")}"))
    }
  }

  /** Run one vector file, writing actuals to outPath (or stdout if None). */
  def runFile(vecPath: String, outPath: Option[String]): Unit = {
    val doc     = io.circe.parser.parse(scala.io.Source.fromFile(vecPath).mkString)
      .fold(e => sys.error(s"bad json: $e"), identity)
    val schema  = doc.hcursor.get[String]("schema").toOption.getOrElse("santa-eval/v1")
    val entries = doc.hcursor.downField("entries").values.getOrElse(Vector.empty)
    val isWire  = schema.startsWith("santa-wire/")
    val isTx    = schema.startsWith("santa-transaction/")
    val pairs   = entries.toVector.map(e =>
      if (isTx) txEntry(e) else if (isWire) wireEntry(e) else evalEntry(schema, e))
    val out     = Json.obj(pairs: _*).spaces2
    outPath match {
      case Some(p) =>
        java.nio.file.Files.write(java.nio.file.Paths.get(p),
          out.getBytes(java.nio.charset.StandardCharsets.UTF_8))
      case None => println(out)
    }
  }

  /** Run every *.json vector in vecDir, writing actuals to outDir/<same-name>. */
  def runDir(vecDir: String, outDir: String): Unit = {
    java.nio.file.Files.createDirectories(java.nio.file.Paths.get(outDir))
    val it = java.nio.file.Files.list(java.nio.file.Paths.get(vecDir)).iterator()
    while (it.hasNext) {
      val p = it.next()
      if (p.toString.endsWith(".json"))
        runFile(p.toString, Some(java.nio.file.Paths.get(outDir, p.getFileName.toString).toString))
    }
  }

  def main(args: Array[String]): Unit = {
    val path = args.headOption.getOrElse {
      System.err.println("usage: runner <vector.json|vectors-dir> [<out.json|out-dir>]")
      sys.exit(2)
    }
    if (java.nio.file.Files.isDirectory(java.nio.file.Paths.get(path))) {
      val outDir = args.lift(1).getOrElse {
        System.err.println("usage: runner <vectors-dir> <out-dir>"); sys.exit(2)
      }
      runDir(path, outDir)
      System.err.println(s"actuals → $outDir/")
    } else {
      runFile(path, args.lift(1))
      args.lift(1).foreach(p => System.err.println(s"actuals → $p"))
    }
  }
}
