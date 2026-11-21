package santa.runner

package object harness {

  /** `s` in double quotes, for messages (Scala 2.12 takes no `\"` inside an interpolated string). */
  def q(s: String): String = "\"" + s + "\""
}
