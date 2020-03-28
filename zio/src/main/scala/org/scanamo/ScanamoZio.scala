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

import cats.{ ~>, Monad }
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBAsync
import com.amazonaws.services.dynamodbv2.model.AmazonDynamoDBException
import org.scanamo.ops._
import zio.IO
import zio.interop.catz._
import zio.stream.{ Stream, ZStream }

class ScanamoZio private (client: AmazonDynamoDBAsync) {
  final private val interpreter: ScanamoOpsA ~> IO[AmazonDynamoDBException, ?] =
    new ZioInterpreter(client)

  final def exec[A](op: ScanamoOps[A]): IO[AmazonDynamoDBException, A] = op.foldMap(interpreter)

  final def execT[M[_]: Monad, A](hoist: IO[AmazonDynamoDBException, ?] ~> M)(op: ScanamoOpsT[M, A]): M[A] =
    op.foldMap(interpreter andThen hoist)
}

object ScanamoZio {
  def apply(client: AmazonDynamoDBAsync): ScanamoZio = new ScanamoZio(client)

  val ToStream: IO[AmazonDynamoDBException, ?] ~> Stream[AmazonDynamoDBException, ?] =
    new (IO[AmazonDynamoDBException, ?] ~> Stream[AmazonDynamoDBException, ?]) {
      def apply[A](fa: IO[AmazonDynamoDBException, A]): Stream[AmazonDynamoDBException, A] = ZStream.fromEffect(fa)
    }
}
