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

package tech.beshu.ror.es.security;

import com.google.common.collect.Iterators;
import org.apache.lucene.index.BinaryDocValues;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.FieldInfos;
import org.apache.lucene.index.Fields;
import org.apache.lucene.index.FilterDirectoryReader;
import org.apache.lucene.index.FilterLeafReader;
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
import tech.beshu.ror.acl.blocks.rules.impl.FieldsSyncRule;

import java.io.IOException;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class DocumentFieldReader extends FilterLeafReader {
  private final FieldInfos remainingFieldsInfo;
  private FieldsSyncRule.FieldPolicy policy;

  private DocumentFieldReader(LeafReader reader, Set<String> fields) {
    super(reader);
    this.policy = new FieldsSyncRule.FieldPolicy(fields);
    FieldInfos fInfos = in.getFieldInfos();
    Set<String> baseFields = new HashSet<>(fInfos.size());
    for (FieldInfo f : fInfos) {
      baseFields.add(f.name);
    }
    Set<String> remainingFields = baseFields.stream().filter(x -> policy.canKeep(x)).collect(Collectors.toSet());
    this.remainingFieldsInfo = new FieldInfos(remainingFields.toArray(new FieldInfo[remainingFields.size()]));

  }

  @Override
  public FieldInfos getFieldInfos() {
    return remainingFieldsInfo;
  }

  public static DocumentFieldDirectoryReader wrap(DirectoryReader in, Set<String> fields) throws IOException {
    return new DocumentFieldDirectoryReader(in, fields);
  }

  @Override
  public Fields getTermVectors(int docID) throws IOException {
    Fields original = in.getTermVectors(docID);

    return new Fields() {
      @Override
      public Iterator<String> iterator() {
        return Iterators.filter(original.iterator(), s -> policy.canKeep(s));
      }

      @Override
      public Terms terms(String field) throws IOException {
        return policy.canKeep(field) ? original.terms(field) : null;
      }

      @Override
      public int size() {
        return remainingFieldsInfo.size();
      }
    };
  }

  @Override
  public NumericDocValues getNumericDocValues(String field) throws IOException {
    return policy.canKeep(field) ? in.getNumericDocValues(field) : null;
  }

  @Override
  public BinaryDocValues getBinaryDocValues(String field) throws IOException {
    return policy.canKeep(field) ? in.getBinaryDocValues(field) : null;
  }

  @Override
  public NumericDocValues getNormValues(String field) throws IOException {
    return policy.canKeep(field) ? in.getNormValues(field) : null;
  }

  @Override
  public SortedDocValues getSortedDocValues(String field) throws IOException {
    return policy.canKeep(field) ? in.getSortedDocValues(field) : null;
  }

  @Override
  public SortedNumericDocValues getSortedNumericDocValues(String field) throws IOException {
    return policy.canKeep(field) ? in.getSortedNumericDocValues(field) : null;
  }

  @Override
  public SortedSetDocValues getSortedSetDocValues(String field) throws IOException {
    return policy.canKeep(field) ? in.getSortedSetDocValues(field) : null;
  }

  @Override
  public PointValues getPointValues() {
    return in.getPointValues();
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
  public Bits getDocsWithField(String field) throws IOException {
    return policy.canKeep(field) ? in.getDocsWithField(field) : null;
  }

  @Override
  public void document(int docID, StoredFieldVisitor visitor) throws IOException {
    super.document(docID, new StoredFieldVisitor() {

      @Override
      public Status needsField(FieldInfo fieldInfo) throws IOException {
        return policy.canKeep(fieldInfo.name) ? visitor.needsField(fieldInfo) : Status.NO;
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
          if (!policy.canKeep(it.next())) {
            it.remove();
          }
        }

        final XContentBuilder xBuilder = XContentBuilder.builder(xContentTypeMapTuple.v1().xContent()).map(map);
        visitor.binaryField(fieldInfo, BytesReference.toBytes(xBuilder.bytes()));
      }
    });
  }

  @Override
  public Object getCoreCacheKey() {
    return in.getCoreCacheKey();
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
      super(in, new DocumentFieldDirectorySubReader(fields));
      this.fields = fields;
    }

    @Override
    protected DirectoryReader doWrapDirectoryReader(DirectoryReader in) throws IOException {
      return new DocumentFieldDirectoryReader(in, fields);
    }

    @Override
    public Object getCoreCacheKey() {
      return in.getCoreCacheKey();
    }
  }
}
