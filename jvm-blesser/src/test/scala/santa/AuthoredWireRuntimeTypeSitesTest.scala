package santa

/** Anchors the runtime-type wire family: `check` already pins each row's verdict and refusal reason; pinned here are
  * the counts per version and the asymmetry that makes the accepts separating: the same collection shape refused
  * for `Coll[T]` and read for `Coll[(Int, Int, T)]`, at every carrier and version. */
class AuthoredWireRuntimeTypeSitesTest extends munit.FunSuite {
  private val B = AuthoredWireRuntimeTypeSites

  test("every row's verdict and reason (check), and the counts per version") {
    B.Rows.foreach(B.check)
    assertEquals(B.Rows.count(r => r.kind == "ErgoTree" && !r.v6), 12)
    assertEquals(B.Rows.count(r => r.kind == "ErgoTree" && r.v6), 14)
    assertEquals(B.Rows.count(r => r.kind == "Box" && !r.v6), 6)
    assertEquals(B.Rows.count(r => r.kind == "Box" && r.v6), 2)
  }

  test("the asymmetry: Coll[T] refused, Coll[(Int, Int, T)] read, in every carrier") {
    Seq("CollT-empty-sized", "CollT-empty", "r4-CollT").foreach(i => assert(!B.Rows.find(_.id == i).get.accept, i))
    Seq("CollTripleT-empty-sized", "CollTripleT-empty", "seg-CollTripleT-empty", "r4-CollTripleT", "OptT-none")
      .foreach(i => assert(B.Rows.find(_.id == i).get.accept, i))
  }

  test("staging files") {
    val outDir = java.nio.file.Paths.get("target", "wire-authored", "runtime-type-sites")
    B.writeVectors(outDir)
    Seq("v5", "v6").foreach(d => Seq(B.OpErgoTree, B.OpBox).foreach(op =>
      assert(java.nio.file.Files.exists(outDir.resolve(d).resolve(s"$op.json")), s"$d/$op")))
  }
}
