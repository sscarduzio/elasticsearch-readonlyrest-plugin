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
package org.elasticsearch.plugin.readonlyrest.wiring;

import com.google.common.collect.ImmutableMap;
import junit.framework.TestCase;
import org.elasticsearch.plugin.readonlyrest.wiring.requestcontext.VariablesManager;

/**
 * Created by sscarduzio on 04/05/2017.
 */
public class VariablesManagerTest extends TestCase {

   public void testSimple(){
     VariablesManager vm = new VariablesManager(ImmutableMap.<String, String>builder()
                                                  .put("key1", "x")
                                                  .build()
     );
     assertEquals("x", vm.apply("@key1"));
  }
  public void testNoReplacement(){
     VariablesManager vm = new VariablesManager(ImmutableMap.<String, String>builder()
                                                  .put("key1", "x")
                                                  .build()
     );
     assertEquals("@nonexistent", vm.apply("@nonexistent"));
  }
  public void testUpperHeadersLowerVar(){
     VariablesManager vm = new VariablesManager(ImmutableMap.<String, String>builder()
                                                  .put("key1", "x")
                                                  .build()
     );
     assertEquals("x", vm.apply("@key1"));
  }

  public void testMessyOriginal(){
    VariablesManager vm = new VariablesManager(ImmutableMap.<String, String>builder()
                                                 .put("key1", "x")
                                                 .build()
    );
    assertEquals("@@@x", vm.apply("@@@@key1"));
    assertEquals("@one@twox@three@@@", vm.apply("@one@two@key1@three@@@"));
    assertEquals(".@one@two.x@three@@@", vm.apply(".@one@two.@key1@three@@@"));
  }
}
