package $

import scala.collection.mutable

object Channel {

  trait Buffer[T] {
    def flush(maxSize: Int): Seq[T]
    def isEmpty: Boolean
    def +=(x: T): Unit
  }

  class ListBuffer[T] extends Buffer[T] {
    private val buf = mutable.ListBuffer[T]()

    override def flush(maxSize: Int): Seq[T] = {
      if (maxSize > buf.size) {
        val xs = buf.toSeq
        buf.clear()
        return xs
      }
      val xs = buf.take(maxSize).toSeq
      buf.dropInPlace(maxSize)
      xs
    }

    override def isEmpty: Boolean = buf.isEmpty

    override def +=(x: T): Unit = buf += x
  }

  class EmptyBuffer[T] extends Buffer[T] {
    @volatile var ready = false

    def +=(x: T) = ready = true

    def isEmpty: Boolean = !ready

    def flush(maxSize: Int): Seq[T] = {
      ready = false
      Seq.empty
    }

  }

  final val MaxFlushSize = Int.MaxValue
  final val MaxTimeoutMs = Long.MaxValue >> 1

}

trait Channel[T](_buf: Channel.Buffer[T] = null) {

  private final val buf = _buf || new Channel.ListBuffer[T]

  def flush(maxSize: Int = Channel.MaxFlushSize, timeoutMs: Long = Channel.MaxTimeoutMs): Seq[T] =
    flushUntil(maxSize, nowMs() + timeoutMs)

  def flushUntil(maxSize: Int = Int.MaxValue, untilMs: Long = Long.MaxValue): Seq[T] = synchronized {
    while (buf.isEmpty) {
      val restTs = untilMs - nowMs()
      if (restTs <= 0) return Seq()
      wait(restTs)
    }
    return buf.flush(maxSize)
  }

  @deprecated
  def poll(waitMs: Long = Long.MaxValue): Seq[T] = synchronized {
    wait(waitMs)
    return buf.flush(Int.MaxValue)
  }

  def push(x: T) = synchronized {
    buf += x
    notify()
  }

  inline def +=(x: T) = push(x)

  def push(xs: Iterable[T]) = synchronized {
    xs.foreach(buf.+=)
    notify()
  }

  inline def +=(xs: Iterable[T]) = push(xs)

}

abstract class PushFirstChannel[T](buf: Channel.Buffer[T] = null) extends Channel[T](buf) {
  @volatile var pushing = false

  override def push(x: T): Unit = {
    pushing = true
    super.push(x)
    pushing = false
  }

  override def flushUntil(maxSize: Int, untilMs: Long): Seq[T] = {
    while (pushing) {
      Thread.`yield`()
    }
    super.flushUntil(maxSize, untilMs)
  }

}

trait MessageHandler[T](
    val flushSize: Int  = Channel.MaxFlushSize,
    val timeoutMs: Long = Channel.MaxTimeoutMs
) extends Channel[T] {
  // ok | stopping | stopped
  type Status = 0 | 1 | 2

  @volatile private var status:  Status = 0
  @volatile private var _thread: Thread = null
  @volatile var async = false

  inline def stop() = if (status == 0) {
    status = 1
    _thread.interrupt()
  }
  inline def active = status == 0

  def process(msg: T): Unit = process(Seq(msg))

  def process(msgs: Seq[T]): Unit = msgs.foreach(process)

  def done(e: Throwable): Unit = clean.done()

  private object clean {
    def apply(): Unit = {
      synchronized {
        status match {
          case 0 => stop(); wait()
          case 1 => wait()
          case 2 =>
        }
      }
    }
    def done(): Unit = synchronized {
      status = 2
      notifyAll()
    }
  }

  def continue = _thread.synchronized {
    _thread.notify()
    this.async = false
  }

  def start(cleanOnExit: Boolean = true, async: Boolean = false, virtualThread: Boolean=false) = {
    def run = {
      if (cleanOnExit) {
        onExit(clean())
      }
      try {
        while (active) {
          val msgs = flush(flushSize, timeoutMs)
          this.async = async
          process(msgs)
          if (this.async) _thread.synchronized { if (this.async) _thread.wait() }
        }
        done(null)
      } catch {
        case _: InterruptedException => done(null)
        case e => done(e)
      }
    }
    _thread = if (virtualThread) vthread(run) else thread(run)
  }

}
