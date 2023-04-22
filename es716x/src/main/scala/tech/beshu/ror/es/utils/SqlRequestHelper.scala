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

import java.lang.reflect.Modifier
import java.time.ZoneId
import java.util.regex.Pattern
import java.util.{List => JList}

import org.elasticsearch.action.{ActionResponse, CompositeIndicesRequest}
import tech.beshu.ror.accesscontrol.domain.FieldLevelSecurity
import tech.beshu.ror.accesscontrol.domain.FieldLevelSecurity.FieldsRestrictions
import tech.beshu.ror.es.handler.response.FieldsFiltering
import tech.beshu.ror.es.handler.response.FieldsFiltering.NonMetadataDocumentFields
import tech.beshu.ror.es.utils.ExtractedIndices.SqlIndices
import tech.beshu.ror.es.utils.ExtractedIndices.SqlIndices.SqlTableRelated.IndexSqlTable
import tech.beshu.ror.es.utils.ExtractedIndices.SqlIndices.{SqlNotTableRelated, SqlTableRelated}
import tech.beshu.ror.utils.ReflecUtils
import tech.beshu.ror.utils.ScalaOps._

import scala.jdk.CollectionConverters._
import scala.util.{Failure, Success, Try}

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
      override lazy val indices: Set[String] = tables.flatMap(_.indices).toSet
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

  sealed trait ModificationError
  object ModificationError {
    final case class UnexpectedException(ex: Throwable) extends ModificationError
  }

  def modifyIndicesOf(request: CompositeIndicesRequest,
                      extractedIndices: SqlIndices,
                      finalIndices: Set[String]): Either[ModificationError, CompositeIndicesRequest] = {
    val result = Try {
      extractedIndices match {
        case s: SqlTableRelated =>
          setQuery(request, newQueryFrom(getQuery(request), s, finalIndices))
        case SqlNotTableRelated =>
          request
      }
    }
    result.toEither.left.map(ModificationError.UnexpectedException.apply)
  }

  def modifyResponseAccordingToFieldLevelSecurity(response: ActionResponse,
                                                  fieldLevelSecurity: FieldLevelSecurity): Either[ModificationError, ActionResponse] = {
    val result = Try {
      implicit val classLoader: ClassLoader = response.getClass.getClassLoader
      new SqlQueryResponse(response).modifyByApplyingRestrictions(fieldLevelSecurity.restrictions)
      response
    }
    result.toEither.left.map(ModificationError.UnexpectedException.apply)
  }

  sealed trait IndicesError
  object IndicesError {
    final case class UnexpectedException(ex: Throwable) extends IndicesError
    case object ParsingException extends IndicesError
  }

  def indicesFrom(request: CompositeIndicesRequest): Either[IndicesError, SqlIndices] = {
    val result = Try {
      val query = getQuery(request)
      val params = ReflecUtils.invokeMethodCached(request, request.getClass, "params")

      implicit val classLoader: ClassLoader = request.getClass.getClassLoader
      val statement = Try(new SqlParser().createStatement(query, params))
      statement match {
        case Success(statement: SimpleStatement) => Right(statement.indices)
        case Success(command: Command) => Right(command.indices)
        case Failure(_) => Left(IndicesError.ParsingException: IndicesError)
      }
    }
    result match {
      case Success(value) => value
      case Failure(exception) => Left(IndicesError.UnexpectedException(exception))
    }
  }

  private def getQuery(request: CompositeIndicesRequest): String = {
    ReflecUtils.invokeMethodCached(request, request.getClass, "query").asInstanceOf[String]
  }

  private def setQuery(request: CompositeIndicesRequest, newQuery: String): CompositeIndicesRequest = {
    ReflecUtils
      .getMethodOf(request.getClass, Modifier.PUBLIC, "query", 1)
      .invoke(request, newQuery)
    request
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

  def createStatement(query: String, params: AnyRef): Statement = {
    val statement = ReflecUtils
      .getMethodOf(aClass, Modifier.PUBLIC, "createStatement", 3)
      .invoke(underlyingObject, query, params, ZoneId.systemDefault())
    if (Command.isClassOf(statement)) new Command(statement)
    else new SimpleStatement(statement)
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
    val tableInfoList = tableInfosFrom {
      doPreAnalyze(newPreAnalyzer, underlyingObject)
    }
    SqlIndices.SqlTableRelated {
      tableInfoList
        .map(tableIdentifierFrom)
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

  private def doPreAnalyze(preAnalyzer: Any, statement: AnyRef)
                          (implicit classLoader: ClassLoader) = {
    ReflecUtils
      .getMethodOf(preAnalyzerClass, Modifier.PUBLIC, "preAnalyze", 1)
      .invoke(preAnalyzer, statement)
  }

  private def tableInfosFrom(preAnalysis: Any)
                            (implicit classLoader: ClassLoader) = {
    ReflecUtils
      .getFieldOf(preAnalysisClass, Modifier.PUBLIC, "indices")
      .get(preAnalysis)
      .asInstanceOf[java.util.List[AnyRef]]
      .asScala.toList
  }

  private def tableIdentifierFrom(tableInfo: Any)
                                 (implicit classLoader: ClassLoader) = {
    ReflecUtils
      .getMethodOf(tableInfoClass, Modifier.PUBLIC, "id", 0)
      .invoke(tableInfo)
  }

  private def indicesStringFrom(tableIdentifier: Any)
                               (implicit classLoader: ClassLoader) = {
    ReflecUtils
      .getMethodOf(tableIdentifierClass, Modifier.PUBLIC, "index", 0)
      .invoke(tableIdentifier)
      .asInstanceOf[String]
  }

  private def preAnalyzerClass(implicit classLoader: ClassLoader) =
    classLoader.loadClass("org.elasticsearch.xpack.sql.analysis.analyzer.PreAnalyzer")

  private def preAnalysisClass(implicit classLoader: ClassLoader) =
    classLoader.loadClass("org.elasticsearch.xpack.sql.analysis.analyzer.PreAnalyzer$PreAnalysis")

  private def tableInfoClass(implicit classLoader: ClassLoader) =
    classLoader.loadClass("org.elasticsearch.xpack.sql.analysis.analyzer.TableInfo")

  private def tableIdentifierClass(implicit classLoader: ClassLoader) =
    classLoader.loadClass("org.elasticsearch.xpack.ql.plan.TableIdentifier")
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
    ReflecUtils
      .getMethodOf(underlyingObject.getClass, Modifier.PUBLIC, "index", 0)
      .invoke(underlyingObject)
      .asInstanceOf[String]
  }

  private def getIndexPatternsString = {
    for {
      pattern <- Option(ReflecUtils
        .getMethodOf(underlyingObject.getClass, Modifier.PUBLIC, "pattern", 0)
        .invoke(underlyingObject))
      index <- Option(ReflecUtils
        .getMethodOf(pattern.getClass, Modifier.PUBLIC, "asIndexNameWildcard", 0)
        .invoke(pattern))
    } yield index.asInstanceOf[String]
  }
}
object Command {
  def isClassOf(obj: Any)(implicit classLoader: ClassLoader): Boolean =
    commandClass.isAssignableFrom(obj.getClass)

  private def commandClass(implicit classLoader: ClassLoader): Class[_] =
    classLoader.loadClass("org.elasticsearch.xpack.sql.plan.logical.command.Command")
}

final class SqlQueryResponse(val underlyingObject: Any)
                            (implicit classLoader: ClassLoader) {

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
    ReflecUtils
      .getMethodOf(sqlQueryResponseClass, Modifier.PUBLIC, "columns", 0)
      .invoke(underlyingObject)
      .asInstanceOf[JList[AnyRef]]
      .asSafeList
      .map(new ColumnInfo(_))
  }

  private def getRows: List[List[Value]] = {
    ReflecUtils
      .getMethodOf(sqlQueryResponseClass, Modifier.PUBLIC, "rows", 0)
      .invoke(underlyingObject)
      .asInstanceOf[JList[JList[AnyRef]]]
      .asSafeList.map(_.asSafeList.map(new Value(_)))
  }

  private def modifyColumns(columns: List[ColumnInfo]): Unit = {
    ReflecUtils
      .getMethodOf(sqlQueryResponseClass, Modifier.PUBLIC, "columns", 1)
      .invoke(underlyingObject, columns.map(_.underlyingObject).asJava)
  }

  private def modifyRows(rows: List[List[Value]]): Unit = {
    ReflecUtils
      .getMethodOf(sqlQueryResponseClass, Modifier.PUBLIC, "rows", 1)
      .invoke(underlyingObject, rows.map(_.map(_.underlyingObject).asJava).asJava)
  }

  private def sqlQueryResponseClass(implicit classLoader: ClassLoader) =
    classLoader.loadClass("org.elasticsearch.xpack.sql.action.SqlQueryResponse")
}

final class ColumnInfo(val underlyingObject: Any)
                      (implicit classLoader: ClassLoader) {

  val name: String = {
    ReflecUtils
      .getMethodOf(columnInfoClass, Modifier.PUBLIC, "name", 0)
      .invoke(underlyingObject)
      .asInstanceOf[String]
  }

  private def columnInfoClass(implicit classLoader: ClassLoader) =
    classLoader.loadClass("org.elasticsearch.xpack.sql.proto.ColumnInfo")
}

final class Value(val underlyingObject: Any)