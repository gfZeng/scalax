package $


trait Logger(name: String = null) {
  val log = {
    val lname = if (name != null) name else s"${this.getClass.getSimpleName}"
    com.typesafe.scalalogging.Logger(lname)
  }
}

val Log = com.typesafe.scalalogging.Logger("")