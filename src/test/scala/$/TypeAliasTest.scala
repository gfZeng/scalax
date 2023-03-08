package $

import org.scalatest.*
import org.scalatest.matchers.*

type ExecInst = "ReduceOnly" | "ADL"

enum Color {
  case Red, Green
}

class TypeAliasTest extends funsuite.AnyFunSuite with should.Matchers with Logger() {

  test("") {
    val x: ExecInst = "ReduceOnly"
    log.info(s"${x.getClass}")
    println(s"${Color.Red.ordinal}, ${Color.Green.ordinal}")
  }

}
