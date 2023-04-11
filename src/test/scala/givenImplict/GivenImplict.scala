package studies.givenImplict

trait GivenImplict {

  given String = "hello"

//  implicit val greet: String = "Hi"

  def giveGreetImpl(name: String)(using greet: String = summon[String]) =
    println(s"$greet, $name")

  def implicitlyGreetImpl(name: String)(implicit greet: String) =
    println(s"$greet, $name")

  def implicitlyGreet(name: String)(implicit greet: String): Unit = ???

  def giveGreet(name: String)(using String): Unit = ???

}
