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

package tech.beshu.ror.commons.utils;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.Base64;

/*
 * @author Datasweet <contact@datasweet.fr>
 */
// We created a class to store the filter instead of using a String var
// because we'll use it in further updates for the field level security
public class FilterTransient implements Serializable {
  private static final long serialVersionUID = -8866625802695512997L;
  private final String _filter;

  private FilterTransient(String filter) {
    this._filter = filter;
  }

  public static FilterTransient createFromFilter(String filter) {
    return new FilterTransient(filter);
  }

  public static FilterTransient Deserialize(String userTransientEncoded) {
    FilterTransient userTransient = null;
    if (userTransientEncoded == null)
      return userTransient;
    try {
      byte[] data = Base64.getDecoder().decode(userTransientEncoded);
      ObjectInputStream ois;
      ois = new ObjectInputStream(new ByteArrayInputStream(data));
      Object o = ois.readObject();
      if (o instanceof FilterTransient) {
        userTransient = (FilterTransient) o;
      }
      ois.close();
    } catch (IOException e) {
      throw new IllegalStateException("Couldn't extract userTransient from threadContext.");
    } catch (ClassNotFoundException e) {
      throw new IllegalStateException("Couldn't extract userTransient from threadContext.");
    }
    return userTransient;

  }

  public String getFilter() {
    return this._filter;
  }

  @Override
  public String toString() {
    return "{ "
        + "FILTER: " + this._filter
        + "}";
  }

  public String serialize() {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    ObjectOutputStream oos;
    try {
      oos = new ObjectOutputStream(baos);
      oos.writeObject(this);
      oos.close();
    } catch (IOException e) {
      return null;
    }
    return Base64.getEncoder().encodeToString(baos.toByteArray());
  }
}