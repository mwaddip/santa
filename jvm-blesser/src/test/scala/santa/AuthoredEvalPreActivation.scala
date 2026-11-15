package santa

// Authored eval vectors for sigma-rust's santa-probe-pre-activation (2026-11-15): Coll.append's 4.x concat and zip of
// unequal sides below the JIT activation. Measured first in santa.spike.PreActivationProbe; the reply is
// prompts/drafts/sigma-rust-pre-activation-reply.md.
//
// THE RULE, read in 6.0.7 and measured: two collection behaviours sit behind `VersionContext.isJitActivated`
// (activated >= 2), whatever the tree version:
//  - `Coll.append` (CollsOverArrays.scala:48-57): below the activation the concat is `concatArrays_v4`
//    (CollectionUtil.scala:11-26), which allocates by the LEFT array's class and copies with `Array.copy`. A
//    collection over references (BigInt, GroupElement, SigmaProp, Box, AvlTree, a collection, a tuple of other than two
//    items) takes ANY collection, the result an array of objects under the left's item type (a value whose items are of
//    another class than its item type, encoded item by item); a Short/Int/Long one takes an EMPTY right operand of any
//    class. From the activation on, the 5.0 concat refuses both.
//  - `pairColl` (CollsOverArrays.scala:183-203): below the activation a `zip` keeps sides of unequal length; `map`,
//    `exists`, `forall`, `filter` walk the LEFT length and read both sides (throwing where the right ends unless
//    exists/forall answered first); `length`, `indices`, `apply`, `getOrElse`, `fold` take the shorter. From the
//    activation on, the zip cuts the longer side. The `Xor` node and `Global.xor` are `left.zip(right).map(...)`:
//    below the activation a longer LEFT operand throws (the argument order matters; from the activation on the xor
//    is over the shorter operand either way).
//
// THE ROWS: sigma-rust's 1,208 (its two table tests' rows, every tree of version 0, at activated 1 or 3) and its six
// `Xor`/`Global.xor` rows at activated 0 to 3, VENDORED AS INPUTS (src/test/resources/sigma-rust-pre-activation/
// rows.tsv: name, tree version, activated, the expression's bytes) with the jars' outcome as an independent
// cross-check. Every expectation is our oracle's; the JVM writer re-serializes every expression byte-identically (our
// trees). A row's label is measured, not copied: SEPARATING when the same tree on the other side of the activation
// (activated 3 for a row below it, 1 for a row above it) answers differently, CONTROL when it answers the same. Rows
// at activated 0/1 go to the `v4` slot, 2 to `v5`, 3 to `v6`. A v1 tree at activated 0 is left out (a tree above its
// activated version is the transaction tier's, runner-contract §2).

import io.circe.Json
import scorex.util.encode.Base16

import sigma.serialization.SigmaSerializer

object AuthoredEvalPreActivation {
  val Source = "santa:authored-pre-activation"
  val Stamp = "jvm:sigma-state-6.0.7"
  val IntInput: Json = Json.obj("kind" -> Json.fromString("Int"), "value" -> Json.fromInt(0))
  val RowsFile: java.nio.file.Path =
    java.nio.file.Paths.get("src", "test", "resources", "sigma-rust-pre-activation", "rows.tsv")

  /** One vendored row: the part (append / zip / xor), sigma-rust's row set and name, the tree version, the activated
    * version, the expression's bytes, and the jars' measurement (outcome, value text, cost of a value). */
  case class Row(part: String, set: String, name: String, treeVersion: Int, activated: Int, body: String,
                 jarsOutcome: String, jarsValue: String, jarsCost: String) {
    def treeHex: String =
      if (treeVersion == 0) "00" + body else f"${8 + treeVersion}%02x" + vlq(body.length / 2) + body
    def below: Boolean = activated < 2
    /** The activated version on the other side of the activation. */
    def other: Int = if (below) 3 else 1
    def op: String = {
      val base = part match {
        case "append" => "Coll.append_concat"
        case "zip"    => "Coll.zip_unequal_sides"
        case "xor"    => "Xor.unequal_sides"
      }
      activated match { case 0 | 1 => base + "_v4"; case 2 => base; case 3 => base + "_v6" }
    }
    def slug: String = name.replace('|', '-').replaceAll("[^A-Za-z0-9.+_-]", "_")
  }

  def rows(): Vector[Row] = {
    val lines = new String(java.nio.file.Files.readAllBytes(RowsFile), "UTF-8").split("\n").toVector
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
      case other => s"<$other>"
    }
  }
  private def said(o: Either[String, (Json, Long)]): String =
    o.fold(e => s"REFUSED ($e)", { case (v, cost) => s"${text(v)} at cost $cost" })
  /** The graded outcome, coarse as the eval tier grades it: a refusal, or the value and its cost. */
  def graded(o: Either[String, (Json, Long)]): Option[(Json, Long)] = o.toOption

  private def rule(r: Row): String = (r.part, r.below) match {
    case ("append", true) => "`Coll.append` below the JIT activation: the 4.x concat (`concatArrays_v4` allocates by the " +
      "LEFT array's class: an array of references takes any collection, a Short/Int/Long one an empty right operand " +
      "of any class)"
    case ("append", false) => "`Coll.append` from the JIT activation on: the 5.0 concat"
    case ("zip", true) => "`zip` below the JIT activation keeps sides of unequal length (map/exists/forall/filter walk " +
      "the LEFT length and read both sides; length/indices/apply/getOrElse/fold take the shorter)"
    case ("zip", false) => "`zip` from the JIT activation on cuts the longer side"
    case ("xor", true) => "`Xor` / `Global.xor` below the JIT activation: `left.zip(right).map(...)` over sides kept " +
      "unequal, so a longer LEFT operand throws"
    case ("xor", false) => "`Xor` / `Global.xor` from the JIT activation on: over the zip cut to the shorter operand"
  }

  /** Whether the row separates: the same tree on the other side of the activation answers differently. */
  def separating(r: Row): Boolean = graded(outcome(r.treeHex, r.activated)) != graded(outcome(r.treeHex, r.other))

  def script(r: Row): String = {
    val here = outcome(r.treeHex, r.activated)
    val there = outcome(r.treeHex, r.other)
    val reading = if (r.below) "the 5.x reading (activated 3)" else "the 4.x reading (activated 1)"
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
