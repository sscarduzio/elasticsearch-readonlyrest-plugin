package tech.beshu.ror.es.security;

import org.apache.lucene.index.BinaryDocValues;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.FilterDirectoryReader;
import org.apache.lucene.index.FilterLeafReader;
import org.apache.lucene.index.LeafMetaData;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.NumericDocValues;
import org.apache.lucene.index.PointValues;
import org.apache.lucene.index.SortedDocValues;
import org.apache.lucene.index.SortedNumericDocValues;
import org.apache.lucene.index.SortedSetDocValues;
import org.apache.lucene.index.StoredFieldVisitor;
import org.apache.lucene.index.Terms;
import org.apache.lucene.util.Bits;
import org.elasticsearch.ExceptionsHelper;
import org.elasticsearch.common.bytes.BytesArray;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.collect.Tuple;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentHelper;
import org.elasticsearch.common.xcontent.XContentType;
import tech.beshu.ror.commons.utils.MatcherWithWildcardsAndNegations;

import java.io.IOException;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

public class DocumentFieldReader extends FilterLeafReader {
  private MatcherWithWildcardsAndNegations fieldsMatcher;

  private DocumentFieldReader(LeafReader reader, Set<String> fields) {
    super(reader);
    this.fieldsMatcher = new MatcherWithWildcardsAndNegations(fields);
  }

  public static DocumentFieldReader.DocumentFieldDirectoryReader wrap(DirectoryReader in, Set<String> fields) throws IOException {
    return new DocumentFieldReader.DocumentFieldDirectoryReader(in, fields);
  }

  @Override
  public NumericDocValues getNumericDocValues(String field) throws IOException {
    return fieldsMatcher.match(field) ? in.getNumericDocValues(field) : null;
  }

  @Override
  public BinaryDocValues getBinaryDocValues(String field) throws IOException {
    return fieldsMatcher.match(field) ? in.getBinaryDocValues(field) : null;
  }

  @Override
  public NumericDocValues getNormValues(String field) throws IOException {
    return fieldsMatcher.match(field) ? in.getNormValues(field) : null;
  }

  @Override
  public SortedDocValues getSortedDocValues(String field) throws IOException {
    return fieldsMatcher.match(field) ? in.getSortedDocValues(field) : null;
  }

  @Override
  public SortedNumericDocValues getSortedNumericDocValues(String field) throws IOException {
    return fieldsMatcher.match(field) ? in.getSortedNumericDocValues(field) : null;
  }

  @Override
  public SortedSetDocValues getSortedSetDocValues(String field) throws IOException {
    return fieldsMatcher.match(field) ? in.getSortedSetDocValues(field) : null;
  }

  @Override
  public PointValues getPointValues(String field) throws IOException {
    return fieldsMatcher.match(field) ? in.getPointValues(field) : null;
  }

  @Override
  public Terms terms(String field) throws IOException {
    return fieldsMatcher.match(field) ? in.terms(field) : null;
  }

  @Override
  public LeafMetaData getMetaData() {
    return in.getMetaData();
  }

  @Override
  public Bits getLiveDocs() {
    return in.getLiveDocs();
  }

  @Override
  public int numDocs() {
    return in.numDocs();
  }

  @Override
  public LeafReader getDelegate() {
    return in;
  }

  @Override
  public void document(int docID, StoredFieldVisitor visitor) throws IOException {
    super.document(docID, new StoredFieldVisitor() {

      @Override
      public Status needsField(FieldInfo fieldInfo) throws IOException {
        return fieldsMatcher.match(fieldInfo.name) ? visitor.needsField(fieldInfo) : Status.NO;
      }

      @Override
      public int hashCode() {
        return visitor.hashCode();
      }

      @Override
      public void stringField(FieldInfo fieldInfo, byte[] value) throws IOException {
        visitor.stringField(fieldInfo, value);
      }

      @Override
      public boolean equals(Object obj) {
        return visitor.equals(obj);
      }

      @Override
      public void doubleField(FieldInfo fieldInfo, double value) throws IOException {
        visitor.doubleField(fieldInfo, value);
      }

      @Override
      public void floatField(FieldInfo fieldInfo, float value) throws IOException {
        visitor.floatField(fieldInfo, value);
      }

      @Override
      public void intField(FieldInfo fieldInfo, int value) throws IOException {
        visitor.intField(fieldInfo, value);
      }

      @Override
      public void longField(FieldInfo fieldInfo, long value) throws IOException {
        visitor.longField(fieldInfo, value);
      }

      @Override
      public void binaryField(FieldInfo fieldInfo, byte[] value) throws IOException {
        if (!"_source".equals(fieldInfo.name)) {
          visitor.binaryField(fieldInfo, value);
          return;
        }
        Tuple<XContentType, Map<String, Object>> xContentTypeMapTuple = XContentHelper.convertToMap(new BytesArray(value), false, XContentType.JSON);
        Map<String, Object> map = xContentTypeMapTuple.v2();

        Iterator<String> it = map.keySet().iterator();
        while (it.hasNext()) {
          if (!fieldsMatcher.match(it.next())) {
            it.remove();
          }
        }

        final XContentBuilder xBuilder = XContentBuilder.builder(xContentTypeMapTuple.v1().xContent()).map(map);
        visitor.binaryField(fieldInfo, BytesReference.toBytes(xBuilder.bytes()));
      }
    });
  }

  @Override
  public CacheHelper getCoreCacheHelper() {
    return this.in.getCoreCacheHelper();
  }

  @Override
  public CacheHelper getReaderCacheHelper() {
    return this.in.getReaderCacheHelper();
  }

  private static final class DocumentFieldDirectorySubReader extends FilterDirectoryReader.SubReaderWrapper {

    private final Set<String> fields;

    public DocumentFieldDirectorySubReader(Set<String> fields) {
      this.fields = fields;
    }

    @Override
    public LeafReader wrap(LeafReader reader) {
      try {
        return new DocumentFieldReader(reader, fields);
      } catch (Exception e) {
        throw ExceptionsHelper.convertToElastic(e);
      }
    }
  }

  public static final class DocumentFieldDirectoryReader extends FilterDirectoryReader {

    private final Set<String> fields;

    DocumentFieldDirectoryReader(DirectoryReader in, Set<String> fields) throws IOException {
      super(in, new DocumentFieldReader.DocumentFieldDirectorySubReader(fields));
      this.fields = fields;
    }

    @Override
    protected DirectoryReader doWrapDirectoryReader(DirectoryReader in) throws IOException {
      return new DocumentFieldReader.DocumentFieldDirectoryReader(in, fields);
    }

    @Override
    public CacheHelper getReaderCacheHelper() {
      return this.in.getReaderCacheHelper();
    }

  }
}
