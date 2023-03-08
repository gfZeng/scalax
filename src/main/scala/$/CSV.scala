package $

import com.fasterxml.jackson.core.{JsonGenerator, JsonParser}
import com.fasterxml.jackson.core.json.JsonReadFeature
import com.fasterxml.jackson.databind.{DeserializationFeature, ObjectWriter}
import com.fasterxml.jackson.dataformat.csv.{CsvMapper, CsvParser, CsvSchema}
import com.fasterxml.jackson.module.scala.DefaultScalaModule

import java.io.{File, FileInputStream, InputStream}
import scala.jdk.CollectionConverters.ListHasAsScala
import scala.reflect.{ClassTag, classTag}

class CSV {

  val mapper =
    CsvMapper.builder()
      .let(JSON.init _)
      .build()

  val schema: CsvSchema = mapper.schema().withHeader.withComments()

  def read[T: ClassTag](in: InputStream): Iterable[T] = {
    val clazz = classTag[T].runtimeClass
    mapper.readerFor(clazz).`with`(schema).readValues[T](in).readAll().asScala
  }

  def read[T: ClassTag](f: File): Iterable[T] = {
    read[T](new FileInputStream((f)))
  }

  def write[T: ClassTag](f: File, xs: Iterable[T]): Unit = {
    val clazz  = classTag[T].runtimeClass
    val schema = mapper.schemaFor(clazz).withHeader()
    mapper.writer(schema).writeValue(f, xs)
  }

  def writer(cols: String*): ObjectWriter = {
    val builder = CsvSchema.builder()
    cols.foreach(builder.addColumn(_))
    val schema = builder.build().withHeader()
    mapper.writer(schema).`with`(JsonGenerator.Feature.IGNORE_UNKNOWN)
  }

  def str[T: ClassTag](xs: Iterable[T]): String = {
    val clazz  = classTag[T].runtimeClass
    val schema = mapper.schemaFor(clazz).withHeader()
    mapper.writer(schema).writeValueAsString(xs)
  }

  def string(xs: Iterable[_]): String = {
    if (xs.isEmpty) {
      return ""
    }
    val clazz  = xs.head.getClass
    val schema = mapper.schemaFor(clazz).withHeader()
    mapper.writer(schema).writeValueAsString(xs)
  }

}

object CSV extends CSV {

  extension (w: ObjectWriter) {

    inline def write(f: File, xs: Iterable[Any]): Unit = {
      w.writeValue(f, xs)
    }

  }

}
