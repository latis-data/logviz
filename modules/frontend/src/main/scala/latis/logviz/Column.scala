package latis.logviz

import cats.Order

case class Column(number: Int, used: Boolean)

//*Columns used to assign to events. Multiple columns indicate events that happened at the same time*/
object Column{
  implicit val orderForColumn: Order[Column] = Order.fromLessThan((x, y) => x.number < y.number)
}