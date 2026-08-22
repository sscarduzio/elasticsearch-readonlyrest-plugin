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
import tech.beshu.ror.es.EsVersion
import tech.beshu.ror.es.esql.{
  EsqlClassificationError,
  EsqlIndexListLocator,
  EsqlIndexListRead,
  EsqlIndexListReplacing,
  EsqlIndexTable,
  EsqlPlanLeafReview,
  EsqlQuery,
  EsqlQueryRejection,
  EsqlReportedRelation,
  EsqlRequestClassification,
  EsqlSourceLocation,
  PlanLeaf
}
import tech.beshu.ror.es.handler.response.FieldsFiltering
import tech.beshu.ror.es.handler.response.FieldsFiltering.NonMetadataDocumentFields
import tech.beshu.ror.syntax.*
import tech.beshu.ror.utils.ScalaOps.*

import java.time.ZoneOffset
import java.util.function.Predicate as JPredicate
import java.util.{List as JList, Locale}
import scala.jdk.CollectionConverters.*
import scala.util.{Failure, Success, Try}

class EsqlRequestHelper(esVersion: EsVersion) {

  def modifyIndicesOf(
      request: CompositeIndicesRequest,
      tables: NonEmptyList[EsqlIndexTable],
      allowedIndices: NonEmptyList[RequestedIndex[ClusterIndexName]]
  ): Either[EsqlQueryRejection, Unit] = {
    val replaced = EsqlIndexListReplacing.queryWithAllowedIndices(getQuery(request), tables, allowedIndices)
    EsqlIndexListReplacing
      .verified(replaced, indexListReadsIn(request, replaced.query))
      .map(setQuery(request, _))
  }

  /** What ES reads out of the rewritten query, asked of the same parser it will use to run it. */
  private def indexListReadsIn(request: CompositeIndicesRequest, query: EsqlQuery): List[EsqlIndexListRead] = {
    implicit val classLoader: ClassLoader = request.getClass.getClassLoader
    new EsqlParser().indexListReadsIn(query, request)
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
      createStatement(getQuery(request).value, request).flatMap { s =>
        indexTablesFrom(request, s).map { tables =>
          NonEmptyList.fromList(tables) match {
            case Some(indexTables) => new IndicesRelatedStatement(s, indexTables)
            case None              => OtherCommand(s)
          }
        }
      }
    }

    private def createStatement(query: String, request: CompositeIndicesRequest) = {
      esVersion match {
        case v if v >= EsVersion(8, 19, 0) => createStatementForEsEqualOrAbove8190(query, request)
        case _                             => createStatementForEsBelow8190(query, request)
      }
    }

    private def createStatementForEsBelow8190(query: String, request: CompositeIndicesRequest) = {
      val params = getParams(request)
      Try(on(underlyingObject).call("createStatement", query, params).get[AnyRef]) match {
        case Success(s)                                                                       => Right(s)
        case Failure(ex: ReflectException) if ex.getCause.isInstanceOf[NoSuchMethodException] => throw ex
        case Failure(ex) => Left(EsqlClassificationError.NotParsable(ex))
      }
    }

    private def createStatementForEsEqualOrAbove8190(query: String, request: CompositeIndicesRequest) = {
      val params = getParams(request)
      val configuration = createConfiguration(request)
      Try(on(underlyingObject).call("createStatement", query, params, configuration).get[AnyRef]) match {
        case Success(s)                                                                       => Right(s)
        case Failure(ex: ReflectException) if ex.getCause.isInstanceOf[NoSuchMethodException] => throw ex
        case Failure(ex) => Left(EsqlClassificationError.NotParsable(ex))
      }
    }

    def indexListReadsIn(query: EsqlQuery, request: CompositeIndicesRequest): List[EsqlIndexListRead] = {
      createStatement(query.value, request)
        .map(statement => reportedRelationsIn(planOf(statement)).map(_.read).filter(_.indexList.nonEmpty))
        .getOrElse(List.empty)
    }

    private def planOf(statement: Any): Any = statement

    private def indexTablesFrom(
        request: CompositeIndicesRequest,
        statement: Any
    ): Either[EsqlClassificationError, List[EsqlIndexTable]] = {
      val plan = planOf(statement)
      for {
        _ <- reviewPlanLeaves(plan)
        tables <- EsqlIndexListLocator
          .indexTablesIn(getQuery(request), reportedRelationsIn(plan))
          .leftMap(EsqlClassificationError.CannotReadIndexList.apply)
      } yield tables
    }

    private val reviewedPlanLeaves: Map[String, PlanLeaf] = Map(
      "UnresolvedRelation" -> PlanLeaf.Handled,
      "Row" -> PlanLeaf.NotAnIndexSource,
      "ShowInfo" -> PlanLeaf.NotAnIndexSource,
      "LocalRelation" -> PlanLeaf.NotAnIndexSource,
      "EsRelation" -> PlanLeaf.UnsupportedIndexSource,
      "StubRelation" -> PlanLeaf.UnsupportedIndexSource,
      "Explain" -> PlanLeaf.UnsupportedIndexSource
    )

    private def reviewPlanLeaves(plan: Any): Either[EsqlClassificationError, Unit] = {
      EsqlPlanLeafReview
        .unreviewedLeavesIn(planLeafTypesIn(plan), reviewedPlanLeaves)
        .map(EsqlClassificationError.UnreviewedQueryContent.apply)
        .toLeft(())
    }

    private def planLeafTypesIn(plan: Any): List[String] = {
      on(plan).call("collectLeaves").get[java.util.List[Any]]().asScala.toList.map(_.getClass.getSimpleName)
    }

    /**
     * The very nodes ES reads the query's indices from when it pre-analyzes the plan - except the pre-analysis
     * deduplicates them by pattern text and keeps one source location per pattern, which is one span too few for
     * a query naming the same pattern twice.
     */
    private def reportedRelationsIn(plan: Any): List[EsqlReportedRelation] = {
      val isUnresolvedRelation: JPredicate[Any] = node => node.getClass.getSimpleName == "UnresolvedRelation"
      on(plan)
        .call("collect", isUnresolvedRelation)
        .get[java.util.List[Any]]()
        .asScala
        .toList
        .map(reportedRelationOf)
    }

    private def reportedRelationOf(relation: Any): EsqlReportedRelation = {
      val indexPattern = on(relation).call("indexPattern").get[Any]()
      val source = on(indexPattern).call("source").get[Any]()
      val location = on(source).call("source").get[Any]()
      EsqlReportedRelation(
        indexList = on(indexPattern).call("indexPattern").get[String](),
        writtenAt = EsqlSourceLocation(
          line = on(location).call("getLineNumber").get[Int](),
          column = on(location).call("getColumnNumber").get[Int]() - 1
        ),
        writtenText = on(source).call("text").get[String](),
        isLookupJoin = isLookupJoinRelation(relation)
      )
    }

    private def isLookupJoinRelation(relation: Any): Boolean = {
      Option(on(relation).call("indexMode").get[AnyRef])
        .exists(indexMode => on(indexMode).call("name").get[String]() == "LOOKUP")
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
