package santa

import io.circe.parser.parse
import munit.FunSuite
import santa.runner.ChainEngine

/** Chain-tier engine tests (gated: SANTA_TX_BLESSER=1; cwd = jvm-blesser/, forked
  * test JVM — fixture paths are cwd-relative like the other gated suites).
  *
  * The first two tests are the FAIL-LOUD anchors (runner-contract-chain §6): the
  * fixtures embed REAL testnet history (docs/findings/chain-captures/), so the
  * engine's output is checked against what the chain actually did — a mismatch is
  * an engine bug, never the data.
  */
class ChainEngineTest extends FunSuite {

  private def load(p: String) =
    parse(scala.io.Source.fromFile(p).mkString).fold(e => sys.error(s"$e"), identity)

  test("retargeting entry reproduces the captured target nBits") {
    val entry = load("src/test/resources/chain-fixtures/retargeting-p1.entry.json")
    val (name, actual) = ChainEngine.chainEntry(entry)
    assertEquals(name, "retargeting-testnet-393601")
    // The real header 393601's nBits (target-p1.json): classic calculate over the 9
    // anchors → difficulty 17324703744 → encodeCompactBits = 84150434 (Task-1 findings).
    assertEquals(actual.hcursor.get[Long]("nbits").toOption, Some(84150434L))
    assert(actual.hcursor.downField("error").focus.exists(_.isNull))
  }

  test("voting entry reproduces the boundary block's real parameters") {
    val entry = load("src/test/resources/chain-fixtures/voting-2560.entry.json")
    val (_, actual) = ChainEngine.chainEntry(entry)
    // expected.parameters is Parameters.parseExtension(2560, real extension)'s table —
    // the full post-epoch table (identity epoch), deep-equality per contract §4.
    val want = entry.hcursor.downField("expected").downField("parameters").focus.get
    assertEquals(actual.hcursor.downField("parameters").focus, Some(want))
    // the empty update's canonical serializer hex — "0000", never "" (contract §2).
    assertEquals(actual.hcursor.get[String]("activated_update").toOption, Some("0000"))
    assert(actual.hcursor.downField("error").focus.exists(_.isNull))
  }

  test("malformed entry panics into the envelope, never throws") {
    val (_, actual) = ChainEngine.chainEntry(parse("""{"name":"x","kind":"voting"}""").toOption.get)
    assertEquals(actual.hcursor.get[String]("error").toOption, Some("panicked"))
    // contract §3: note is required with panicked (the caught-panic message).
    assert(actual.hcursor.get[String]("note").toOption.exists(_.nonEmpty))
  }

  test("voting actuals carry the union shape for nbits: absent or null, never a value") {
    // contract §3: the verdict shape is per-kind; the OTHER kind's value keys may be
    // present-and-null (union shape) but a voting actual never carries a non-null nbits.
    val entry = load("src/test/resources/chain-fixtures/voting-2560.entry.json")
    val (_, actual) = ChainEngine.chainEntry(entry)
    assert(actual.hcursor.downField("nbits").focus.forall(_.isNull))
  }
}
