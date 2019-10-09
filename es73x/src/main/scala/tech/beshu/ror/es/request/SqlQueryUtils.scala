package tech.beshu.ror.es.request

import java.lang.reflect.Modifier

import org.elasticsearch.action.CompositeIndicesRequest
import tech.beshu.ror.utils.ReflecUtils
import tech.beshu.ror.utils.ScalaOps._

import scala.collection.JavaConverters._

object SqlQueryUtils {

  def modifyIndicesOf(request: CompositeIndicesRequest, indices: List[String]): CompositeIndicesRequest = {
    ReflecUtils
      .getMethodOf(request.getClass, Modifier.PUBLIC, "query", 1)
      .invoke(request, s"DESCRIBE LIKE '${indices.mkString(",")}'")
    // todo: 
    request
  }

  def indicesFrom(request: CompositeIndicesRequest): Set[String] = {
    val query = ReflecUtils.invokeMethodCached(request, request.getClass, "query").asInstanceOf[String]
    val params = ReflecUtils.invokeMethodCached(request, request.getClass, "params")

    implicit val classLoader: ClassLoader = request.getClass.getClassLoader
    val statement = new SqlParser().createStatement(query, params)
    statement match {
      case statement: SimpleStatement => statement.indices
      case command: Command => command.indices
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

  lazy val indices: Set[String] = {
    val tableInfoList = tableInfosFrom {
      doPreAnalyze(newPreAnalyzer, underlyingObject)
    }
    tableInfoList
      .map(tableIdentifierFrom)
      .map(indicesStringFrom)
      .flatMap(splitToIndicesPatterns)
      .toSet
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

  lazy val indices: Set[String] = {
    getIndicesString
      .orElse(getIndexPatternsString)
      .toSet
      .flatMap(splitToIndicesPatterns)
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
    obj.getClass == showTablesClass || obj.getClass == showColumnsClass

  private def showTablesClass(implicit classLoader: ClassLoader): Class[_] =
    classLoader.loadClass("org.elasticsearch.xpack.sql.plan.logical.command.ShowTables")

  private def showColumnsClass(implicit classLoader: ClassLoader): Class[_] =
    classLoader.loadClass("org.elasticsearch.xpack.sql.plan.logical.command.ShowColumns")
}
