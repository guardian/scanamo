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

package org.scanamo.ops

import cats._
import cats.syntax.either._
import com.amazonaws.AmazonWebServiceRequest
import com.amazonaws.handlers.AsyncHandler
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBAsync
import com.amazonaws.services.dynamodbv2.model.{ Put => _, Get => _, Delete => _, Update => _, _ }

import scala.concurrent.{ ExecutionContext, Future, Promise }
import scala.util.{ Failure, Success }

/*
 * Interpret Scanamo operations into a `Future` using the AmazonDynamoDBAsync client
 * which doesn't block, using it's own thread pool for I/O requests internally
 */
class ScanamoAsyncInterpreter(client: AmazonDynamoDBAsync)(implicit ec: ExecutionContext)
    extends (ScanamoOpsA ~> Future) {
  final private def futureOf[X <: AmazonWebServiceRequest, T](
    call: (X, AsyncHandler[X, T]) => java.util.concurrent.Future[T],
    req: X
  ): Future[T] = {
    val p = Promise[T]()
    val h = new AsyncHandler[X, T] {
      def onError(exception: Exception): Unit = { p.complete(Failure(exception)); () }
      def onSuccess(request: X, result: T): Unit = { p.complete(Success(result)); () }
    }
    call(req, h)
    p.future
  }

  override def apply[A](op: ScanamoOpsA[A]): Future[A] = op match {
    case Put(req) =>
      futureOf(client.putItemAsync, JavaRequests.put(req))
    case ConditionalPut(req) =>
      futureOf(client.putItemAsync, JavaRequests.put(req))
        .map(Either.right[ConditionalCheckFailedException, PutItemResult])
        .recover {
          case e: ConditionalCheckFailedException => Either.left(e)
        }
    case Get(req) =>
      futureOf(client.getItemAsync, req)
    case Delete(req) =>
      futureOf(client.deleteItemAsync, JavaRequests.delete(req))
    case ConditionalDelete(req) =>
      futureOf(client.deleteItemAsync, JavaRequests.delete(req))
        .map(Either.right[ConditionalCheckFailedException, DeleteItemResult])
        .recover { case e: ConditionalCheckFailedException => Either.left(e) }
    case Scan(req) =>
      futureOf(client.scanAsync, JavaRequests.scan(req))
    case Query(req) =>
      futureOf(client.queryAsync, JavaRequests.query(req))
    // Overloading means we need explicit parameter types here
    case BatchWrite(req) =>
      futureOf(
        client.batchWriteItemAsync(
          _: BatchWriteItemRequest,
          _: AsyncHandler[BatchWriteItemRequest, BatchWriteItemResult]
        ),
        req
      )
    case BatchGet(req) =>
      futureOf(
        client.batchGetItemAsync(_: BatchGetItemRequest, _: AsyncHandler[BatchGetItemRequest, BatchGetItemResult]),
        req
      )
    case Update(req) =>
      futureOf(client.updateItemAsync, JavaRequests.update(req))
    case ConditionalUpdate(req) =>
      futureOf(client.updateItemAsync, JavaRequests.update(req))
        .map(Either.right[ConditionalCheckFailedException, UpdateItemResult])
        .recover {
          case e: ConditionalCheckFailedException => Either.left(e)
        }
    case TransactWriteAll(req) => futureOf(client.transactWriteItemsAsync, JavaRequests.transactItems(req))
  }
}
