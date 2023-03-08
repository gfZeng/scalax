package $

import java.io.IOException
import java.net.{InetAddress, Socket}
import java.time.Duration
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory
import scala.concurrent.{Future, Promise}
import java.net.{InetSocketAddress, Proxy, URI}
import okhttp3.*

class HTTP(_client: OkHttpClient) {

  val client = _client || Builder.newClient(null, 16, 60 * 60 * 1000)

  def this(proxyUrl: String, poolSize: Int = 16, poolTimeoutMs: Long = 60 * 60 * 1000) =
    this(Builder.newClient(proxyUrl, poolSize, poolTimeoutMs))

  def request(req: HTTP.Request): Future[Response] = request(req.method, req.url, req.headers, req.body)

  def request(
      method:  String                                = "GET",
      url:     String,
      headers: Map[String, Any] | Seq[(String, Any)] = Map.empty,
      body:    Any                                   = null
  ): Future[Response] = {
    val builder = new Request.Builder()
    builder.url(url)
    var mdTyp: MediaType = null
    (headers || Map.empty).foreach { (k, _v) =>
      val v = _v.toString
      if (body != null && k.equalsIgnoreCase("content-type")) {
        mdTyp = MediaType.parse(v)
      }
      builder.header(k, v)
    }
    if (body == null)
      builder.method(method, null)
    else {
      val payload = body match {
        case x: String => x
        case _ =>
          if (mdTyp == null || mdTyp.subtype() == "json")
            JSON.str(body)
          else
            throw IllegalArgumentException(s"not supported body type ${body.getClass} for ${mdTyp.subtype()}")
      }
      builder.method(method, RequestBody.create(payload, mdTyp))
    }
    val req = builder.build()
    val pf  = Promise[Response]()
    client
      .newCall(req)
      .enqueue(
        new Callback {
          def onFailure(call: Call, e: IOException): Unit = {
            pf.failure(e)
          }

          def onResponse(call: Call, response: Response): Unit = {
            pf.success(response)
          }
        }
      )
    pf.future
  }

}

object Builder {

  def newClient(proxyUrl: String, poolSize: Int, poolTimeoutMs: Long) = {
    val b = new OkHttpClient.Builder()
      .connectionPool(new ConnectionPool(poolSize, poolTimeoutMs, TimeUnit.MILLISECONDS))
    val pingInterval = "http.pingInterval".prop("0").toLong
    if (pingInterval > 0) {
      b.pingInterval(Duration.ofMillis(pingInterval))
    }
    if (proxyUrl != null && proxyUrl.nonEmpty) {
      val uri    = URI.create(proxyUrl)
      val sa     = new InetSocketAddress(uri.getHost, uri.getPort)
      val scheme = uri.getScheme
      scheme match {
        case "http" | "https" =>
          b.proxy(Proxy(Proxy.Type.HTTP, sa))
        case "socks" | "socks5" | "socks5h" =>
          b.proxy(Proxy(Proxy.Type.SOCKS, sa))
        case "inet" =>
          b.socketFactory(new SocketFactory {
            private final val default = SocketFactory.getDefault

            override def createSocket(): Socket = {
              default.createSocket().let(_.bind(sa))
            }
            override def createSocket(s: String, i: Int): Socket = ???

            override def createSocket(s: String, i: Int, inetAddress: InetAddress, i1: Int): Socket = ???

            override def createSocket(inetAddress: InetAddress, i: Int): Socket = ???

            override def createSocket(
                inetAddress:  InetAddress,
                i:            Int,
                inetAddress1: InetAddress,
                i1:           Int
            ): Socket = ???
          })
        case _ =>
          throw IllegalArgumentException(s"unsupported proxy type $scheme")
      }

    }
    b.build()
  }

}

object HTTP extends HTTP(null) {

  object Headers {
    final val JSON = "Content-Type" -> "application/json"
  }

  type Response = okhttp3.Response

  case class Request(
      method:  String,
      url:     String,
      headers: Map[String, Any] | Seq[(String, Any)],
      body:    Any
  )
}
