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

package org.scanamo

import cats.data.NonEmptyList
import cats.free.Free
import cats.free.FreeT
import cats.instances.option._
import cats.syntax.apply._
import com.amazonaws.services.dynamodbv2.model._
import org.scanamo.request._

package object ops {
  type ScanamoOps[A] = Free[ScanamoOpsA, A]
  type ScanamoOpsT[M[_], A] = FreeT[ScanamoOpsA, M, A]

  private[ops] object JavaRequests {
    import collection.JavaConverters._

    def scan(req: ScanamoScanRequest): ScanRequest = {
      def queryRefinement[T](
        o: ScanamoScanRequest => Option[T]
      )(rt: (ScanRequest, T) => ScanRequest): ScanRequest => ScanRequest = { qr => o(req).foldLeft(qr)(rt) }

      NonEmptyList
        .of(
          queryRefinement(_.index)(_.withIndexName(_)),
          queryRefinement(_.options.limit)(_.withLimit(_)),
          queryRefinement(_.options.exclusiveStartKey)((r, k) => r.withExclusiveStartKey(k.toJavaMap)),
          queryRefinement(_.options.filter) { (r, f) =>
            val requestCondition = f.apply
            requestCondition.dynamoValues
              .filter(_.nonEmpty)
              .flatMap(_.toExpressionAttributeValues)
              .foldLeft(
                r.withFilterExpression(requestCondition.expression)
                  .withExpressionAttributeNames(requestCondition.attributeNames.asJava)
              )(_ withExpressionAttributeValues _)
          }
        )
        .reduceLeft(_.compose(_))(
          new ScanRequest().withTableName(req.tableName).withConsistentRead(req.options.consistent)
        )
    }

    def query(req: ScanamoQueryRequest): QueryRequest = {
      def queryRefinement[T](
        f: ScanamoQueryRequest => Option[T]
      )(g: (QueryRequest, T) => QueryRequest): QueryRequest => QueryRequest = { qr => f(req).foldLeft(qr)(g) }

      val queryCondition: RequestCondition = req.query.apply
      val requestCondition: Option[RequestCondition] = req.options.filter.map(_.apply)

      val request = NonEmptyList
        .of(
          queryRefinement(_.index)(_ withIndexName _),
          queryRefinement(_.options.limit)(_ withLimit _),
          queryRefinement(_.options.exclusiveStartKey.map(_.toJavaMap))(_ withExclusiveStartKey _)
        )
        .reduceLeft(_ compose _)(
          new QueryRequest()
            .withTableName(req.tableName)
            .withConsistentRead(req.options.consistent)
            .withScanIndexForward(req.options.ascending)
            .withKeyConditionExpression(queryCondition.expression)
        )

      requestCondition.fold {
        val requestWithCondition = request.withExpressionAttributeNames(queryCondition.attributeNames.asJava)
        queryCondition.dynamoValues
          .filter(_.nonEmpty)
          .flatMap(_.toExpressionAttributeValues)
          .foldLeft(requestWithCondition)(_ withExpressionAttributeValues _)
      } { condition =>
        val requestWithCondition = request
          .withFilterExpression(condition.expression)
          .withExpressionAttributeNames((queryCondition.attributeNames ++ condition.attributeNames).asJava)
        val attributeValues =
          (
            queryCondition.dynamoValues orElse Some(DynamoObject.empty),
            condition.dynamoValues orElse Some(DynamoObject.empty)
          ).mapN(_ <> _)

        attributeValues
          .flatMap(_.toExpressionAttributeValues)
          .foldLeft(requestWithCondition)(_ withExpressionAttributeValues _)
      }
    }

    def put(req: ScanamoPutRequest): PutItemRequest = {
      val request = new PutItemRequest()
        .withTableName(req.tableName)
        .withItem(req.item.asObject.getOrElse(DynamoObject.empty).toJavaMap)
        .withReturnValues(req.ret.asDynamoValue)

      req.condition.fold(request) { condition =>
        val requestWithCondition = request
          .withConditionExpression(condition.expression)
          .withExpressionAttributeNames(condition.attributeNames.asJava)

        condition.dynamoValues
          .flatMap(_.toExpressionAttributeValues)
          .foldLeft(requestWithCondition)(_ withExpressionAttributeValues _)
      }
    }

    def delete(req: ScanamoDeleteRequest): DeleteItemRequest = {
      val request = new DeleteItemRequest().withTableName(req.tableName).withKey(req.key.toJavaMap)
      req.condition.fold(request) { condition =>
        val requestWithCondition = request
          .withConditionExpression(condition.expression)
          .withExpressionAttributeNames(condition.attributeNames.asJava)

        condition.dynamoValues
          .flatMap(_.toExpressionAttributeValues)
          .foldLeft(requestWithCondition)(_ withExpressionAttributeValues _)
      }
    }

    def update(req: ScanamoUpdateRequest): UpdateItemRequest = {
      val attributeNames: Map[String, String] = req.condition.map(_.attributeNames).foldLeft(req.attributeNames)(_ ++ _)
      val attributeValues: DynamoObject = req.condition.flatMap(_.dynamoValues).foldLeft(req.dynamoValues)(_ <> _)
      val request = new UpdateItemRequest()
        .withTableName(req.tableName)
        .withKey(req.key.toJavaMap)
        .withUpdateExpression(req.updateExpression)
        .withReturnValues(ReturnValue.ALL_NEW)
        .withExpressionAttributeNames(attributeNames.asJava)

      val requestWithCondition =
        req.condition.fold(request)(condition => request.withConditionExpression(condition.expression))

      attributeValues.toExpressionAttributeValues.fold(requestWithCondition) { avs =>
        if (req.addEmptyList) {
          avs.put(":emptyList", DynamoValue.EmptyList)
        }
        requestWithCondition withExpressionAttributeValues avs
      }
    }

    def transactItems(req: ScanamoTransactWriteRequest): TransactWriteItemsRequest = {
      val putItems = req.putItems.map { item ⇒
        new TransactWriteItem()
          .withPut(
            new com.amazonaws.services.dynamodbv2.model.Put()
              .withItem(item.item.asObject.getOrElse(DynamoObject.empty).toJavaMap)
              .withTableName(item.tableName)
          )
      }
      val updateItems = req.updateItems.map { item ⇒
        val update = new com.amazonaws.services.dynamodbv2.model.Update()
          .withTableName(item.tableName)
          .withUpdateExpression(item.updateExpression.expression)
          .withExpressionAttributeNames(item.updateExpression.attributeNames.asJava)
          .withKey(item.key.toJavaMap)
        val updateWithAvs = DynamoObject(item.updateExpression.dynamoValues).toExpressionAttributeValues.fold(update) {
          avs ⇒ update.withExpressionAttributeValues(avs)
        }
        new TransactWriteItem().withUpdate(updateWithAvs)
      }
      val deleteItems = req.deleteItems.map { item ⇒
        new TransactWriteItem()
          .withDelete(
            new com.amazonaws.services.dynamodbv2.model.Delete()
              .withKey(item.key.toJavaMap)
              .withTableName(item.tableName)
          )
      }
      new TransactWriteItemsRequest()
        .withTransactItems((putItems ++ updateItems ++ deleteItems).asJava)
    }
  }
}
