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
import java.util.regex.Pattern

import cats.data.NonEmptySet
import eu.timepit.refined.types.string.NonEmptyString
import org.elasticsearch.action.CompositeIndicesRequest
import tech.beshu.ror.accesscontrol.domain.{IndexName, InvolvingIndexOperation}
import tech.beshu.ror.accesscontrol.domain.InvolvingIndexOperation.{NonIndexOperation, SqlOperation}
import tech.beshu.ror.accesscontrol.domain.InvolvingIndexOperation.SqlOperation.IndexSqlTable
import tech.beshu.ror.utils.ReflecUtils
import tech.beshu.ror.utils.ScalaOps._
import tech.beshu.ror.accesscontrol.orders._

import scala.collection.JavaConverters._
import scala.util.Try

object SqlRequestHelper {

  def modifyIndicesOf(request: CompositeIndicesRequest,
                      operation: SqlOperation,
                      finalIndices: Set[String]): Try[CompositeIndicesRequest] = Try {
    setQuery(request, newQueryFrom(getQuery(request), operation.tables, finalIndices))
  }

  def indicesFrom(request: CompositeIndicesRequest): Try[InvolvingIndexOperation] = Try {
    val query = getQuery(request)
    val params = ReflecUtils.invokeMethodCached(request, request.getClass, "params")

    implicit val classLoader: ClassLoader = request.getClass.getClassLoader
    val statement = new SqlParser().createStatement(query, params)
    statement match {
      case statement: SimpleStatement => statement.indices
      case command: Command => command.indices
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

  private def newQueryFrom(oldQuery: String, tables: List[IndexSqlTable], finalIndices: Set[String]) = {
    tables match {
      case Nil =>
        s"""$oldQuery "${finalIndices.mkString(",")}""""
      case xs =>
        xs.foldLeft(oldQuery) {
          case (currentQuery, table) =>
            currentQuery.replaceAll(Pattern.quote(table.tableStringInQuery.value), finalIndices.mkString(","))
        }
    }
  }
}

final class SqlParser(implicit classLoader: ClassLoader) {

  private val aClass = classLoader.loadClass("org.elasticsearch.xpack.sql.parser.SqlParser")
  private val underlyingObject = aClass.getConstructor().newInstance()

  def createStatement(query: String, params: AnyRef): Statement = {
    val statement = ReflecUtils
      .getMethodOf(aClass, Modifier.PUBLIC, "createStatement", 2)
      .invoke(underlyingObject, query, params)
    if (Command.isClassOf(statement)) new Command(statement)
    else new SimpleStatement(statement)
  }

}

sealed trait Statement {
  protected def splitToIndicesPatterns(value: NonEmptyString): NonEmptySet[IndexName] = {
    value.value
      .split(',').asSafeSet
      .flatMap(NonEmptyString.unapply)
      .map(IndexName.apply)
      .unsafeToNonEmptySet
  }
}

final class SimpleStatement(val underlyingObject: AnyRef)
                           (implicit classLoader: ClassLoader)
  extends Statement {

  lazy val indices: SqlOperation = {
    val tableInfoList = tableInfosFrom {
      doPreAnalyze(newPreAnalyzer, underlyingObject)
    }
    SqlOperation {
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
    NonEmptyString.unsafeFrom {
      ReflecUtils
        .getMethodOf(tableIdentifierClass, Modifier.PUBLIC, "index", 0)
        .invoke(tableIdentifier)
        .asInstanceOf[String]
    }
  }

  private def preAnalyzerClass(implicit classLoader: ClassLoader) =
    classLoader.loadClass("org.elasticsearch.xpack.sql.analysis.analyzer.PreAnalyzer")

  private def preAnalysisClass(implicit classLoader: ClassLoader) =
    classLoader.loadClass("org.elasticsearch.xpack.sql.analysis.analyzer.PreAnalyzer$PreAnalysis")

  private def tableInfoClass(implicit classLoader: ClassLoader) =
    classLoader.loadClass("org.elasticsearch.xpack.sql.analysis.analyzer.TableInfo")

  private def tableIdentifierClass(implicit classLoader: ClassLoader) =
    classLoader.loadClass("org.elasticsearch.xpack.sql.plan.TableIdentifier")
}

final class Command(val underlyingObject: Any)
                   (implicit classLoader: ClassLoader)
  extends Statement {

  lazy val indices: InvolvingIndexOperation = {
    Try {
      getIndicesString
        .orElse(getIndexPatternsString)
        .map { indicesString =>
          SqlOperation(IndexSqlTable(indicesString, splitToIndicesPatterns(indicesString)) :: Nil)
        }
        .getOrElse(SqlOperation(Nil))
    } getOrElse {
      NonIndexOperation
    }
  }

  private def getIndicesString =
  for {
    value <- Option(ReflecUtils
      .getMethodOf(underlyingObject.getClass, Modifier.PUBLIC, "index", 0)
      .invoke(underlyingObject)
      .asInstanceOf[String])
    indicesString <- NonEmptyString.unapply(value)
  } yield indicesString

  private def getIndexPatternsString = {
    for {
      pattern <- Option(ReflecUtils
        .getMethodOf(underlyingObject.getClass, Modifier.PUBLIC, "pattern", 0)
        .invoke(underlyingObject))
      index <- Option(ReflecUtils
        .getMethodOf(pattern.getClass, Modifier.PUBLIC, "asIndexNameWildcard", 0)
        .invoke(pattern))
      nonEmptyIndexName <- NonEmptyString.unapply(index.asInstanceOf[String])
    } yield nonEmptyIndexName
  }
}
object Command {
  def isClassOf(obj: Any)(implicit classLoader: ClassLoader): Boolean =
    commandClass.isAssignableFrom(obj.getClass)

  private def commandClass(implicit classLoader: ClassLoader): Class[_] =
    classLoader.loadClass("org.elasticsearch.xpack.sql.plan.logical.command.Command")
}
