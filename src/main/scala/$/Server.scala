package $

import scala.concurrent.{ExecutionContext, ExecutionContextExecutor, Future}
import scala.reflect.{ClassTag, classTag}
import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.Behaviors
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.*
import akka.http.scaladsl.model.ContentTypes.*
import akka.http.scaladsl.model.headers.`Content-Type`
import akka.http.scaladsl.model.ws.{Message, TextMessage}
import akka.http.scaladsl.server.Directives.*
import akka.http.scaladsl.server.{Directive, Route}
import akka.http.scaladsl.unmarshalling.{FromRequestUnmarshaller, Unmarshaller}
import akka.stream.{BoundedSourceQueue, Materializer}
import akka.stream.scaladsl.{Flow, Sink, Source}
import com.fasterxml.jackson.module.scala.JavaTypeable

import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicInteger
import scala.collection.concurrent.TrieMap
import scala.util.{Failure, Success}

object Server {

  System.setProperty("akka.http.server.parsing.illegal-header-warnings", "off")

  implicit val system: ActorSystem[Nothing] = ActorSystem(Behaviors.empty, "engine-server")

  implicit val executionContext: ExecutionContextExecutor = system.executionContext

  val contentTypePattern = "json|stream|form|html|xml|csv|text".r

  val snull: String = null

  given FromRequestUnmarshaller[String] with {
    override def apply(req: HttpRequest)(implicit
        ec:                 ExecutionContext,
        materializer:       Materializer
    ): Future[String] =
      Unmarshaller.byteStringUnmarshaller(req.entity).map { bs =>
        bs.utf8String
      }
  }

  given [T: JavaTypeable]: FromRequestUnmarshaller[T] with {
    override def apply(req: HttpRequest)(implicit
        ec:                 ExecutionContext,
        materializer:       Materializer
    ): Future[T] = {
      Unmarshaller.byteStringUnmarshaller(req.entity).map { bs =>
        val typ = req.header[`Content-Type`].map(_.value()).getOrElse("json")
        contentTypePattern.findFirstIn(typ) match {
          case Some("json") | Some("stream") =>
            val in = new ByteArrayInputStream(bs.toArray)
            JSON.read[T](in)

          case Some("form") =>
            val m = bs.utf8String.split("&").xMap { itm =>
              val kv = itm.split("=")
              kv(0) -> urldecode(kv(1))
            }
            JSON.read[T](JSON.str(m))

          case _ =>
            throw IllegalArgumentException(s"unsupported Content-Type ${typ}")
        }
      }
    }
  }

  def ent[T](using FromRequestUnmarshaller[T]) = entity(as[T])

  def cookieValue(name: String) = optionalCookie(name).map(c => c.map(_.value))

  sealed trait Body
  case class Json(x: Any) extends Body
  case class Csv(x: Any) extends Body
  case class Html(x: String) extends Body
  case class Text(x: String) extends Body

  private def toEntity(body: Body) = body match {
    case Json(x) => HttpEntity(
        `application/json`,
        x match {
          case x: String    => x
          case x: JSON.Node => x.toString
          case x: Throwable => s"""{"error":"${x.getMessage()}"}"""
          case _ => JSON.str(x)
        }
      )
    case Csv(x) => HttpEntity(
        `text/csv(UTF-8)`,
        x match {
          case x: String        => x
          case x: Iterable[Any] => CSV.string(x)
          case _ =>
            throw IllegalArgumentException(s"Unsupports type: ${x.getClass}, String | Iterable supported")
        }
      )
    case Html(x) => HttpEntity(`text/html(UTF-8)`, x)
    case Text(x) => HttpEntity(`text/plain(UTF-8)`, x)
  }

  def respond(code: StatusCode, x: Body) = complete(code, toEntity(x))

  def respond(code: StatusCode, x: String) = complete(code, x)

  def respond(code: StatusCode, x: Any) = {
    headerValueByName("Accept") { accept =>
      if (accept.contains("*/*"))
        complete(code, x.toString)
      else
        contentTypePattern.findFirstIn(accept) match {
          case Some("json") => ok(Json(x))
          case Some("csv")  => ok(Csv(x))
          case Some("html") => ok(Html(x.toString))
          case Some("text") => ok(Text(x.toString))
          case _            => throw IllegalAccessException(s"Unsupported content type $accept")
        }
    }
  }

  def ok(x: Body) = complete(StatusCodes.OK, toEntity(x))

  def ok(x: Any): Route = respond(StatusCodes.OK, x)

  def ok() = complete(StatusCodes.NoContent, HttpEntity.Empty)

  given Conversion[Body, Route] with {
    override def apply(x: Body): Route = ok(x)
  }

  object websocket {

    val idGen = AtomicInteger(0)

    class Connection {
      val id       = idGen.incrementAndGet()
      val (q, src) = Source.queue[Message](100).preMaterialize()

      def send(msg: String): Boolean = q.offer(TextMessage(msg)).isEnqueued

      @volatile var onclose: () => Unit = () => ()

      def onclose(cb: => Unit): Unit = {
        onclose = () => cb
      }

      inline def send(msg: Any): Boolean = send(JSON.str(msg))

      def foreach(cb: String => Unit): Route = {
        /*
        val sink = Sink.foreach[Message] {
          case TextMessage.Strict(msg) =>
            cb(msg)
          case x =>
            Log.warn(s"not supported message type ${x.getClass}")
        }
         */
        val (q, sink) = Sink.queue[Message]().preMaterialize()
        def loop(): Unit = {
          q.pull().map {
            case Some(TextMessage.Strict(msg)) =>
              cb(msg)
              loop()
            case Some(x) =>
              Log.warn(s"not supported message type ${x.getClass}")
              loop()
            case None =>
              onclose()
          }.onComplete {
            case Success(_) =>
            case Failure(e) => e.printStackTrace()
          }
        }
        loop()
        handleWebSocketMessages(Flow.fromSinkAndSource(sink, src))
      }
    }

  }
}

trait Server {

  import Server.given

  def router: Route

  def listen(host: String = "0.0.0.0", port: Int) = {
    Log.info(s"listen at $host:$port")
    Http().newServerAt(host, port).bind(router)
  }

}
