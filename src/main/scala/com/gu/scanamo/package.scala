package com.gu

import com.gu.scanamo.query._
import com.gu.scanamo.update._

package object scanamo {

  object syntax {
    implicit class SymbolKeyCondition(s: Symbol) {
      def <[V: DynamoFormat](v: V) = KeyIs(s, LT, v)
      def >[V: DynamoFormat](v: V) = KeyIs(s, GT, v)
      def <=[V: DynamoFormat](v: V) = KeyIs(s, LTE, v)
      def >=[V: DynamoFormat](v: V) = KeyIs(s, GTE, v)
      def beginsWith[V: DynamoFormat](v: V) = BeginsWith(s, v)
      def equals[V: DynamoFormat](v: V) = Equals(s, v)
      def between[V: DynamoFormat](bounds: Bounds[V]) = Between(s, bounds)
      def and(other: Symbol) =  HashAndRangeKeyNames(s, other)
    }

    case class HashAndRangeKeyNames(hash: Symbol, range: Symbol)

    implicit def symbolTupleToUniqueKey[V: DynamoFormat](pair: (Symbol, V)) =
      UniqueKey(KeyEquals(pair._1, pair._2))

    implicit def symbolTupleToKeyCondition[V: DynamoFormat](pair: (Symbol, V)) =
      KeyEquals(pair._1, pair._2)

    implicit def toUniqueKey[T: UniqueKeyCondition](t: T) = UniqueKey(t)

    implicit def symbolListTupleToUniqueKeys[V: DynamoFormat](pair: (Symbol, Set[V])) =
      UniqueKeys(KeyList(pair._1, pair._2))

    implicit def toMultipleKeyList[H: DynamoFormat, R: DynamoFormat](pair: (HashAndRangeKeyNames, Set[(H, R)])) =
      UniqueKeys(MultipleKeyList(pair._1.hash -> pair._1.range, pair._2))

    implicit def symbolTupleToQuery[V: DynamoFormat](pair: (Symbol, V)) =
      Query(KeyEquals(pair._1, pair._2))

    implicit def toQuery[T: QueryableKeyCondition](t: T) = Query(t)

    case class Bounds[V: DynamoFormat](lowerBound: Bound[V], upperBound: Bound[V])

    implicit class Bound[V: DynamoFormat](val v: V) {
      def and(upperBound: V) = Bounds(Bound(v), Bound(upperBound))
    }

    def attributeExists(symbol: Symbol) = AttributeExists(symbol)

    def attributeNotExists(symbol: Symbol) = AttributeNotExists(symbol)

    def not[T: ConditionExpression](t: T) = Not(t)

    implicit class AndConditionExpression[X: ConditionExpression](x: X) {
      def and[Y: ConditionExpression](y: Y) = AndCondition(x, y)
    }

    implicit class OrConditionExpression[X: ConditionExpression](x: X) {
      def or[Y: ConditionExpression](y: Y) = OrCondition(x, y)
    }

    def set(fields: (Field, Field)): UpdateExpression =
      UpdateExpression.setFromAttribute(fields)
    def set[V: DynamoFormat](fieldValue: (Field, V)): UpdateExpression =
      UpdateExpression.set(fieldValue)
    def append[V: DynamoFormat](fieldValue: (Field, V)): UpdateExpression =
      UpdateExpression.append(fieldValue)
    def prepend[V: DynamoFormat](fieldValue: (Field, V)): UpdateExpression =
      UpdateExpression.prepend(fieldValue)
    def add[V: DynamoFormat](fieldValue: (Field, V)): UpdateExpression =
      UpdateExpression.add(fieldValue)
    def delete[V: DynamoFormat](fieldValue: (Field, V)): UpdateExpression =
      UpdateExpression.delete(fieldValue)
    def remove(field: Field): UpdateExpression =
      UpdateExpression.remove(field)

    implicit def symbolField(s: Symbol): Field = Field.of(s)
    implicit def symbolFieldValue[T](sv: (Symbol, T)): (Field, T) = Field.of(sv._1) -> sv._2
    implicit def fieldField(ss: (Symbol, Symbol)): (Field, Field) = Field.of(ss._1) -> Field.of(ss._2)

    implicit class AndUpdateExpression(x: UpdateExpression) {
      def and(y: UpdateExpression): UpdateExpression = AndUpdate(x, y)
    }
  }
}
