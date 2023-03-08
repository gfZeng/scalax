package $

import okhttp3.{OkHttpClient, Request, Response, WebSocket, WebSocketListener}

import java.io.{EOFException, IOException}
import scala.concurrent.Future
import okio.ByteString

trait WebSocket {

  self =>

  def url: String
  def headers: List[(String, String)] = List()
  def reconnect = true

  def client = HTTP.client

  import WebSocket._
  @volatile var transport: okhttp3.WebSocket = null
  @volatile var openFns = List[() => Unit]()

  def connectedUri = transport.request().url().toString.replaceFirst("^http", "ws")

  connect()

  object listener extends WebSocketListener {

    @volatile var transport: okhttp3.WebSocket = null

    def ready = transport eq self.transport

    override def onOpen(webSocket: okhttp3.WebSocket, response: Response): Unit = {
      super.onOpen(webSocket, response)
      self.synchronized {
        transport = webSocket
        if (ready) {
          openFns.foreach(_())
        }
      }
    }

    override def onClosed(webSocket: okhttp3.WebSocket, code: Int, reason: String): Unit = {
      super.onClosed(webSocket, code, reason)
      self.onClosed(code, reason)
    }

    override def onFailure(webSocket: okhttp3.WebSocket, t: Throwable, response: Response): Unit = {
      if (webSocket == self.transport) {
        transport = webSocket
      }
      super.onFailure(webSocket, t, response)
      self.onFailure(t)
    }

    override def onMessage(webSocket: okhttp3.WebSocket, text: String): Unit = {
      self.onMessage(text)
    }

    override def onMessage(webSocket: okhttp3.WebSocket, bytes: ByteString): Unit = {
      self.onMessage(bytes.toByteArray)
    }

  }

  protected def connect(): Unit = self.synchronized {
    if (!listener.ready) { return error("listener not ready") }
    val builder = new Request.Builder().url(url)
    headers.foreach { (k, v) => builder.header(k, v) }
    val req = builder.build()
    self.transport = client.newWebSocket(req, listener)
  }

  def error(msg: String): Unit = {
    System.err.println(s"${__clazzName} [ERROR] - $msg. $connectedUri")
  }

  val __clazzName = this.getClass.getName

  def onClosed(code: Int, reason: String): Unit = {
    println(s"closed($code), $reason - $connectedUri")
    if (reconnect && code != WebSocket.NormalCode) {
      connect()
    }
  }

  protected def onFailure(e: Throwable): Unit = {
    e.printStackTrace()
    error(e.toString)
    close(reason = s"${e.getMessage}")
    if (reconnect) { connect() }
  }

  def send(s: String) = transport.send(s)

  def send(bs: Array[Byte]) = transport.send(new ByteString(bs))

  def close(code: Int = WebSocket.NormalCode, reason: String = "") =
    if (listener.ready) transport.close(code, reason)

  def onOpen(f: => Unit) = self.synchronized {
    openFns :+= (() => f)
    if (listener.ready) { f }
  }

  def onMessage(text: String): Unit = onMessage(text.getBytes())

  def onMessage(bs: Array[Byte]): Unit = onMessage(String(bs))

}

object WebSocket {
  final val NormalCode = 1000
}
