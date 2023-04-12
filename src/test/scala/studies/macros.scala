
package studies

import scala.quoted.*


def prnImpl[T: Type](e: Expr[T])(using quotes: Quotes): Expr[Unit] = {
  import quotes.reflect.*
  report.info(e.asTerm.show(using Printer.TreeStructure))
  '{()}
}

inline def prn[T](inline x: T): Unit = ${
  prnImpl('x)
}




object loop {

  def |> = ()

  def loopImpl[T: Type](e: Expr[T])(using quotes: Quotes): Expr[Unit] = {
    import quotes.reflect.*
    given Printer[Tree] = Printer.TreeStructure
    report.info(e.asTerm.show)
    report.info("good")
    e.asTerm match {
      case Inlined(_, _, Block(xs, _)) =>
        xs.foreach {x =>
          report.info(x.show)
        }
    }
    '{
      ()
    }
  }
  inline def loop[T](inline e: T): Unit = ${ loopImpl('e) }
}




