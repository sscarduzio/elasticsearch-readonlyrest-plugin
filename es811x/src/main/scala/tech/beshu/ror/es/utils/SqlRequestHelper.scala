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
import org.joor.Reflect.*
import tech.beshu.ror.accesscontrol.domain.FieldLevelSecurity
import tech.beshu.ror.accesscontrol.domain.FieldLevelSecurity.FieldsRestrictions
import tech.beshu.ror.accesscontrol.domain.RequestId
import tech.beshu.ror.es.handler.response.FieldsFiltering
import tech.beshu.ror.es.handler.response.FieldsFiltering.NonMetadataDocumentFields
import tech.beshu.ror.es.sql.{Query, ReflectiveSqlPlanReader, SqlPlanReader}
import tech.beshu.ror.utils.ScalaOps.*

import java.time.ZoneId
import java.util.List as JList
import scala.jdk.CollectionConverters.*

object SqlRequestHelper {

  def extractSqlQueryFrom(request: CompositeIndicesRequest)(
      implicit requestId: RequestId
  ): Query = {
    Query.from(getQuery(request), readerFor(request))
  }

  def setSqlQueryTo(request: CompositeIndicesRequest, query: Query): Unit = {
    if (query.stringify != getQuery(request)) setQuery(request, query.stringify)
  }

  def modifyResponseAccordingToFieldLevelSecurity(
      response: ActionResponse,
      fieldLevelSecurity: FieldLevelSecurity
  ): ActionResponse = {
    new SqlQueryResponse(response).modifyByApplyingRestrictions(fieldLevelSecurity.restrictions)
    response
  }

  private def readerFor(request: CompositeIndicesRequest): SqlPlanReader = {
    implicit val classLoader: ClassLoader = request.getClass.getClassLoader
    new EsSqlPlanReader(request)
  }

  private def getQuery(request: CompositeIndicesRequest): String = {
    on(request).call("query").get[String]
  }

  private def setQuery(request: CompositeIndicesRequest, newQuery: String): Unit = {
    on(request).call("query", newQuery)
  }

  private def getParams(request: CompositeIndicesRequest): AnyRef = {
    on(request).call("params").get[AnyRef]
  }

  private final class EsSqlPlanReader(
      request: CompositeIndicesRequest
  )(
      implicit classLoader: ClassLoader
  ) extends ReflectiveSqlPlanReader {

    override protected def parsed(query: String): AnyRef = {
      val parser = onClass(classLoader.loadClass("org.elasticsearch.xpack.sql.parser.SqlParser")).create().get[Any]()
      on(parser).call("createStatement", query, getParams(request), ZoneId.systemDefault()).get[AnyRef]
    }

    override protected def tableIdentifiersIn(plan: AnyRef): List[Any] = {
      val preAnalyzer = onClass(classLoader.loadClass("org.elasticsearch.xpack.ql.analyzer.PreAnalyzer")).create()
      val preAnalysis = preAnalyzer.call("preAnalyze", plan).get[Any]()
      on(preAnalysis)
        .get[JList[AnyRef]]("indices")
        .asScala
        .toList
        .map(tableInfo => on(tableInfo).get[AnyRef]("id"))
    }

  }

}

final class SqlQueryResponse(val underlyingObject: Any) {

  def modifyByApplyingRestrictions(restrictions: FieldsRestrictions): Unit = {
    val columnsAndValues = getColumns.zip(getRows.transpose)
    val columnsMap = columnsAndValues.map { case (column, values) => (column.name, (column, values)) }.toMap

    val filteredColumnsAndValues = FieldsFiltering
      .filterNonMetadataDocumentFields(NonMetadataDocumentFields(columnsMap), restrictions)
      .value
      .values

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

final class ColumnInfo(val underlyingObject: Any) {
  val name: String = on(underlyingObject).get[String]("name")
}

final class Value(val underlyingObject: Any)
