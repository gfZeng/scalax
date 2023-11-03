package $

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.core.{JsonGenerator, JsonParser}
import com.fasterxml.jackson.core.json.JsonReadFeature
import com.fasterxml.jackson.databind.cfg.MapperBuilder
import com.fasterxml.jackson.databind.deser.{Deserializers, KeyDeserializers}
import com.fasterxml.jackson.databind.deser.std.StdDeserializer
import com.fasterxml.jackson.module.scala.{ClassTagExtensions, DefaultScalaModule, JavaTypeable}
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.databind.module.{SimpleDeserializers, SimpleModule}
import com.fasterxml.jackson.databind.{BeanDescription, DeserializationConfig, DeserializationContext, DeserializationFeature, JavaType, JsonDeserializer, JsonNode, JsonSerializer, KeyDeserializer, Module, ObjectMapper, SerializationConfig, SerializerProvider}
import com.fasterxml.jackson.databind.node.{NullNode, ObjectNode}
import com.fasterxml.jackson.databind.ser.Serializers

import java.io.{File, FileInputStream, InputStream, OutputStream}
import scala.annotation.nowarn
import scala.reflect.{ClassTag, classTag}
import scala.jdk.CollectionConverters.IteratorHasAsScala
import scala.reflect.Enum
import scala.util.Try

private object EnumSerializer extends JsonSerializer[Enum] {
  def serialize(value: Enum, jgen: JsonGenerator, provider: SerializerProvider): Unit =
    provider.defaultSerializeValue(value.toString, jgen)
}

private object EnumKeySerializer extends JsonSerializer[Enum] {
  def serialize(value: Enum, jgen: JsonGenerator, provider: SerializerProvider): Unit =
    jgen.writeFieldName(value.toString)
}

private object EnumSerializerResolver extends Serializers.Base {
  override def findSerializer(
      config:   SerializationConfig,
      javaType: JavaType,
      beanDesc: BeanDescription
  ): JsonSerializer[Enum] =
    if (classOf[Enum].isAssignableFrom(javaType.getRawClass)) EnumSerializer else null
}

private object EnumKeySerializerResolver extends Serializers.Base {
  override def findSerializer(
      config:   SerializationConfig,
      javaType: JavaType,
      beanDesc: BeanDescription
  ): JsonSerializer[Enum] =
    if (classOf[Enum] isAssignableFrom javaType.getRawClass) EnumKeySerializer else null
}

private case class EnumDeserializer[T <: Enum](clazz: Class[T]) extends StdDeserializer[T](clazz) {
  val method =
    clazz.getMethods.find(_.getAnnotation(classOf[JsonCreator]) != null)
      .getOrElse(clazz.getMethod("valueOf", classOf[String]))

  override def deserialize(p: JsonParser, ctxt: DeserializationContext): T = {
    method.invoke(null, p.getValueAsString).asInstanceOf[T]
  }
}

private case class EnumKeyDeserializer[T <: Enum](clazz: Class[T]) extends KeyDeserializer {
  val method =
    clazz.getMethods.find(_.getAnnotation(classOf[JsonCreator]) != null)
      .getOrElse(clazz.getMethod("valueOf", classOf[String]))

  override def deserializeKey(key: String, ctxt: DeserializationContext): AnyRef = {
    method.invoke(null, key)
  }
}

private object EnumDeserializerResolver extends Deserializers.Base {
  override def findBeanDeserializer(
      javaType: JavaType,
      config:   DeserializationConfig,
      beanDesc: BeanDescription
  ): JsonDeserializer[Enum] =
    if (classOf[Enum] isAssignableFrom javaType.getRawClass)
      EnumDeserializer(javaType.getRawClass.asInstanceOf[Class[Enum]])
    else None.orNull
}

private object EnumKeyDeserializerResolver extends KeyDeserializers {
  override def findKeyDeserializer(
      javaType: JavaType,
      config:   DeserializationConfig,
      beanDesc: BeanDescription
  ): KeyDeserializer =
    if (classOf[Enum] isAssignableFrom javaType.getRawClass)
      EnumKeyDeserializer(javaType.getRawClass.asInstanceOf[Class[Enum]])
    else None.orNull
}

object JSON {

  type Obj  = ObjectNode
  type Node = JsonNode
  final val Null = NullNode.instance

  val module = new SimpleModule() {
    this.addKeyDeserializer(classOf[BigDecimal], (key, _) => BigDecimal(key))

    override def setupModule(context: Module.SetupContext): Unit = {
      super.setupModule(context)
      context.addSerializers(EnumSerializerResolver)
      context.addKeySerializers(EnumKeySerializerResolver)
      context.addDeserializers(EnumDeserializerResolver)
      context.addKeyDeserializers(EnumKeyDeserializerResolver)
    }

  }

  @nowarn
  def init[M <: ObjectMapper, B <: MapperBuilder[M, B]](builder: MapperBuilder[M, B]): B = {
    builder
      .addModule(module)
      .addModule(DefaultScalaModule)
      .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
      .configure(JsonParser.Feature.ALLOW_NON_NUMERIC_NUMBERS, true)
      .configure(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS, true)

  }

  val mapper = JsonMapper
    .builder()
    .let(init _)
    .build() :: ClassTagExtensions

  export mapper.{readValue as read, readTree as parse, writeValue as write, writeValueAsString as str}

  def obj(kvs: (String, Any)*): Obj = {
    val node = mapper.createObjectNode()
    kvs.foreach { case (k, v) =>
      node.putPOJO(k, v)
    }
    node
  }

  trait To[T] {
    def apply(node: Node): T
    def apply(node: Node, defaultVal: => T): T
  }

  given Conversion[Node, ObjectNode] with {
    def apply(x: Node): ObjectNode = x.asInstanceOf[ObjectNode]
  }

  extension (json: Node) {

    inline def apply(k: String): Node = json.get(k) || Null

    inline def apply(i: Int): Node = json.get(i) || Null

    def iter(): Iterator[Node] = json.iterator().asScala

    def seq(): Seq[Node] = iter().toSeq

    inline def as[T](using to: To[T]): T = to(json)

    inline def as[T](defaultVal: => T)(using to: To[T]): T = to(json, defaultVal)
  }

  given To[String] with {

    def apply(node: Node): String = node.asText(null)

    def apply(node: Node, defaultVal: => String): String = node.asText(defaultVal)
  }

  given To[Long] with {
    def apply(node: Node): Long = node.asLong()

    def apply(node: Node, defaultVal: => Long): Long = node.asLong(defaultVal)
  }

  given To[Int] with {
    def apply(node: Node): Int = node.asInt()

    def apply(node: Node, defaultVal: => Int): Int = node.asInt(defaultVal)
  }

  given To[Boolean] with {
    def apply(node: Node): Boolean = node.asBoolean()

    def apply(node: Node, defaultVal: => Boolean): Boolean = node.asBoolean(defaultVal)
  }

  given To[Double] with {
    def apply(node: Node): Double = node.asDouble()

    def apply(node: Node, defaultVal: => Double): Double = node.asDouble(defaultVal)
  }

  given To[BigDecimal] with {

    def apply(node: Node): BigDecimal = apply(node, null)

    def apply(node: Node, defaultVal: => BigDecimal): BigDecimal =
      if (node == Null) defaultVal
      else if (node.isNumber) node.decimalValue()
      else {
        val s = node.asText()
        if (s.isBlank) defaultVal else BigDecimal(s)
      }

  }

  given [T: JavaTypeable]: To[T] with {

    def apply(node: Node): T = JSON.this.read[T](node.traverse)

    def apply(node: Node, defaultVal: => T): T =
      if (node == Null) defaultVal else JSON.this.read(node.traverse)

  }

  extension (obj: Obj) {

    inline def update(k: String, v: String) = obj.put(k, v)

    inline def update(k: String, v: BigDecimal) = obj.put(k, v.bigDecimal)

    inline def update(k: String, v: Boolean) = obj.put(k, v)

    inline def update(k: String, v: Int) = obj.put(k, v)

    inline def update(k: String, v: Long) = obj.put(k, v)

    inline def update(k: String, v: Double) = obj.put(k, v)

    inline def update(k: String, v: Node) = obj.set(k, v)

    inline def update(k: String, v: Any) = obj.putPOJO(k, v)

  }

}
