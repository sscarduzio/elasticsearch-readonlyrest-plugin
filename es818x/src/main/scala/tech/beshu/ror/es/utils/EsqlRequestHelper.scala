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
import tech.beshu.ror.es.EsVersion
import tech.beshu.ror.es.handler.response.FieldsFiltering
import tech.beshu.ror.es.handler.response.FieldsFiltering.NonMetadataDocumentFields
import tech.beshu.ror.es.utils.EsqlRequestHelper.{ClassificationError, EsqlRequestClassification, IndexTable}
import tech.beshu.ror.syntax.*
import tech.beshu.ror.utils.ScalaOps.*

import java.time.ZoneOffset
import java.util.regex.Pattern
import java.util.{Locale, List as JList}
import scala.jdk.CollectionConverters.*
import scala.util.{Failure, Success, Try}

class EsqlRequestHelper(esVersion: EsVersion) {

  def modifyIndicesOf(request: CompositeIndicesRequest,
                      requestTables: NonEmptyList[IndexTable],
                      finalIndices: Set[String]): CompositeIndicesRequest = {
    setQuery(request, newQueryFrom(getQuery(request), requestTables, finalIndices))
  }

  def modifyResponseAccordingToFieldLevelSecurity(response: ActionResponse,
                                                  fieldLevelSecurity: FieldLevelSecurity): ActionResponse = {
    new EsqlQueryResponse(response).modifyByApplyingRestrictions(fieldLevelSecurity.restrictions).underlyingObject
  }

  import EsqlRequestClassification.*

  def classifyEsqlRequest(request: CompositeIndicesRequest): Either[ClassificationError, EsqlRequestClassification] = {
    createStatement(request) match {
      case Right(statement: IndicesRelatedStatement) => Right(IndicesRelated(statement.indices))
      case Right(command: OtherCommand) => Right(NonIndicesRelated)
      case Left(error) => Left(error)
    }
  }

  private def createStatement(request: CompositeIndicesRequest): Either[ClassificationError, Statement] = {
    implicit val classLoader: ClassLoader = request.getClass.getClassLoader
    new EsqlParser().createStatementBasedOn(request)
  }

  private def getQuery(request: CompositeIndicesRequest): String = {
    on(request).call("query").get[String]
  }

  private def setQuery(request: CompositeIndicesRequest, newQuery: String): CompositeIndicesRequest = {
    on(request).call("query", newQuery)
    request
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
        getQuery(request),
        on(request).call("profile").get[AnyRef],
        on(request).call("tables").get[AnyRef],
        System.nanoTime(),
        Option(on(request).call("allowPartialResults").get[Any]).getOrElse(true)
      )
      .get[AnyRef]()
  }

  private def newQueryFrom(oldQuery: String, requestTables: NonEmptyList[IndexTable], finalIndices: Set[String]) = {
    requestTables.toList.foldLeft(oldQuery) {
      case (currentQuery, table) =>
        val (beforeFrom, afterFrom) = currentQuery.splitBy("FROM")
        afterFrom match {
          case None =>
            replaceTableNameInQueryPart(currentQuery, table.tableStringInQuery, finalIndices)
          case Some(tablesPart) =>
            s"${beforeFrom}FROM ${replaceTableNameInQueryPart(tablesPart, table.tableStringInQuery, finalIndices)}"
        }
    }
  }

  private def replaceTableNameInQueryPart(currentQuery: String, originTable: String, finalIndices: Set[String]) = {
    currentQuery.replaceAll(Pattern.quote(originTable), finalIndices.mkString(","))
  }

  private final class EsqlParser(implicit classLoader: ClassLoader) {

    private val underlyingObject =
      onClass(classLoader.loadClass("org.elasticsearch.xpack.esql.parser.EsqlParser"))
        .create().get[Any]()

    def createStatementBasedOn(request: CompositeIndicesRequest): Either[ClassificationError, Statement] = {
      val statement = esVersion match {
        case v if v >= EsVersion(8, 19, 0) => createStatementForEsEqualOrAbove8190(request)
        case v => createStatementForEsBelow8190(request)
      }
      statement.map { s =>
        NonEmptyList.fromList(indicesFrom(s)) match {
          case Some(indices) => new IndicesRelatedStatement(statement, indices)
          case None => OtherCommand(statement)
        }
      }
    }

    private def createStatementForEsBelow8190(request: CompositeIndicesRequest) = {
      val query = getQuery(request)
      val params = getParams(request)
      Try(on(underlyingObject).call("createStatement", query, params).get[AnyRef]) match {
        case Success(s) => Right(s)
        case Failure(ex: ReflectException) if ex.getCause.isInstanceOf[NoSuchMethodException] => throw ex
        case Failure(ex) => Left(ClassificationError.ParsingException(ex))
      }
    }

    private def createStatementForEsEqualOrAbove8190(request: CompositeIndicesRequest) = {
      val query = getQuery(request)
      val params = getParams(request)
      val configuration = createConfiguration(request)
      Try(on(underlyingObject).call("createStatement", query, params, configuration).get[AnyRef]) match {
        case Success(s) => Right(s)
        case Failure(ex: ReflectException) if ex.getCause.isInstanceOf[NoSuchMethodException] => throw ex
        case Failure(ex) => Left(ClassificationError.ParsingException(ex))
      }
    }

    private def indicesFrom(statement: Any) = {
      val preAnalyze = doPreAnalyze(newPreAnalyzer, statement)
      val tableInfoList = tableInfosFrom(preAnalyze)
      tableInfoList
        .map(tableIdentifierFrom)
        .map(indexStringFrom)
        .flatMap { tableString =>
          NonEmptyList
            .fromList(splitIntoIndices(tableString))
            .map(IndexTable(tableString, _))
        }
    }

    private def splitIntoIndices(tableString: String) = {
      tableString.split(',').asSafeList.filter(_.nonEmpty)
    }

    private def newPreAnalyzer(implicit classLoader: ClassLoader) = {
      onClass(classLoader.loadClass("org.elasticsearch.xpack.esql.analysis.PreAnalyzer")).create().get[Any]()
    }

    private def doPreAnalyze(preAnalyzer: Any, statement: Any) = {
      on(preAnalyzer).call("preAnalyze", statement).get[Any]()
    }

    private def tableInfosFrom(preAnalysis: Any) = {
      on(preAnalysis).get[java.util.List[Any]]("indices").asScala.toList
    }

    private def tableIdentifierFrom(tableInfo: Any) = {
      on(tableInfo).call("id").get[Any]()
    }

    private def indexStringFrom(tableIdentifier: Any) = {
      on(tableIdentifier).call("indexPattern").get[String]()
    }

  }

  private sealed trait Statement
  private final class IndicesRelatedStatement(val underlyingObject: Any,
                                              val indices: NonEmptyList[IndexTable])
    extends Statement

  private final class OtherCommand(val underlyingObject: Any)
    extends Statement

  private final class EsqlQueryResponse(val underlyingObject: ActionResponse) {

    def modifyByApplyingRestrictions(restrictions: FieldsRestrictions): this.type = {
      val columnsMap = originColumns.map(ci => (ci.name, ci)).toMap

      val filteredColumns = FieldsFiltering
        .filterNonMetadataDocumentFields(NonMetadataDocumentFields(columnsMap), restrictions)
        .value.values

      modifyColumns(filteredColumns)
      modifyPages(filteredColumns)

      this
    }

    private lazy val originColumns = {
      on(underlyingObject)
        .get[JList[Any]]("columns").asSafeList
        .map(new ColumnInfo(_))
    }

    private lazy val originPages: List[Page] = {
      on(underlyingObject)
        .get[JList[Any]]("pages").asSafeList
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
        case (acc, _) => acc
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
        originBlocks
          .view.zipWithIndex
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

object EsqlRequestHelper {

  final case class IndexTable(tableStringInQuery: String, indices: NonEmptyList[String])

  sealed trait EsqlRequestClassification
  object EsqlRequestClassification {
    final case class IndicesRelated(tables: NonEmptyList[IndexTable]) extends EsqlRequestClassification {
      lazy val indices: Set[String] = tables.toCovariantSet.flatMap(_.indices.toIterable)
    }
    case object NonIndicesRelated extends EsqlRequestClassification
  }

  sealed trait ClassificationError
  object ClassificationError {
    final case class ParsingException(cause: Throwable) extends ClassificationError
  }
}