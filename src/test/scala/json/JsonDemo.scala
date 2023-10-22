package json

import $.*

object  JsonDemo {
    def main(args: Array[String]): Unit = {
        val f = JSON.read[Foo]("""{"x": 3}""")
        println(f)
        
    }

    case class Foo(var xs: Seq[Int] = Seq()) {
        def setX(x: Int) = {
            xs :+= x
        }
    }
}
