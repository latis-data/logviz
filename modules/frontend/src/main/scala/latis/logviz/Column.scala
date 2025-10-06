package latis.logviz

import cats.Order

case class Column(number: Int, used: Boolean)

object Column{
  implicit val orderForColumn: Order[Column] = Order.fromLessThan((x, y) => x.number < y.number)
}