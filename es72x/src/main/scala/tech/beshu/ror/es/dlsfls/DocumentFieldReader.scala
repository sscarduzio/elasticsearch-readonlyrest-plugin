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
package tech.beshu.ror.es.dlsfls

import java.io.ByteArrayOutputStream
import java.util.{Iterator => JavaIterator}

import com.google.common.collect.Iterators
import org.apache.logging.log4j.scala.Logging
import org.apache.lucene.index.StoredFieldVisitor.Status
import org.apache.lucene.index._
import org.apache.lucene.util.Bits
import org.elasticsearch.ExceptionsHelper
import org.elasticsearch.common.bytes.{BytesArray, BytesReference}
import org.elasticsearch.common.xcontent.{XContentBuilder, XContentHelper, XContentType}
import tech.beshu.ror.Constants
import tech.beshu.ror.accesscontrol.domain.FieldLevelSecurity.FieldsRestrictions
import tech.beshu.ror.es.dlsfls.DocumentFieldDirectoryReader.DocumentFieldDirectorySubReader
import tech.beshu.ror.fls.FieldsPolicy
import ujson._

import scala.collection.JavaConverters._
import scala.util.Try

private class DocumentFieldReader(reader: LeafReader, fieldsRestrictions: FieldsRestrictions)
  extends FilterLeafReader(reader) with Logging {

  private val policy = new FieldsPolicy(fieldsRestrictions)
  private val remainingFieldsInfo = {
    val fInfos = in.getFieldInfos
    val newfInfos = if (fInfos.asScala.isEmpty) {
      logger.warn("original fields were empty! This is weird!")
      fInfos
    } else {
      val remainingFields = fInfos.asScala.filter(f => policy.canKeep(f.name)).toSet
      new FieldInfos(remainingFields.toArray)
    }
    logger.debug(s"always allow: ${Constants.FIELDS_ALWAYS_ALLOW.asScala.mkString(",")}")
    logger.debug(s"original fields were: ${fInfos.asScala.map(_.name).mkString(",")}")
    logger.debug(s"new fields are: ${newfInfos.asScala.map(_.name).mkString(",")}")
    newfInfos
  }

  override def getFieldInfos: FieldInfos = remainingFieldsInfo

  override def getTermVectors(docID: Int): Fields = {
    val original = in.getTermVectors(docID)
    new Fields {
      override def iterator(): JavaIterator[String] = Iterators.filter(original.iterator, (s: String) => policy.canKeep(s))
      override def terms(field: String): Terms = if (policy.canKeep(field)) original.terms(field) else null
      override def size(): Int = remainingFieldsInfo.size
    }
  }

  override def getNumericDocValues(field: String): NumericDocValues =
    if (policy.canKeep(field)) in.getNumericDocValues(field) else null

  override def getBinaryDocValues(field: String): BinaryDocValues =
    if (policy.canKeep(field)) in.getBinaryDocValues(field) else null

  override def getNormValues(field: String): NumericDocValues =
    if (policy.canKeep(field)) in.getNormValues(field) else null

  override def getSortedDocValues(field: String): SortedDocValues =
    if (policy.canKeep(field)) in.getSortedDocValues(field) else null

  override def getSortedNumericDocValues(field: String): SortedNumericDocValues =
    if (policy.canKeep(field)) in.getSortedNumericDocValues(field) else null

  override def getSortedSetDocValues(field: String): SortedSetDocValues =
    if (policy.canKeep(field)) in.getSortedSetDocValues(field) else null

  override def getPointValues(field: String): PointValues =
    if (policy.canKeep(field)) in.getPointValues(field) else null

  override def terms(field: String): Terms =
    if (policy.canKeep(field)) in.terms(field) else null

  override def getMetaData: LeafMetaData = in.getMetaData

  override def getLiveDocs: Bits = in.getLiveDocs

  override def numDocs: Int = in.numDocs

  override def getDelegate: LeafReader = in

  override def document(docID: Int, visitor: StoredFieldVisitor): Unit = {
    super.document(docID, new StoredFieldVisitor {
      override def needsField(fieldInfo: FieldInfo): StoredFieldVisitor.Status =
        if (policy.canKeep(fieldInfo.name)) visitor.needsField(fieldInfo) else Status.NO

      override def hashCode: Int = visitor.hashCode

      override def stringField(fieldInfo: FieldInfo, value: Array[Byte]): Unit = visitor.stringField(fieldInfo, value)

      override def equals(obj: Any): Boolean = visitor == obj

      override def doubleField(fieldInfo: FieldInfo, value: Double): Unit = visitor.doubleField(fieldInfo, value)

      override def floatField(fieldInfo: FieldInfo, value: Float): Unit = visitor.floatField(fieldInfo, value)

      override def intField(fieldInfo: FieldInfo, value: Int): Unit = visitor.intField(fieldInfo, value)

      override def longField(fieldInfo: FieldInfo, value: Long): Unit = visitor.longField(fieldInfo, value)

      override def binaryField(fieldInfo: FieldInfo, value: Array[Byte]): Unit = {
        if ("_source" != fieldInfo.name) {
          visitor.binaryField(fieldInfo, value)
        } else {
          val xContentTypeMapTuple = XContentHelper.convertToMap(new BytesArray(value), false, XContentType.JSON)

          val xBuilder = buildFilteredResponse(
            XContentBuilder.builder(xContentTypeMapTuple.v1.xContent),
            ujson.read(value)
          )

          val out = new ByteArrayOutputStream
          BytesReference.bytes(xBuilder).writeTo(out)
          visitor.binaryField(fieldInfo, out.toByteArray)
        }
      }

      private def buildFilteredResponse(builder: XContentBuilder, json: Value): XContentBuilder = {
        buildFilteredResponse(builder, None, json,  "")
      }

      private def buildFilteredResponse(builder: XContentBuilder, key: Option[String], json: Value, collectedField: String): XContentBuilder = {
        def startObject(currentBuilder: XContentBuilder) = key match {
          case Some(name) => currentBuilder.startObject(name)
          case None => currentBuilder.startObject()
        }
        def startArray(currentBuilder: XContentBuilder) = key match {
          case Some(name) => currentBuilder.startArray(name)
          case None => currentBuilder.startArray()
        }
        def addBoolField(key: Option[String], value: Boolean) = key match {
          case Some(aKey) => builder.field(aKey, value)
          case None => builder.value(value)
        }
        json match {
          case Obj(map) =>
            map
              .foldLeft(startObject(builder)) {
                case (currentBuilder, (fieldName, fieldValue)) =>
                  val newlyCollectedField = currentField(collectedField, fieldName)
                  if(policy.canKeep(newlyCollectedField)) {
                    buildFilteredResponse(currentBuilder, Some(fieldName), fieldValue, newlyCollectedField)
                  } else {
                    currentBuilder
                  }
              }
              .endObject()
          case Arr(values) =>
            values
              .foldLeft(startArray(builder)) {
                case (currentBuilder, arrayObject) =>
                  buildFilteredResponse(currentBuilder, None, arrayObject, collectedField)
              }
              .endArray()
          case Str(value) =>
            key match {
              case Some(aKey) => builder.field(aKey, value)
              case None => builder.value(value)
            }
          case Num(value) =>
            key match {
              case Some(aKey) => builder.field(aKey, value)
              case None => builder.value(value)
            }
          case Bool(value) =>
            addBoolField(key, value)
          case True =>
            addBoolField(key, value = true)
          case False =>
            addBoolField(key, value = false)
          case Null =>
            key match {
              case Some(aKey) => builder.nullField(aKey)
              case None => builder.nullValue()
            }
        }
      }

      private def currentField(collectedField: String, currentFieldPart: String) = {
        if(collectedField.isEmpty) currentFieldPart else s"$collectedField.$currentFieldPart"
      }
    })
  }

  override def getCoreCacheHelper: IndexReader.CacheHelper = this.in.getCoreCacheHelper

  override def getReaderCacheHelper: IndexReader.CacheHelper = this.in.getCoreCacheHelper

}

object DocumentFieldReader {
  def wrap(in: DirectoryReader, fieldsRestrictions: FieldsRestrictions): DocumentFieldDirectoryReader =
    new DocumentFieldDirectoryReader(in, fieldsRestrictions)
}

final class DocumentFieldDirectoryReader(in: DirectoryReader, fieldsRestrictions: FieldsRestrictions)
  extends FilterDirectoryReader(in, new DocumentFieldDirectorySubReader(fieldsRestrictions)) {

  override protected def doWrapDirectoryReader(in: DirectoryReader) =
    new DocumentFieldDirectoryReader(in, fieldsRestrictions)

  override def getReaderCacheHelper: IndexReader.CacheHelper =
    in.getReaderCacheHelper
}

object DocumentFieldDirectoryReader {
  private class DocumentFieldDirectorySubReader(fieldsRestrictions: FieldsRestrictions)
    extends FilterDirectoryReader.SubReaderWrapper {

    override def wrap(reader: LeafReader): LeafReader = {
      Try(new DocumentFieldReader(reader, fieldsRestrictions))
        .recover { case ex: Exception => throw ExceptionsHelper.convertToElastic(ex) }
        .get
    }
  }
}