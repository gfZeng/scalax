package $

import $.*
import kotlin.text.Charsets
import org.redisson.Redisson
import org.redisson.api.{RPatternTopic, RTopic, RTransaction, RedissonClient}
import org.redisson.client.codec.BaseCodec
import org.redisson.client.handler.State
import org.redisson.client.protocol.{Decoder, Encoder}
import org.redisson.config.Config

import java.io.OutputStream
import java.net.URI
import java.nio.charset.Charset
import io.netty.buffer.{ByteBuf, ByteBufInputStream, ByteBufOutputStream, Unpooled}

import scala.jdk.FutureConverters.*
import scala.reflect.{ClassTag, classTag}
import scala.util.Try

type Redis = RedissonClient

extension (tp: RTopic) {
  def subscribe[T: ClassTag](fn: (CharSequence, T) => Unit) = {
    val clazz = classTag[T].runtimeClass.asInstanceOf[Class[T]]
    tp.addListener(clazz, { (chnl, x: T) => fn(chnl, x) })
  }

  def subscribe[T: ClassTag](fn: T => Unit) = {
    val clazz = classTag[T].runtimeClass.asInstanceOf[Class[T]]
    tp.addListener(clazz, { (_, x: T) => fn(x) })
  }

  def unsubscribe(ids: Integer*) = {
    tp.removeListener(ids: _*)
  }
}

extension (tp: RPatternTopic) {
  def psubscribe[T: ClassTag](fn: T => Unit) = {
    val clazz = classTag[T].runtimeClass.asInstanceOf[Class[T]]
    tp.addListener(clazz, { (ptn, chnl, x: T) => fn(x) })
  }

  def psubscribe[T: ClassTag](fn: (CharSequence, CharSequence, T) => Unit) = {
    val clazz = classTag[T].runtimeClass.asInstanceOf[Class[T]]
    tp.addListener(clazz, { (ptn, chnl, x: T) => fn(ptn, chnl, x) })
  }
}


extension (r: Redis) {
  def ptopic[T: ClassTag](ptn: String) = {
    r.getPatternTopic(ptn, Redis.JsonCodec[String, T]())
  }

  def topic[T: ClassTag](name: String) = {
    r.getTopic(name, Redis.JsonCodec[String, T]())
  }

  def subscribe[T: ClassTag](name: String)(fn: T => Unit) = {
    val clazz = classTag[T].runtimeClass.asInstanceOf[Class[T]]
    r.topic[T](name).addListener(clazz, { (_, x: T) => fn(x) })
  }

  def subscribe[T: ClassTag](name: String)(fn: (CharSequence, T) => Unit) = {
    val clazz = classTag[T].runtimeClass.asInstanceOf[Class[T]]
    r.topic[T](name).addListener(clazz, { (chnl, x: T) => fn(chnl, x) })
  }

  def unsubscribe[T: ClassTag](name: String, ids: Integer*) = {
    val clazz = classTag[T].runtimeClass.asInstanceOf[Class[T]]
    r.topic[T](name).removeListener(ids: _*)
  }

  def psubscribe[T: ClassTag](name: String)(fn: T => Unit) = {
    val clazz = classTag[T].runtimeClass.asInstanceOf[Class[T]]
    r.ptopic[T](name).addListener(clazz, { (ptn, chnl, x: T) => fn(x) })
  }

  def psubscribe[T: ClassTag](name: String)(fn: (CharSequence, CharSequence, T) => Unit) = {
    val clazz = classTag[T].runtimeClass.asInstanceOf[Class[T]]
    r.ptopic[T](name).addListener(clazz, { (ptn, chnl, x: T) => fn(ptn, chnl, x) })
  }

  def map[T: ClassTag](name: String) = r.getMap[String, T](name, Redis.JsonCodec[String, T]())

  def cache[T: ClassTag](name: String) = r.getMapCache[String, T](name, Redis.JsonCodec[String, T]())

  def list[T: ClassTag](name: String) = r.getList[T](name, Redis.JsonCodec[String, T]())

  def sortedSet[T: ClassTag](name: String) = r.getSortedSet[T](name, Redis.JsonCodec[String, T]())

  def scoredSortedSet[T: ClassTag](name: String) = r.getScoredSortedSet[T](name, Redis.JsonCodec[String, T]())

  def bucket[T: ClassTag](name: String) = r.getBucket[T](name, Redis.JsonCodec[String, T]())

  def stream[V: ClassTag](name: String) = r.getStream[String, V](name, Redis.JsonCodec[String, V]())

}

extension (rt: RTransaction) {
  def map[T: ClassTag](name: String) = rt.getMap[String, T](name, Redis.JsonCodec[String, T]())
  def bucket[T: ClassTag](name: String) = rt.getBucket[T](name, Redis.JsonCodec[String, T]())
}

extension (topic: RTopic) {

  infix def !!(x: Any) = topic.publish(x)

  infix def !(x: Any) = topic.publishAsync(x).whenComplete { (x, e) =>
    if (e != null) Log.error(e)(s"publish on ${topic.getChannelNames}")
  }

}

object Redis {
  def create(url: String) = {
    val cfg   = Config()
    val uri   = URI.create(url)
    val addr  = s"${uri.getScheme}:${uri.getSchemeSpecificPart}"
    val db    = uri.getPath.substring(1).toInt
    val query = uri.getQuery || "&"
    val params = query.split("&").xMap { s =>
      val x = s.split("="); (x(0), x(1))
    }
    cfg.useSingleServer()
      .setAddress(addr)
      .setDatabase(db)
      .let { c =>
        params.foreach { (k, v) =>
          k match {
            case "poolSize" =>
              c.setConnectionPoolSize(v.toInt)
            case "subPoolSize" =>
              c.setSubscriptionConnectionPoolSize(v.toInt)
            case _ =>
              throw IllegalArgumentException(s"WARN  unsupported param $k=$v")
          }
        }
      }
    Redisson.create(cfg)
  }

  class JsonCodec[K: ClassTag, V: ClassTag](bufSize: Int = 64) extends BaseCodec {


    override def getMapKeyDecoder: Decoder[Any] = (buf, _) =>
      buf.toString(Charsets.UTF_8)

    override def getMapKeyEncoder: Encoder = in =>
      Unpooled.buffer(bufSize).let { buf =>
        ByteBufOutputStream(buf).writeBytes(in.toString)
      }

    override def getValueEncoder: Encoder =
      if (classTag[V].runtimeClass == classOf[String])
        in => {
          val buf = Unpooled.buffer(bufSize)
          buf.writeBytes(in.asInstanceOf[String].getBytes())
          buf
        }
      else
        in => {
          val buf = Unpooled.buffer(bufSize)
          val out: OutputStream = ByteBufOutputStream(buf)
          JSON.write(out, in)
          buf
        }
    override def getValueDecoder: Decoder[Any] =
      if (classTag[V].runtimeClass == classOf[String])
        (buf, _) => String(ByteBufInputStream(buf).readAllBytes())
      else
        (buf, _) => {
          JSON.read[V](ByteBufInputStream(buf))
        }
  }
}