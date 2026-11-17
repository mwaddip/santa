package santa

// Transaction-tier companion of AuthoredEvalSubstConstantsLift (sigma-rust's santa-probe-substconstants-lift,
// 2026-11-16): each of sigma-rust's 60 whole trees, `sigmaProp(<an eval row>.size > 0)` at tree versions 0 to 3,
// guards a box that is SPENT through TxEngine.validateBytes, at activated 3 (block 4, the storage-rent context) and,
// below tree version 3, at activated 2 (the window re-linked at block 3, AuthoredTxTreeVersion.Block3).
//
// The context is a node's: NINE headers, so `CONTEXT.headers` given as the new values holds nine items. Two of the 60
// trees (`item|headers|*|v3`) name one position for it; under nine headers the JVM's `positions.length ==
// newVals.length` requirement (ErgoTreeSerializer.substituteConstants :323) refuses them, where sigma-rust's one-header
// probe reads the bytes. Their separating twin is added: change 1 of the request (a Header lifted below tree version
// 3) with nine positions, all past the template's constants, so the template comes back unchanged wherever the lift
// passes: refused below tree version 3 (the lift), spent at 3.
//
// The addendum (santa-probe-substconstants-lift-2.md, 2026-11-17) adds its `source|…` eval rows as spends, appended:
// `Coll[String]` new values from context variable 1 (the input's extension) or R4 (the spent box), each as the node's
// readers take them from the transaction and the box; refused at tree version 3, spent below.
//
// extract() FAILS LOUD unless each verdict is the jars' (a value spends, a throw does not), or for the two rows above
// the length requirement.

import io.circe.Json
import org.ergoplatform.ErgoBox
import scorex.util.encode.Base16
import sigma.VersionContext
import sigma.ast.{EvaluatedValue, SType}
import sigma.serialization.{ErgoTreeSerializer, SigmaSerializer}
import santa.runner.TxEngine

import RentFixtures._

object AuthoredTxSubstConstantsLift {
  val Op = "substconstants-lift"
  val Source = "santa:authored-substconstants-lift"
  val Paths: Map[Int, String] = Map(
    2 -> "transaction/v5/authored/substconstants-lift.json",
    3 -> "transaction/v6/authored/substconstants-lift.json")
  private val V = 1000000000L
  private val H = AuthoredTxStorageRent.H

  private def b(h: String): Array[Byte] = Base16.decode(h).get
  private def hx(bs: Array[Byte]): String = Base16.encode(bs).toLowerCase
  private def vlq(n: Int): String = { val w = SigmaSerializer.startWriter(); w.putUInt(n.toLong); hx(w.toBytes) }
  def treeAt(tv: Int, body: String): String =
    if (tv == 0) "00" + body else f"${8 + tv}%02x" + vlq(body.length / 2) + body
  /** A tree's body: its bytes after the header and, when sized, the size (one VLQ byte for these small trees). */
  def bodyOf(tv: Int, treeHex: String): String =
    if (tv == 0) treeHex.drop(2)
    else { val body = treeHex.drop(4); require(treeAt(tv, body) == treeHex, s"unexpected sized form $treeHex"); body }

  /** One spend: a tree from sigma-rust's table (`jarsOutcome` its jars' outcome) or SANTA's nine-position twin. */
  final case class Spend(name: String, treeVersion: Int, activated: Int, treeHex: String, jarsOutcome: Option[String]) {
    def slug: String = name.replace('|', '-').replaceAll("[^A-Za-z0-9.+_=-]", "_")
    def nine: Boolean = name.startsWith("headers9|")
    /** The addendum's `source|…` rows as spends: context variable 1 travels in the input's extension, R4 on the box. */
    def fromContext: Boolean = name.startsWith("source|")
  }

  /** Coll[Int](0) as the rows write the positions, and Coll[Int](0, 1, …, 8). */
  val OnePosition = "100100"
  val NinePositions: String = "1009" + (0 to 8).map(i => f"${2 * i}%02x").mkString

  def spends(): Vector[Spend] = {
    val lines = new String(java.nio.file.Files.readAllBytes(AuthoredEvalSubstConstantsLift.RowsFile), "UTF-8")
      .split("\n").toVector.drop(1).filter(_.trim.nonEmpty).map(_.split("\t", -1)).filter(_(0) == "tree")
    val fromTable = lines.flatMap { c =>
      val as = c(3) match { case "2 and 3" => Seq(2, 3); case a => Seq(a.toInt) }
      as.map(a => Spend(c(1), c(2).toInt, a, c(4), Some(c(5))))
    }
    // change 1 with nine positions, from the table's `item|headers|nocon` body (the same at every tree version)
    val bodies = lines.filter(_(1).startsWith("item|headers|nocon|")).map(c => bodyOf(c(2).toInt, c(4))).distinct
    require(bodies.size == 1, s"item|headers|nocon: one body at every tree version, got $bodies")
    val one = bodies.head
    require(one.indexOf(OnePosition) >= 0 && one.indexOf(OnePosition) == one.lastIndexOf(OnePosition), one)
    val nineBody = one.replace(OnePosition, NinePositions)
    val twins = for { tv <- (0 to 3).toVector; a <- if (tv < 3) Seq(2, 3) else Seq(3) }
      yield Spend(s"headers9|nocon|v$tv", tv, a, treeAt(tv, nineBody), None)
    // the addendum's `source|…` eval rows, each as `sigmaProp(<row>.size > 0)` (the first file's tree form), appended
    val sources = new String(java.nio.file.Files.readAllBytes(AuthoredEvalSubstConstantsLift.StringsFile), "UTF-8")
      .split("\n").toVector.drop(1).filter(_.trim.nonEmpty).map(_.split("\t", -1))
      .filter(c => c(0).startsWith("eval") && c(1).startsWith("source|")).flatMap { c =>
        val as = c(3) match { case "2 and 3" => Seq(2, 3); case a => Seq(a.toInt) }
        as.map(a => Spend(c(1), c(2).toInt, a, treeAt(c(2).toInt, "d191b1" + c(4) + "0400"), Some(c(5))))
      }
    fromTable ++ twins ++ sources
  }

  private val LengthReq = "expected positions and newVals to have the same length"
  private def ctxOf(activated: Int): AuthoredTxTreeVersion.Ctx =
    if (activated == 3) AuthoredTxTreeVersion.Block4 else AuthoredTxTreeVersion.Block3

  /** The verdict this spend must have, and why. */
  private def want(s: Spend): (Boolean, String) = s.jarsOutcome match {
    case Some("value") if s.name.startsWith("item|headers|") =>
      (false, "the JVM's length requirement: nine headers against one position")
    case Some("value") => (true, "the jars reduce the tree to true")
    case Some(o)       => (false, s"the jars $o")
    case None          => (s.treeVersion >= 3, if (s.treeVersion >= 3) "every Header lifts at tree version 3"
                                               else "a Header has no lift below tree version 3")
  }

  private def description(s: Spend, valid: Boolean): String = {
    val what = if (s.nine)
      "`sigmaProp(substConstants(<a template without constants>, Coll(0, 1, …, 8), CONTEXT.headers).size > 0)`: " +
        "change 1 of the request with a position for each of the nine headers, all past the template's constants"
    else if (s.fromContext) "`sigmaProp(substConstants(…).size > 0)` over sigma-rust's eval row of that name, with " +
      "context variable 1 = `Coll[String](\"C\")` in the input's extension and R4 = `Coll[String](\"D\")` on the box"
    else "`sigmaProp(substConstants(…).size > 0)` over sigma-rust's eval row of that name"
    val why = (s.nine, s.jarsOutcome) match {
      case (_, _) if s.fromContext && valid => "A String lifts below tree version 3, and the box SPENDS."
      case (_, _) if s.fromContext => "A String has no lift at tree version 3, wherever it comes from, so the call " +
        "throws and the box does NOT spend. SEPARATING ROW: an implementation that lifts a String at every version " +
        "spends it."
      case (true, _) if valid => "Every header lifts at tree version 3, nothing is written, and the box SPENDS."
      case (true, _) => "A Header has no case in the lift below tree version 3, so the call throws before the " +
        "template is read and the box does NOT spend. SEPARATING ROW: an implementation that turns every new value into " +
        "data without the table's version gates spends it."
      case (_, Some("value")) if !valid => "Under nine headers the new values hold nine items for one position: the " +
        "JVM's requirement that they have the same length refuses the call, and the box does NOT spend."
      case _ if valid => "The tree reduces to true and the box SPENDS."
      case _ => "The call throws and the box does NOT spend."
    }
    s"[${s.name}] $what (tree v${s.treeVersion}) guards the box being SPENT at activated ${s.activated}, under nine " +
      s"headers. $why"
  }

  def extract(): Map[Int, Json] = spends().groupBy(_.activated).map { case (a, ss) =>
    val c = ctxOf(a)
    val entries = ss.zipWithIndex.map { case (s, i) =>
      val tree = VersionContext.withVersions(3, 3)(ErgoTreeSerializer.DefaultSerializer.deserializeErgoTree(b(s.treeHex)))
      require(hx(VersionContext.withVersions(3, 3)(tree.bytes)) == s.treeHex, s"${s.name}: the tree must travel as written")
      val E = AuthoredEvalSubstConstantsLift
      val regs: Regs = if (s.fromContext) Map[ErgoBox.NonMandatoryRegisterId, EvaluatedValue[_ <: SType]](
        ErgoBox.R4 -> E.constantOf(E.R4Bytes)).asInstanceOf[Regs] else Map()
      val extension = if (s.fromContext) ext(1.toByte -> E.constantOf(E.Var1Bytes)) else ext()
      val bx = VersionContext.withVersions(3, 3)(box(s"santa:scl:${s.name}:a$a", V, 1, tree, Nil, regs))
      val txHex = VersionContext.withVersions(3, 3)(hex(txBytes(tx(Seq(input(bx, extension)), Seq(candidate(V, 1))))))
      val v = TxEngine.validateBytes(txHex, Seq(hex(bx.bytes)), Nil, c.headersHex, c.preHeader,
        AuthoredTxStorageRent.params)
      val (wantValid, because) = want(s)
      require(v.valid == wantValid, s"${s.name} a$a: want valid=$wantValid ($because), got ${v.valid} ${v.reason.getOrElse("")}")
      if (!wantValid && s.jarsOutcome.contains("value"))
        require(v.reason.exists(_.contains(LengthReq)), s"${s.name} a$a: want the length requirement, got ${v.reason}")
      Json.obj(
        "name"                 -> Json.fromString(s"substconstants-lift-spend-${s.slug}-a$a#$i"),
        "source"               -> Json.fromString(Source),
        "description"          -> Json.fromString(description(s, v.valid)),
        "tx_bytes_hex"         -> Json.fromString(txHex),
        "input_boxes_hex"      -> Json.arr(Json.fromString(hex(bx.bytes))),
        "data_input_boxes_hex" -> Json.arr(),
        "headers_hex"          -> Json.arr(c.headersHex.map(Json.fromString): _*),
        "preHeader"            -> c.preHeader,
        "parameters"           -> AuthoredTxStorageRent.params,
        "context"              -> Json.obj("height" -> Json.fromInt(H)),
        "version"              -> Json.obj("activated" -> Json.fromInt(a), "ergoTree" -> Json.fromInt(s.treeVersion)),
        "expected"             -> Json.obj(
          "valid"  -> Json.fromBoolean(v.valid),
          "cost"   -> v.cost.map(Json.fromLong).getOrElse(Json.Null),
          "reason" -> v.reason.map(Json.fromString).getOrElse(Json.Null)))
    }
    a -> Json.obj(
      "schema"     -> Json.fromString("santa-transaction/v1"),
      "op"         -> Json.fromString(Op),
      "blessed_by" -> Json.fromString("jvm:ergo-core-6.0.7-validateStateful"),
      "entries"    -> Json.arr(entries: _*))
  }

  def writeVectors(vectorsRoot: java.nio.file.Path): Unit = extract().foreach { case (a, env) =>
    val path = vectorsRoot.resolve(Paths(a))
    java.nio.file.Files.createDirectories(path.getParent)
    java.nio.file.Files.write(path, env.spaces2.getBytes(java.nio.charset.StandardCharsets.UTF_8))
  }
}
