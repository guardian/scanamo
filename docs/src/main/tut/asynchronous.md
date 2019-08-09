---
layout: docs
title: Asynchronous Requests
position: 7
---

## Asynchronous requests
 
Whilst for simplicity most examples in these documents are based on synchronous
requests to DynamoDB, Scanamo supports making the requests asynchronously with
a client that implements the `AmazonDynamoDBAsync` interface:

```tut:silent
import org.scanamo._
import org.scanamo.syntax._
import org.scanamo.auto._

import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global
 
val client = LocalDynamoDB.client()
val scanamo = ScanamoAsync(client)
import com.amazonaws.services.dynamodbv2.model.ScalarAttributeType._
LocalDynamoDB.createTable(client)("farm")("name" -> S)

case class Farm(animals: List[String])
case class Farmer(name: String, age: Long, farm: Farm)
val farmTable = Table[Farmer]("farm")
val ops = for {
  _ <- farmTable.putAll(Set(
    Farmer("Boggis", 43L, Farm(List("chicken"))),
    Farmer("Bunce", 52L, Farm(List("goose"))),
    Farmer("Bean", 55L, Farm(List("turkey")))
  ))
  bunce <- farmTable.get("name" -> "Bunce")
} yield bunce
```
```scala
concurrent.Await.result(scanamo(ops), 5.seconds)
```

Note that `AmazonDynamoDBAsyncClient` uses a thread pool internally.

## Asynchronous and Non-blocking requests
The `AmazonDynamoDBAsyncClient` is not *truly* asynchronous as it relies on 
Java Futures which block as soon as you try to access the value within them. 
Underneath the hood, they make use of a thread pool to perform a blocking call
when making the HTTP request to DynamoDB. There is a possibility that you may
not be able to reach your provisioned throughput because you have exhausted 
the thread pool to make HTTP requests. Until Amazon switches to a truly 
non-blocking implementation (backed by Netty), we have an Akka Streams based
interpreter which is a truly non-blocking HTTP client. Scanamo supports 
making non-blocking asynchronous HTTP requests with the Alpakka interpreter. 
Using the Alpakka client means you need an `ActorSystem` and an 
`ActorMaterializer` in order to make use of the streaming infrastructure
that the Alpakka interpreter uses behind the scenes:

```tut:silent
import org.scanamo._
import org.scanamo.syntax._
import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.alpakka.dynamodb.{ DynamoClient, DynamoSettings }
import com.amazonaws.services.dynamodbv2.model.ScalarAttributeType._
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain
import scala.concurrent.duration._

implicit val system = ActorSystem("scanamo-alpakka")
implicit val materializer = ActorMaterializer.create(system)
implicit val executor = system.dispatcher

val alpakkaClient = DynamoClient(
    DynamoSettings(region = "", host = "localhost")
      .withPort(8042)
      .withParallelism(2)
      .withCredentialsProvider(DefaultAWSCredentialsProviderChain.getInstance)
)

// Use the non-Alpakka client to create the table for tests
val client = LocalDynamoDB.client()

val scanamo = ScanamoAlpakka(alpakkaClient)

LocalDynamoDB.createTable(client)("nursery-farmers")("name" -> S)

case class Farm(animals: List[String])
case class Farmer(name: String, age: Long, farm: Farm)
val farmTable = Table[Farmer]("farm")
val ops = for {
  _ <- farmTable.putAll(Set(
    Farmer("Boggis", 43L, Farm(List("chicken"))),
    Farmer("Bunce", 52L, Farm(List("goose"))),
    Farmer("Bean", 55L, Farm(List("turkey")))
  ))
  bunce <- farmTable.get("name" -> "Bunce")
} yield bunce

// Use the Alpakka interpreter
scanamo.exec(ops)

system.terminate()
```

In order to use the Alpakka interpreter, you need to import the `scanamo-alpakka` library dependency
```sbt
val scanamoV = "<latest scanamo version>"
libraryDependencies += "com.gu" %% "scanamo-alpakka" % scanamoV
```
