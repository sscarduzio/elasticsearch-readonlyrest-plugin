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
package org.elasticsearch.plugin.readonlyrest;

/**
 * Created by sscarduzio on 18/07/2017.
 */
public class ESVersion {

  public static final ESVersion V_5_0_0 = new ESVersion(5000099);

  public static final ESVersion V_5_1_1 = new ESVersion(5010199);

  public static final ESVersion V_5_2_0 = new ESVersion(5020099);

  public static final ESVersion V_5_3_0 = new ESVersion(5030099);

  public static final ESVersion V_5_4_0 = new ESVersion(5040099);


  private final int id;

  public ESVersion(int id) {
    this.id = id;
  }

  public boolean after(ESVersion ESVersion) {
    return ESVersion.id < this.id;
  }

  public boolean onOrAfter(ESVersion ESVersion) {
    return ESVersion.id <= this.id;
  }

  public boolean before(ESVersion ESVersion) {
    return ESVersion.id > this.id;
  }

  public boolean onOrBefore(ESVersion ESVersion) {
    return ESVersion.id >= this.id;
  }
}
