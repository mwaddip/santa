package santa

// ─────────────────────────────────────────────────────────────────────────────
// Authored Coll-HOF per-element cost ladder (ergots Ask 14). The per-lambda-invocation
// ADD_TO_ENV charge lives in FuncValue.eval's closure (values.scala:1046-1049,
// AddToEnvironmentDesc, FixedCost(5)) — NOT in the HOFs — so every Coll HOF that calls
// its lambda charges it per element. flatMap and Option.map were already JVM-blessed;
// these pin the remaining five arms: map / filter / exists / forall / fold.
//
// TWO entries per arm (n=2 and n=4, same lambda) so the committed vectors pin the
// per-element SLOPE, not just one total — a wrong per-element model with a compensating
// constant matches one point but never two. Spike ladder (n=2/3/4, exactly linear):
//   map 100/127/154 (slope 27) · filter 110/142/174 (32) · exists 93/125/157 (32) ·
//   forall 93/125/157 (32) · fold 132/183/234 (51)
// Slopes differ per arm because the lambda BODIES differ (inc vs GT vs pair-sum);
// each includes the ADD_TO_ENV 5. Predicates traverse ALL n elements (exists' predicate
// is everywhere-false, forall's everywhere-true) — no short-circuit ambiguity.
// v5 surface, {activated 2, ergoTree 0}; spike-confirmed identical at activated 3.
// ─────────────────────────────────────────────────────────────────────────────

import io.circe.Json
import scorex.util.encode.Base16
import sigma.VersionContext
import sigma.ast.{ArithOp, ConcreteCollection, Exists, Filter, Fold, ForAll, FuncValue, GT,
  IntConstant, MapCollection, SInt, SPair, SType, SelectField, ValUse, Value}
import sigma.ast.ErgoTree.ZeroHeader

object AuthoredCollHofEnv {

  val Activated: Byte = 2
  val ErgoTreeV0: Int = 0
  val Source = "santa:authored-coll-hof-env"

  val Op = "Coll.hof_per_element_env"

  private val dummyInput: Json = Json.obj("kind" -> Json.fromString("Int"), "value" -> Json.fromInt(0))

  private def hexAtV0(root: Value[SType]): String =
    VersionContext.withVersions(0.toByte, 0.toByte) {
      Base16.encode(sigma.santa.LenientErgoTree.serialize(ZeroHeader, root))
    }

  private def coll(n: Int) = ConcreteCollection((1 to n).map(IntConstant(_)), SInt)
  private def incLambda = FuncValue(IndexedSeq(1 -> SInt), ArithOp(ValUse(1, SInt), IntConstant(1), ArithOp.Plus.opCode))
  private def gt0Lambda = FuncValue(IndexedSeq(1 -> SInt), GT(ValUse(1, SInt), IntConstant(0)))
  private def gt5Lambda = FuncValue(IndexedSeq(1 -> SInt), GT(ValUse(1, SInt), IntConstant(5)))
  private def sumLambda = {
    val pair = SPair(SInt, SInt)
    FuncValue(IndexedSeq(1 -> pair),
      ArithOp(SelectField(ValUse(1, pair), 1), SelectField(ValUse(1, pair), 2), ArithOp.Plus.opCode))
  }

  private def arm(kind: String, n: Int): Value[SType] = kind match {
    case "map"    => MapCollection(coll(n), incLambda)
    case "filter" => Filter(coll(n), gt0Lambda)
    case "exists" => Exists(coll(n), gt5Lambda)
    case "forall" => ForAll(coll(n), gt0Lambda)
    case "fold"   => Fold(coll(n), IntConstant(0), sumLambda)
    case other    => sys.error(s"unknown HOF arm $other")
  }

  def extract(): Map[String, Json] = {
    val scripts = Map(
      "map"    -> "Coll(1..n).map(x => x + 1)  // per-element: lambda body + ADD_TO_ENV(5); slope 27",
      "filter" -> "Coll(1..n).filter(x => x > 0)  // keeps all; slope 32",
      "exists" -> "Coll(1..n).exists(x => x > 5)  // everywhere-false: traverses all n; slope 32",
      "forall" -> "Coll(1..n).forall(x => x > 0)  // everywhere-true: traverses all n; slope 32",
      "fold"   -> "Coll(1..n).fold(0, (acc, x) => acc + x)  // pair-typed unary lambda; slope 51")
    val entries = for {
      kind <- Seq("map", "filter", "exists", "forall", "fold")
      n    <- Seq(2, 4)
    } yield SpecExtract.authoredEntryV(Op,
      s"{ ${scripts(kind)} } at n=$n",
      hexAtV0(arm(kind, n)), s"$kind-n$n#0", dummyInput, Activated, ErgoTreeV0)
    Map(Op -> SpecExtract.authoredEnvelope(Op, entries, Source))
  }

  def writeVectors(outDir: java.nio.file.Path): Unit =
    SpecExtract.writeStaging("AuthoredCollHofEnv", extract(), outDir)
}
