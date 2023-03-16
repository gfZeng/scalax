package $

import scala.reflect.{classTag, ClassTag}

import java.io.File
import com.typesafe.config.{Config, ConfigBeanFactory, ConfigFactory, ConfigRenderOptions}

object Hocon {

  export ConfigFactory.*

  def read[T: ClassTag](c: Config): T = {
    JSON.read[T](c.root().render(ConfigRenderOptions.concise()))
  }

  def read[T: ClassTag](f: File): T = read[T](parseFile(f))

  extension (c: Config) {
    def as[T: ClassTag] = read[T](c)
  }

}