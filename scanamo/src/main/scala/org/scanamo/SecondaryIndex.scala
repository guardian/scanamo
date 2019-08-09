package org.scanamo

import com.amazonaws.services.dynamodbv2.model.{ QueryResult, ScanResult }
import cats.{ Monad, MonoidK }
import org.scanamo.DynamoResultStream.{ QueryResultStream, ScanResultStream }
import org.scanamo.error.DynamoReadError
import org.scanamo.ops.{ ScanamoOps, ScanamoOpsT }
import org.scanamo.query.{ Condition, ConditionExpression, Query, UniqueKey, UniqueKeyCondition }
import org.scanamo.request.{ ScanamoQueryOptions, ScanamoQueryRequest, ScanamoScanRequest }

/**
  * Represents a secondary index on a DynamoDB table.
  *
  * Can be constructed via the [[org.scanamo.Table#index index]] method on [[org.scanamo.Table Table]]
  */
sealed abstract class SecondaryIndex[V] {

  /**
    * Scan a secondary index
    *
    *
    * This will only return items with a value present in the secondary index
    *
    * {{{
    * >>> case class Bear(name: String, favouriteFood: String, antagonist: Option[String])
    *
    * >>> val client = LocalDynamoDB.client()
    * >>> val scanamo = Scanamo(client)
    * >>> import com.amazonaws.services.dynamodbv2.model.ScalarAttributeType._
    *
    * >>> import org.scanamo.auto._
    *
    * >>> LocalDynamoDB.withRandomTableWithSecondaryIndex(client)("name" -> S)("antagonist" -> S) { (t, i) =>
    * ...   val table = Table[Bear](t)
    * ...   val ops = for {
    * ...     _ <- table.put(Bear("Pooh", "honey", None))
    * ...     _ <- table.put(Bear("Yogi", "picnic baskets", Some("Ranger Smith")))
    * ...     _ <- table.put(Bear("Paddington", "marmalade sandwiches", Some("Mr Curry")))
    * ...     antagonisticBears <- table.index(i).scan()
    * ...   } yield antagonisticBears
    * ...   scanamo.exec(ops)
    * ... }
    * List(Right(Bear(Paddington,marmalade sandwiches,Some(Mr Curry))), Right(Bear(Yogi,picnic baskets,Some(Ranger Smith))))
    * }}}
    */
  def scan(): ScanamoOps[List[Either[DynamoReadError, V]]]

  /**
    * Scan a secondary index and returns the raw DynamoDB result. Sometimes, one might want to access metadata returned
    * in the [[com.amazonaws.services.dynamodbv2.model.ScanResult]] object, such as the last evaluated key for example.
    * [[org.scanamo.SecondaryIndex#scan()]] only returns a list of results, so there is no place for putting that
    * information: this is where `scan0` comes in handy!
    *
    * {{{
    * >>> case class Bear(name: String, favouriteFood: String, antagonist: Option[String])
    *
    * >>> val client = LocalDynamoDB.client()
    * >>> val scanamo = Scanamo(client)
    * >>> import com.amazonaws.services.dynamodbv2.model.ScalarAttributeType._
    *
    * >>> import org.scanamo.auto._
    *
    * >>> LocalDynamoDB.withRandomTableWithSecondaryIndex(client)('name -> S)('antagonist -> S) { (t, i) =>
    * ...   val table = Table[Bear](t)
    * ...   val ops = for {
    * ...     _ <- table.put(Bear("Pooh", "honey", None))
    * ...     _ <- table.put(Bear("Yogi", "picnic baskets", Some("Ranger Smith")))
    * ...     _ <- table.put(Bear("Paddington", "marmalade sandwiches", Some("Mr Curry")))
    * ...     antagonisticBears <- table.index(i).scan0()
    * ...   } yield antagonisticBears
    * ...   scanamo.exec(ops)
    * ... }
    * }}}
    */
  def scan0(): ScanamoOps[ScanResult]

  /**
    * Performs a scan with the ability to introduce effects into the computation. This is
    * useful for huge tables when you don't want to load the whole of it in memory, but
    * scan it page by page.
    *
    * To control how many maximum items to load at once, use [[scanPaginatedM]]
    */

  final def scanM[M[_]: Monad: MonoidK]: ScanamoOpsT[M, List[Either[DynamoReadError, V]]] = scanPaginatedM(Int.MaxValue)

  /**
    * Performs a scan with the ability to introduce effects into the computation. This is
    * useful for huge tables when you don't want to load the whole of it in memory, but
    * scan it page by page, with a maximum of `pageSize` items per page..
    *
    * @note DynamoDB will only ever return maximum 1MB of data per scan, so `pageSize` is an
    * upper bound.
    */
  def scanPaginatedM[M[_]: Monad: MonoidK](pageSize: Int): ScanamoOpsT[M, List[Either[DynamoReadError, V]]]

  /**
    * Run a query against keys in a secondary index
    *
    * {{{
    * >>> case class GithubProject(organisation: String, repository: String, language: String, license: String)
    *
    * >>> val client = LocalDynamoDB.client()
    * >>> val scanamo = Scanamo(client)
    * >>> import com.amazonaws.services.dynamodbv2.model.ScalarAttributeType._
    *
    * >>> import org.scanamo.syntax._
    * >>> import org.scanamo.auto._
    *
    * >>> LocalDynamoDB.withRandomTableWithSecondaryIndex(client)("organisation" -> S, "repository" -> S)("language" -> S, "license" -> S) { (t, i) =>
    * ...   val githubProjects = Table[GithubProject](t)
    * ...   val operations = for {
    * ...     _ <- githubProjects.putAll(Set(
    * ...       GithubProject("typelevel", "cats", "Scala", "MIT"),
    * ...       GithubProject("localytics", "sbt-dynamodb", "Scala", "MIT"),
    * ...       GithubProject("tpolecat", "tut", "Scala", "MIT"),
    * ...       GithubProject("guardian", "scanamo", "Scala", "Apache 2")
    * ...     ))
    * ...     scalaMIT <- githubProjects.index(i).query("language" -> "Scala" and ("license" -> "MIT"))
    * ...   } yield scalaMIT.toList
    * ...   scanamo.exec(operations)
    * ... }
    * List(Right(GithubProject(typelevel,cats,Scala,MIT)), Right(GithubProject(tpolecat,tut,Scala,MIT)), Right(GithubProject(localytics,sbt-dynamodb,Scala,MIT)))
    * }}}
    */
  def query(query: Query[_]): ScanamoOps[List[Either[DynamoReadError, V]]]

  /**
    * Run a query against keys in a secondary index. Sometimes, one might want to access metadata returned in the
    * [[com.amazonaws.services.dynamodbv2.model.QueryResult]] object, such as the last evaluated key for example.
    * [[org.scanamo.SecondaryIndex#query(org.scanamo.query.Query)]] only returns a list of results, so there is no
    * place for putting that information: this is where `query0` comes in handy!
    *
    * {{{
    * >>> case class GithubProject(organisation: String, repository: String, language: String, license: String)
    *
    * >>> val client = LocalDynamoDB.client()
    * >>> val scanamo = Scanamo(client)
    * >>> import com.amazonaws.services.dynamodbv2.model.ScalarAttributeType._
    *
    * >>> import org.scanamo.syntax._
    * >>> import org.scanamo.auto._
    *
    * >>> LocalDynamoDB.withRandomTableWithSecondaryIndex(client)('organisation -> S, 'repository -> S)('language -> S, 'license -> S) { (t, i) =>
    * ...   val githubProjects = Table[GithubProject](t)
    * ...   val operations = for {
    * ...     _ <- githubProjects.putAll(Set(
    * ...       GithubProject("typelevel", "cats", "Scala", "MIT"),
    * ...       GithubProject("localytics", "sbt-dynamodb", "Scala", "MIT"),
    * ...       GithubProject("tpolecat", "tut", "Scala", "MIT"),
    * ...       GithubProject("guardian", "scanamo", "Scala", "Apache 2")
    * ...     ))
    * ...     scalaMIT <- githubProjects.index(i).query0('language -> "Scala" and ('license -> "MIT"))
    * ...   } yield scalaMIT.toList
    * ...   scanamo.exec(operations)
    * ... }
    * }}}
    */
  def query0(query: Query[_]): ScanamoOps[QueryResult]

  /**
    * Performs a query with the ability to introduce effects into the computation. This is
    * useful for huge tables when you don't want to load the whole of it in memory, but
    * scan it page by page.
    *
    * To control how many maximum items to load at once, use [[queryPaginatedM]]
    */
  final def queryM[M[_]: Monad: MonoidK](query: Query[_]): ScanamoOpsT[M, List[Either[DynamoReadError, V]]] =
    queryPaginatedM(query, Int.MaxValue)

  /**
    * Performs a scan with the ability to introduce effects into the computation. This is
    * useful for huge tables when you don't want to load the whole of it in memory, but
    * scan it page by page, with a maximum of `pageSize` items per page.
    *
    * @note DynamoDB will only ever return maximum 1MB of data per query, so `pageSize` is an
    * upper bound.
    */
  def queryPaginatedM[M[_]: Monad: MonoidK](query: Query[_],
                                            pageSize: Int): ScanamoOpsT[M, List[Either[DynamoReadError, V]]]

  /**
    * Query or scan an index, limiting the number of items evaluated by Dynamo
    *
    * {{{
    * >>> case class Transport(mode: String, line: String, colour: String)
    *
    * >>> val client = LocalDynamoDB.client()
    * >>> val scanamo = Scanamo(client)
    * >>> import com.amazonaws.services.dynamodbv2.model.ScalarAttributeType._
    * >>> import org.scanamo.syntax._
    * >>> import org.scanamo.auto._
    *
    * >>> LocalDynamoDB.withRandomTableWithSecondaryIndex(client)(
    * ...   "mode" -> S, "line" -> S)("mode" -> S, "colour" -> S
    * ... ) { (t, i) =>
    * ...   val transport = Table[Transport](t)
    * ...   val operations = for {
    * ...     _ <- transport.putAll(Set(
    * ...       Transport("Underground", "Circle", "Yellow"),
    * ...       Transport("Underground", "Metropolitan", "Magenta"),
    * ...       Transport("Underground", "Central", "Red"),
    * ...       Transport("Underground", "Picadilly", "Blue"),
    * ...       Transport("Underground", "Northern", "Black")))
    * ...     somethingBeginningWithBl <- transport.index(i).limit(1).descending.query(
    * ...       ("mode" -> "Underground" and ("colour" beginsWith "Bl"))
    * ...     )
    * ...   } yield somethingBeginningWithBl.toList
    * ...   scanamo.exec(operations)
    * ... }
    * List(Right(Transport(Underground,Picadilly,Blue)))
    * }}}
    */
  def limit(n: Int): SecondaryIndex[V]

  /**
    * Filter the results of `scan` or `query` within DynamoDB
    *
    * Note that rows filtered out still count towards your consumed capacity
    * {{{
    * >>> case class Transport(mode: String, line: String, colour: String)
    *
    * >>> val client = LocalDynamoDB.client()
    * >>> val scanamo = Scanamo(client)
    * >>> import com.amazonaws.services.dynamodbv2.model.ScalarAttributeType._
    * >>> import org.scanamo.syntax._
    * >>> import org.scanamo.auto._
    *
    * >>> LocalDynamoDB.withRandomTableWithSecondaryIndex(client)(
    * ...   "mode" -> S, "line" -> S)("mode" -> S, "colour" -> S
    * ... ) { (t, i) =>
    * ...   val transport = Table[Transport](t)
    * ...   val operations = for {
    * ...     _ <- transport.putAll(Set(
    * ...       Transport("Underground", "Circle", "Yellow"),
    * ...       Transport("Underground", "Metropolitan", "Magenta"),
    * ...       Transport("Underground", "Central", "Red"),
    * ...       Transport("Underground", "Picadilly", "Blue"),
    * ...       Transport("Underground", "Northern", "Black")))
    * ...     somethingBeginningWithC <- transport.index(i)
    * ...                                   .filter("line" beginsWith ("C"))
    * ...                                   .query("mode" -> "Underground")
    * ...   } yield somethingBeginningWithC.toList
    * ...   scanamo.exec(operations)
    * ... }
    * List(Right(Transport(Underground,Central,Red)), Right(Transport(Underground,Circle,Yellow)))
    * }}}
    */
  def filter[C: ConditionExpression](condition: C): SecondaryIndex[V]

  def descending: SecondaryIndex[V]

  def from[K: UniqueKeyCondition](key: UniqueKey[K]): SecondaryIndex[V]
}

private[scanamo] case class SecondaryIndexWithOptions[V: DynamoFormat](
  tableName: String,
  indexName: String,
  queryOptions: ScanamoQueryOptions
) extends SecondaryIndex[V] {
  def limit(n: Int): SecondaryIndexWithOptions[V] = copy(queryOptions = queryOptions.copy(limit = Some(n)))
  def from[K: UniqueKeyCondition](key: UniqueKey[K]) =
    copy(queryOptions = queryOptions.copy(exclusiveStartKey = Some(key.toDynamoObject)))
  def filter[C: ConditionExpression](condition: C) =
    SecondaryIndexWithOptions[V](tableName, indexName, ScanamoQueryOptions.default).filter(Condition(condition))
  def filter[T](c: Condition[T]): SecondaryIndexWithOptions[V] =
    copy(queryOptions = queryOptions.copy(filter = Some(c)))
  def descending: SecondaryIndexWithOptions[V] =
    copy(queryOptions = queryOptions.copy(ascending = false))
  def scan() = ScanResultStream.stream[V](ScanamoScanRequest(tableName, Some(indexName), queryOptions)).map(_._1)
  def scan0(): ScanamoOps[ScanResult] =
    ScanamoOps.scan(ScanamoScanRequest(tableName, None, queryOptions))
  def query(query: Query[_]) =
    QueryResultStream.stream[V](ScanamoQueryRequest(tableName, Some(indexName), query, queryOptions)).map(_._1)
  def query0(query: Query[_]): ScanamoOps[QueryResult] =
    ScanamoOps.query(ScanamoQueryRequest(tableName, None, query, ScanamoQueryOptions.default))
  def scanPaginatedM[M[_]: Monad: MonoidK](pageSize: Int) =
    ScanResultStream.streamTo[M, V](ScanamoScanRequest(tableName, Some(indexName), queryOptions), pageSize)
  def queryPaginatedM[M[_]: Monad: MonoidK](query: Query[_], pageSize: Int) =
    QueryResultStream.streamTo[M, V](ScanamoQueryRequest(tableName, Some(indexName), query, queryOptions), pageSize)
}
