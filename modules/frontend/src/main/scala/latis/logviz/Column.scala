package latis.logviz

import cats.Order

case class Column(number: Int, used: Boolean)

//https://typelevel.org/cats-effect/docs/std/pqueue
//implicit val orderForInt: Order[Int] = Order.fromLessThan((x, y) => x < y)
// orderForInt: Order[Int] = cats.kernel.Order$$anon$9@4cc27019
object Column{
  implicit val orderForColumn: Order[Column] = Order.fromLessThan((x, y) => x.number < y.number)
}