package $

import scala.sys.process.*

object Demo extends App {
  ("echo hello" #&& "sleep 10" #&& "echo world").run()
  println("good")
}
