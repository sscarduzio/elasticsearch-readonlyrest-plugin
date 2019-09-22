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

import org.apache.lucene.index._
import org.apache.lucene.search._
import org.apache.lucene.util.{BitSetIterator, Bits, FixedBitSet}
import org.elasticsearch.ExceptionsHelper
import tech.beshu.ror.es.dlsfls.DocumentFilterDirectoryReader.DocumentFilterDirectorySubReader

import scala.util.Try

// Code based on
// https://stackoverflow.com/questions/40949286/apply-lucene-query-on-bits
// https://github.com/apache/lucene-solr/blob/master/lucene/misc/src/java/org/apache/lucene/index/PKIndexSplitter.java#L127-L170
private class DocumentFilterReader(reader: LeafReader, query: Query)
  extends FilterLeafReader(reader) {

  private val liveDocs = {
    val searcher = new IndexSearcher(this)
    searcher.setQueryCache(null)

    val preserveWeight: Weight = searcher.createWeight(query, ScoreMode.COMPLETE_NO_SCORES, 0)

    val bits = new FixedBitSet(in.maxDoc)
    val preserveScorer: Scorer = preserveWeight.scorer(getContext)
    if (preserveScorer != null) bits.or(preserveScorer.iterator)

    if (in.hasDeletions) {
      val oldLiveDocs = in.getLiveDocs
      assert(oldLiveDocs != null)
      val it = new BitSetIterator(bits, 0L)
      var i = it.nextDoc
      while (i != DocIdSetIterator.NO_MORE_DOCS) {
        if (!oldLiveDocs.get(i)) bits.clear(i)
        i = it.nextDoc
      }
    }
    bits
  }

  override val numDocs: Int = liveDocs.cardinality

  override val hasDeletions = true

  override val getLiveDocs: Bits = liveDocs

  override val getCoreCacheHelper: IndexReader.CacheHelper = in.getCoreCacheHelper

  override val getReaderCacheHelper: IndexReader.CacheHelper = in.getReaderCacheHelper

}
object DocumentFilterReader {
  def wrap(in: DirectoryReader, filterQuery: Query): DocumentFilterDirectoryReader =
    new DocumentFilterDirectoryReader(in, filterQuery)
}

final class DocumentFilterDirectoryReader(in: DirectoryReader, filterQuery: Query)
  extends FilterDirectoryReader(in, new DocumentFilterDirectorySubReader(filterQuery)) {

  override protected def doWrapDirectoryReader(in: DirectoryReader) =
    new DocumentFilterDirectoryReader(in, filterQuery)

  override def getReaderCacheHelper: IndexReader.CacheHelper = in.getReaderCacheHelper
}
object DocumentFilterDirectoryReader {
  private class DocumentFilterDirectorySubReader(filterQuery: Query) extends FilterDirectoryReader.SubReaderWrapper {
    override def wrap(reader: LeafReader): LeafReader = {
      Try(new DocumentFilterReader(reader, filterQuery))
        .recover { case ex: Exception => throw ExceptionsHelper.convertToElastic(ex) }
        .get
    }
  }
}
