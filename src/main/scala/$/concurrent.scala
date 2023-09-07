package $

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}
import java.util.concurrent.TimeUnit
import scala.concurrent.ExecutionContext

given ExecutionContext = ExecutionContext.Implicits.global

extension [T](f: Future[T]) {

  def get(): T = Await.result(f, Duration.Inf)

  def get(ts: Long) = Await.result(f, Duration(ts, TimeUnit.MILLISECONDS))

}


