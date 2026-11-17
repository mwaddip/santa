package santa

// Authored eval vectors for sigma-rust's santa-probe-specialised-patch-array (2026-11-17, its queue item 31): a
// specialised collection's `patch` casts the patch's ARRAY, also an empty one. Measured first in
// santa.spike.SpecialisedPatchArrayProbe (local); the reply is prompts/drafts/sigma-rust-specialised-patch-array-reply.md.
// Transaction companion: AuthoredTxPatchArrayClass.
//
// THE RULE, read in 6.0.7 and measured: CollOverArray.patch is `toArray.patch(from, patch.toArray, replaced)`
// (sigma/data/CollsOverArrays.scala). In a specialised class (`CollOverArray$mcB$sp`, `$mcZ$sp`, `$mcI$sp`) the
// patch's `toArray` is cast to `byte[]`, `boolean[]` or `int[]` before anything is stored, so a patch held over another
// array throws ClassCastException although it is empty and nothing is replaced. A generic collection hands its builder
// the patch's items and takes an empty patch of any class. PairOfCols.patch unzips the patch and patches side by side,
// so a specialised side refuses the other collection's side. The cost is added before the call. What decides is the
// receiver's CLASS, not its item type: a `Coll[Byte]` built by `map` or `flatMap`, a ConcreteCollection, a `Coll[Int]`
// constant are generic on the JVM and take any empty patch (sigma-rust measured them; they have no row here).
//
// THE ROWS: sigma-rust's 2,843 (src/test/resources/sigma-rust-patch-array-class/rows.tsv, with the jars' outcome, value
// and cost as an independent cross-check), each the bare root `<receiver>.patch(0, <patch>, 0)` at tree version 0
// (unsized, `00` + body; the jars give the same answer at activated 2 and 3, and so does our oracle: authored at both) or
// 3 (`0b` + size + body, activated 3). A row is `<set>|patch|<receiver>|<patch>|v<tree version>`. The receiver's first
// letter is its item type: B Byte, Z Boolean, I Int, P (Int, Long), Q (Byte, Boolean). The patch is `ctl<n>` (a
// constant of n items of the receiver's own type), or `<X><source><n>[.direct]` (X the patch's item type, the same
// letters plus L Long, S Short, N BigInt, C Coll[Byte]; n its items; behind `if (false) <one item of the receiver's
// type> else <it>` unless `.direct`), or `triple<X>.direct` (the tuple constant `(1, 1, 1)` of X, a Coll[Any] at
// runtime). Tree-v0 rows go to the v5 files at activated 2 and to the `_v6` files at activated 3, tree-v3 rows to the
// `_v6` files; the rows whose receiver the CONTEXT brings go to the `_context` files.
//
// THE CONTEXT of the `_context` files (santa-eval/v6-fullctx): the header and pre-header of the substConstants-lift
// families (AuthoredEvalSubstConstantsLift), and sigma-rust's own SELF box and extension (its ctx.env), each as the
// bytes a node reads: SELF holds R4 = Coll[Byte](1, 2), R5 = Coll(true, false), R6 = Coll(Coll[Byte](1), Coll[Byte](2))
// and one token; context variable 1 = Coll[Byte](1, 2) and 2 = Coll(true, false), `Value` bindings, on the rows that
// read them (`getVar`; the reader decides the runtime class, which is the point there; every other row's context
// carries no extension). The outcomes do not depend on the fields' values, the costs do not
// either (measured: every cost is the jars'); a value that holds a header's, the pre-header's parent or
// LastBlockUtxoRootHash's bytes is ours.
//
// ONE DIFFERENCE FROM sigma-rust's TABLE, measured at the transaction tier first: a node builds the pre-header's
// `parentId` and `votes` as GENERIC collections (ergo-core's PreHeader.toSigma, `toColl`), so the 16 moves over those two
// receivers answer on a node. sigma-rust's harness built them from bytes as specialised collections, and so did this
// oracle until EvalCore.preHeaderBytes (the whole committed corpus re-blessed unchanged with it).

import scala.util.{Failure, Success, Try}

import io.circe.Json
import scorex.util.encode.Base16

import sigma.VersionContext
import sigma.ast.UnparsedErgoTree
import sigma.serialization.SigmaSerializer

object AuthoredEvalPatchArrayClass {
  val Source = "santa:authored-patch-array-class"
  val Stamp = "jvm:sigma-state-6.0.7"
  val IntInput: Json = Json.obj("kind" -> Json.fromString("Int"), "value" -> Json.fromInt(0))
  val RowsFile: java.nio.file.Path =
    java.nio.file.Paths.get("src", "test", "resources", "sigma-rust-patch-array-class", "rows.tsv")

  // ── the context ──────────────────────────────────────────────────────────────────────────────────
  private val L = AuthoredEvalSubstConstantsLift
  /** sigma-rust's SELF (ctx.env PROBE_SELF): 1,000,000 nanoErg, the generator's P2PK, created at 100, one token
    * (0x22 x 32, amount 1), R4 = Coll[Byte](1, 2), R5 = Coll(true, false), R6 = Coll(Coll[Byte](1), Coll[Byte](2)),
    * transaction id 0x11 x 32, index 0. */
  val SelfHex: String = "c0843d0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f817986401" +
    "22" * 32 + "01" + "03" + "0e020102" + "0d0201" + "1a0201010102" + "11" * 32 + "00"
  /** Context variables 1 and 2 (ctx.env PROBE_EXT `02010e020102020d0201`), as the bytes the extension reader takes.
    * They travel only with the rows that read them (`B.getVar`, `Z.getVar`): a `Value` binding is for a verdict that
    * depends on how the bound value was built (contract §4), and on no other row does the outcome or the cost depend on
    * the extension (the jars measured every row with it; the test holds every cost to theirs). */
  val Var1Bytes = "0e020102"
  val Var2Bytes = "0d0201"
  def valueBinding(h: String): Json =
    Json.obj("kind" -> Json.fromString("Value"), "bytes_hex" -> Json.fromString(h))
  val Ext: Map[Int, Json] = Map(1 -> valueBinding(Var1Bytes), 2 -> valueBinding(Var2Bytes))
  private def extJson(e: Map[Int, Json]): Json = Json.obj(e.toSeq.sortBy(_._1).map { case (k, v) => k.toString -> v }: _*)
  def context(activated: Int, ext: Map[Int, Json]): Json = contextWith(activated, SelfHex, ext)
  /** The same context around another SELF (AuthoredEvalGenericProducers' rv rows). */
  def contextWith(activated: Int, selfHex: String, ext: Map[Int, Json]): Json = Json.obj(
    "self_index"       -> Json.fromInt(0),
    "inputs"           -> Json.arr(Json.fromString(selfHex)),
    "data_inputs"      -> Json.arr(),
    "outputs"          -> Json.arr(),
    "headers"          -> Json.arr(Json.fromString(L.HeaderHex(activated))),
    "pre_header_hex"   -> Json.fromString(L.preHeaderHex(activated)),
    "height"           -> Json.fromInt(L.Height),
    "extension"        -> extJson(ext),
    "input_extensions" -> Json.arr(extJson(ext)))
  def extOf(r: Row): Map[Int, Json] = if (r.readsExtension) Ext else Map.empty
  def contextOf(r: Row): Json = context(r.activated, extOf(r))

  // ── the rows ─────────────────────────────────────────────────────────────────────────────────────
  /** One vendored row at one activated version. `jars*` is sigma-rust's measurement on the 6.0.7 jars (under its own
    * header and pre-header for the context rows). */
  case class Row(part: String, name: String, treeVersion: Int, activated: Int, body: String,
                 jarsOutcome: String, jarsValue: String, jarsCost: String) {
    private val f = name.split('|')
    def receiver: String = f(2)
    def patch: String = f(3)
    def inContext: Boolean = part.startsWith("eval, in the context")
    /** A receiver read from a context variable: the only rows whose context carries the extension. */
    def readsExtension: Boolean = receiver.endsWith(".getVar")
    def control: Boolean = patch.startsWith("ctl")
    def treeHex: String =
      if (treeVersion == 0) "00" + body else f"${8 + treeVersion}%02x" + vlq(body.length / 2) + body
    def op: String = "Coll.patch_array_class" + (if (inContext) "_context" else "") + (if (activated == 3) "_v6" else "")
    def slug: String = name.replace('|', '-').replaceAll("[^A-Za-z0-9.+_=-]", "_")
    /** A value holding bytes of OUR header, pre-header or LastBlockUtxoRootHash (sigma-rust's harness has its own). */
    def holdsOurHeader: Boolean = HeaderReceivers(receiver)
    /** A receiver a node builds as a GENERIC collection: the pre-header's parentId and votes (EvalCore.preHeaderBytes).
      * sigma-rust's harness built them specialised, so its table says `throws` where a node answers. */
    def nodeBuiltGeneric: Boolean = NodeBuiltGeneric(receiver)
    /** The jars' outcome on a node: sigma-rust's, except a move over a receiver a node builds generic answers. */
    def nodeAnswers: Boolean = jarsOutcome == "value" || nodeBuiltGeneric
  }
  val NodeBuiltGeneric: Set[String] = Set("B.preHeader.parentId", "B.preHeader.votes")
  val HeaderReceivers: Set[String] = Set("B.header.id", "B.header.parentId", "B.header.ADProofsRoot",
    "B.header.stateRoot.digest", "B.header.transactionsRoot", "B.header.extensionRoot", "B.header.powNonce",
    "B.preHeader.parentId", "B.lastBlockUtxoRoot.digest")

  def rows(): Vector[Row] = {
    val lines = new String(java.nio.file.Files.readAllBytes(RowsFile), "UTF-8").split("\n").toVector
    lines.drop(1).filter(_.trim.nonEmpty).map(_.split("\t", -1)).filter(_(0).startsWith("eval")).flatMap { c =>
      val as = c(3) match { case "2 and 3" => Seq(2, 3); case a => Seq(a.toInt) }
      as.map(a => Row(c(0), c(1), c(2).toInt, a, c(4), c(5), c(6), c(7)))
    }
  }

  private def hx(bs: Array[Byte]): String = Base16.encode(bs).toLowerCase
  private def vlq(n: Int): String = { val w = SigmaSerializer.startWriter(); w.putUInt(n.toLong); hx(w.toBytes) }

  // ── the oracle ───────────────────────────────────────────────────────────────────────────────────
  sealed trait Outcome
  final case class Answer(value: Json, cost: Long) extends Outcome
  final case class RefusedAtParse(t: Throwable) extends Outcome
  final case class ThrowsAtEval(t: Throwable) extends Outcome

  /** The tree read under the row's own (activated, tree version), as a node reads a script. */
  def parse(treeHex: String, activated: Int, treeVersion: Int): Either[Throwable, Either[Throwable, Unit]] =
    Try(VersionContext.withVersions(activated.toByte, treeVersion.toByte) {
      sigma.santa.LenientErgoTree.deserialize(Base16.decode(treeHex).get)
    }) match {
      case Failure(t) => Left(t)
      case Success(t) => Right(t.root match {
        case Left(UnparsedErgoTree(_, err)) => Left(err)
        case Right(_)                       => Right(())
      })
    }

  def outcome(r: Row): Outcome = parse(r.treeHex, r.activated, r.treeVersion) match {
    case Left(t)        => RefusedAtParse(t)
    case Right(Left(t)) => RefusedAtParse(t)
    case Right(Right(())) =>
      val res =
        if (r.inContext)
          EvalCore.evalFullContextOutcome(r.treeHex, 0, Seq(SelfHex), Nil, Nil, Seq(L.HeaderHex(r.activated)),
            L.preHeaderHex(r.activated), Seq(extOf(r)), None, r.activated.toByte)._2
        else EvalCore.evalAppliedOutcome(r.treeHex, IntInput, r.activated.toByte)._2
      res match {
        case Right((v, cost)) => Answer(v, cost)
        case Left(t)          => ThrowsAtEval(t)
      }
  }

  /** sigma-rust's text form of a value. */
  def text(j: Json): String = AuthoredEvalPreActivation.text(j)

  def chain(t: Throwable): Seq[Throwable] = Iterator.iterate(t)(_.getCause).takeWhile(_ != null).toSeq
  /** A failure as the script states it: the innermost cause's class and message, identity hashes masked. */
  private def why(t: Throwable): String = {
    val c = chain(t).last
    val m = Option(c.getMessage).getOrElse("").replaceAll("@[0-9a-f]{4,}", "@<hash>").replaceAll("\\s+", " ").trim
    s"${c.getClass.getSimpleName}${if (m.isEmpty) "" else ": " + clip(m, 140)}"
  }
  private def clip(m: String, n: Int): String =
    if (m.length <= n) m else m.take(n).reverse.dropWhile(_ != ' ').reverse.trim + "…"
  def said(o: Outcome): String = o match {
    case Answer(v, cost)   =>
      val t = text(v)
      s"${if (t.length <= 120) t else clip(t, 120)} at cost $cost"
    case RefusedAtParse(t) => s"REFUSED AT PARSE (${why(t)})"
    case ThrowsAtEval(t)   => s"THROWS AT EVALUATION (${why(t)})"
  }

  // ── the scripts ──────────────────────────────────────────────────────────────────────────────────
  private val G = "g" // the generator, as the scripts write it
  val Receiver: Map[String, String] = Map(
    "B.const" -> "Coll[Byte](1, 2)", "B.const0" -> "Coll[Byte]()",
    "B.slice" -> "Coll[Byte](1, 2, 3).slice(0, 2)", "B.slice0" -> "Coll[Byte](1, 2).slice(0, 0)",
    "B.filter" -> "Coll[Byte](1, 2).filter({ (b: Byte) => true })", "B.append" -> "(Coll[Byte](1) ++ Coll[Byte](2))",
    "B.blake" -> "blake2b256(Coll[Byte](1))", "B.l2b" -> "longToByteArray(1L)",
    "B.patched" -> "Coll[Byte](1, 2).patch(0, Coll[Byte](1), 0)",
    "B.inner" -> "Coll(Coll[Byte](1), Coll[Byte](2))(0) (an inner collection of a nested constant)",
    "B.xor" -> "xor(Coll[Byte](1, 2), Coll[Byte](3, 4))", "B.globalxor" -> "Global.xor(Coll[Byte](1, 2), Coll[Byte](3, 4))",
    "B.serialize" -> "Global.serialize(1)", "B.propBytes" -> s"proveDlog($G).propBytes",
    "B.getEncoded" -> s"$G.getEncoded",
    "B.subst" -> s"substConstants(<the P2PK tree of $G>, Coll(0), Coll(proveDlog($G)))",
    "B.updated" -> "Coll[Byte](1, 2).updated(0, 3.toByte)",
    "B.updateMany" -> "Coll[Byte](1, 2).updateMany(Coll(0), Coll[Byte](3))", "B.reverse" -> "Coll[Byte](1, 2).reverse",
    "Z.const" -> "Coll(true, false)", "Z.const0" -> "Coll[Boolean]()",
    "Z.slice" -> "Coll(true, false, true).slice(0, 2)", "Z.filter" -> "Coll(true, false).filter({ (b: Boolean) => true })",
    "Z.append" -> "(Coll(true) ++ Coll(false))",
    "Z.inner" -> "Coll(Coll(true, false))(0) (an inner collection of a nested constant)",
    "Z.updated" -> "Coll(true, false).updated(0, false)", "Z.reverse" -> "Coll(true, false).reverse",
    "I.indices" -> "Coll(1, 2).indices", "I.indices0" -> "Coll[Int]().indices",
    "I.indices.slice" -> "Coll(1, 2, 3).indices.slice(0, 2)",
    "I.indices.filter" -> "Coll(1, 2).indices.filter({ (i: Int) => true })",
    "I.indices.append" -> "(Coll(1, 2).indices ++ Coll(1, 2).indices)",
    "I.indices.updated" -> "Coll(1, 2).indices.updated(0, 3)", "I.indices.reverse" -> "Coll(1, 2).indices.reverse",
    "P.zipidx" -> "Coll(1, 2).indices.zip(Coll(1L, 2L))", "Q.zip" -> "Coll[Byte](1, 2).zip(Coll(true, false))",
    "Q.zip0" -> "Coll[Byte]().zip(Coll[Boolean]())",
    // brought by the context
    "B.self.id" -> "SELF.id", "B.self.propositionBytes" -> "SELF.propositionBytes", "B.self.bytes" -> "SELF.bytes",
    "B.self.bytesWithoutRef" -> "SELF.bytesWithoutRef", "B.self.R4" -> "SELF.R4[Coll[Byte]].get",
    "Z.self.R5" -> "SELF.R5[Coll[Boolean]].get", "B.self.R6.inner" -> "SELF.R6[Coll[Coll[Byte]]].get(0)",
    "B.getVar" -> "getVar[Coll[Byte]](1).get", "Z.getVar" -> "getVar[Coll[Boolean]](2).get",
    "B.self.token.id" -> "SELF.tokens(0)._1", "B.inputs0.id" -> "INPUTS(0).id",
    "B.context.self.id" -> "CONTEXT.selfBox.id",
    "B.header.id" -> "CONTEXT.headers(0).id", "B.header.parentId" -> "CONTEXT.headers(0).parentId",
    "B.header.ADProofsRoot" -> "CONTEXT.headers(0).ADProofsRoot",
    "B.header.stateRoot.digest" -> "CONTEXT.headers(0).stateRoot.digest",
    "B.header.transactionsRoot" -> "CONTEXT.headers(0).transactionsRoot",
    "B.header.extensionRoot" -> "CONTEXT.headers(0).extensionRoot",
    "B.header.minerPk.getEncoded" -> "CONTEXT.headers(0).minerPk.getEncoded",
    "B.header.powNonce" -> "CONTEXT.headers(0).powNonce", "B.header.votes" -> "CONTEXT.headers(0).votes",
    "B.preHeader.parentId" -> "CONTEXT.preHeader.parentId", "B.preHeader.votes" -> "CONTEXT.preHeader.votes",
    "B.preHeader.minerPk.getEncoded" -> "CONTEXT.preHeader.minerPk.getEncoded", "B.minerPubKey" -> "MinerPubkey",
    "B.lastBlockUtxoRoot.digest" -> "LastBlockUtxoRootHash.digest", "I.inputs.indices" -> "INPUTS.indices")

  private val ItemType: Map[Char, String] = Map('B' -> "Byte", 'Z' -> "Boolean", 'I' -> "Int", 'L' -> "Long",
    'S' -> "Short", 'N' -> "BigInt", 'P' -> "(Int, Long)", 'C' -> "Coll[Byte]", 'Q' -> "(Byte, Boolean)")
  private val Producer: Seq[(String, String)] = Seq(
    "zipBmapZ" -> "built by `zip` of a `map`-built Coll[Byte] and a Coll[Boolean] constant",
    "zipBZmap" -> "built by `zip` of a Coll[Byte] constant and a `map`-built Coll[Boolean]",
    "zipmap" -> "built by `zip` of two `map`-built sides", "zip" -> "built by `zip` of two constants",
    "const" -> "constant", "map" -> "built by `map`", "idx" -> "built by `indices` of a Coll[Int] constant",
    "inner" -> "read out of a nested constant")
  private def items(n: Int): String = n match { case 0 => "an EMPTY"; case 1 => "a one-item"; case 3 => "a three-item" }

  /** The patch, as the script states it. */
  def patchText(r: Row): String = {
    val own = ItemType(r.receiver.head)
    if (r.control) s"${items(r.patch.drop(3).toInt)} Coll[$own] constant (the receiver's own type), as the argument itself"
    else if (r.patch.startsWith("triple")) {
      s"the tuple constant `(1, 1, 1)` of ${ItemType(r.patch(6))}s (a Coll[Any] over an Object array at runtime), as " +
        "the argument itself"
    } else {
      val direct = r.patch.endsWith(".direct")
      val core = r.patch.stripSuffix(".direct")
      val (src, srcText) = Producer.find { case (k, _) => core.drop(1).startsWith(k) }
        .getOrElse(sys.error(s"${r.name}: unknown patch producer $core"))
      val n = core.drop(1 + src.length).toInt
      val what = s"${items(n)} Coll[${ItemType(core.head)}] $srcText"
      if (direct) s"$what, as the argument itself"
      else s"$what, behind `if (false) <one $own item> else <it>`"
    }
  }

  private def arrayOf(t: Char): String = t match {
    case 'B' => "`byte[]`"; case 'Z' => "`boolean[]`"; case 'I' => "`int[]`"; case _ => "its own array class"
  }

  def label(r: Row, o: Outcome): String = (o, r.receiver.head) match {
    case (Answer(_, _), _) if r.control =>
      "CONTROL: a patch held over the receiver's own array class; the cast passes and the call answers."
    case (Answer(_, _), _) if r.nodeBuiltGeneric && r.jarsOutcome == "throws" =>
      "NODE'S CLASS: a node builds the pre-header's `parentId` and `votes` as GENERIC collections (ergo-core's " +
        "`PreHeader.toSigma` writes them with `toColl`), which hand the builder the patch's items and take an empty " +
        "patch of any class: the call answers. Every other collection the context brings (a header's fields, the " +
        "box's, a register, a context variable, a token id) is specialised and throws on this patch. A port that " +
        "treats these two as specialised refuses here."
    case (Answer(_, _), _) =>
      "CONTROL: the patch's array (each side's, for a pair) is of the receiver's array class, which a generic " +
        "collection of the same item type holds too; the cast passes and the call answers."
    case (ThrowsAtEval(_), 'P' | 'Q') =>
      "MOVE: the receiver is a pair of collections (PairOfCols); its `patch` unzips the patch and patches side by " +
        "side, and a specialised side casts the patch's side to its own array class before anything is stored, so " +
        "the call throws although nothing is replaced. A port that reads the patch's items answers a value."
    case (ThrowsAtEval(_), t) =>
      s"MOVE: the receiver is a SPECIALISED collection on the JVM; its `patch` casts the patch's ARRAY to " +
        s"${arrayOf(t)} before anything is stored, and this patch is held over another array, so the call throws " +
        "although nothing is replaced. A port that reads the patch's items answers a value."
    case (RefusedAtParse(_), _) => "PARSE: the tree names a type or method its version does not have."
  }

  def script(r: Row, o: Outcome): String =
    s"[${r.name}] `${Receiver(r.receiver)}.patch(0, <patch>, 0)`, the patch ${patchText(r)} — ${said(o)}. " +
      s"${label(r, o)}  // tree v${r.treeVersion}, activated ${r.activated}"

  // ── the vectors ──────────────────────────────────────────────────────────────────────────────────
  private def contextEntry(r: Row, i: Int, o: Outcome): Json = {
    val expected = o match {
      case Answer(v, cost) => Json.obj("value" -> v, "cost" -> Json.fromLong(cost), "error" -> Json.Null)
      case _               => Json.obj("value" -> Json.Null, "cost" -> Json.Null, "error" -> Json.fromString("errored"))
    }
    Json.obj(
      "name"           -> Json.fromString(s"${r.slug}-a${r.activated}#$i"),
      "script"         -> Json.fromString(script(r, o)),
      "tree_bytes_hex" -> Json.fromString(r.treeHex),
      "version"        -> Json.obj("activated" -> Json.fromInt(r.activated), "ergoTree" -> Json.fromInt(r.treeVersion)),
      "context"        -> contextOf(r),
      "expected"       -> expected)
  }

  def extract(): Map[String, Json] =
    rows().groupBy(_.op).map { case (op, rs) =>
      val entries = rs.zipWithIndex.map { case (r, i) =>
        val o = outcome(r)
        val name = s"${r.slug}-a${r.activated}#$i"
        val a = r.activated.toByte
        if (r.inContext) contextEntry(r, i, o)
        else o match {
          case Answer(_, _) =>
            SpecExtract.authoredEntryV(op, script(r, o), r.treeHex, name, IntInput, a, ergoTree = r.treeVersion)
          case _ =>
            SpecExtract.authoredRejectEntryV(op, script(r, o), r.treeHex, name, IntInput, a, ergoTree = r.treeVersion)
        }
      }
      op -> (
        if (rs.head.inContext) Json.obj(
          "schema"     -> Json.fromString("santa-eval/v6-fullctx"),
          "op"         -> Json.fromString(op),
          "blessed_by" -> Json.fromString(Stamp),
          "source"     -> Json.fromString(Source),
          "entries"    -> Json.arr(entries: _*))
        else SpecExtract.authoredEnvelope(op, entries, Source).mapObject(_.add("blessed_by", Json.fromString(Stamp))))
    }

  def writeVectors(outDir: java.nio.file.Path): Unit =
    SpecExtract.writeStaging("AuthoredEvalPatchArrayClass", extract(), outDir)
}
