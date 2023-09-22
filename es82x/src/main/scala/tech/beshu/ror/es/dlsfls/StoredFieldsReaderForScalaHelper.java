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
package tech.beshu.ror.es.dlsfls;

import org.apache.lucene.codecs.StoredFieldsReader;
import org.apache.lucene.index.StoredFieldVisitor;

import java.io.IOException;

// hack: we need this class because when we try to call `clone` from Scala, the java.Object#clone is called and
//       compiler tells us that protected method cannot be called. The method is overridden in the StoredFieldsReader
//       class and should be visible as a public one. But it's not. That's why we need this helper.
public class StoredFieldsReaderForScalaHelper extends StoredFieldsReader {

  private final StoredFieldsReader underlying;

  public StoredFieldsReaderForScalaHelper(StoredFieldsReader underlying) {
    this.underlying = underlying;
  }

  @Override
  public void visitDocument(int docID, StoredFieldVisitor visitor) throws IOException {
    underlying.visitDocument(docID, visitor);
  }

  @Override
  public StoredFieldsReader clone() {
    return new StoredFieldsReaderForScalaHelper(cloneUnderlying());
  }

  @Override
  public void checkIntegrity() throws IOException {
    underlying.checkIntegrity();
  }

  @Override
  public void close() throws IOException {
    underlying.close();
  }

  public StoredFieldsReader cloneUnderlying() {
    return underlying.clone();
  }
}
