package tech.beshu.ror.es.security

import org.apache.lucene.index.{DirectoryReader, FilterDirectoryReader, FilterLeafReader, IndexReader, LeafReader}
import org.apache.lucene.search.{DocIdSetIterator, IndexSearcher, Query, ScoreMode, Scorer, Weight}
import org.apache.lucene.util.{BitSetIterator, Bits, FixedBitSet}
import org.elasticsearch.ExceptionsHelper
import DocumentFilterDirectoryReader.DocumentFilterDirectorySubReader

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
