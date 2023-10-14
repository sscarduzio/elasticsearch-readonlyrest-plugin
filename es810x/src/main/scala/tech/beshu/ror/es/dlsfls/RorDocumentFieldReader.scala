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

import com.google.common.collect.Iterators
import org.apache.logging.log4j.scala.Logging
import org.apache.lucene.codecs.StoredFieldsReader
import org.apache.lucene.index.StoredFieldVisitor.Status
import org.apache.lucene.index._
import org.apache.lucene.util.Bits
import org.elasticsearch.ExceptionsHelper
import org.elasticsearch.common.bytes.{BytesArray, BytesReference}
import org.elasticsearch.common.lucene.index.SequentialStoredFieldsLeafReader
import org.elasticsearch.common.xcontent.XContentHelper
import org.elasticsearch.xcontent.{XContentBuilder, XContentType}
import tech.beshu.ror.constants
import tech.beshu.ror.accesscontrol.domain.FieldLevelSecurity.FieldsRestrictions
import tech.beshu.ror.es.dlsfls.RorDocumentFieldDirectoryReader.RorDocumentFieldDirectorySubReader
import tech.beshu.ror.es.utils.XContentBuilderOps._
import tech.beshu.ror.fls.{FieldsPolicy, JsonPolicyBasedFilterer}

import java.io.ByteArrayOutputStream
import java.util.{Iterator => JavaIterator}
import scala.jdk.CollectionConverters._
import scala.util.Try

private class RorDocumentFieldReader(reader: LeafReader, fieldsRestrictions: FieldsRestrictions)
  extends SequentialStoredFieldsLeafReader(reader) with Logging {

  private val policy = new FieldsPolicy(fieldsRestrictions)
  private val remainingFieldsInfo = {
    val fInfos = in.getFieldInfos
    val newInfos = if (fInfos.asScala.isEmpty) {
      logger.warn("original fields were empty! This is weird!")
      fInfos
    } else {
      val remainingFields = fInfos.asScala.filter(f => policy.canKeep(f.name)).toSet
      new FieldInfos(remainingFields.toArray)
    }
    logger.debug(s"always allow: ${constants.FIELDS_ALWAYS_ALLOW.asScala.mkString(",")}")
    logger.debug(s"original fields were: ${fInfos.asScala.map(_.name).mkString(",")}")
    logger.debug(s"new fields are: ${newInfos.asScala.map(_.name).mkString(",")}")
    newInfos
  }
  private val jsonPolicyBasedFilterer = new JsonPolicyBasedFilterer(policy)

  override def getFieldInfos: FieldInfos = remainingFieldsInfo

  override def termVectors(): TermVectors = {
    val originalTermVectors = in.termVectors()
    new TermVectors {
      override def get(doc: Int): Fields = new Fields {
        private val originalFields = originalTermVectors.get(doc)

        override def iterator(): JavaIterator[String] = Iterators.filter(originalFields.iterator, (s: String) => policy.canKeep(s))
        override def terms(field: String): Terms = if (policy.canKeep(field)) originalFields.terms(field) else null
        override def size(): Int = remainingFieldsInfo.size
      }
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

  override def document(docID: Int, visitor: StoredFieldVisitor): Unit =
    super.document(docID, new RorStoredFieldVisitorDecorator(visitor))

  override def storedFields(): StoredFields = {
    val storedFields = super.storedFields()
    new StoredFields {
      override def document(docID: Int, visitor: StoredFieldVisitor): Unit =
        storedFields.document(docID, new RorStoredFieldVisitorDecorator(visitor))
    }
  }

  override def getCoreCacheHelper: IndexReader.CacheHelper = this.in.getCoreCacheHelper

  override def getReaderCacheHelper: IndexReader.CacheHelper = this.in.getCoreCacheHelper

  override def doGetSequentialStoredFieldsReader(reader: StoredFieldsReader): StoredFieldsReader =
    new RorStoredFieldsReaderDecorator(reader)

  private class RorStoredFieldsReaderDecorator(final val underlying: StoredFieldsReader)
    extends StoredFieldsReaderForScalaHelper(underlying) {

    override def document(docID: Int, visitor: StoredFieldVisitor): Unit = {
      underlying.document(docID, new RorStoredFieldVisitorDecorator(visitor))
    }

    override def clone(): StoredFieldsReader = {
      new RorStoredFieldsReaderDecorator(this.cloneUnderlying())
    }
  }

  private class RorStoredFieldVisitorDecorator(underlying: StoredFieldVisitor)
    extends StoredFieldVisitor {

    override def needsField(fieldInfo: FieldInfo): StoredFieldVisitor.Status =
      if (policy.canKeep(fieldInfo.name)) underlying.needsField(fieldInfo) else Status.NO

    override def hashCode: Int = underlying.hashCode

    override def stringField(fieldInfo: FieldInfo, value: String): Unit = underlying.stringField(fieldInfo, value)

    override def equals(obj: Any): Boolean = underlying == obj

    override def doubleField(fieldInfo: FieldInfo, value: Double): Unit = underlying.doubleField(fieldInfo, value)

    override def floatField(fieldInfo: FieldInfo, value: Float): Unit = underlying.floatField(fieldInfo, value)

    override def intField(fieldInfo: FieldInfo, value: Int): Unit = underlying.intField(fieldInfo, value)

    override def longField(fieldInfo: FieldInfo, value: Long): Unit = underlying.longField(fieldInfo, value)

    override def binaryField(fieldInfo: FieldInfo, value: Array[Byte]): Unit = {
      if ("_source" != fieldInfo.name) {
        underlying.binaryField(fieldInfo, value)
      } else {
        val filteredJson = jsonPolicyBasedFilterer.filteredJson(ujson.read(value))

        val xBuilder = XContentBuilder
          .builder(
            XContentHelper
              .convertToMap(new BytesArray(value), false, XContentType.JSON)
              .v1().xContent()
          )
          .json(filteredJson)

        val out = new ByteArrayOutputStream
        BytesReference.bytes(xBuilder).writeTo(out)
        underlying.binaryField(fieldInfo, out.toByteArray)
      }
    }
  }
}

object RorDocumentFieldReader {

  def wrap(in: DirectoryReader, fieldsRestrictions: FieldsRestrictions): RorDocumentFieldDirectoryReader =
    new RorDocumentFieldDirectoryReader(in, fieldsRestrictions)
}

final class RorDocumentFieldDirectoryReader(in: DirectoryReader, fieldsRestrictions: FieldsRestrictions)
  extends FilterDirectoryReader(in, new RorDocumentFieldDirectorySubReader(fieldsRestrictions)) {

  override protected def doWrapDirectoryReader(in: DirectoryReader) =
    new RorDocumentFieldDirectoryReader(in, fieldsRestrictions)

  override def getReaderCacheHelper: IndexReader.CacheHelper =
    in.getReaderCacheHelper
}

object RorDocumentFieldDirectoryReader {
  private class RorDocumentFieldDirectorySubReader(fieldsRestrictions: FieldsRestrictions)
    extends FilterDirectoryReader.SubReaderWrapper {

    override def wrap(reader: LeafReader): LeafReader = {
      Try(new RorDocumentFieldReader(reader, fieldsRestrictions))
        .recover { case ex: Exception => throw ExceptionsHelper.convertToElastic(ex) }
        .get
    }
  }
}
