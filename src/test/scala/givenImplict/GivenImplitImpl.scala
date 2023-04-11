package studies.givenImplict

class GivenImplitImpl extends GivenImplict {

  implicit val greet: String = "good"

  override def giveGreet(name: String)(using String): Unit = {
    giveGreetImpl(name)
  }

  override def implicitlyGreet(name: String)(implicit greet: String): Unit = {
    implicitlyGreetImpl(name)
  }

}
