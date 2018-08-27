package com.gu.scanamo.time

import com.gu.scanamo.DynamoFormat
import java.time.{Instant, OffsetDateTime, ZonedDateTime}
import java.time.format.{DateTimeParseException, DateTimeFormatter}

object JavaTimeFormats {

  /**  Format for dealing with points in time stored as the number of milliseconds since Epoch.
    *  {{{
    *  prop> import com.gu.scanamo.DynamoFormat
    *  prop> import java.time.Instant
    *  prop> import com.gu.scanamo.time.JavaTimeFormats.instantAsLongFormat
    *  prop> import com.gu.scanamo.time.TimeGenerators.instantAsLongArb
    *  prop> (x: Instant) =>
    *      | DynamoFormat[Instant].read(DynamoFormat[Instant].write(x)) == Right(x)
    *  }}}
    */
  implicit val instantAsLongFormat =
    DynamoFormat.coercedXmap[Instant, Long, ArithmeticException](x => Instant.ofEpochMilli(x))(x => x.toEpochMilli)

  /**  Format for dealing with date-times with an offset from UTC.
    *  {{{
    *  prop> import com.gu.scanamo.DynamoFormat
    *  prop> import java.time.OffsetDateTime
    *  prop> import com.gu.scanamo.time.JavaTimeFormats.offsetDateTimeFormat
    *  prop> import com.gu.scanamo.time.TimeGenerators.offsetDateTimeArb
    *  prop> (x: OffsetDateTime) =>
    *      | DynamoFormat[OffsetDateTime].read(DynamoFormat[OffsetDateTime].write(x)) == Right(x)
    *  }}}
    */
  implicit val offsetDateTimeFormat = DynamoFormat.coercedXmap[OffsetDateTime, String, DateTimeParseException](
    OffsetDateTime.parse
  )(
    _.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
  )

  /**  Format for dealing with date-times with a time zone in the ISO-8601 calendar system.
    *  {{{
    *  prop> import com.gu.scanamo.DynamoFormat
    *  prop> import java.time.ZonedDateTime
    *  prop> import com.gu.scanamo.time.JavaTimeFormats.zonedDateTimeFormat
    *  prop> import com.fortysevendeg.scalacheck.datetime.jdk8.ArbitraryJdk8._
    *  prop> (x: ZonedDateTime) =>
    *      | DynamoFormat[ZonedDateTime].read(DynamoFormat[ZonedDateTime].write(x)) == Right(x)
    *  }}}
    */
  implicit val zonedDateTimeFormat = DynamoFormat.coercedXmap[ZonedDateTime, String, DateTimeParseException](
    ZonedDateTime.parse
  )(
    _.format(DateTimeFormatter.ISO_ZONED_DATE_TIME)
  )
}
