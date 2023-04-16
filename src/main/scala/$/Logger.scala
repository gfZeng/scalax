package $


trait Logger(name: String = null) {
  val log = com.typesafe.scalalogging.Logger(name || this.getClass.getSimpleName)
}

val Log = com.typesafe.scalalogging.Logger("")