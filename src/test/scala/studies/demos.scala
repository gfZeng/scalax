package studies

import com.fasterxml.jackson.annotation.{JsonAlias, JsonCreator, JsonIgnore, JsonIgnoreProperties, JsonProperty}
import $.*

import java.util.*
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{Failure, Success}
import scala.concurrent.Promise

object Demo {

  class Foo {
    def a: String = "Foo"
    def b(): Unit = {
      println(s"this is $a")
    }
  }

  class Koo(val f: Foo) {
    export f.b
    def a: String = "Koo"
  }

  def main(args: Array[String]): Unit = {
    Koo(Foo()).b()
  }

}
class Loop(i: Int) extends
      PushFirstChannel[String](Channel.ListBuffer[String]()),
      MessageHandler[String](timeoutMs = 3000),
      AsyncLoopTask(true) {
  override def process(msgs: Seq[String]): Unit = {
    println(s"$i receive messages ${msgs.mkString(",")}")
    Future {continue}
  }

  override def done(e: Throwable): Unit = {
    val fs = (0 until 32).map { i =>
      Future {
        sleep(300)
        throw Exception("good")
      }
    }
    Future.sequence(fs).onComplete {
      case _ =>
        println(s"$i stopped")
        fs.awake()
    }
    fs.sleep()
  }
}

object Loop {
  def main(args: Array[String]): Unit = {
    for (i <- 0 until 30) {
      val l = Loop(i)
      l.start()
      schedule(10000, 10000) {
        l.push("hello")
      }
    }

    Future {
      sleep(21000)
      throw Exception("try stop")
    } onComplete {
      case Success(x) =>
      case Failure(e) =>
        println(e)
        schedule(0) { System.exit(1) }
    }
  }

}

@main def scheduleTest() = {
  // this test show us the schedule run job sequences in one thread
  def launch(x: String): Unit = {
    schedule(0) {
      Thread.sleep(5000)
      println(s"${Date()} $x")
      launch(x)
    }
  }
  launch("hello")
  launch("world")
}

object GivenImplicitDeme {
//  implicit val greet: String = "Heh"
//  given String = "Heh2"

  import givenImplict.{GivenImplict, GivenImplitImpl}
  def main(args: Array[String]) = {

    val gi = GivenImplitImpl()
    gi.implicitlyGreet("Isaac")(using "abc")
  }
}

@main def proxyFetch(proxyUrl: String, url: String) = {
  val client = HTTP(proxyUrl)
  val rsp    = client.request(url = url).await()
  println(rsp)
  println(rsp.body().string())
  System.exit(0)
}


object JsonDemo {
  trait IFoo {
    var a: String = null
    var b: Long = _
  }

  enum A {
    case a1, a2
  }

  object  A {
  }

  case class Foo(c: String, @JsonAlias(Array("a")) z: String = null) extends IFoo {
    def setA(a: String) = {
      println("use set hahah")
      this.a = a
    }

  }
  import JSON.*
  object Foo {
    def parse2(a: String, b: Long) = {
      new Foo("c").let {f =>
        f.a = a
        f.b = b
      }
    }
    def parse(node: JSON.Node): Foo = {
      new Foo("c").let {f =>
        f.a = node("a").as[String]
        f.b = node("b").as[Long]
      }
    }
  }

  def main(args: Array[String]): Unit = {
    val f = JSON.read[Foo]("""{"a": 3, "b": "5"}""")
    println(s"after init ${f.a} ${f.b} ${f.z}")
  }
}

object ForDemo {
  def main(args: Array[String]): Unit = {
    val xs = for {
      i <- 0 until 10
      if i % 2 == 0
      x = i + 1
    } yield x

    println(xs)

  }
}

object ExtensionOverride {
  class Foo
  extension (f: Foo) {
    def apply(x: String) = "str"
    def len(x: String) = x.length
  }

  def main(args: Array[String]): Unit = {
    val f = new Foo

    val typ2 = f.apply("a") // ok
    val len = f.len("a") // ok else

    val typ = f("a") // just wrong inference on this invoke way
  }
}


object MultipleArgListTypeInfer {
  class Foo {

    def foo(): String = "foo"

    def foo()(cb: String => String): String = {
      cb(foo())
    }

    def koo(): Int = 3
    def koo()(x: Int): Int = koo() + x
  }

  def main(args: Array[String]): Unit = {
    val foo = new Foo

    foo.koo()(9) // can't jump to definition

    foo.foo() { a => a } // lose definition infer, so, what's type of `a`?

  }

}

object MessageHandlerDemo {
  inline def time = System.nanoTime()

  @volatile var totalTime = 0L
  @volatile var total = 0L
  @volatile var nHandle = 0L

  def reset() = {
    totalTime = 0L
    total = 0L
    nHandle = 0L
  }

  def waitFor(n: Long) = {
    assert(total <= n)
    while (total < n) {
      println(s"waiting ${total}")
      Thread.`yield`()
    }
  }

  def main(args: Array[String]): Unit = {
    val l = new PushFirstChannel[Long]() with MessageHandler[Long](timeoutMs = 1000L) with NormalLoopTask()  {
      override def done(e: Throwable): Unit = {}
      override def process(xs:Seq[Long]) = {
        nHandle += 1
        xs.foreach {x =>
          totalTime += time - x
          total += 1
        }
      }
    }

    l.start(false)



    thread {

      val preheat = 10000000L

      for (_ <- 0L until preheat) {
        l.push(time)
      }

      waitFor(preheat)
      println("wait done")

      reset()

      val n = 100000000L
      for(_ <- 0L until n) {
        l.push(time)
      }
      waitFor(n)
      val avg = totalTime.toDouble/total/1e6
      println(f"nHandle: $nHandle, total: $total, totalTime: $totalTime, avgMs: $avg%.3e")
    }

  }
}

object JsonDemo2 {

  case class Foo(name: String)

  object Foo extends Memoize[String, Foo]  {
    override def make(k: String): Foo = new Foo(k)
  }

  def main(args: Array[String]): Unit = {
    val f = JSON.read[Foo]("""{"name": "a"}""")
    println(f)
    println(Foo.values.toList)
  }
}


object FutureThreadVisableDemo {
  case class A(var x: Boolean) {
    def update(x: Boolean)  = 
      this.x = x
      x
  }

  val p = Promise[A]()

  def main(args: Array[String]): Unit = {
    val a = A(false)
    p.future.map {_ =>
      if (a.x) println(a) else println("nothing")
    }
    Future {
      val z = a() = true
      println(s"z $z")
      p.success(a)
    }
    Thread.sleep(1000)
  }
}
