package santa

import scorex.util.encode.Base16

/** Byte-parity guard for the walker full-context PreHeader sub-encoding against the
  * shared ergots golden (prompts/walker-jvm-oracle-santa.md, 2026-06-14). The whole
  * full-context walk rides on byte agreement here — if this drifts, the ergots↔SANTA
  * wire contract is broken. The timestamp is deliberately > 2^53 to stress the
  * unsigned-LEB128 (VLQ-u64) path ergots flagged. */
class PreHeaderCodecTest extends munit.FunSuite {

  // The shared golden hex (ergots' `serializeFullContext` output for the fields below).
  private val goldenHex =
    "03" +                                                                 // version
      "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f" + // parentId
      "ffff83fea6dee111" +                                                 // timestamp VLQ-u64
      "ffff83e801" +                                                       // nBits VLQ-u32
      "87ad4b" +                                                           // height VLQ-u32
      "02aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" + // minerPk
      "010203"                                                             // votes

  private val golden = PreHeaderCodec.Fields(
    version = 3.toByte,
    parentId = (0 until 32).map(_.toByte).toArray,
    timestamp = 9999999999999999L, // > 2^53
    nBits = 486604799L, // 0x1d00ffff
    height = 1234567,
    minerPk = Array(0x02.toByte) ++ Array.fill(32)(0xaa.toByte),
    votes = Array(0x01, 0x02, 0x03).map(_.toByte)
  )

  test("encode reproduces the ergots golden pre_header_hex byte-for-byte") {
    assertEquals(PreHeaderCodec.encodeHex(golden), goldenHex)
  }

  test("decode of the golden recovers the field values") {
    val f = PreHeaderCodec.decodeHex(goldenHex)
    assertEquals(f.version, golden.version)
    assertEquals(Base16.encode(f.parentId), Base16.encode(golden.parentId))
    assertEquals(f.timestamp, golden.timestamp)
    assertEquals(f.nBits, golden.nBits)
    assertEquals(f.height, golden.height)
    assertEquals(Base16.encode(f.minerPk), Base16.encode(golden.minerPk))
    assertEquals(Base16.encode(f.votes), Base16.encode(golden.votes))
  }

  test("encode is the inverse of decode on the golden bytes") {
    assertEquals(PreHeaderCodec.encodeHex(PreHeaderCodec.decodeHex(goldenHex)), goldenHex)
  }

  // The high-half of the u64 carrier must round-trip — a timestamp in [2^63, 2^64)
  // arrives as a negative signed Long; the unsigned LEB128 must still reproduce it.
  test("u64-max timestamp round-trips through the VLQ-u64 path") {
    val f = golden.copy(timestamp = -1L) // 0xFFFFFFFFFFFFFFFF
    assertEquals(PreHeaderCodec.decode(PreHeaderCodec.encode(f)).timestamp, -1L)
  }
}
