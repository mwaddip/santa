package santa

import com.sun.net.httpserver.{HttpExchange, HttpHandler, HttpServer}
import io.circe.Json
import io.circe.parser.{parse => parseJson}
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

/** Walker JVM-oracle (walker-jvm-oracle-santa.md, Plan 3): bless a `santa-eval/v6-fullctx`
  * envelope against the REAL reconstructed context (`EvalCore.evalFullContext`). The
  * `evalEnvelope` / `blessEnvelope` core is the envelope-JSON adapter; `OracleService` is
  * the persistent HTTP service the walker calls per-tx (batchable per block). */
object WalkerOracle {

  /** Parse a santa-eval/v6-fullctx envelope (entry) and evaluate against the reconstructed full
    * context. Returns (treeVersion, Either[errClass, (valueJson, cost)]) — the same shape the other
    * EvalCore.evalXxx methods return, so rudolph's Runner can dispatch to it directly. Envelope-parse
    * failures map to Left(errClass) (errored), never thrown. */
  def evalEnvelope(env: Json): (Int, Either[String, (Json, Long)]) =
    try {
      val c = env.hcursor
      val tree = c.downField("tree_bytes_hex").as[String].getOrElse(sys.error("missing tree_bytes_hex"))
      val activated = c.downField("version").downField("activated").as[Int]
        .getOrElse(sys.error("missing version.activated")).toByte
      val ctx = c.downField("context")
      val selfIndex = ctx.downField("self_index").as[Int].getOrElse(sys.error("missing context.self_index"))
      def hexes(field: String): Seq[String] = ctx.downField(field).as[List[String]].getOrElse(Nil)
      val preHeaderHex = ctx.downField("pre_header_hex").as[String].getOrElse(sys.error("missing pre_header_hex"))
      val inputsHex = hexes("inputs")
      // Per-input extensions (authoritative when present): input_extensions[i] = input i's
      // ContextExtension (read by getVarFromInput(i, var)); the SELF input's is also the
      // top-level context.extension. Legacy fallback: a SELF-only `extension` at selfIndex.
      val inputExtensions: Seq[Map[Int, Json]] =
        ctx.downField("input_extensions").as[List[Map[String, Json]]].toOption match {
          case Some(list) => list.map(_.map { case (k, v) => k.toInt -> v })
          case None =>
            val selfExt = ctx.downField("extension").as[Map[String, Json]]
              .getOrElse(Map.empty).map { case (k, v) => k.toInt -> v }
            inputsHex.indices.map(i => if (i == selfIndex) selfExt else Map.empty[Int, Json])
        }
      // option-a override: an explicit lastBlockUtxoRoot wins; else evalFullContext derives
      // it from headers[0].stateRoot (option b).
      val lastRootHex = ctx.downField("last_block_utxo_root_hex").as[String].toOption
      val (treeVer, res) = EvalCore.evalFullContext(
        tree, selfIndex, inputsHex, hexes("data_inputs"), hexes("outputs"),
        hexes("headers"), preHeaderHex, inputExtensions, lastRootHex, activated)
      (treeVer.toInt, res)
    } catch {
      case t: Throwable => (0, Left(EvalCore.errClass(t)))
    }

  /** Bless one envelope → result JSON `{tree_version, value, cost, error, reason?}` (the HTTP/oracle shape).
    *
    * `value`+`cost`+`error` are exactly the envelope's `expected` shape: on success
    * `error` is null with the typed value + raw JIT cost; on failure `error` is "errored"
    * with a diagnostic `reason` (and value/cost null). `tree_version` is the parsed
    * ErgoTree version (0 on parse error). Never throws — a malformed envelope surfaces as errored. */
  def blessEnvelope(env: Json): Json = {
    val (treeVer, res) = evalEnvelope(env)
    val base = Json.obj("tree_version" -> Json.fromInt(treeVer))
    res match {
      case Right((value, cost)) =>
        base.deepMerge(Json.obj("value" -> value, "cost" -> Json.fromLong(cost), "error" -> Json.Null))
      case Left(err) =>
        base.deepMerge(Json.obj("value" -> Json.Null, "cost" -> Json.Null,
          "error" -> Json.fromString("errored"), "reason" -> Json.fromString(err)))
    }
  }

  /** Bless a batch (a block's worth of txs) → JSON array of results, input order preserved. */
  def blessBatch(envs: List[Json]): Json = Json.arr(envs.map(blessEnvelope): _*)
}

/** Persistent HTTP oracle the walker calls. Localhost only, dependency-free (the JDK's
  * `com.sun.net.httpserver`). Loads sigma-state once; stateless per request.
  *
  *   POST /eval        body = one v6-fullctx envelope   → one result JSON
  *   POST /eval-batch  body = [envelope, ...]            → [result, ...] (block batch)
  *   POST /avl-proof   body = tree+ops                   → {proof_bytes, proof_digest, tree_digest}
  *                     (+ gen_proof_after: [Int])         → {proofs: [...], digests: [...]} (multi-cycle)
  *   GET  /health      → {"status":"ok"}
  *
  * Run: `sbt --batch "runMain santa.OracleService [port]"` (default 9777). */
object OracleService {

  private def respond(ex: HttpExchange, code: Int, body: String): Unit = {
    val bytes = body.getBytes(StandardCharsets.UTF_8)
    ex.getResponseHeaders.add("Content-Type", "application/json")
    ex.sendResponseHeaders(code, bytes.length.toLong)
    val os = ex.getResponseBody
    os.write(bytes)
    os.close()
  }

  private def readBody(ex: HttpExchange): String =
    new String(ex.getRequestBody.readAllBytes(), StandardCharsets.UTF_8)

  def main(args: Array[String]): Unit = {
    val port = args.headOption.map(_.toInt).getOrElse(9777)
    val server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0)

    server.createContext("/health", new HttpHandler {
      def handle(ex: HttpExchange): Unit = respond(ex, 200, """{"status":"ok"}""")
    })

    server.createContext("/eval", new HttpHandler {
      def handle(ex: HttpExchange): Unit = parseJson(readBody(ex)) match {
        case Right(env) => respond(ex, 200, WalkerOracle.blessEnvelope(env).noSpaces)
        case Left(e)    => respond(ex, 400, Json.obj("error" -> Json.fromString(s"bad JSON: ${e.getMessage}")).noSpaces)
      }
    })

    server.createContext("/eval-batch", new HttpHandler {
      def handle(ex: HttpExchange): Unit = parseJson(readBody(ex)).flatMap(_.as[List[Json]]) match {
        case Right(envs) => respond(ex, 200, WalkerOracle.blessBatch(envs).noSpaces)
        case Left(e)     => respond(ex, 400, Json.obj("error" -> Json.fromString(s"bad batch JSON: ${e.getMessage}")).noSpaces)
      }
    })

    server.createContext("/avl-proof", new HttpHandler {
      def handle(ex: HttpExchange): Unit = try {
        respond(ex, 200, AvlProofGenerator.fromJson(readBody(ex)).noSpaces)
      } catch {
        case t: Throwable =>
          respond(ex, 400, Json.obj("error" -> Json.fromString(s"${t.getClass.getName}: ${Option(t.getMessage).getOrElse("")}")).noSpaces)
      }
    })

    server.setExecutor(Executors.newFixedThreadPool(4))
    server.start()
    println(s"santa walker JVM oracle listening on http://127.0.0.1:$port  (POST /eval, POST /eval-batch, POST /avl-proof, GET /health)")
  }
}
