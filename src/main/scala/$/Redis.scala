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
import org.redisson.api.RMapCache
import org.redisson.api.map.event.EntryCreatedListener
import org.redisson.api.map.event.EntryEvent
import org.redisson.api.map.event.EntryUpdatedListener
import org.redisson.api.map.event.EntryExpiredListener
import org.redisson.api.map.event.EntryRemovedListener
import java.util.Comparator
import org.redisson.api.StreamMessageId

type Redis = RedissonClient

object Redis {
  def create(url: String) = {
    val cfg = Config()
    val uri = URI.create(url)
    val addr = s"${uri.getScheme}:${uri.getSchemeSpecificPart}"
    val db = uri.getPath.substring(1).toInt
    val query = uri.getQuery || "&"
    val params = query.split("&").xMap { s =>
      val x = s.split("="); (x(0), x(1))
    }
    cfg
      .useSingleServer()
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

    override def getMapKeyDecoder: Decoder[Any] = (buf, _) => buf.toString(Charsets.UTF_8)

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

  given StreamMessageIdComparator: Comparator[StreamMessageId] = new Comparator[StreamMessageId] {
    def compare(o1: StreamMessageId, o2: StreamMessageId): Int = {
      val c1 = o1.getId0() compareTo o2.getId0()
      if (c1 == 0) o1.getId1() compareTo o2.getId1() else c1
    }
  }

  import java.util.Map.Entry
  type StreamMessage = Entry[StreamMessageId, java.util.Map[String, String]]
  given StreamMessageComparator: Comparator[StreamMessage] = new Comparator[StreamMessage] {
    def compare(o1: StreamMessage, o2: StreamMessage): Int = {
      StreamMessageIdComparator.compare(o1.getKey(), o2.getKey())
    }
  }

  given Ordering[StreamMessage] with {
    def compare(x: StreamMessage, y: StreamMessage): Int = StreamMessageComparator.compare(x, y)
  }

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
      tp.removeListener(ids*)
    }

    def listen(fn: Either[String, String] => Unit) = {
      tp.addListener (new {
        def onSubscribe(chnl: String): Unit = fn(Left(chnl))
        def onUnsubscribe(chnl: String): Unit = (fn(Right(chnl)))
      })
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
      r.topic[T](name).removeListener(ids*)
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

  import EntryEvent.Type

  export Type.*

  extension [K, V](cache: RMapCache[K, V]) {
    def listen(fn: EntryEvent[K, V] => Unit): Unit =
      listen(Type.values*)(fn)

    def listen(typs: Type*)(fn: EntryEvent[K, V] => Unit) = {
      typs.foreach {
        case CREATED =>
          cache.addListener(new EntryCreatedListener[K, V] {
            def onCreated(event: EntryEvent[K, V]): Unit = {
              fn(event)
            }
          })
        case UPDATED =>
          cache.addListener(new EntryUpdatedListener[K, V] {
            def onUpdated(event: EntryEvent[K, V]): Unit = {
              fn(event)
            }
          })
        case EXPIRED =>
          cache.addListener(new EntryExpiredListener[K, V] {
            def onExpired(event: EntryEvent[K, V]): Unit = {
              fn(event)
            }
          })
        case REMOVED =>
          cache.addListener(new EntryRemovedListener[K, V] {
            def onRemoved(event: EntryEvent[K, V]): Unit = {
              fn(event)
            }
          })
      }
    }
  }

  extension (rt: RTransaction) {
    def map[T: ClassTag](name: String) = rt.getMap[String, T](name, Redis.JsonCodec[String, T]())
    def bucket[T: ClassTag](name: String) = rt.getBucket[T](name, Redis.JsonCodec[String, T]())
  }

  extension (topic: RTopic) {

    infix def !!(x: Any) = topic.publish(x)

    infix def !(x: Any) = topic.publishAsync(x).whenComplete { (x, e) =>
      if (e != null) Log.error(s"publish on ${topic.getChannelNames}", e)
    }

  }
}
