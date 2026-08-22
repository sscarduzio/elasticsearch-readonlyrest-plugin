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
package tech.beshu.ror.es.utils

import cats.data.NonEmptyList
import cats.implicits.*
import org.elasticsearch.action.{ActionResponse, CompositeIndicesRequest}
import org.joor.Reflect.*
import org.joor.ReflectException
import tech.beshu.ror.accesscontrol.domain.FieldLevelSecurity
import tech.beshu.ror.accesscontrol.domain.FieldLevelSecurity.FieldsRestrictions
import tech.beshu.ror.accesscontrol.domain.{ClusterIndexName, RequestedIndex}
import tech.beshu.ror.es.esql.{
  EsqlClassificationError,
  EsqlIndexTable,
  EsqlPreAnalysisReview,
  EsqlQuery,
  EsqlQueryNarrowing,
  EsqlQueryRejection,
  EsqlRequestClassification,
  NormalizedIndexList,
  PreAnalysisField
}
import tech.beshu.ror.es.handler.response.FieldsFiltering
import tech.beshu.ror.es.handler.response.FieldsFiltering.NonMetadataDocumentFields
import tech.beshu.ror.syntax.*
import tech.beshu.ror.utils.ScalaOps.*

import java.time.ZoneOffset
import java.util.{List as JList, Locale}
import scala.jdk.CollectionConverters.*
import scala.util.{Failure, Success, Try}

object EsqlRequestHelper {

  def narrowIndicesOf(
      request: CompositeIndicesRequest,
      tables: NonEmptyList[EsqlIndexTable],
      allowedIndices: NonEmptyList[RequestedIndex[ClusterIndexName]]
  ): Either[EsqlQueryRejection, Unit] = {
    EsqlQueryNarrowing.narrowedQuery(getQuery(request), tables, allowedIndices) match {
      case Right(narrowedQuery) => Right(setQuery(request, narrowedQuery))
      case Left(mismatch)       => Left(EsqlQueryRejection.CannotNarrowQuery(mismatch))
    }
  }

  def modifyResponseAccordingToFieldLevelSecurity(
      response: ActionResponse,
      fieldLevelSecurity: FieldLevelSecurity
  ): ActionResponse = {
    new EsqlQueryResponse(response).modifyByApplyingRestrictions(fieldLevelSecurity.restrictions).underlyingObject
  }

  import EsqlRequestClassification.*

  def classifyEsqlRequest(
      request: CompositeIndicesRequest
  ): Either[EsqlClassificationError, EsqlRequestClassification] = {
    createStatement(request) match {
      case Right(statement: IndicesRelatedStatement) => Right(IndicesRelated(statement.tables))
      case Right(_: OtherCommand)                    => Right(NonIndicesRelated)
      case Left(error)                               => Left(error)
    }
  }

  private def createStatement(request: CompositeIndicesRequest): Either[EsqlClassificationError, Statement] = {
    implicit val classLoader: ClassLoader = request.getClass.getClassLoader
    new EsqlParser().createStatementBasedOn(request)
  }

  private def getQuery(request: CompositeIndicesRequest): EsqlQuery = {
    EsqlQuery(on(request).call("query").get[String])
  }

  private def setQuery(request: CompositeIndicesRequest, query: EsqlQuery): Unit = {
    on(request).call("query", query.value)
  }

  private def getParams(request: CompositeIndicesRequest): AnyRef = {
    on(request).call("params").get[AnyRef]
  }

  private def createConfiguration(request: CompositeIndicesRequest): AnyRef = {
    val classLoader = request.getClass.getClassLoader
    onClass(classLoader.loadClass("org.elasticsearch.xpack.esql.session.Configuration"))
      .create(
        ZoneOffset.UTC,
        Option(on(request).call("locale").get[Locale]).getOrElse(Locale.US),
        null, // at the moment it's not used anywhere, so it's null here - probably to be fixed in the future
        "ROR", // at the moment it's not used anywhere, so it's placeholder here - probably to be fixed in the future
        on(request).call("pragmas").get[AnyRef],
        Int.MaxValue,
        Int.MaxValue,
        getQuery(request).value,
        on(request).call("profile").get[AnyRef],
        on(request).call("tables").get[AnyRef],
        System.nanoTime(),
        Option(on(request).call("allowPartialResults").get[Any]).getOrElse(true)
      )
      .get[AnyRef]()
  }

  private final class EsqlParser(
      implicit classLoader: ClassLoader
  ) {

    private val underlyingObject =
      onClass(classLoader.loadClass("org.elasticsearch.xpack.esql.parser.EsqlParser"))
        .create()
        .get[Any]()

    def createStatementBasedOn(request: CompositeIndicesRequest): Either[EsqlClassificationError, Statement] = {
      createStatement(request).flatMap { statement =>
        indexTablesFrom(statement).map { tables =>
          NonEmptyList.fromList(tables) match {
            case Some(indexTables) => new IndicesRelatedStatement(statement, indexTables)
            case None              => OtherCommand(statement)
          }
        }
      }
    }

    private def createStatement(request: CompositeIndicesRequest) = {
      val query = getQuery(request).value
      val params = getParams(request)
      val configuration = createConfiguration(request)
      Try(on(underlyingObject).call("createStatement", query, params, configuration).get[AnyRef]) match {
        case Success(s)                                                                       => Right(s)
        case Failure(ex: ReflectException) if ex.getCause.isInstanceOf[NoSuchMethodException] => throw ex
        case Failure(ex) => Left(EsqlClassificationError.NotParsable(ex))
      }
    }

    private def indexTablesFrom(statement: Any): Either[EsqlClassificationError, List[EsqlIndexTable]] = {
      val preAnalysis = doPreAnalyze(newPreAnalyzer, statement)
      for {
        _ <- reviewPreAnalysis(preAnalysis)
        sourceCommands <- tablesFrom(sourceCommandIndexLists(preAnalysis), EsqlIndexTable.SourceCommand.parse)
        lookupJoins <- tablesFrom(lookupJoinIndexLists(preAnalysis), EsqlIndexTable.LookupJoin.parse)
      } yield sourceCommands ::: lookupJoins
    }

    private val reviewedPreAnalysisFields: Map[String, PreAnalysisField] = Map(
      "indexMode" -> PreAnalysisField.NotAnIndexSource,
      "indices" -> PreAnalysisField.Handled,
      "enriches" -> PreAnalysisField.NotAnIndexSource,
      "inferencePlans" -> PreAnalysisField.NotAnIndexSource,
      "lookupIndices" -> PreAnalysisField.Handled
    )

    private def reviewPreAnalysis(preAnalysis: Any): Either[EsqlClassificationError, Unit] = {
      EsqlPreAnalysisReview
        .unreviewedFieldsIn(
          fieldNames = EsqlPreAnalysisReview.instanceFieldNamesOf(preAnalysis.getClass),
          reviewed = reviewedPreAnalysisFields,
          valueOf = name => Try(on(preAnalysis).get[AnyRef](name))
        )
        .map(EsqlClassificationError.UnreviewedQueryContent.apply)
        .toLeft(())
    }

    private def tablesFrom(
        reportedIndexLists: List[String],
        tableFrom: NormalizedIndexList => Option[EsqlIndexTable]
    ): Either[EsqlClassificationError, List[EsqlIndexTable]] = {
      reportedIndexLists
        .flatMap(NormalizedIndexList.fromEsReport)
        .traverse(text => tableFrom(text).toRight(EsqlClassificationError.UnsupportedIndexList(text)))
    }

    private def newPreAnalyzer(
        implicit classLoader: ClassLoader
    ) = {
      onClass(classLoader.loadClass("org.elasticsearch.xpack.esql.analysis.PreAnalyzer")).create().get[Any]()
    }

    private def doPreAnalyze(preAnalyzer: Any, statement: Any) = {
      on(preAnalyzer).call("preAnalyze", statement).get[Any]()
    }

    private def sourceCommandIndexLists(preAnalysis: Any): List[String] = {
      indexListsIn(preAnalysis, field = "indices")
    }

    private def lookupJoinIndexLists(preAnalysis: Any): List[String] = {
      indexListsIn(preAnalysis, field = "lookupIndices")
    }

    private def indexListsIn(preAnalysis: Any, field: String): List[String] = {
      on(preAnalysis)
        .get[java.util.List[Any]](field)
        .asScala
        .toList
        .map(indexPattern => on(indexPattern).call("indexPattern").get[String]())
    }

  }

  private sealed trait Statement
  private final class IndicesRelatedStatement(val underlyingObject: Any, val tables: NonEmptyList[EsqlIndexTable])
      extends Statement

  private final class OtherCommand(val underlyingObject: Any) extends Statement

  private final class EsqlQueryResponse(val underlyingObject: ActionResponse) {

    def modifyByApplyingRestrictions(restrictions: FieldsRestrictions): this.type = {
      val columnsMap = originColumns.map(ci => (ci.name, ci)).toMap

      val filteredColumns = FieldsFiltering
        .filterNonMetadataDocumentFields(NonMetadataDocumentFields(columnsMap), restrictions)
        .value
        .values

      modifyColumns(filteredColumns)
      modifyPages(filteredColumns)

      this
    }

    private lazy val originColumns = {
      on(underlyingObject)
        .get[JList[Any]]("columns")
        .asSafeList
        .map(new ColumnInfo(_))
    }

    private lazy val originPages: List[Page] = {
      on(underlyingObject)
        .get[JList[Any]]("pages")
        .asSafeList
        .map(new Page(_))
    }

    private def modifyColumns(allowedColumns: Iterable[ColumnInfo]): Unit = {
      val allowedColumnsJava = sortByOriginOrder(allowedColumns.toCovariantSet).map(_.underlyingObject).asJava
      on(underlyingObject).set("columns", allowedColumnsJava)
    }

    private def modifyPages(allowedColumns: Iterable[ColumnInfo]): Unit = {
      val allowedColumnsIds = getAllowedColumnsIds(allowedColumns.toCovariantSet)
      originPages.foreach(_.updateBlocksByLeavingAllowedColumns(allowedColumnsIds))
    }

    private def getAllowedColumnsIds(allowedColumns: Set[ColumnInfo]) = {
      originColumns.zipWithIndex.foldLeft(Set.empty[Int]) {
        case (acc, (column, idx)) if allowedColumns.contains(column) => acc + idx
        case (acc, _)                                                => acc
      }
    }

    private def sortByOriginOrder(allowedColumns: Set[ColumnInfo]): List[ColumnInfo] = {
      originColumns.filter(allowedColumns.contains)
    }

    private final class ColumnInfo(val underlyingObject: Any) {
      lazy val name: String = on(underlyingObject).get[String]("name")
    }

    private final class Page(val underlyingObject: Any) {

      def updateBlocksByLeavingAllowedColumns(columnsIdxs: Set[Int]): Unit = {
        updateBlocks(onlyAllowedBlocks(columnsIdxs))
      }

      private lazy val originBlocks = {
        on(underlyingObject).get[Array[Any]]("blocks").toList
      }

      private def onlyAllowedBlocks(allowedColumnsIdxs: Set[Int]) = {
        originBlocks.view.zipWithIndex
          .filter { case (_, idx) => allowedColumnsIdxs.contains(idx) }
          .map(_._1)
          .toArray
      }

      private def updateBlocks(newBlocks: Array[Any]): Unit = {
        on(underlyingObject).set("blocks", asJavaBlocksArray(newBlocks))
      }

      private def asJavaBlocksArray(blocks: Array[Any]) = {
        import java.lang.reflect.Array as JArray
        val array = JArray.newInstance(
          on(underlyingObject).field("blocks").`type`().getComponentType,
          blocks.length
        )
        blocks.indices.foreach { i =>
          JArray.set(array, i, blocks(i))
        }
        array
      }

    }

  }

}
