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
package tech.beshu.ror.es.utils.esql

import cats.Show
import cats.implicits.*
import org.elasticsearch.action.{ActionResponse, CompositeIndicesRequest}
import org.joor.Reflect.*
import tech.beshu.ror.accesscontrol.domain.FieldLevelSecurity
import tech.beshu.ror.accesscontrol.domain.FieldLevelSecurity.FieldsRestrictions
import tech.beshu.ror.es.handler.response.FieldsFiltering
import tech.beshu.ror.es.handler.response.FieldsFiltering.NonMetadataDocumentFields
import tech.beshu.ror.es.utils.*
import tech.beshu.ror.es.utils.esql.ExtractedIndices.*
import tech.beshu.ror.es.utils.esql.ExtractedIndices.EsqlIndices.EsqlTableRelated.IndexEsqlTable
import tech.beshu.ror.es.utils.esql.ExtractedIndices.EsqlIndices.{EsqlNotTableRelated, EsqlTableRelated}
import tech.beshu.ror.syntax.*
import tech.beshu.ror.utils.ReflecUtils
import tech.beshu.ror.utils.ScalaOps.*

import java.lang.reflect.Modifier
import java.util.List as JList
import java.util.regex.Pattern
import scala.jdk.CollectionConverters.*
import scala.util.{Failure, Success, Try}

sealed trait ExtractedIndices {
  def indices: Set[String]
}
object ExtractedIndices {
  case object NoIndices extends ExtractedIndices {
    override def indices: Set[String] = Set.empty
  }
  final case class RegularIndices(override val indices: Set[String]) extends ExtractedIndices
  sealed trait EsqlIndices extends ExtractedIndices {
    def indices: Set[String]
  }
  object EsqlIndices {
    final case class EsqlTableRelated(tables: List[IndexEsqlTable]) extends EsqlIndices {
      override lazy val indices: Set[String] = tables.flatMap(_.indices).toCovariantSet
    }
    object EsqlTableRelated {
      final case class IndexEsqlTable(tableStringInQuery: String, indices: Set[String])
    }
    case object EsqlNotTableRelated extends EsqlIndices {
      override def indices: Set[String] = Set.empty
    }
  }
}

object EsqlRequestHelper {

  sealed trait ModificationError
  object ModificationError {
    final case class UnexpectedException(ex: Throwable) extends ModificationError

    implicit val show: Show[ModificationError] = Show.show(_.toString)
  }

  def modifyIndicesOf(request: CompositeIndicesRequest,
                      extractedIndices: EsqlIndices,
                      finalIndices: Set[String]): Either[ModificationError, CompositeIndicesRequest] = {
    val result = Try {
      extractedIndices match {
        case s: EsqlTableRelated =>
          setQuery(request, newQueryFrom(getQuery(request), s, finalIndices))
        case EsqlNotTableRelated =>
          request
      }
    }
    result.toEither.left.map(ModificationError.UnexpectedException.apply)
  }

  def modifyResponseAccordingToFieldLevelSecurity(response: ActionResponse,
                                                  fieldLevelSecurity: FieldLevelSecurity): Either[ModificationError, ActionResponse] = {
    Try(new EsqlQueryResponse(response).modifyByApplyingRestrictions(fieldLevelSecurity.restrictions).underlyingObject)
      .toEither.left.map(ModificationError.UnexpectedException.apply)
  }

  sealed trait IndicesError
  object IndicesError {
    final case class UnexpectedException(ex: Throwable) extends IndicesError
    case object ParsingException extends IndicesError
  }

  def indicesFrom(request: CompositeIndicesRequest): Either[IndicesError, EsqlIndices] = {
    val result = Try {
      val query = getQuery(request)
      val params = ReflecUtils.invokeMethodCached(request, request.getClass, "params")

      implicit val classLoader: ClassLoader = request.getClass.getClassLoader
      val statement = Try(new EsqlParser().createStatement(query, params))
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

  private def newQueryFrom(oldQuery: String, extractedIndices: EsqlIndices.EsqlTableRelated, finalIndices: Set[String]) = {
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

final class EsqlParser(implicit classLoader: ClassLoader) {

  private val aClass = classLoader.loadClass("org.elasticsearch.xpack.esql.parser.EsqlParser")
  private val underlyingObject = aClass.getConstructor().newInstance()

  def createStatement(query: String, params: AnyRef): Statement = {
    val statement = ReflecUtils
      .getMethodOf(aClass, Modifier.PUBLIC, "createStatement", 2)
      .invoke(underlyingObject, query, params)
    new SimpleStatement(statement)
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

  lazy val indices: EsqlIndices = {
    val tableInfoList = tableInfosFrom {
      doPreAnalyze(newPreAnalyzer, underlyingObject)
    }
    EsqlIndices.EsqlTableRelated {
      tableInfoList
        .map(tableIdentifierFrom)
        .map(indicesStringFrom)
        .map { tableString =>
          IndexEsqlTable(tableString, splitToIndicesPatterns(tableString))
        }
    }
  }

  private def newPreAnalyzer(implicit classLoader: ClassLoader) = {
    onClass(classLoader.loadClass("org.elasticsearch.xpack.esql.analysis.PreAnalyzer")).create().get[Any]()
  }

  private def doPreAnalyze(preAnalyzer: Any, statement: AnyRef) = {
    on(preAnalyzer).call("preAnalyze", statement).get[Any]()
  }

  private def tableInfosFrom(preAnalysis: Any) = {
    on(preAnalysis).get[java.util.List[AnyRef]]("indices").asScala.toList
  }

  private def tableIdentifierFrom(tableInfo: Any) = {
    on(tableInfo).call("id").get[Any]()
  }

  private def indicesStringFrom(tableIdentifier: Any) = {
    on(tableIdentifier).call("index").get[String]()
  }

}

final class Command(val underlyingObject: Any)
  extends Statement {

  lazy val indices: EsqlIndices = {
    Try {
      getIndicesString
        .orElse(getIndexPatternsString)
        .map { indicesString =>
          EsqlTableRelated(IndexEsqlTable(indicesString, splitToIndicesPatterns(indicesString)) :: Nil)
        }
        .getOrElse(EsqlTableRelated(Nil))
    } getOrElse {
      EsqlNotTableRelated
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

final class EsqlQueryResponse(val underlyingObject: ActionResponse) {

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
//      val cls = underlyingObject.getClass
//
//      val field = cls.getDeclaredField("blocks")
//      field.setAccessible(true) // Make the private field accessible
//
//      // Get the type of the 'blocks' field (which is an array type)
//      val fieldType = field.getType // This is of type Block[]
//
//      // Get the component type of the array (which is Block)
//      val componentType = fieldType.getComponentType
//
//      // Create a new array of the correct component type
//      val array = JArray.newInstance(componentType, newBlocks.length)
//
//      // Populate the new array with elements from newBlocks
//      for (i <- newBlocks.indices) {
//        JArray.set(array, i, newBlocks(i))
//      }
    }
  }
}
