package santa

import scorex.util.bytesToId
import scorex.util.encode.Base16
import sigma.serialization.{GroupElementSerializer, SigmaSerializer}

/** The eval tiers build a pre-header as a node does. ergo-core's `PreHeader.toSigma` makes `parentId` and `votes`
  * GENERIC collections (through `sigma.Extensions.ArrayOps.toColl`), and a script can tell: a specialised collection's
  * `patch` throws on an empty patch of another array class, a generic one answers (`Coll.patch_array_class_context`, the
  * pre-header rows). Pinned against ergo-core itself, for the full context's pre-header (decoded from its bytes) and for
  * the dummy context's. */
class EvalCorePreHeaderClassTest extends munit.FunSuite {
  private def node(hex: String): sigma.PreHeader = {
    val f = PreHeaderCodec.decode(Base16.decode(hex).get)
    org.ergoplatform.modifiers.history.PreHeader.toSigma(org.ergoplatform.modifiers.history.CPreHeader(
      f.version, bytesToId(f.parentId), f.timestamp, f.nBits, f.height, f.votes,
      GroupElementSerializer.parse(SigmaSerializer.startReader(f.minerPk))))
  }

  test("a full context's pre-header: parentId and votes of the node's class and value") {
    Seq(2, 3).foreach { a =>
      val hex = AuthoredEvalSubstConstantsLift.preHeaderHex(a)
      val (ours, theirs) = (EvalCore.preHeaderFromHex(hex), node(hex))
      assertEquals(ours.parentId.getClass.getName, theirs.parentId.getClass.getName, s"activated $a")
      assertEquals(ours.votes.getClass.getName, theirs.votes.getClass.getName, s"activated $a")
      assertEquals(ours.parentId.toArray.toSeq, theirs.parentId.toArray.toSeq)
      assertEquals(ours.votes.toArray.toSeq, theirs.votes.toArray.toSeq)
      assert(!ours.parentId.getClass.getName.contains("$mc"), ours.parentId.getClass.getName)
    }
  }

  test("the dummy context's pre-header: the same classes") {
    val theirs = node(AuthoredEvalSubstConstantsLift.preHeaderHex(3))
    val ours = EvalCore.dummyPreHeader(1, 3)
    assertEquals(ours.parentId.getClass.getName, theirs.parentId.getClass.getName)
    assertEquals(ours.votes.getClass.getName, theirs.votes.getClass.getName)
  }
}
