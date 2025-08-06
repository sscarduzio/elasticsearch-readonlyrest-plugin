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

import org.elasticsearch.action.{ActionResponse, CompositeIndicesRequest}
import org.joor.Reflect.on
import tech.beshu.ror.accesscontrol.domain.FieldLevelSecurity
import tech.beshu.ror.accesscontrol.domain.FieldLevelSecurity.FieldsRestrictions
import tech.beshu.ror.es.handler.response.FieldsFiltering
import tech.beshu.ror.es.handler.response.FieldsFiltering.NonMetadataDocumentFields
import tech.beshu.ror.es.utils.ExtractedIndices.SqlIndices
import tech.beshu.ror.es.utils.ExtractedIndices.SqlIndices.SqlTableRelated.IndexSqlTable
import tech.beshu.ror.es.utils.ExtractedIndices.SqlIndices.{SqlNotTableRelated, SqlTableRelated}
import tech.beshu.ror.es.utils.SqlRequestHelper.IndicesError
import tech.beshu.ror.syntax.*
import tech.beshu.ror.utils.ScalaOps.*

import java.util.List as JList
import java.util.regex.Pattern
import scala.jdk.CollectionConverters.*
import scala.util.Try

sealed trait ExtractedIndices {
  def indices: Set[String]
}
object ExtractedIndices {
  case object NoIndices extends ExtractedIndices {
    override def indices: Set[String] = Set.empty
  }
  final case class RegularIndices(override val indices: Set[String]) extends ExtractedIndices
  sealed trait SqlIndices extends ExtractedIndices {
    def indices: Set[String]
  }
  object SqlIndices {
    final case class SqlTableRelated(tables: List[IndexSqlTable]) extends SqlIndices {
      override lazy val indices: Set[String] = tables.flatMap(_.indices).toCovariantSet
    }
    object SqlTableRelated {
      final case class IndexSqlTable(tableStringInQuery: String, indices: Set[String])
    }
    case object SqlNotTableRelated extends SqlIndices {
      override def indices: Set[String] = Set.empty
    }
  }
}

object SqlRequestHelper {

  def modifyIndicesOf(request: CompositeIndicesRequest,
                      extractedIndices: SqlIndices,
                      finalIndices: Set[String]): CompositeIndicesRequest = {
    extractedIndices match {
      case s: SqlTableRelated =>
        setQuery(request, newQueryFrom(getQuery(request), s, finalIndices))
      case SqlNotTableRelated =>
        request
    }
  }

  def modifyResponseAccordingToFieldLevelSecurity(response: ActionResponse,
                                                  fieldLevelSecurity: FieldLevelSecurity): ActionResponse = {
    new SqlQueryResponse(response).modifyByApplyingRestrictions(fieldLevelSecurity.restrictions)
    response
  }

  sealed trait IndicesError
  object IndicesError {
    final case class ParsingException(cause: Throwable) extends IndicesError
  }

  def indicesFrom(request: CompositeIndicesRequest): Either[IndicesError, SqlIndices] = {
    val query = getQuery(request)
    val params = getParams(request)

    implicit val classLoader: ClassLoader = request.getClass.getClassLoader
    new SqlParser()
      .createStatement(query, params)
      .map {
        case statement: SimpleStatement => statement.indices
        case command: Command => command.indices
      }
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

  private def newQueryFrom(oldQuery: String, extractedIndices: SqlIndices.SqlTableRelated, finalIndices: Set[String]) = {
    extractedIndices.tables match {
      case Nil =>
        s"""$oldQuery "${finalIndices.mkString(",")}""""
      case tables =>
        tables.foldLeft(oldQuery) {
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
  }

  private def replaceTableNameInQueryPart(currentQuery: String, originTable: String, finalIndices: Set[String]) = {
    currentQuery.replaceAll(Pattern.quote(originTable), finalIndices.mkString(","))
  }
}

final class SqlParser(implicit classLoader: ClassLoader) {

  private val aClass = classLoader.loadClass("org.elasticsearch.xpack.sql.parser.SqlParser")
  private val underlyingObject = aClass.getConstructor().newInstance()

  def createStatement(query: String, params: AnyRef): Either[IndicesError.ParsingException, Statement] = {
    Try(on(underlyingObject).call("createStatement", query, params))
      .toEither
      .map {
        case s if Command.isClassOf(s) => new Command(s)
        case s => new SimpleStatement(s)
      }
      .left.map { ex => IndicesError.ParsingException(ex) }
  }

}

sealed trait Statement {
  protected def splitToIndicesPatterns(value: String): Set[String] = {
    value.split(',').asSafeSet.filter(_.nonEmpty)
  }
}

final class SimpleStatement(val underlyingObject: AnyRef)
                           (implicit classLoader: ClassLoader)
  extends Statement {

  lazy val indices: SqlIndices = {
    val tableIdentifiersList = tableIdentifiersFrom {
      doPreAnalyze(newPreAnalyzer, underlyingObject)
    }
    SqlIndices.SqlTableRelated {
      tableIdentifiersList
        .map(indicesStringFrom)
        .map { tableString =>
          IndexSqlTable(tableString, splitToIndicesPatterns(tableString))
        }
    }
  }

  private def newPreAnalyzer(implicit classLoader: ClassLoader) = {
    val preAnalyzerConstructor = preAnalyzerClass.getConstructor()
    preAnalyzerConstructor.newInstance()
  }

  private def doPreAnalyze(preAnalyzer: Any, statement: AnyRef) = {
    on(preAnalyzer).call("preAnalyze", statement).get[Any]()
  }

  private def tableIdentifiersFrom(preAnalysis: Any) = {
    on(preAnalysis)
      .get[java.util.List[AnyRef]]("indices")
      .asScala.toList
  }

  private def indicesStringFrom(tableIdentifier: Any) = {
    on(tableIdentifier).get[String]("index")
  }

  private def preAnalyzerClass(implicit classLoader: ClassLoader) =
    classLoader.loadClass("org.elasticsearch.xpack.sql.analysis.analyzer.PreAnalyzer")
}

final class Command(val underlyingObject: Any)
  extends Statement {

  lazy val indices: SqlIndices = {
    Try {
      getIndicesString
        .orElse(getIndexPatternsString)
        .map { indicesString =>
          SqlTableRelated(IndexSqlTable(indicesString, splitToIndicesPatterns(indicesString)) :: Nil)
        }
        .getOrElse(SqlTableRelated(Nil))
    } getOrElse {
      SqlNotTableRelated
    }
  }

  private def getIndicesString = Option {
    on(underlyingObject).get[String]("index")
  }

  private def getIndexPatternsString = {
    for {
      pattern <- Option(on(underlyingObject).get[AnyRef]("pattern"))
      index <- Option(on(pattern).get[String]("asIndexNameWildcard"))
    } yield index
  }
}
object Command {
  def isClassOf(obj: Any)(implicit classLoader: ClassLoader): Boolean =
    commandClass.isAssignableFrom(obj.getClass)

  private def commandClass(implicit classLoader: ClassLoader): Class[_] =
    classLoader.loadClass("org.elasticsearch.xpack.sql.plan.logical.command.Command")
}

final class SqlQueryResponse(val underlyingObject: Any) {

  def modifyByApplyingRestrictions(restrictions: FieldsRestrictions): Unit = {
    val columnsAndValues = getColumns.zip(getRows.transpose)
    val columnsMap = columnsAndValues.map { case (column, values) => (column.name, (column, values)) }.toMap

    val filteredColumnsAndValues = FieldsFiltering
      .filterNonMetadataDocumentFields(NonMetadataDocumentFields(columnsMap), restrictions)
      .value.values

    val filteredColumns = filteredColumnsAndValues.map(_._1).toList
    val filteredRows = filteredColumnsAndValues.map(_._2).toList.transpose

    modifyColumns(filteredColumns)
    modifyRows(filteredRows)
  }

  private def getColumns: List[ColumnInfo] = {
    on(underlyingObject)
      .get[JList[AnyRef]]("columns")
      .asSafeList
      .map(new ColumnInfo(_))
  }

  private def getRows: List[List[Value]] = {
    on(underlyingObject)
      .get[JList[JList[AnyRef]]]("rows")
      .asSafeList
      .map(_.asSafeList.map(new Value(_)))
  }

  private def modifyColumns(columns: List[ColumnInfo]): Unit = {
    on(underlyingObject)
      .call("columns", columns.map(_.underlyingObject).asJava)
  }

  private def modifyRows(rows: List[List[Value]]): Unit = {
    on(underlyingObject)
      .call("rows", rows.map(_.map(_.underlyingObject).asJava).asJava)
  }
}

final class ColumnInfo(val underlyingObject: Any){
  val name: String = on(underlyingObject).get[String]("name")
}

final class Value(val underlyingObject: Any)