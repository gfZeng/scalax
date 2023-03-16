package $

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}

extension [T](f: Future[T]) {
  def await(): T = Await.result(f, Duration.Inf)
}


