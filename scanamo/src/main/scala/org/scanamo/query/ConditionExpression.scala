/*
 * Copyright 2019 Scanamo
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.scanamo.query

import com.amazonaws.services.dynamodbv2.model.{
  AttributeValue,
  ConditionalCheckFailedException,
  DeleteItemResult,
  PutItemResult
}
import org.scanamo.{ ConditionNotMet, DeleteReturn, DynamoFormat, DynamoObject, PutReturn, ScanamoError }
import org.scanamo.ops.ScanamoOps
import org.scanamo.request.{ RequestCondition, ScanamoDeleteRequest, ScanamoPutRequest, ScanamoUpdateRequest }
import org.scanamo.update.UpdateExpression
import cats.instances.either._
import cats.instances.option._
import cats.syntax.either._
import cats.syntax.functor._

final case class ConditionalOperation[V, T](tableName: String, t: T)(
  implicit state: ConditionExpression[T],
  format: DynamoFormat[V]
) {
  def put(item: V): ScanamoOps[Either[ScanamoError, Unit]] =
    nativePut(PutReturn.Nothing, item).map(_.leftMap(ConditionNotMet(_)).void)

  def putAndReturn(ret: PutReturn)(item: V): ScanamoOps[Option[Either[ScanamoError, V]]] =
    nativePut(ret, item).map(decodeReturnValue[PutItemResult](_, _.getAttributes))

  private def nativePut(ret: PutReturn, item: V): ScanamoOps[Either[ConditionalCheckFailedException, PutItemResult]] =
    ScanamoOps.conditionalPut(ScanamoPutRequest(tableName, format.write(item), Some(state.apply(t)), ret))

  def delete(key: UniqueKey[_]): ScanamoOps[Either[ScanamoError, Unit]] =
    nativeDelete(DeleteReturn.Nothing, key).map(_.leftMap(ConditionNotMet(_)).void)

  def deleteAndReturn(ret: DeleteReturn)(key: UniqueKey[_]): ScanamoOps[Option[Either[ScanamoError, V]]] =
    nativeDelete(ret, key).map(decodeReturnValue[DeleteItemResult](_, _.getAttributes))

  private def nativeDelete(ret: DeleteReturn,
                           key: UniqueKey[_]): ScanamoOps[Either[ConditionalCheckFailedException, DeleteItemResult]] =
    ScanamoOps
      .conditionalDelete(
        ScanamoDeleteRequest(tableName = tableName, key = key.toDynamoObject, Some(state.apply(t)), ret)
      )

  private def decodeReturnValue[A](
    either: Either[ConditionalCheckFailedException, A],
    attrs: A => java.util.Map[String, AttributeValue]
  ): Option[Either[ScanamoError, V]] = {
    import cats.data.EitherT

    EitherT
      .fromEither[Option](either)
      .leftMap(ConditionNotMet(_))
      .flatMap(deleteItemResult =>
        EitherT[Option, ScanamoError, V](
          Option(attrs(deleteItemResult))
            .filterNot(_.isEmpty)
            .map(DynamoObject(_).toDynamoValue)
            .map(format.read)
        )
      )
      .value
  }

  def update(key: UniqueKey[_], update: UpdateExpression): ScanamoOps[Either[ScanamoError, V]] =
    ScanamoOps
      .conditionalUpdate(
        ScanamoUpdateRequest(
          tableName,
          key.toDynamoObject,
          update.expression,
          update.attributeNames,
          DynamoObject(update.dynamoValues),
          update.addEmptyList,
          Some(state.apply(t))
        )
      )
      .map(
        _.leftMap(ConditionNotMet(_))
          .flatMap(r => format.read(DynamoObject(r.getAttributes).toDynamoValue))
      )
}

trait ConditionExpression[T] {
  def apply(t: T): RequestCondition
}

object ConditionExpression {
  def apply[T](implicit C: ConditionExpression[T]): ConditionExpression[T] = C

  implicit def stringValueEqualsCondition[V: DynamoFormat] = new ConditionExpression[(String, V)] {
    override def apply(pair: (String, V)): RequestCondition =
      attributeValueEqualsCondition.apply((AttributeName.of(pair._1), pair._2))
  }

  implicit def attributeValueEqualsCondition[K <: AttributeName, V: DynamoFormat] = new ConditionExpression[(K, V)] {
    val prefix = "equalsCondition"
    override def apply(pair: (K, V)): RequestCondition = {
      val attributeName = pair._1
      RequestCondition(
        s"#${attributeName.placeholder(prefix)} = :conditionAttributeValue",
        attributeName.attributeNames(s"#$prefix"),
        Some(DynamoObject("conditionAttributeValue" -> pair._2))
      )
    }
  }

  implicit def stringValueInCondition[V: DynamoFormat] = new ConditionExpression[(String, Set[V])] {
    override def apply(pair: (String, Set[V])): RequestCondition =
      attributeValueInCondition.apply((AttributeName.of(pair._1), pair._2))
  }

  implicit def attributeValueInCondition[K <: AttributeName, V: DynamoFormat] =
    new ConditionExpression[(K, Set[V])] {
      val prefix = "inCondition"
      override def apply(pair: (K, Set[V])): RequestCondition = {
        val attributeName = pair._1
        val attributeValues = pair._2.zipWithIndex.foldLeft(DynamoObject.empty) {
          case (m, (v, i)) => m <> DynamoObject(s"conditionAttributeValue$i" -> v)
        }
        RequestCondition(
          s"""#${attributeName
            .placeholder(prefix)} IN ${attributeValues.mapKeys(':' + _).keys.mkString("(", ",", ")")}""",
          attributeName.attributeNames(s"#$prefix"),
          Some(attributeValues)
        )
      }
    }

  implicit def attributeExistsCondition = new ConditionExpression[AttributeExists] {
    val prefix = "attributeExists"
    override def apply(t: AttributeExists): RequestCondition =
      RequestCondition(s"attribute_exists(#${t.key.placeholder(prefix)})", t.key.attributeNames(s"#$prefix"), None)
  }

  implicit def attributeNotExistsCondition = new ConditionExpression[AttributeNotExists] {
    val prefix = "attributeNotExists"
    override def apply(t: AttributeNotExists): RequestCondition =
      RequestCondition(s"attribute_not_exists(#${t.key.placeholder(prefix)})", t.key.attributeNames(s"#$prefix"), None)
  }

  implicit def notCondition[T](implicit pcs: ConditionExpression[T]) = new ConditionExpression[Not[T]] {
    override def apply(not: Not[T]): RequestCondition = {
      val conditionToNegate = pcs(not.condition)
      conditionToNegate.copy(expression = s"NOT(${conditionToNegate.expression})")
    }
  }

  implicit def beginsWithCondition[V: DynamoFormat] = new ConditionExpression[BeginsWith[V]] {
    val prefix = "beginsWith"
    override def apply(b: BeginsWith[V]): RequestCondition =
      RequestCondition(
        s"begins_with(#${b.key.placeholder(prefix)}, :conditionAttributeValue)",
        b.key.attributeNames(s"#$prefix"),
        Some(DynamoObject("conditionAttributeValue" -> b.v))
      )
  }

  implicit def betweenCondition[V: DynamoFormat] = new ConditionExpression[Between[V]] {
    val prefix = "between"
    override def apply(b: Between[V]): RequestCondition =
      RequestCondition(
        s"#${b.key.placeholder(prefix)} BETWEEN :lower and :upper",
        b.key.attributeNames(s"#$prefix"),
        Some(
          DynamoObject(
            "lower" -> b.bounds.lowerBound.v,
            "upper" -> b.bounds.upperBound.v
          )
        )
      )
  }

  implicit def keyIsCondition[V: DynamoFormat] = new ConditionExpression[KeyIs[V]] {
    val prefix = "keyIs"
    override def apply(k: KeyIs[V]): RequestCondition =
      RequestCondition(
        s"#${k.key.placeholder(prefix)} ${k.operator.op} :conditionAttributeValue",
        k.key.attributeNames(s"#$prefix"),
        Some(DynamoObject("conditionAttributeValue" -> k.v))
      )
  }

  implicit def andCondition[L: ConditionExpression, R: ConditionExpression] =
    new ConditionExpression[AndCondition[L, R]] {
      override def apply(and: AndCondition[L, R]): RequestCondition =
        combineConditions(and.l, and.r, "AND")
    }

  implicit def orCondition[L: ConditionExpression, R: ConditionExpression] =
    new ConditionExpression[OrCondition[L, R]] {
      override def apply(and: OrCondition[L, R]): RequestCondition =
        combineConditions(and.l, and.r, "OR")
    }

  private def prefixKeys[T](map: Map[String, T], prefix: String, magicChar: Char): Map[String, T] = map.map {
    case (k, v) => (newKey(k, prefix, Some(magicChar)), v)
  }

  private def newKey(oldKey: String, prefix: String, magicChar: Option[Char]): String =
    magicChar.fold(s"$prefix$oldKey")(mc => s"$mc$prefix${oldKey.stripPrefix(mc.toString)}")

  private def prefixKeysIn(string: String, keys: Iterable[String], prefix: String, magicChar: Option[Char]): String =
    keys.foldLeft(string)((result, key) => result.replaceAllLiterally(key, newKey(key, prefix, magicChar)))

  private def combineConditions[L, R](l: L, r: R, combininingOperator: String)(
    implicit lce: ConditionExpression[L],
    rce: ConditionExpression[R]
  ): RequestCondition = {
    val lPrefix: String = s"${combininingOperator.toLowerCase}_l_"
    val rPrefix: String = s"${combininingOperator.toLowerCase}_r_"

    val lCondition: RequestCondition = lce(l)
    val rCondition: RequestCondition = rce(r)

    val mergedExpressionAttributeNames: Map[String, String] =
      prefixKeys(lCondition.attributeNames, lPrefix, '#') ++
        prefixKeys(rCondition.attributeNames, rPrefix, '#')

    val mergedExpressionAttributeValues =
      (lCondition.dynamoValues.map(_.mapKeys(lPrefix ++ _)) getOrElse DynamoObject.empty) <>
        (rCondition.dynamoValues.map(_.mapKeys(rPrefix ++ _)) getOrElse DynamoObject.empty)

    val lConditionExpression =
      prefixKeysIn(
        prefixKeysIn(lCondition.expression, lCondition.attributeNames.keys, lPrefix, Some('#')),
        lCondition.dynamoValues.toList.flatMap(_.keys),
        lPrefix,
        None
      )
    val rConditionExpression =
      prefixKeysIn(
        prefixKeysIn(rCondition.expression, rCondition.attributeNames.keys, rPrefix, Some('#')),
        rCondition.dynamoValues.toList.flatMap(_.keys),
        rPrefix,
        None
      )

    RequestCondition(
      s"($lConditionExpression $combininingOperator $rConditionExpression)",
      mergedExpressionAttributeNames,
      if (mergedExpressionAttributeValues.isEmpty) None else Some(mergedExpressionAttributeValues)
    )
  }
}

case class AndCondition[L: ConditionExpression, R: ConditionExpression](l: L, r: R)

case class OrCondition[L: ConditionExpression, R: ConditionExpression](l: L, r: R)

case class Condition[T](t: T)(implicit T: ConditionExpression[T]) {
  def apply = T.apply(t)
  def and[Y: ConditionExpression](other: Y) = AndCondition(t, other)
  def or[Y: ConditionExpression](other: Y) = OrCondition(t, other)
}

object Condition {
  implicit def conditionExpression[T]: ConditionExpression[Condition[T]] =
    new ConditionExpression[Condition[T]] {
      override def apply(condition: Condition[T]): RequestCondition = condition.apply
    }
}
