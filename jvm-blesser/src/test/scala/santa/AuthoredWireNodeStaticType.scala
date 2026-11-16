package santa

// Authored wire vectors: which trees a node READS when `Exponentiate` and `MultiplyGroup` are typed GroupElement
// whatever their operands. Request: ~/projects/sigma-rust/prompts/santa-probe-node-static-type.md (its 88 whole trees);
// measured by santa.spike.NodeStaticTypeProbe (local). Eval companion: AuthoredEvalNodeStaticType (THE RULE is stated
// there); spend companion: AuthoredTxNodeStaticType.
//
// THE TREES: sigma-rust's 22 roots, each as tree version 0 without the size flag and as versions 0, 1 and 3 with it,
// carried as a bare box and as a transaction's output 0 (BoxTreeWireFixtures), read under the node's v6 context (3, 3),
// so the root-type check (rule 1001) runs as it does for a box (the ErgoTree kind parses an arbitrary root and would
// not). Each verdict is the oracle's, re-derived in extract(); ergo-core's ErgoTransactionSerializer agrees on every
// transaction (measured in the probe: it refuses exactly the 40 rejects at the read).
//  - The node's type decides 7 roots (28 trees): `==` between the node and a group element is read (both sides are
//    GroupElement), and `+ 1` over the node is refused by the builder (`ConstraintFailed`, arithmetic over a group
//    element), at every form: a ConstraintFailed is no ValidationException, so the size flag does not degrade it.
//  - The rest are controls: the carrier (`sigmaProp(true)`); the node as the root (no proposition: rejected unsized,
//    degraded sized, whatever its type); `proveDlog` over the node (it checks nothing at parse); and the `Filter` over
//    an Int, which shows which consumers read a node's type while the tree is read (Filter's type is a strict cast of
//    its input's: a ClassCastException where the root check, a ValDef, an If, a Tuple or a lambda reads it; SizeOf does
//    not).

import io.circe.Json

object AuthoredWireNodeStaticType extends BoxTreeWireFixtures {
  val OpBox = "Box.tree_group_node_static_type"
  val OpTx  = "Transaction.tree_group_node_static_type"
  val Source = "santa:authored-node-static-type"
  val Stamp = "jvm:sigma-state-6.0.7"
  private val RowsFile = AuthoredEvalNodeStaticType.RowsFile
  private val NoSizeBit = "ErgoTree serialized without size bit"
  private val Rule1001  = "Deserialized script should have SigmaProp type"

  case class Tree(root: String, form: String, bytes: String, jars: String) {
    def name: String = s"$root|$form"
    def formText: String = form match {
      case "v0" => "unsized, v0"; case "v0-sized" => "sized, v0"; case "v1-sized" => "sized, v1"
      case "v3-sized" => "sized, v3"
    }
    /** The tree's body: after the header, and after the size where the flag is set. */
    def body: String = if (form == "v0") bytes.drop(2) else {
      val r = sigma.serialization.SigmaSerializer.startReader(b(bytes).drop(1)); r.getUInt(); hex(b(bytes).drop(1 + r.position))
    }
    def slug: String = name.replace('|', '-').replaceAll("[^A-Za-z0-9.+_=-]", "_")
  }

  def trees(): Vector[Tree] =
    new String(java.nio.file.Files.readAllBytes(RowsFile), "UTF-8").split("\n").toVector.drop(1)
      .filter(_.trim.nonEmpty).map(_.split("\t", -1)).filter(_(0).startsWith("wire"))
      .map(c => Tree(c(1).split('|')(0), c(2), c(4), c(5)))

  private def hex(bs: Array[Byte]): String = RentFixtures.hex(bs).toLowerCase

  // The bodies, built from their parts (the test checks each tree's body against these).
  private val S = AuthoredEvalNodeStaticType
  private val G = "07" + S.G
  private val Lambda = "d901010401" + "01"                 // { (i: Int) => true }
  private val FilterInt = "b50402" + Lambda                // Filter(1, { (i: Int) => true })
  private val True = "0101"
  private def exp(x: String) = S.nodeBytes("exp", x)
  private def mul(x: String) = S.nodeBytes("mul", x)
  /** Each root: its body, what it is, and the label that says what the verdict rests on. */
  val Roots: Map[String, (String, String, String)] = {
    val ctl = "CONTROL"
    val eqNode = (t: String) => s"STATIC TYPE: the node is GroupElement, so `==` compares two group elements and the " +
      s"tree is read; a port that types the node by its left operand sees `$t == GroupElement`, which the builder refuses."
    val plusNode = "STATIC TYPE: the node is GroupElement, so `+ 1` is arithmetic over a group element, which the " +
      "builder refuses; a port that types the node by its left operand sees `Int + Int` and reads the tree: the over-accept."
    Map(
      "true-prop" -> ("d1" + True, "`sigmaProp(true)`", s"$ctl: the carrier."),
      "filter.ctl" -> ("b5" + "1003020406" + Lambda, "`Coll(1, 2, 3).filter({ (i: Int) => true })`, a Coll[Int] root",
        s"$ctl: no proposition (rule 1001), read in full: rejected without the size flag, degraded with it."),
      "filter.int" -> (FilterInt, "`Filter` over the Int 1, the root",
        s"$ctl: Filter's type is a strict cast of its input's, which the root check reads: a ClassCastException, no " +
          "ValidationException, so the size flag does not degrade it."),
      "lambda.filter.int" -> ("d9010904" + "b57209" + Lambda, "a lambda `{ (x: Int) => x.filter(...) }`, the root",
        s"$ctl: the lambda's type reads its body's (a Filter over an Int): a ClassCastException at any form."),
      "block.filter.int" -> ("d801d6010402" + FilterInt, "a block `{ val v = 1; 1.filter(...) }`, the root",
        s"$ctl: the block's type reads its result's (a Filter over an Int): a ClassCastException at any form."),
      "block.val-filter.int" -> ("d801d601" + FilterInt + "d1" + True,
        "a block `{ val v = 1.filter(...); sigmaProp(true) }`",
        s"$ctl: a ValDef's type reads its right side's (a Filter over an Int): a ClassCastException at any form."),
      "if.filter.int" -> ("95" + True + FilterInt + FilterInt, "`if (true) 1.filter(...) else 1.filter(...)`, the root",
        s"$ctl: If's type reads its branch's (a Filter over an Int): a ClassCastException at any form."),
      "tuple.filter.int" -> ("8602" + FilterInt + "0402", "the tuple `(1.filter(...), 1)`, the root",
        s"$ctl: a Tuple's type reads its items' (a Filter over an Int): a ClassCastException at any form."),
      "prop.size-filter.int" -> ("d193b1" + FilterInt + "0400", "`sigmaProp(1.filter(...).size == 0)`",
        s"$ctl: SizeOf does not read its input's type, so the tree is read (it fails only when evaluated)."),
      "prop.untaken.size-filter.int" -> ("d1ed" + True + "93b1" + FilterInt + "0400",
        "`sigmaProp(true && 1.filter(...).size == 0)`", s"$ctl: as above, behind `&&`."),
      "exp.ctl" -> (exp("ctl"), "`Exponentiate(g, 2)`, the root",
        s"$ctl: no proposition (rule 1001), whatever the node's type: rejected without the size flag, degraded with it."),
      "exp.int" -> (exp("int"), "`Exponentiate(1, 2)`, the root",
        s"$ctl: no proposition (rule 1001) whether the node is typed GroupElement or Int: rejected without the size " +
          "flag, degraded with it."),
      "exp.anyge" -> (exp("anyge"), "`Exponentiate((g, true, 1L)(0), 2)`, the root",
        s"$ctl: no proposition (rule 1001) whether the node is typed GroupElement or Any: rejected without the size " +
          "flag, degraded with it."),
      "prop.dlog-exp.anyge" -> ("cd" + exp("anyge"), "`proveDlog(Exponentiate((g, true, 1L)(0), 2))`",
        s"$ctl: proveDlog checks nothing of its operand while the tree is read."),
      "prop.dlog-exp.int" -> ("cd" + exp("int"), "`proveDlog(Exponentiate(1, 2))`",
        s"$ctl: proveDlog checks nothing of its operand while the tree is read."),
      "prop.exp.anyge==g" -> ("d193" + exp("anyge") + G, "`sigmaProp(Exponentiate((g, true, 1L)(0), 2) == g)`",
        eqNode("Any")),
      "prop.exp.int==g" -> ("d193" + exp("int") + G, "`sigmaProp(Exponentiate(1, 2) == g)`", eqNode("Int")),
      "prop.exp.int+1==1" -> ("d1939a" + exp("int") + "0402" + "0402", "`sigmaProp(Exponentiate(1, 2) + 1 == 1)`",
        plusNode),
      "prop.untaken.exp.int==g" -> ("d1ec" + True + "93" + exp("int") + G, "`sigmaProp(true || Exponentiate(1, 2) == g)`",
        eqNode("Int") + " Untaken: a parse refusal, whatever the evaluation would do."),
      "prop.untaken.exp.int+1==1" -> ("d1ec" + True + "939a" + exp("int") + "0402" + "0402",
        "`sigmaProp(true || Exponentiate(1, 2) + 1 == 1)`", plusNode + " Untaken: refused while the tree is read."),
      "prop.mul.anyge==g" -> ("d193" + mul("anyge") + G, "`sigmaProp(MultiplyGroup((g, true, 1L)(0), g) == g)`",
        eqNode("Any")),
      "prop.untaken.mul.int+1==1" -> ("d1ec" + True + "939a" + mul("int") + "0402" + "0402",
        "`sigmaProp(true || MultiplyGroup(1, g) + 1 == 1)`", plusNode + " Untaken: refused while the tree is read."))
  }

  /** What a reject must say: the root check without the size flag, Filter's cast, or the builder's arithmetic check. */
  def rejectMentions(t: Tree): Seq[String] =
    if (t.root.contains("+1==1")) Seq("ConstraintFailed")
    else if (t.root.contains("filter.int")) Seq("java.lang.ClassCastException")
    else Seq(Rule1001, NoSizeBit)

  private def carrier(kind: String, t: Tree): Array[Byte] = {
    val cand = Value ++ b(t.bytes) ++ Fields
    if (kind == "Box") boxWith(cand) else txWith(cand)
  }

  def entryFor(kind: String, t: Tree, i: Int): Json = {
    val (_, what, label) = Roots(t.root)
    val subject = if (kind == "Box") "A box whose tree" else "A transaction with an output whose tree"
    val name = s"group-node-static-type-${t.slug}#$i"
    val bytes = carrier(kind, t)
    def desc(verdict: String) = s"[${t.name}] $subject (${t.formText}) is $what. $verdict $label"
    t.jars match {
      case "parsed" => accept(name, kind, desc("The JVM reads it and writes it back as read."), bytes, degrade = None)
      case "degraded (unparsed)" =>
        accept(name, kind, desc("The JVM degrades the tree (rule 1001: its root is no SigmaProp) and keeps its bytes."),
          bytes, degrade = Some(1001))
      case "rejected" =>
        val why = rejectMentions(t) match {
          case Seq("ConstraintFailed") => "the builder's arithmetic check fails (ConstraintFailed)"
          case Seq("java.lang.ClassCastException") => "Filter's type casts an Int to a collection (ClassCastException)"
          case _ => "rule 1001 (its root is no SigmaProp), which an unsized tree cannot degrade"
        }
        reject(name, kind, desc(s"The JVM REJECTS it: $why."), bytes, mention = rejectMentions(t))
    }
  }

  def extract(): Map[String, Json] = {
    val ts = trees()
    def env(op: String, kind: String) = op -> Json.obj(
      "schema"     -> Json.fromString("santa-wire/v1"),
      "op"         -> Json.fromString(op),
      "blessed_by" -> Json.fromString(Stamp),
      "entries"    -> Json.arr(ts.zipWithIndex.map { case (t, i) => entryFor(kind, t, i) }: _*))
    Map(env(OpBox, "Box"), env(OpTx, "Transaction"))
  }

  def writeVectors(outDir: java.nio.file.Path): Unit =
    SpecExtract.writeStaging("AuthoredWireNodeStaticType", extract(), outDir)
}
