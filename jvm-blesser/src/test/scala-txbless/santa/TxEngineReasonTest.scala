package santa

import santa.runner.TxEngine

/** TxEngine's reason is the exception's class and message, with each identity hash (`Object.toString`'s `@` and hex
  * hash code, as in `[B@4b023973`) replaced by `@<hash>`. The hash changes from run to run, so a blessed reason that
  * carried it would rewrite its vector on every bless. */
class TxEngineReasonTest extends munit.FunSuite {
  test("identity hashes become @<hash>, and the rest of the message is kept") {
    val e = new RuntimeException("Cannot deserialize type prefix 0. Unexpected buffer " +
      "sigma.serialization.SigmaByteReader@3ee4b252 with bytes [B@75789fc7")
    assertEquals(TxEngine.reasonOf(e), "java.lang.RuntimeException: Cannot deserialize type prefix 0. Unexpected " +
      "buffer sigma.serialization.SigmaByteReader@<hash> with bytes [B@<hash>")
  }

  test("an array of objects, and a hash followed by a comma") {
    assertEquals(TxEngine.reasonOf(new AssertionError("Invalid type of collection value in [Lsigma.ast.Value;@4f7d340e")),
      "java.lang.AssertionError: Invalid type of collection value in [Lsigma.ast.Value;@<hash>")
    assertEquals(TxEngine.reasonOf(new RuntimeException("ErgoTree(0,WrappedArray(),[B@4b023973,Some(false))")),
      "java.lang.RuntimeException: ErgoTree(0,WrappedArray(),[B@<hash>,Some(false))")
  }

  test("a message with no identity hash is kept as it is, and so is a null message") {
    val m = "Box size should not exceed 4096. 006075be1fac: #0 => Success((false,16)), user@example"
    assertEquals(TxEngine.reasonOf(new RuntimeException(m)), s"java.lang.RuntimeException: $m")
    assertEquals(TxEngine.reasonOf(new RuntimeException()), "java.lang.RuntimeException: null")
  }
}
