/*
 *    This file is part of ReadonlyREST.
 *
 *    ReadonlyREST is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU General Public License as published by
 *    the Free Software Foundation, either version 3 of the License, or
 *    (at your option) any later version.
 *
 *    ReadonlyREST is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU General Public License for more details.
 *
 *    You should have received a copy of the GNU General Public License
 *    along with ReadonlyREST.  If not, see http://www.gnu.org/licenses/
 */
package tech.beshu.ror.unit.es.esql

import cats.data.NonEmptyList
import org.scalatest.matchers.should.Matchers.*
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.accesscontrol.domain.{ClusterIndexName, RequestedIndex}
import tech.beshu.ror.es.esql.*
import tech.beshu.ror.es.esql.Query.SourceLocation

class EsqlQueryNarrowerTest extends AnyWordSpec {

  "An ES|QL query narrower" when {
    "classifying a query" should {
      "read the indices a source command asks for" in {
        val classification = classify("FROM logs-1,logs-2 | LIMIT 10", from("FROM logs-1,logs-2", "logs-1,logs-2"))

        classification.map(requestedIndicesOf) shouldBe Right(List("logs-1", "logs-2"))
      }
      "take a query naming no index list as unrelated to indices" in {
        classify("ROW a = 1") shouldBe Right(RequestClassification.NonIndicesRelated)
      }
      "reject a query Elasticsearch cannot parse" in {
        val narrower = new EsqlQueryNarrower(readerFailingWith(new IllegalArgumentException("cannot parse")))

        narrower.classify(Query("FROM")) shouldBe Left(Rejection.CannotParseQuery)
      }
      "reject a query whose index list is not where Elasticsearch reported it" in {
        val narrower = new EsqlQueryNarrower(
          readerReading(_ =>
            List(ReportedIndexList(IndexListRead.SourceCommand("logs-1"), SourceLocation(1, 0), "FROM elsewhere"))
          )
        )

        narrower.classify(Query("FROM logs-1 | LIMIT 10")) shouldBe
          Left(Rejection.CannotExtractIndices(ReadingFailure.NotWhereEsReportedIt("logs-1")))
      }
    }
    "narrowing a query down" should {
      "rewrite the index list and confirm it with Elasticsearch" in {
        narrow(
          query = "FROM logs-* | LIMIT 10",
          allowed = allowed("logs-1"),
          reads = Map(
            "FROM logs-* | LIMIT 10" -> List(from("FROM logs-*", "logs-*")),
            "FROM logs-1 | LIMIT 10" -> List(from("FROM logs-1", "logs-1"))
          )
        ) shouldBe Right("FROM logs-1 | LIMIT 10")
      }
      "reject the query when Elasticsearch reads the rewrite as naming other indices" in {
        narrow(
          query = "FROM logs-* | LIMIT 10",
          allowed = allowed("logs-1"),
          reads = Map(
            "FROM logs-* | LIMIT 10" -> List(from("FROM logs-*", "logs-*")),
            "FROM logs-1 | LIMIT 10" -> List(from("FROM logs-1", "logs-1,logs-2"))
          )
        ) shouldBe Left(Rejection.SubstitutionNotConfirmed(List("logs-1"), List("logs-1,logs-2")))
      }
      "reject the query when Elasticsearch cannot parse the rewrite" in {
        val narrower = new EsqlQueryNarrower(new StubReader({
          case Query("FROM logs-* | LIMIT 10") =>
            Right(List(reported("FROM logs-* | LIMIT 10", from("FROM logs-*", "logs-*"))))
          case _ => Left(new IllegalArgumentException("cannot parse"))
        }))
        val classification = narrower.classify(Query("FROM logs-* | LIMIT 10")).map(indicesRelated)

        classification.flatMap(narrower.narrowedTo(_, allowed("logs-1"))) shouldBe
          Left(Rejection.SubstitutionNotConfirmed(List("logs-1"), List.empty))
      }
    }
  }

  private def classify(query: String, reported: ReadWrittenAs*): Either[Rejection, RequestClassification] =
    new EsqlQueryNarrower(readerReading(q => reported.toList.map(reported => reported.at(q.value))))
      .classify(Query(query))

  private def narrow(
      query: String,
      allowed: NonEmptyList[RequestedIndex[ClusterIndexName]],
      reads: Map[String, List[ReadWrittenAs]]
  ): Either[Rejection, String] = {
    val narrower = new EsqlQueryNarrower(new StubReader({
      case q if reads.contains(q.value) => Right(reads(q.value).map(_.at(q.value)))
      case q                            => throw new IllegalStateException(s"unexpected query: ${q.value}")
    }))
    narrower
      .classify(Query(query))
      .map(indicesRelated)
      .flatMap(narrower.narrowedTo(_, allowed))
      .map(_.value)
  }

  private def requestedIndicesOf(classification: RequestClassification): List[String] =
    indicesRelated(classification).requestedIndices.toList.map(_.name.stringify).sorted

  private def indicesRelated(classification: RequestClassification): RequestClassification.IndicesRelated =
    classification match {
      case indicesRelated: RequestClassification.IndicesRelated => indicesRelated
      case RequestClassification.NonIndicesRelated              => fail("the query was taken as unrelated to indices")
    }

  private def readerReading(reads: Query => List[ReportedIndexList]): EsqlIndexListsReader =
    new StubReader(query => Right(reads(query)))

  private def readerFailingWith(cause: Throwable): EsqlIndexListsReader =
    new StubReader(_ => Left(cause))

  private def reported(query: String, read: ReadWrittenAs): ReportedIndexList = read.at(query)

  private def from(writtenText: String, indexList: String): ReadWrittenAs =
    ReadWrittenAs(writtenText, IndexListRead.SourceCommand(indexList))

  private def allowed(names: String*): NonEmptyList[RequestedIndex[ClusterIndexName]] =
    NonEmptyList.fromListUnsafe(names.toList.flatMap(RequestedIndex.fromString))

  private final case class ReadWrittenAs(writtenText: String, read: IndexListRead) {

    def at(query: String): ReportedIndexList = {
      val offset = query.indexOf(writtenText)
      val before = query.take(offset)
      ReportedIndexList(
        read = read,
        writtenAt =
          SourceLocation(line = before.count(_ == '\n') + 1, column = offset - (before.lastIndexOf('\n') + 1)),
        writtenText = writtenText
      )
    }

  }

  private final class StubReader(reads: Query => Either[Throwable, List[ReportedIndexList]])
      extends EsqlIndexListsReader {

    override def indexListsIn(query: Query): Either[Throwable, List[ReportedIndexList]] = reads(query)
  }

}
