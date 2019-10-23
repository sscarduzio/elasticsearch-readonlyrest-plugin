package tech.beshu.ror.es.request

import java.lang.reflect.Modifier
import java.util.regex.Pattern

import org.elasticsearch.action.CompositeIndicesRequest
import tech.beshu.ror.accesscontrol.request.RequestInfoShim.ExtractedIndices.SqlIndices
import tech.beshu.ror.accesscontrol.request.RequestInfoShim.ExtractedIndices.SqlIndices.{SqlNotTableRelated, SqlTableRelated}
import tech.beshu.ror.accesscontrol.request.RequestInfoShim.ExtractedIndices.SqlIndices.SqlTableRelated.IndexSqlTable
import tech.beshu.ror.utils.ReflecUtils
import tech.beshu.ror.utils.ScalaOps._

import scala.collection.JavaConverters._
import scala.util.Try

object SqlQueryUtils {

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

  def indicesFrom(request: CompositeIndicesRequest): Try[SqlIndices] = Try {
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

  private def newQueryFrom(oldQuery: String, extractedIndices: SqlIndices.SqlTableRelated, finalIndices: Set[String]) = {
    extractedIndices.tables match {
      case Nil =>
        s"""$oldQuery "${finalIndices.mkString(",")}""""
      case tables =>
        tables.foldLeft(oldQuery) {
          case (currentQuery, table) =>
            currentQuery.replaceAll(Pattern.quote(table.tableStringInQuery), finalIndices.mkString(","))
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
    classLoader.loadClass("org.elasticsearch.xpack.sql.plan.TableIdentifier")
}

final class Command(val underlyingObject: Any)
                   (implicit classLoader: ClassLoader)
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

  private def showTablesClass(implicit classLoader: ClassLoader): Class[_] =
    classLoader.loadClass("org.elasticsearch.xpack.sql.plan.logical.command.ShowTables")

  private def showColumnsClass(implicit classLoader: ClassLoader): Class[_] =
    classLoader.loadClass("org.elasticsearch.xpack.sql.plan.logical.command.ShowColumns")
}
