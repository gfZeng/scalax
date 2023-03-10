package $

trait Logger(name: String = null) {
  val log = {
    val lname = if (name != null) name else s"${this.getClass.getSimpleName}"
    org.log4s.getLogger(lname)
  }
}

val Log = org.log4s.getLogger("")
