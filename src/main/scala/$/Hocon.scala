package $

import scala.reflect.{classTag, ClassTag}

import java.io.File
import com.typesafe.config.{Config, ConfigBeanFactory, ConfigFactory, ConfigRenderOptions}
import com.fasterxml.jackson.module.scala.JavaTypeable

object Hocon {

  export ConfigFactory.*

  def read[T: JavaTypeable](c: Config): T = {
    JSON.read[T](c.root().render(ConfigRenderOptions.concise()))
  }

  def read[T: JavaTypeable](f: File): T = read[T](parseFile(f))

  extension (c: Config) {
    def as[T: JavaTypeable] = read[T](c)
  }

}