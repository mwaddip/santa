package santa

import santa.blesser.Blesser

class OpNameTest extends munit.FunSuite {
  test("op derives from filename stem, hyphens to underscores") {
    assertEquals(Blesser.opFromPath("/x/y/sigma-or.json"), "sigma_or")
    assertEquals(Blesser.opFromPath("decode-point.json"), "decode_point")
  }
}
