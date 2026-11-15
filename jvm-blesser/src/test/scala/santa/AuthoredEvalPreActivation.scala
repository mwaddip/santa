package santa

// Authored eval vectors for sigma-rust's santa-probe-pre-activation and its follow-up (2026-11-15). Measured first in
// santa.spike.PreActivationProbe; the replies are prompts/drafts/sigma-rust-pre-activation{,-2}-reply.md.
//
// THE RULE, read in 6.0.7 and measured: two collection behaviours sit behind `VersionContext.isJitActivated`
// (activated >= 2), whatever the tree version. From the activation on, which is every block made today:
//  - `Coll.append` (CollsOverArrays.scala:48-57) is the 5.0 concat. It refuses what the 4.x `concatArrays_v4`
//    (CollectionUtil.scala:11-26) took below the activation: a collection over references taking ANY collection, a
//    Short/Int/Long one taking an EMPTY right operand of any class.
//  - `pairColl` (CollsOverArrays.scala:183-203) cuts the longer side of a `zip`, so `map`, `exists`, `forall`,
//    `filter`, the AvlTree methods (`getMany` is `keys.map`, `insert`/`update` are `entries.forall`,
//    CErgoTreeEvaluator.scala:118/140/178) and `Xor` / `Global.xor` (`left.zip(right).map(...)`) read equal sides.
// An implementation that applies the 4.x behaviours at every activated version answers otherwise on the SEPARATING
// rows.
//
// THE ROWS: sigma-rust's, VENDORED AS INPUTS (src/test/resources/sigma-rust-pre-activation/rows.tsv, rows-2.tsv:
// part, set, name, tree version, activated, the expression's bytes) with the jars' outcome as an independent
// cross-check. Every expectation is our oracle's; the JVM writer re-serializes every expression byte-identically (our
// trees). A row's label is measured, not copied: SEPARATING when the same tree at activated 1 (the 4.x reading)
// answers differently, CONTROL when it answers the same. Rows at activated 2 go to `v5`, 3 to `v6`.
//
// THE ROWS BELOW THE ACTIVATION WERE DELETED (2026-11-15, the user's rule: what nothing can reach is irrelevant).
// Activated 0/1 exists only in mainnet blocks up to 889,855, and a full-validation replay of that history with an
// implementation that has none of these 4.x behaviours was clean (ergo-node-rust, 2026-11-08): no historical spend
// needs them. 855 vendored rows went with them.

import io.circe.Json
import scorex.util.encode.Base16

import sigma.serialization.SigmaSerializer

object AuthoredEvalPreActivation {
  val Source = "santa:authored-pre-activation"
  val Stamp = "jvm:sigma-state-6.0.7"
  val IntInput: Json = Json.obj("kind" -> Json.fromString("Int"), "value" -> Json.fromInt(0))
  private val RowsDir = java.nio.file.Paths.get("src", "test", "resources", "sigma-rust-pre-activation")
  /** The first request's rows (append / zip / xor), then the follow-up's (zip-readers / append-by-class). */
  val RowsFiles: Seq[java.nio.file.Path] = Seq(RowsDir.resolve("rows.tsv"), RowsDir.resolve("rows-2.tsv"))

  /** One vendored row: the part (append / zip / xor, then zip-readers / append-by-class), sigma-rust's row set and name, the tree version, the activated
    * version, the expression's bytes, and the jars' measurement (outcome, value text, cost of a value). */
  case class Row(part: String, set: String, name: String, treeVersion: Int, activated: Int, body: String,
                 jarsOutcome: String, jarsValue: String, jarsCost: String) {
    def treeHex: String =
      if (treeVersion == 0) "00" + body else f"${8 + treeVersion}%02x" + vlq(body.length / 2) + body
    /** The activated version a SEPARATING row is measured against: the 4.x reading below the activation. */
    def other: Int = 1
    def op: String = {
      val base = part match {
        case "append" => "Coll.append_concat"
        case "zip"    => "Coll.zip_unequal_sides"
        case "xor"    => "Xor.unequal_sides"
        case "zip-readers"     => "Coll.unequal_pairs_readers"
        case "append-by-class" => "Coll.object_arrays_by_class"
      }
      activated match { case 2 => base; case 3 => base + "_v6" }
    }
    def slug: String = name.replace('|', '-').replaceAll("[^A-Za-z0-9.+_-]", "_")
  }

  def rows(): Vector[Row] = RowsFiles.toVector.flatMap { f =>
    val lines = new String(java.nio.file.Files.readAllBytes(f), "UTF-8").split("\n").toVector
    lines.drop(1).filter(_.trim.nonEmpty).map(_.split("\t", -1)).map(c =>
      Row(c(0), c(1), c(2), c(3).toInt, c(4).toInt, c(5), c(6), c(7), c(8)))
  }

  private def hx(bs: Array[Byte]): String = Base16.encode(bs).toLowerCase
  private def vlq(n: Int): String = { val w = SigmaSerializer.startWriter(); w.putUInt(n.toLong); hx(w.toBytes) }

  /** The oracle's outcome: Right((value, cost)) or Left(the exception's class). */
  def outcome(treeHex: String, activated: Int): Either[String, (Json, Long)] =
    EvalCore.evalApplied(treeHex, IntInput, activated.toByte)._2.left.map(_.takeWhile(_ != ':'))

  /** sigma-rust's text form of a value: numbers decimal, Coll(a,b), a pair (a,b), <sigmaprop>, <point>, <box>, <tree>. */
  def text(j: Json): String = {
    val c = j.hcursor
    c.get[String]("kind").getOrElse("?") match {
      case "Boolean" => c.get[Boolean]("value").map(_.toString).getOrElse("?")
      case "Byte" | "Short" | "Int" => c.get[Int]("value").map(_.toString).getOrElse("?")
      case "Long" | "BigInt" | "UnsignedBigInt" => c.get[String]("value").getOrElse("?")
      case "Coll" => c.downField("items").as[List[Json]].map(_.map(text).mkString("Coll(", ",", ")")).getOrElse("?")
      case "Tuple" => c.downField("items").as[List[Json]].map(_.map(text).mkString("(", ",", ")")).getOrElse("?")
      case "SigmaProp" => "<sigmaprop>"
      case "GroupElement" => "<point>"
      case "Box" => "<box>"
      case "AvlTree" => "<tree>"
      case "Option" => c.downField("value").focus.filterNot(_.isNull).map(v => s"Some(${text(v)})").getOrElse("None")
      case other => s"<$other>"
    }
  }
  private def said(o: Either[String, (Json, Long)]): String =
    o.fold(e => s"REFUSED ($e)", { case (v, cost) => s"${text(v)} at cost $cost" })
  /** The graded outcome, coarse as the eval tier grades it: a refusal, or the value and its cost. */
  def graded(o: Either[String, (Json, Long)]): Option[(Json, Long)] = o.toOption

  private def rule(r: Row): String = r.part match {
    case "append" => "`Coll.append` from the JIT activation on: the 5.0 concat"
    case "zip" => "`zip` from the JIT activation on cuts the longer side"
    case "xor" => "`Xor` / `Global.xor` from the JIT activation on: over the zip cut to the shorter operand"
    case "zip-readers" => "a reader over a `zip` from the JIT activation on, the zip cut to the shorter side"
    case "append-by-class" => "`++` and the readers of its result from the JIT activation on (the 5.0 concat)"
  }

  /** Whether the row separates: the same tree on the other side of the activation answers differently. */
  def separating(r: Row): Boolean = graded(outcome(r.treeHex, r.activated)) != graded(outcome(r.treeHex, r.other))

  def script(r: Row): String = {
    val here = outcome(r.treeHex, r.activated)
    val there = outcome(r.treeHex, r.other)
    val reading = "the 4.x reading (activated 1)"
    val why = if (graded(here) != graded(there)) s"SEPARATING: $reading gives ${said(there)}."
              else s"CONTROL: $reading gives the same."
    s"[${r.name}] ${rule(r)} — ${said(here)}. $why  // tree v${r.treeVersion}, activated ${r.activated}"
  }

  def extract(): Map[String, Json] =
    rows().groupBy(_.op).map { case (op, rs) =>
      val entries = rs.zipWithIndex.map { case (r, i) =>
        val name = s"${r.slug}-a${r.activated}#$i"
        val a = r.activated.toByte
        if (outcome(r.treeHex, r.activated).isRight)
          SpecExtract.authoredEntryV(op, script(r), r.treeHex, name, IntInput, a, ergoTree = r.treeVersion)
        else SpecExtract.authoredRejectEntryV(op, script(r), r.treeHex, name, IntInput, a, ergoTree = r.treeVersion)
      }
      op -> SpecExtract.authoredEnvelope(op, entries, Source).mapObject(_.add("blessed_by", Json.fromString(Stamp)))
    }

  def writeVectors(outDir: java.nio.file.Path): Unit =
    SpecExtract.writeStaging("AuthoredEvalPreActivation", extract(), outDir)
}
