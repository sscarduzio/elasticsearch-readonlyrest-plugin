package tech.beshu.ror.es.request

import java.lang.reflect.Modifier

import org.elasticsearch.action.CompositeIndicesRequest
import org.reflections.ReflectionUtils._
import tech.beshu.ror.utils.ReflecUtils

import scala.collection.JavaConverters._

object SqlQueryRequestUtils {

  def indicesFrom(request: CompositeIndicesRequest): Set[String] = {
    val query = ReflecUtils.invokeMethodCached(request, request.getClass, "query")
    val params = ReflecUtils.invokeMethodCached(request, request.getClass, "params")

    implicit val classLoader: ClassLoader = request.getClass.getClassLoader

    val tableInfoList = tableInfosFrom {
      doPreAnalyze(
        newPreAnalyzer,
        createStatement(newSqlParser, query, params)
      )
    }
    tableInfoList
      .map(tableIdentifierFrom)
      .map(indexFrom)
      .toSet
  }

  private def newSqlParser(implicit classLoader: ClassLoader) = {
    val sqlParserConstructor = sqlParserClass.getConstructor()
    sqlParserConstructor.newInstance()
  }

  private def createStatement(sqlParser: Any, query: AnyRef, params: AnyRef)
                             (implicit classLoader: ClassLoader) = {
    val createStatementMethods = getAllMethods(
      sqlParserClass,
      withModifier(Modifier.PUBLIC),
      withName("createStatement"),
      withParametersCount(2)
    ).asScala
    if (createStatementMethods.size != 1) {
      throw new IllegalArgumentException("!!!") // todo:
    }
    createStatementMethods.toList.head.invoke(sqlParser, query, params)
  }

  private def newPreAnalyzer(implicit classLoader: ClassLoader) = {
    val preAnalyzerConstructor = preAnalyzerClass.getConstructor()
    preAnalyzerConstructor.newInstance()
  }

  private def doPreAnalyze(preAnalyzer: Any, statement: AnyRef)
                          (implicit classLoader: ClassLoader) = {
    val preAnalyzeMethods = getAllMethods(
      preAnalyzerClass,
      withModifier(Modifier.PUBLIC),
      withName("preAnalyze"),
      withParametersCount(1)
    ).asScala
    if (preAnalyzeMethods.size != 1) {
      throw new IllegalArgumentException("!!!") // todo:
    }
    preAnalyzeMethods
      .toList.head
      .invoke(preAnalyzer, statement)
  }

  private def tableInfosFrom(preAnalysis: Any)
                            (implicit classLoader: ClassLoader) = {
    val indicesFields = getAllFields(
      preAnalysisClass,
      withModifier(Modifier.PUBLIC),
      withName("indices")
    ).asScala
    if (indicesFields.size != 1) {
      throw new IllegalArgumentException("!!!") // todo:
    }
    indicesFields
      .toList.head
      .get(preAnalysis)
      .asInstanceOf[java.util.List[AnyRef]]
      .asScala.toList
  }

  private def tableIdentifierFrom(tableInfo: Any)
                                 (implicit classLoader: ClassLoader) = {
    val idMethods = getAllMethods(
      tableInfoClass,
      withModifier(Modifier.PUBLIC),
      withName("id"),
      withParametersCount(0)
    ).asScala
    if (idMethods.size != 1) {
      throw new IllegalArgumentException("!!!") // todo:
    }
    idMethods
      .toList.head
      .invoke(tableInfo)
  }

  private def indexFrom(tableIdentifier: Any)
                       (implicit classLoader: ClassLoader) = {
    val indexMethod = getAllMethods(
      tableIdentifierClass,
      withModifier(Modifier.PUBLIC),
      withName("index"),
      withParametersCount(0)
    ).asScala
    if (indexMethod.size != 1) {
      throw new IllegalArgumentException("!!!") // todo:
    }
    indexMethod
      .toList.head
      .invoke(tableIdentifier)
      .asInstanceOf[String]
  }

  private def sqlParserClass(implicit classLoader: ClassLoader) =
    classLoader.loadClass("org.elasticsearch.xpack.sql.parser.SqlParser")

  private def preAnalyzerClass(implicit classLoader: ClassLoader) =
    classLoader.loadClass("org.elasticsearch.xpack.sql.analysis.analyzer.PreAnalyzer")

  private def preAnalysisClass(implicit classLoader: ClassLoader) =
    classLoader.loadClass("org.elasticsearch.xpack.sql.analysis.analyzer.PreAnalyzer$PreAnalysis")

  private def tableInfoClass(implicit classLoader: ClassLoader) =
    classLoader.loadClass("org.elasticsearch.xpack.sql.analysis.analyzer.TableInfo")

  private def tableIdentifierClass(implicit classLoader: ClassLoader) =
    classLoader.loadClass("org.elasticsearch.xpack.sql.plan.TableIdentifier")
}
