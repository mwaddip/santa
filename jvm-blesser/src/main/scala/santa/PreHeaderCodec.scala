package santa

import scorex.util.encode.Base16

/** Codec for the walker full-context envelope's `pre_header_hex` sub-encoding — the
  * ergots ↔ SANTA shared wire contract (see prompts/walker-jvm-oracle-santa.md).
  *
  * `PreHeader` is a derived view of the spending block's header with **no consensus
  * standalone `sigmaSerialize`**, so ergots defined this sub-encoding by hand and SANTA
  * must mirror it byte-for-byte. Field order + widths:
  *
  *   version    1 raw byte
  *   parentId   32 raw bytes
  *   timestamp  VLQ u64 (unsigned LEB128 — timestamps exceed 2^53; the [2^63, 2^64)
  *              range carries as a negative signed Long and still round-trips)
  *   nBits      VLQ u32 (unsigned LEB128)
  *   height     VLQ u32 (unsigned LEB128)
  *   minerPk    33 raw bytes (SEC1)
  *   votes      3 raw bytes
  *
  * The VLQ is plain unsigned LEB128 (NOT sigma-state's `putLong` ZigZag). Implemented
  * directly rather than via `SigmaByteWriter` because this is ergots' encoding, not a
  * sigma-state serializer — the correctness criterion is byte-parity with the ergots
  * golden, machine-checked in PreHeaderCodecTest (incl. the >2^53 timestamp run).
  *
  * Round-trip-pinned: `decode(encode(f)) == f` and `encode(decode(hex)) == hex`. */
object PreHeaderCodec {

  /** Raw field view of a PreHeader — the wire shape, independent of sigma-state types.
    * The seam (EvalCore full-context path) maps this to a `CPreHeader` for eval. */
  final case class Fields(
      version: Byte,
      parentId: Array[Byte], // 32
      timestamp: Long, // u64 carrier (>= 2^63 surfaces as negative)
      nBits: Long, // u32 domain
      height: Int,
      minerPk: Array[Byte], // 33 (SEC1)
      votes: Array[Byte] // 3
  )

  // ── unsigned LEB128 (VLQ) ──────────────────────────────────────────────────
  // `>>> 7` is the logical (unsigned) shift, so a Long with the high bit set encodes
  // as its full u64 magnitude (10 bytes at most), matching ergots' bigint VLQ path.

  private def writeVlqU(out: scala.collection.mutable.ArrayBuffer[Byte], value: Long): Unit = {
    var v = value
    var done = false
    while (!done) {
      val low = (v & 0x7fL).toInt
      v = v >>> 7
      if (v == 0L) { out += low.toByte; done = true }
      else out += (low | 0x80).toByte
    }
  }

  /** Reads one unsigned-LEB128 value; returns (value, next offset). */
  private def readVlqU(bytes: Array[Byte], offset: Int): (Long, Int) = {
    var result = 0L
    var shift = 0
    var pos = offset
    var done = false
    while (!done) {
      require(pos < bytes.length, s"VLQ truncated at offset $pos")
      require(shift < 64, "VLQ overflows 64 bits")
      val b = bytes(pos) & 0xff
      pos += 1
      result |= (b & 0x7fL) << shift
      if ((b & 0x80) == 0) done = true else shift += 7
    }
    (result, pos)
  }

  def encode(f: Fields): Array[Byte] = {
    require(f.parentId.length == 32, s"parentId must be 32 bytes, got ${f.parentId.length}")
    require(f.minerPk.length == 33, s"minerPk must be 33 bytes, got ${f.minerPk.length}")
    require(f.votes.length == 3, s"votes must be 3 bytes, got ${f.votes.length}")
    val out = scala.collection.mutable.ArrayBuffer[Byte]()
    out += f.version
    out ++= f.parentId
    writeVlqU(out, f.timestamp)
    writeVlqU(out, f.nBits & 0xffffffffL)
    writeVlqU(out, f.height.toLong & 0xffffffffL)
    out ++= f.minerPk
    out ++= f.votes
    out.toArray
  }

  def encodeHex(f: Fields): String = Base16.encode(encode(f))

  def decode(bytes: Array[Byte]): Fields = {
    var pos = 0
    val version = bytes(pos); pos += 1
    val parentId = bytes.slice(pos, pos + 32); pos += 32
    require(parentId.length == 32, "parentId truncated")
    val (timestamp, p1) = readVlqU(bytes, pos); pos = p1
    val (nBits, p2) = readVlqU(bytes, pos); pos = p2
    val (height, p3) = readVlqU(bytes, pos); pos = p3
    val minerPk = bytes.slice(pos, pos + 33); pos += 33
    require(minerPk.length == 33, "minerPk truncated")
    val votes = bytes.slice(pos, pos + 3); pos += 3
    require(votes.length == 3, "votes truncated")
    Fields(version, parentId, timestamp, nBits, height.toInt, minerPk, votes)
  }

  def decodeHex(hex: String): Fields = decode(Base16.decode(hex).get)
}
