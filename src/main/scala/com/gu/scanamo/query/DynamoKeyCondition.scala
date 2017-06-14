package com.gu.scanamo.query

import com.gu.scanamo.DynamoFormat
import com.gu.scanamo.syntax.Bounds

case class KeyEquals[V: DynamoFormat](key: Symbol, v: V) {
  def and[R: DynamoFormat](equalsKeyCondition: KeyEquals[R]) =
    AndEqualsCondition(this, equalsKeyCondition)
  def and[R: DynamoFormat](rangeKeyCondition: RangeKeyCondition[R]) =
    AndQueryCondition(this, rangeKeyCondition)

  def descending = Descending(this)
}

case class AndEqualsCondition[H: UniqueKeyCondition, R: UniqueKeyCondition](
  hashEquality: H, rangeEquality: R
)

case class Descending[T: QueryableKeyCondition](queryCondition: T)

case class AndQueryCondition[H: DynamoFormat, R: DynamoFormat](
  hashCondition: KeyEquals[H], rangeCondition: RangeKeyCondition[R]
) {
  def descending = Descending(this)
}

sealed abstract class RangeKeyCondition[V](implicit f: DynamoFormat[V]) extends Product with Serializable {
  val key: Symbol
  def attributes: Map[String, V]
  def keyConditionExpression(s: String): String
}

sealed abstract class DynamoOperator(val op: String) extends Product with Serializable
final case object LT  extends DynamoOperator("<")
final case object LTE extends DynamoOperator("<=")
final case object GT  extends DynamoOperator(">")
final case object GTE extends DynamoOperator(">=")

final case class KeyIs[V: DynamoFormat](key: Symbol, operator: DynamoOperator, v: V) extends RangeKeyCondition[V]{
  override def keyConditionExpression(s: String): String = s"#$s ${operator.op} :${key.name}"
  override def attributes = Map(key.name -> v)
}

final case class BeginsWith[V: DynamoFormat](key: Symbol, v: V) extends RangeKeyCondition[V] {
  override def keyConditionExpression(s: String): String = s"begins_with(#$s, :${key.name})"
  override def attributes = Map(key.name -> v)
}

final case class Equals[V: DynamoFormat](key: Symbol, v: V) extends RangeKeyCondition[V] {
  override def keyConditionExpression(s: String): String = s"#$s = :${key.name}"
  override def attributes = Map(key.name -> v)
}

final case class Between[V: DynamoFormat](key: Symbol, bounds: Bounds[V]) extends RangeKeyCondition[V] {
  override def keyConditionExpression(s: String): String = s"#$s BETWEEN :lower AND :upper"
  override def attributes = Map(
    "lower" -> bounds.lowerBound.v,
    "upper" -> bounds.upperBound.v
  )
}

final case class AttributeExists(key: Symbol)
final case class AttributeNotExists(key: Symbol)
final case class Not[T: ConditionExpression](condition: T)
