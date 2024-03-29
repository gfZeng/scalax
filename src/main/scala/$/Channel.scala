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

trait LoopTask extends Runnable {


  def thread: Thread

  def done(e: Throwable): Unit

  def continue: Unit

  def `do`(): Unit

  @volatile var active: Boolean = true

  def stop() = if (active) {
    active = false
    thread.interrupt()
  }

  def start(cleanOnExit:Boolean=true) = {
    if (cleanOnExit) {
      onExit {
        stop()
        if(thread ne null) thread.join()
      }
    }
    thread.start()
  }
}


trait NormalLoopTask(virtualThread:Boolean=false) extends LoopTask {

  val thread = if (virtualThread) Thread.ofVirtual().unstarted(this) else new Thread(this)

  def continue: Unit = throw IllegalStateException("NormalLoopTask does not supports continue")

  def run() = {
    try {
      while (active) {`do`()}
      done(null)
    } catch {
      case _: InterruptedException => done(null)
      case e => done(e)
    }
  }

}

trait AsyncLoopTask(virtualThread:Boolean=false) extends LoopTask {

  val thread = if (virtualThread) Thread.ofVirtual().unstarted(this) else new Thread(this)


  @volatile var waiting = false

  def continue = thread.synchronized {
    thread.notify()
    waiting = false
  }

  def run() = {
    try {
      while (active) {
        waiting = true
        `do`()
        if (this.waiting) thread.synchronized { if (this.waiting) thread.wait() }
      }
      done(null)
    } catch {
      case _: InterruptedException => done(null)
      case e => done(e)
    }
  }

}

trait CallbackLoopTask extends LoopTask {

  @volatile var _thread: Thread = new Thread(this)

  def thread = _thread

  def continue = run()

  override def stop(): Unit = {
    super.stop()
    _thread = null
    done(null)
  }

  def run() = {
    try {
      if (active) {
        _thread = Thread.currentThread()
        `do`()
      }
    } catch {
      case _: InterruptedException =>
      case e => done(e)
    }
  }

}


trait MessageHandler[T](
    val flushSize: Int  = Channel.MaxFlushSize,
    val timeoutMs: Long = Channel.MaxTimeoutMs ) extends Channel[T], LoopTask {

  def process(msg: T): Unit = process(Seq(msg))

  def process(msgs: Seq[T]): Unit = msgs.foreach(process)


  inline def `do`() = process(flush(flushSize, timeoutMs))

}
