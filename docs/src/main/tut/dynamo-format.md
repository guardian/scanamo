---
layout: docs
title: DynamoFormat
position:  6
---

## DynamoFormat

Scanamo uses the [`DynamoFormat`](latest/api/org/scanamo/DynamoFormat.html)
type class to define how to read and write different types to DynamoDB.

Many common types have a `DynamoFormat` provided by Scanamo. For a full list see
of those supported, you can look at the [companion object](latest/api/org/scanamo/DynamoFormat$.html).

Scanamo also supports automatically deriving formats for case classes and
sealed trait families where all the contained types have a defined or derivable
`DynamoFormat`.

### Automatic Derivation

Scanamo can automatically derive `DynamoFormat` for case classes (as long as all its members can also be derived). Ex:

```tut:silent
import org.scanamo._
import org.scanamo.syntax._
import org.scanamo.auto._

case class Farm(animals: List[String])
case class Farmer(name: String, age: Long, farm: Farm)

val table = Table[Simple, String, Farmer]("farmer")
table.putAll(
    Set(
        Farmer("McDonald", 156L, Farm(List("sheep", "cow"))),
        Farmer("Boggis", 43L, Farm(List("chicken")))
    )
)
```

### Semi-automatic Derivation

Scanamo offers a convenient way (semi-automoatic) to derive `DynamoFormat` in your code. 
Ex:

```tut:silent
import org.scanamo._
import org.scanamo.syntax._
import org.scanamo.semiauto._

case class Farm(animals: List[String])
case class Farmer(name: String, age: Long, farm: Farm)

implicit val formatFarm: DynamoFormat[Farm] = deriveDynamoFormat[Farm]
implicit val formatFarmer: DynamoFormat[Farmer] = deriveDynamoFormat
```

### Custom Formats

It's also possible to define a serialisation format for types which Scanamo
doesn't already support and can't derive. Normally this involves using the
[xmap](latest/api/org/scanamo/DynamoFormat$.html#xmap[A,B](r:B=>Either[org.scanamo.error.DynamoReadError,A])(w:A=>B)(implicitf:org.scanamo.DynamoFormat[B]):org.scanamo.DynamoFormat[A])
or [coercedXmap](latest/api/org/scanamo/DynamoFormat$.html#coercedXmap[A,B,T>:Null<:Throwable](read:B=>A)(write:A=>B)(implicitf:org.scanamo.DynamoFormat[B],implicitT:scala.reflect.ClassTag[T],implicitNT:cats.NotNull[T]):org.scanamo.DynamoFormat[A])
to translate between the type and one Scanamo does already know about.

For example, to store Joda `DateTime` objects as ISO `String`s in Dynamo:

```tut:silent
import org.joda.time._

import org.scanamo._
import org.scanamo.syntax._
import org.scanamo.auto._

case class Foo(dateTime: DateTime)

val client = LocalDynamoDB.client()
val scanamo = Scanamo(client)
import com.amazonaws.services.dynamodbv2.model.ScalarAttributeType._
LocalDynamoDB.createTable(client)("foo")("dateTime" -> S)
```
```tut:book
implicit val jodaStringFormat = DynamoFormat.coercedXmap[DateTime, String, IllegalArgumentException](
  DateTime.parse(_).withZone(DateTimeZone.UTC)
)(
  _.toString
)
val fooTable = Table[Simple, DateTime, Foo]("foo")
val operations = for {
  _           <- fooTable.put(Foo(new DateTime(0)))
  results     <- fooTable.scan()
} yield results

scanamo.exec(operations).toList
```

### Formats for Refined Types

Scanamo supports Scala refined types via the `scanamo-refined` module, helping you to define custom formats
for types built using the predicates provided by the [refined](https://github.com/fthomas/refined) project.
Refined types give an extra layer of type safety to our programs making the compilation fail when we try to
assign wrong values to them.

To use them in your project you will need to include the dependency in your project:

```
libraryDependencies += "com.gu" %% "scanamo-refined" % "x.y.z"
```

And then import the support for refined types and define your model:

```tut:silent
import org.scanamo._
import org.scanamo.refined._
import org.scanamo.auto._
import eu.timepit.refined._
import eu.timepit.refined.api.Refined
import eu.timepit.refined.auto._
import eu.timepit.refined.numeric._

type PosInt = Int Refined Positive

case class Customer(age: PosInt)

LocalDynamoDB.createTable(client)("Customer")("age" -> N)
```

You just now use it like if the type `PosInt` was natively supported by `scanamo`:

```tut:book
val customerTable = Table[Customer]("Customer")
val operations = for {
  _       <- customerTable.put(Customer(67))
  results <- customerTable.scan()
} yield results

scanamo.exec(operations).toList
```

### Derived Formats

Scanamo uses [shapeless](https://github.com/milessabin/shapeless) and implicit derivation to automatically derive [`DynamoFormat`](latest/api/org/scanamo/DynamoFormat)s for case classes and sealed trait families. You may also see or hear sealed trait families referred to as Algebraic Data Types (ADTs) and co-products. Here is an example that could be used to support event sourcing (assuming a table with a partition key of `id` and sort key `seqNo`):

```tut:silent
import java.util.UUID

import org.scanamo._
import org.scanamo.syntax._
import org.scanamo.auto._

// Sealed trait family for events.
sealed trait Event
final case class Create(name: String) extends Event
final case class Delete(reason: String) extends Event

// An event envelope that wraps events.
final case class EventEnvelope(id: UUID, seqNo: Int, event: Event)

// Example instantiations.
val id = UUID.fromString("9e5fd6e9-65ef-472c-ad89-e5fe658f14c6")
val create = EventEnvelope(id, 0, Create("Something"))
val delete = EventEnvelope(id, 1, Delete("Oops"))
```

```tut:book
val attributeValue = DynamoFormat[EventEnvelope].write(create)

val dynamoRecord = DynamoFormat[EventEnvelope].read(attributeValue)
```

If you look carefully at the attribute value (pretty-printed below) then you can see that the envelope (or wrapper) is necessary. This is because Scanamo writes the sealed trait family and associated case classes into a map. This allows Scanamo to use the map key to disambiguate between the sub-classes when reading attribute values. This makes sense and works well for most use cases, but it does mean you cannot persist an sealed trait family directly (i.e. without an envelope or wrapper) using automatic derivation because partition keys do not support maps.

Here is the pretty-printed attribute value that Scanamo generates:

```
{
  M: {
    seqNo={
      N: 0,
    },
    id={
      S: 9e5fd6e9-65ef-472c-ad89-e5fe658f14c6,
    },
    event={
      M: {
        Create={
          M: {
            name={
              S: Something,
            }
          },
        }
      },
    }
  },
}
```
