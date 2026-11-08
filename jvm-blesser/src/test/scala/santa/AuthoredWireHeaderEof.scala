package santa

// Authored wire vectors — a size-flagged tree whose INPUT ENDS where a nested tree's header byte
// belongs. Request: `prompts/santa-probe-header-eof.md` (sigma-rust session, 2026-11-08).
//
// The question is which KIND of failure "ran out of input" is, because that decides whether the
// size bit can degrade it. `deserializeErgoTree` catches `ReaderPositionLimitExceeded` and
// re-raises it as a ValidationException (`ErgoTreeSerializer.scala:190-191`), and the outer catch
// degrades a size-flagged tree on a ValidationException ONLY (`:197`). Running off the end of the
// BYTE ARRAY is neither: measured, it is a raw `BufferUnderflowException` or an
// `ArrayIndexOutOfBoundsException`, so it escapes the degrade gate and the tree is REJECTED at
// every version. A nested tree's read is `deserializeErgoTree(r, MaxPropositionSize)`, which
// OVERWRITES positionLimit with `position + 4096` (`:141-143`) — so the enclosing tree's declared
// size never bounds it, and on a short input the limit lands past the buffer end.
//
// **TWO exception classes, and that is the point for an implementation with an allow-list.**
// Measured at 6.0.7:
//   `BufferUnderflowException`        when a `getByte()` runs off the end (the ByteBuffer path) —
//                                     the nested tree's HEADER byte, and the box VALUE
//   `ArrayIndexOutOfBoundsException`  when a VLQ / array read runs off the end — a body truncated
//                                     at an opcode boundary
// Both are fatal and neither is a ValidationException. So the correct gate is "anything that is
// NOT a ValidationException is hard", not an enumeration of IO error kinds — which is exactly the
// shape of the fork's bug (it maps the read error to `ErgoTreeHeaderError::IoError` and its gate
// does not list that as hard, so it keeps the tree as `Unparsed` and hands the bytes back).
//
// THIS FAMILY IS THE `ErgoTree` KIND ONLY, and that is forced rather than chosen: the fact under
// test is that the INPUT ends, and inside a Box or a Transaction there are always more bytes after
// the tree. `{Box,Transaction}.tree_read_window` and `*.sized_tree_declared_size` already cover a
// declared size that disagrees with a carrier that keeps supplying bytes; neither can express an
// input that simply stops.
//
// extract() re-derives every verdict through `WireCanonicalize` and asserts each reject's
// exception CLASS out of the cause chain.

import io.circe.Json

object AuthoredWireHeaderEof {
  val Op     = "ErgoTree.header_eof"
  val Source = "santa:authored-header-eof"
  private val Activated: Byte = 3

  /** A complete tree carrying a nested Box constant: `sigmaProp(box.value == 1000000L)` with the
    * box inline, size-flagged. The accept control — without it "a truncated input is rejected" is
    * trivial, and with it the pair says the thing that matters: the size bit rescues a
    * ValidationException at this very offset and does NOT rescue a truncation. Built by
    * `santa.spike.HeaderEofProbe` from `RentFixtures.box("santa:eof:nested", 1000000L, 1)`. */
  val CompleteV1: String = "0932d193c163c0843d0008d30100004e7166ab475f4200b93f5e9b4ea80a8ab0" +
    "322f488fd64f3e61a15765e74228f8000580897a"
  val CompleteV3: String = "0b" + CompleteV1.drop(2)

  private def version(treeVersion: Int): Json =
    Json.obj("activated" -> Json.fromInt(Activated.toInt), "ergoTree" -> Json.fromInt(treeVersion))

  private def entry(name: String, description: String, in: String, treeVersion: Int,
                    extra: (String, Json)*): Json =
    Json.obj(Seq(
      "name" -> Json.fromString(name), "kind" -> Json.fromString("ErgoTree"),
      "source" -> Json.fromString(Source), "description" -> Json.fromString(description),
      "bytes_hex" -> Json.fromString(in)) ++ extra ++ Seq("version" -> version(treeVersion)): _*)

  private def canonical(in: String, treeVersion: Byte): String =
    WireCanonicalize.canonicalize("ErgoTree", in, Activated, treeVersion)

  private def accept(name: String, description: String, in: String, treeVersion: Byte): Json = {
    val out = canonical(in, treeVersion)
    require(out == in, s"$name: the JVM must round-trip an accept vector to itself — in $in, out $out")
    entry(name, description, in, treeVersion.toInt)
  }

  /** A reject whose exception CLASS is asserted out of the cause chain. These exceptions carry no
    * message at all (`BufferUnderflowException`'s is null), so the class is the only thing there
    * is to match — which is itself worth pinning. */
  private def reject(name: String, description: String, in: String, treeVersion: Byte,
                     wantClass: String): Json = {
    scala.util.Try(canonical(in, treeVersion)) match {
      case scala.util.Success(out) => sys.error(s"$name: must be REFUSED, round-tripped to $out")
      case scala.util.Failure(t) =>
        val classes = Iterator.iterate(t: Throwable)(_.getCause).takeWhile(_ != null)
          .map(_.getClass.getName).mkString(" <- ")
        require(classes.contains(wantClass),
          s"$name: refused by the wrong exception (want $wantClass): $classes")
    }
    entry(name, description, in, treeVersion.toInt, "error" -> Json.fromString("errored"))
  }

  private val BUE   = "BufferUnderflowException"
  private val AIOOBE = "ArrayIndexOutOfBoundsException"

  def extract(): Map[String, Json] = {
    val entries = Seq(
      accept("header-eof-nested-box-complete-v1-accept#0",
        "CONTROL: a COMPLETE size-flagged v1 tree carrying a nested Box constant inline — " +
        "`sigmaProp(box.value == 1000000)`. It parses and round-trips verbatim, which is what makes " +
        "the rejects below say something: the shape is legal, and only the missing bytes are not. " +
        "Without this row the family would just assert that truncated input fails.",
        CompleteV1, 1.toByte),
      accept("header-eof-nested-box-complete-v3-accept#1",
        "CONTROL: the same complete tree size-flagged at v3. Pairs with the v1 row to show the " +
        "version plays no part in any of this — the JVM's answer is identical at both.",
        CompleteV3, Activated),
      reject("header-eof-ends-at-nested-tree-header-v1-reject#2",
        "The input ENDS exactly where the nested Box's own tree header byte belongs: `09` (v1, " +
        "size-flagged), declared size 2, body = a Box constant (`63`) whose value VLQ is `01`, and " +
        "then nothing. The nested read is `deserializeErgoTree(r, MaxPropositionSize)`, which " +
        "overwrites positionLimit with `position + 4096`, so the enclosing declared size does not " +
        "bound it and the `getByte()` for the nested header runs off the BUFFER — a " +
        "`BufferUnderflowException`, which is not a ValidationException, so the size bit cannot " +
        "degrade it. REJECTED. **THE SEPARATING ROW:** an implementation that maps this read error " +
        "to a soft IO-error kind keeps the tree as `Unparsed` and hands the bytes back, accepting " +
        "a tree the JVM refuses.",
        "09026301", 1.toByte, BUE),
      reject("header-eof-ends-at-nested-tree-header-v3-reject#3",
        "The same input at v3 (`0b`), size-flagged. REJECTED identically — the second half of the " +
        "pair that shows the degrade gate, not the version, is what decides this. **ALSO A " +
        "SEPARATING ROW.**",
        "0b026301", Activated, BUE),
      reject("header-eof-ends-at-box-value-v1-reject#4",
        "CONTROL for the reject side: the input ends one byte earlier, at the Box constant's VALUE " +
        "rather than at its tree header (declared size 1, body `63`). Also a " +
        "`BufferUnderflowException` and also REJECTED, and the probe reports that sigma-rust " +
        "already refuses this one — so a red here would mean something quite different from a red " +
        "on the two rows above, and a green here is not evidence that they pass.",
        "090163", 1.toByte, BUE),
      reject("header-eof-truncated-body-v1-reject#5",
        "The SECOND failure class. The complete v1 tree's body cut after `d193`, leaving the " +
        "declared size (50) describing bytes that are not there. The overrun now happens in a " +
        "VLQ / array read rather than a `getByte()`, so the JVM raises an " +
        "`ArrayIndexOutOfBoundsException` instead — a DIFFERENT class, equally fatal, equally not " +
        "a ValidationException. This row exists because the fix shape matters: the correct gate " +
        "is `anything that is not a ValidationException is hard`, not an enumeration of IO error " +
        "kinds, and an allow-list that learns `BufferUnderflowException` from the rows above would " +
        "still get this one wrong.",
        CompleteV1.take(8), 1.toByte, AIOOBE),
      reject("header-eof-truncated-body-v3-reject#6",
        "The same truncation at v3. REJECTED identically; the pair pins that neither failure class " +
        "degrades at either version.",
        CompleteV3.take(8), Activated, AIOOBE))

    Map(Op -> Json.obj(
      "schema"     -> Json.fromString("santa-wire/v1"),
      "op"         -> Json.fromString(Op),
      "blessed_by" -> Json.fromString("jvm:sigma-state-6.0.7"),
      "entries"    -> Json.arr(entries: _*)))
  }

  def writeVectors(outDir: java.nio.file.Path): Unit =
    SpecExtract.writeStaging("AuthoredWireHeaderEof", extract(), outDir)
}
