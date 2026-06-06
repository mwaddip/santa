package santa

import java.nio.file.{Files, Path, Paths}

import scala.collection.mutable

import io.circe.{Json, Printer}

import scorex.util.encode.Base16
import sigma.ast.{MethodCall, Value}

/** Coverage-manifest builder — the standing per-family op/method/arm map of the eval
  * corpus, committed at `docs/coverage/eval-coverage.json`.
  *
  * Coverage is read off each entry's DESERIALIZED TREE (the oracle's own parse), never
  * the `script` text: method names collide across types textually (`.get` is
  * Option/AvlTree/Coll), while the tree carries the exact nodes. Per entry:
  * `LenientErgoTree.deserialize(tree_bytes_hex)` → walk the root value graph in its
  * committed placeholder form → collect every node's `opName` (+ `opCode` for the
  * global index) and every `MethodCall`'s `(typeId, methodId, name)` — PropertyCall
  * materializes as MethodCall, so one arm covers both. Tree-header facts
  * (version / hasSize / constant-segregation / hasDeserialize) and the arm
  * (`expected.error` null ⇒ accept) are recorded per shape so questions like
  * "is there a treeVersion≥3 + hasSize accept for family X?" read straight off the
  * manifest. The `(typeId, methodId)` namespace is shared with conformer method
  * registries (e.g. ergots' `mir/method-signatures.ts`), so a registry diff is a key
  * set-difference — the manifest itself stays registry-free.
  *
  * Deterministic: sorted construction + sortKeys printer, no timestamps — regenerating
  * on an unchanged corpus is byte-identical. CoverageManifestTest gates the committed
  * artifact current; regenerate via `SANTA_WRITE_COVERAGE=1 sbt test`.
  */
object CoverageManifest {

  val vectorsDir: Path   = Paths.get("../vectors/eval")
  val manifestPath: Path = Paths.get("../docs/coverage/eval-coverage.json")

  // ── corpus walk (mirrors EvalConformanceTest) ────────────────────────────────

  def vectorFiles: Seq[Path] = {
    import scala.jdk.CollectionConverters._
    Files.walk(vectorsDir)
      .filter((p: Path) => p.toString.endsWith(".json"))
      .iterator()
      .asScala
      .toSeq
      .sortBy(_.toString)
  }

  private def parseJson(s: String): Json =
    io.circe.parser.parse(s).fold(e => sys.error(s"JSON parse failed: $e"), identity)

  // ── generic value-graph walk ─────────────────────────────────────────────────
  // sigma.ast nodes are case classes; Product/Iterable recursion reaches every Value
  // without per-node knowledge. Constant payloads (sigma.Coll, BigInteger, …) are
  // neither Product nor Iterable, so data is not descended into.
  private def walkValues(node: Any)(f: Value[_] => Unit): Unit = {
    node match {
      case v: Value[_] => f(v)
      case _           => ()
    }
    node match {
      case p: Product      => p.productIterator.foreach(walkValues(_)(f))
      case it: Iterable[_] => it.foreach(walkValues(_)(f))
      case arr: Array[_]   => arr.foreach(walkValues(_)(f))
      case _               => ()
    }
  }

  // ── build ────────────────────────────────────────────────────────────────────

  def build(): Json = {
    val files = vectorFiles
    require(files.nonEmpty, s"no vector files under $vectorsDir")

    val families  = mutable.SortedMap.empty[String, Json]
    // opName → (op codes seen, families seen in)
    val opIndex   = mutable.SortedMap.empty[String, (mutable.SortedSet[String], mutable.SortedSet[String])]
    // "typeId:methodId" → (qualified name, families seen in)
    val methodIdx = mutable.SortedMap.empty[String, (String, mutable.SortedSet[String])]
    var tEntries, tAccepts, tRejects, tUnwalked = 0

    files.foreach { path =>
      val rel   = vectorsDir.relativize(path).toString
      val parts = rel.split('/')
      require(parts.length == 3, s"unexpected vector path shape: $rel")
      val versionDir = parts(0)
      val provenance = parts(1)

      val doc = parseJson(new String(Files.readAllBytes(path)))
      val c   = doc.hcursor
      val label = c.get[String]("op").fold(e => sys.error(s"missing op in $rel: $e"), identity)
      val entries = c.downField("entries").as[List[Json]]
        .fold(e => sys.error(s"missing/invalid entries in $rel: $e"), identity)

      val ops     = mutable.SortedSet.empty[String]
      val methods = mutable.SortedSet.empty[String]
      // (treeVersion, hasSize, segregation, hasDeserialize) → [accepts, rejects]
      val shapes  = mutable.SortedMap.empty[(Int, Boolean, Boolean, Boolean), Array[Int]]
      var accepts, rejects, unwalked = 0

      entries.foreach { entry =>
        val ec      = entry.hcursor
        val name    = ec.get[String]("name").getOrElse("?")
        val treeHex = ec.get[String]("tree_bytes_hex")
          .fold(e => sys.error(s"missing tree_bytes_hex in $rel '$name': $e"), identity)
        val errJson = ec.downField("expected").downField("error").focus
          .getOrElse(sys.error(s"missing expected.error in $rel '$name'"))
        val isAccept = errJson.isNull
        if (isAccept) accepts += 1 else rejects += 1

        try {
          val tree = sigma.santa.LenientErgoTree.deserialize(Base16.decode(treeHex).get)
          val root = tree.root match {
            case Right(v) => v
            case Left(_)  => sys.error("unparsed root")
          }
          val shape = (tree.version.toInt, tree.hasSize, tree.isConstantSegregation, tree.hasDeserialize)
          val cell  = shapes.getOrElseUpdate(shape, Array(0, 0))
          cell(if (isAccept) 0 else 1) += 1

          walkValues(root) { v =>
            val code = f"0x${v.opCode & 0xFF}%02x"
            ops += v.opName
            val (codes, opFams) = opIndex.getOrElseUpdate(
              v.opName, (mutable.SortedSet.empty[String], mutable.SortedSet.empty[String]))
            codes += code
            opFams += rel
            v match {
              case mc: MethodCall =>
                val m   = mc.method
                val key = s"${m.objType.typeId}:${m.methodId}"
                val mn  = s"${m.objType.typeName}.${m.name}"
                methods += s"$key $mn"
                val (existing, mFams) = methodIdx.getOrElseUpdate(key, (mn, mutable.SortedSet.empty[String]))
                if (existing != mn)
                  sys.error(s"method-name disagreement for $key: '$existing' vs '$mn' in $rel")
                mFams += rel
              case _ => ()
            }
          }
        } catch {
          // Entry whose tree can't be decoded/parsed to a walkable root (e.g. a
          // deserialize-level reject arm): counted, contributes no ops/shapes.
          case _: Throwable => unwalked += 1
        }
      }

      families += rel -> Json.obj(
        "op"         -> Json.fromString(label),
        "version"    -> Json.fromString(versionDir),
        "provenance" -> Json.fromString(provenance),
        "entries"    -> Json.fromInt(entries.size),
        "accepts"    -> Json.fromInt(accepts),
        "rejects"    -> Json.fromInt(rejects),
        "unwalked"   -> Json.fromInt(unwalked),
        "ops"        -> Json.arr(ops.toSeq.map(Json.fromString): _*),
        "methods"    -> Json.arr(methods.toSeq.map(Json.fromString): _*),
        "tree_shapes" -> Json.arr(shapes.toSeq.map { case ((tv, hs, cs, hd), cell) =>
          Json.obj(
            "tree_version"         -> Json.fromInt(tv),
            "has_size"             -> Json.fromBoolean(hs),
            "constant_segregation" -> Json.fromBoolean(cs),
            "has_deserialize"      -> Json.fromBoolean(hd),
            "accepts"              -> Json.fromInt(cell(0)),
            "rejects"              -> Json.fromInt(cell(1)))
        }: _*))
      tEntries += entries.size
      tAccepts += accepts
      tRejects += rejects
      tUnwalked += unwalked
    }

    Json.obj(
      "schema" -> Json.fromString("santa-coverage/v1"),
      "tier"   -> Json.fromString("eval"),
      "totals" -> Json.obj(
        "files"            -> Json.fromInt(files.size),
        "entries"          -> Json.fromInt(tEntries),
        "accepts"          -> Json.fromInt(tAccepts),
        "rejects"          -> Json.fromInt(tRejects),
        "unwalked"         -> Json.fromInt(tUnwalked),
        "distinct_ops"     -> Json.fromInt(opIndex.size),
        "distinct_methods" -> Json.fromInt(methodIdx.size)),
      "families" -> Json.obj(families.toSeq: _*),
      "op_index" -> Json.obj(opIndex.toSeq.map { case (opName, (codes, fams)) =>
        opName -> Json.obj(
          "op_codes" -> Json.arr(codes.toSeq.map(Json.fromString): _*),
          "families" -> Json.arr(fams.toSeq.map(Json.fromString): _*))
      }: _*),
      "method_index" -> Json.obj(methodIdx.toSeq.map { case (key, (mn, fams)) =>
        key -> Json.obj(
          "name"     -> Json.fromString(mn),
          "families" -> Json.arr(fams.toSeq.map(Json.fromString): _*))
      }: _*))
  }

  // ── render / write ───────────────────────────────────────────────────────────

  def render(j: Json): String =
    Printer.spaces2.copy(sortKeys = true).print(j) + "\n"

  def write(): Unit = {
    Files.createDirectories(manifestPath.getParent)
    Files.write(manifestPath, render(build()).getBytes(java.nio.charset.StandardCharsets.UTF_8))
  }
}
