import java.nio.file.{FileSystems, Files, Paths}
import java.util
import java.net.{URL, URLDecoder, URLEncoder}
import java.lang.reflect.{Method, Modifier, Type}
import java.security.MessageDigest
import scala.collection.mutable
import scala.concurrent.{Future, Promise}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.reflect.ClassTag
import scala.math.BigDecimal.RoundingMode.*
import java.time.Instant
import java.util.{Base64, Timer, TimerTask}
import javax.crypto.spec.SecretKeySpec
import javax.crypto.Mac
import scala.annotation.{nowarn, tailrec}
import scala.collection.concurrent.TrieMap
import scala.collection.mutable.ListBuffer
import scala.util.{Failure, Random, Success}

package object $ {


  final val Zero: BigDecimal = 0
  final val One: BigDecimal = 1

  given Conversion[String, BigDecimal] with {
    override def apply(x: String): BigDecimal = BigDecimal(x)
  }

  extension (x: BigDecimal) {

    @nowarn
    def %%(unitOfMeasure: BigDecimal): BigDecimal = {
      val d = x.bigDecimal
      val n = unitOfMeasure.bigDecimal
      val mode = if (unitOfMeasure.signum > Zero) DOWN else UP
      d.divide(n, 0, mode.id)
    }

    def roundBy(unitOfMeasure: BigDecimal): BigDecimal = {
      if (unitOfMeasure == Zero) return x
      val mode = if (unitOfMeasure.signum > 0) DOWN else UP
      roundWith(unitOfMeasure, mode)
    }

    inline def ~(unit: BigDecimal): BigDecimal = roundBy(unit)

    inline def ~~(unit: BigDecimal): BigDecimal = roundWith(unit)

    @nowarn
    def roundWith(unitOfMeasure: BigDecimal, mode: RoundingMode = HALF_UP): BigDecimal = {
      val d = x.bigDecimal
      val n = unitOfMeasure.bigDecimal
      val q = d.divide(n, 0, mode.id)

      new BigDecimal(q.multiply(n), x.mc)
    }

    def trim = new BigDecimal(x.bigDecimal.stripTrailingZeros)
  }

  trait Memoize[K, V](m: mutable.Map[K, V] = null) {
    final val _VALUES = m || mutable.HashMap[K, V]()

    export _VALUES.{get, contains, remove, -=, values}

    def make(k: K) = null.asInstanceOf[V]

    def apply(k: K) = _VALUES.getOrElseUpdate(k, make(k))

    def update(k: K, v: V) = _VALUES(k) = v
  }


  @nowarn
  def construct[T](clsname: String, args: Array[String]): T = {
    val cls = Class.forName(clsname)
    if (args.isEmpty)
      cls.getDeclaredConstructor().newInstance().asInstanceOf[T]
    else {
      val ctors = cls.getDeclaredConstructors()
      ctors.foreach {ctor =>
        val typs = ctor.getGenericParameterTypes
        val argv = tryCoerce(typs, args)
        if (argv ne null) {
          return ctor.newInstance(argv: _*).asInstanceOf[T]
        }
      }
      throw NoSuchMethodError(s"No ctor for: $clsname(${args.mkString(",")})")
    }
  }


  class ObjectMemoize[T](packagePath: String = null) extends Memoize[String, T](TrieMap()) {

    val pkg =  packagePath || this.getClass.getPackageName

    @nowarn
    override def make(nameArg: String): T = {
      val s = nameArg.split("@", 2)
      val clsname = s"$pkg.${s(0)}"
      val args = if (s.size == 1) Array[String]() else s(1).split(",")
      construct[T](clsname, args)
    }

  }


  extension (x: Boolean) {

    inline infix def ||(y: => Nothing): Unit = if (!x) y

    inline infix def &&(y: => Nothing):  Unit = if (x) y
  }

  extension [T <: AnyRef](x: T) {

    inline infix def ||(y: => T): T = {
      if (x ne null) x else y
    }

    inline def ??[R](inline fn: T=>R): R = {
      if (x eq null) null.asInstanceOf[R] else fn(x)
    }
  }

  extension [T](x: T) {

    inline def let(fn: T => Any) = {fn(x); x}

  }


  def thread(cb: => Unit): Thread = {
    val th = new Thread(() => cb)
    th.start()
    th
  }

  def vthread(cb: => Unit): Thread = Thread.startVirtualThread(() => cb)



  inline def nowMs(): Long = System.currentTimeMillis()
  inline def now() = Instant.now()
  inline def nowSeconds(): Long = System.currentTimeMillis() / 1000L



  def timingNanos[T](key: String)(fn: => T) = {
    val startNs = System.nanoTime()
    val ret     = fn
    println(s"Elapsed time(ms): $key = ${(System.nanoTime() - startNs) / 1e6}")
    ret
  }

  def timingNanos[T](fn: => T): T = timingNanos(null) { fn }

  def timing[T](key: String)(fn: => T) = {
    val startMs = nowMs()
    val ret     = fn
    println(s"Elapsed time(ms): $key = ${nowMs() - startMs}")
    ret
  }

  def timing[T](fn: => T): T = timing(null) { fn }

  def timing[T](key: String)(fn: => Future[T]) = {
    val startMs = nowMs()
    fn.andThen { case _ =>
      println(s"Elapsed time(ms): $key = ${nowMs() - startMs}")
    }
  }

  def timing[T](fn: => Future[T]): Future[T] = timing(null) { fn }

  def clamp[T <: Ordered[T]: ClassTag](x: T, y: T, z: T): T = {
    Array(x, y, z).sortInPlace().apply(1)
  }

  private def getProp(s: String): String = {
    val p = System.getProperty(s)
    if (p eq null) {
      val k = s.replace('.', '_').toUpperCase
      System.getenv(k)
    } else p
  }

  private val  __HEXES = "0123456789ABCDEF";

  extension (bs: Array[Byte]) {
    def base64 = Base64.getEncoder.encodeToString(bs)

    def hex = {
      val sb = new StringBuilder(2 * bs.length)
      bs.foreach { b =>
        sb.append(__HEXES.charAt((b & 0xF0) >> 4)).append(__HEXES.charAt((b & 0x0F)));
      }
      sb.toString()
    }
  }


  trait Signer {
    def apply(s: String): Array[Byte]
  }
  object Signer {
    def md5(): Signer = s => $.md5(s)
    def hmac(algo: String, key: String): Signer = new Signer {
      val spec = new SecretKeySpec(key.getBytes(), algo)
      override def apply(s: String): Array[Byte] = {
        val m = Mac.getInstance(algo)
        m.init(spec)
        m.doFinal(s.getBytes())
      }
    }
  }

  extension (s: String) {

    def resource: URL = Thread.currentThread().getContextClassLoader.getResource(s)

    def prop: String = getProp(s)

    def prop(default: => String): String = {
      getProp(s) || default
    }

    inline def notEmpty = if ((s eq null) || s.isEmpty) null else s

    inline def unary_! = s.notEmpty

    inline def notBlank = if ((s eq null) || s.isBlank) null else s

    inline def unary_!! = s.notBlank

    def md5 = MessageDigest.getInstance("MD5").digest(s.getBytes)

    def hmac(algo: String, spec: String) = {
      val m = Mac.getInstance(algo)
      m.init(SecretKeySpec(spec.getBytes, algo))
      m.doFinal(s.getBytes)
    }

    @nowarn
    def urlencode = URLEncoder.encode(s)

    @nowarn
    def urldecode = URLDecoder.decode(s)

  }

  extension [E](xs: Iterable[E]) {

    def asMap[K](kf: E => K): Map[K, E] = {
      xs.map(v => (kf(v), v)).toMap
    }

    def xMap[K, V](kvf: E => (K, V)): Map[K, V] = {
      xs.map(kvf).toMap
    }
  }



  lazy val timer = new Timer()

  def schedule(delay: Long)(task: => Unit): Unit = {
    timer.schedule(
      new TimerTask {
        def run(): Unit = task
      },
      delay
    )
  }

  def schedule(delay: Long, period: Long)(task: => Unit): Unit = {
    timer.schedule(
      new TimerTask {
        def run(): Unit = task
      },
      delay,
      period
    )
  }

  def timeout[T](delay: Long)(fn: => T) = {
    val p = Promise[T]()
    schedule(delay) {
      p.success(fn)
    }
    p.future
  }

  inline def sleep(ms: Long = Long.MaxValue) = Thread.sleep(ms)


  @main def classpath(path: String) = {
    val classpath = System.getProperty("java.class.path")
    Files.write(Paths.get(path), util.List.of(classpath))
    println(s"write to $path: $classpath")
  }

  def coerce(typ: String, arg: String): Object = {
    typ match {
      case "java.lang.String"              => arg
      case "java.lang.Short" | "short"     => java.lang.Short.valueOf(arg)
      case "java.lang.Integer" | "int"     => java.lang.Integer.valueOf(arg)
      case "java.lang.Long" | "long"       => java.lang.Long.valueOf(arg)
      case "java.lang.Float" | "float"     => java.lang.Float.valueOf(arg)
      case "java.lang.Double" | "double"   => java.lang.Double.valueOf(arg)
      case "java.lang.Boolean" | "boolean" => java.lang.Boolean.valueOf(arg)
      case "java.match.BigDecimal"         => java.math.BigDecimal(arg)
      case _ =>
        val c = Class.forName(typ)
        if (classOf[scala.reflect.Enum].isAssignableFrom(c) || c.isEnum)
          c.getDeclaredMethod("valueOf", arg.getClass).invoke(null, arg)
        else
          throw IllegalArgumentException(s"unmatched $typ $arg")
    }
  }

  private def tryCoerce(typs: Array[Type], args: Seq[String], argv: Seq[Object] = Seq()): Seq[Object] = {
    if (typs.size > args.size) return null
    if (typs.size == 0)        return if (args.size == 0) argv else null
    val tpName = typs(0).getTypeName
    try {
      if (tpName.startsWith("scala.collection.immutable.Seq")) {
        val elTyp = tpName.replaceFirst("scala.collection.immutable.Seq<(.*)>", "$1")
        return argv :+ args.map(coerce(elTyp, _))
      }
      tryCoerce(typs.slice(1, typs.size), args.slice(1, args.size), argv :+ coerce(tpName, args(0)))
    } catch {
      case e => null
    }
  }

  @nowarn
  def invoke(pkg: String, method: String, args: Seq[String]): Any = {
    val pth       = pkg.replace('.', '/')
    val resources = Thread.currentThread().getContextClassLoader.getResources(pth)
    var cnames    = List[String]()
    resources.asIterator().forEachRemaining { url =>
      val uri = url.toURI
      val p =
        if (uri.getScheme == "jar") {
          FileSystems
            .newFileSystem(url.toURI, util.Collections.emptyMap[String, Object]())
            .getPath(pth)
        } else { Paths.get(uri) }

      Files.list(p)
           .map(_.getFileName.toString)
           .filter(_.endsWith("$package.class"))
           .forEach { c =>
             cnames ::= c.split('.')(0)
           }
    }

    cnames.foreach { cname =>
      val cls = Class.forName(s"$pkg.$cname")
      cls.getMethods.foreach { m =>
        if (m.getName == method) {
          val typs = m.getGenericParameterTypes
          val argv = tryCoerce(typs, args)
          if (argv ne null) {
            return m.invoke(null, argv: _*)
          }
        }
      }
    }

    throw new NoSuchMethodError(s"$pkg/$method(${args.mkString(", ")})")
  }

  @nowarn
  def invoke(method: String, args: Seq[String]): Any = {
    val parsed           = method.split("/")
    val clsArg = parsed(0).split("@")
    val clsname = clsArg(0)
    val ctorArg = if (clsArg.size == 1) Array[String]() else clsArg(1).split(",")
    val mname = if (parsed.size == 1) "main" else parsed(1)
    val cls =
      try { Class.forName(clsname) }
      catch {
        case e: ClassNotFoundException =>
          return invoke(parsed(0), mname, args)
        case e => throw e
      }

    cls.getMethods.sortInPlaceBy(m => !Modifier.isStatic(m.getModifiers)).foreach { m =>
      if (m.getName == mname) {
        val typs = m.getGenericParameterTypes
        val argv = tryCoerce(typs, args)
        if (argv ne null) {
          val obj =  if(Modifier.isStatic(m.getModifiers)) null else construct[Object](clsname, ctorArg)
          println(s"${obj || clsname} for $m")
          return m.invoke(obj, argv: _*)
        }
      }
    }

    throw new NoSuchMethodError(s"$method(${args.mkString(", ")})")
  }

  @main def launch(method: String, args: String*): Unit = {
    val ret = invoke(method, args)
    if ("return.print".prop("true").toBoolean) {
      ret match {
        case f: Future[_] =>
          f.onComplete {
            case Success(v) =>
              println(v)
              System.exit(0)
            case Failure(e) =>
              e.printStackTrace()
              System.exit(1)
          }
        case _ => println(ret)
      }
    }
  }

  def onExit(fn: => Unit) = {
    Runtime.getRuntime.addShutdownHook(new Thread(() => fn))
  }


  class BufferedFunction[T, R](timedTs: Long)(fn: Seq[T] => R) {

    val buf = ListBuffer[T]()

    def apply(x: T): Unit = apply(Seq(x))

    def apply(xs: Seq[T]): Unit = synchronized {
      if (buf.isEmpty) {
        schedule(timedTs) {
          val xs = synchronized {
            val xs = buf.toSeq
            buf.clear()
            xs
          }
          fn(xs)
        }
      }
      buf ++= xs
    }

  }


  extension (e: Throwable) {
    @tailrec
    def rootMsg: String = {
      val cause = e.getCause
      if (cause eq null) e.getMessage
      else if (cause eq e) e.getMessage
      else cause.rootMsg
    }
  }

  extension [T <: Comparable[T]](x: T)  {

    def min(y: T) = if (x eq null) y else if (y eq null) x else if ((x compareTo y) < 0) x else y

    def max(y: T) = if (x eq null) y else if (y eq null) x else if ((x compareTo y) > 0) x else y
  }


  export org.apache.commons.lang3.RandomStringUtils.{randomAlphanumeric as randomStr}


  extension [T](xs: Seq[T]) {
    def random = xs(Random.nextInt(xs.size))
  }

  extension [T](xs: Array[T]) {
    def random = xs(Random.nextInt(xs.size))
  }

  extension [T](n: Int) {
    def times(fn: => Unit): Unit = {
      (0 until n).foreach(_ => fn)
    }
  }


}
