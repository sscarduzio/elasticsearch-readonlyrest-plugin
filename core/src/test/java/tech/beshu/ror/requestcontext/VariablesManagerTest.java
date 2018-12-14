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
package tech.beshu.ror.requestcontext;

import com.google.common.collect.ImmutableMap;
import org.junit.Test;
import org.mockito.Mockito;
import tech.beshu.ror.commons.domain.__old_LoggedUser;
import tech.beshu.ror.mocks.MockedESContext;

import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.when;


/**
 * Created by sscarduzio on 04/05/2017.
 */
public class VariablesManagerTest {

  @Test
  public void testSimple() {
    VariablesManager vm = new VariablesManager(
      ImmutableMap.<String, String>builder().put("key1", "x").build(),
      getMock(Optional.of("simone")),
      MockedESContext.INSTANCE
    );
    assertEquals(Optional.of("x"), vm.apply("@{key1}"));
  }

  @Test
  public void testSimpleWithUser() {
    VariablesManager vm = new VariablesManager(
      ImmutableMap.<String, String>builder().build(),
      getMock(Optional.of("simone")),
      MockedESContext.INSTANCE
    );
    assertEquals(Optional.of("simone"), vm.apply("@{user}"));
  }

  @Test
  public void testNoReplacement() {
    VariablesManager vm = new VariablesManager(
      ImmutableMap.<String, String>builder().put("key1", "x").build(),
      getMock(Optional.of("simone")),
      MockedESContext.INSTANCE
    );
    assertEquals(Optional.empty(), vm.apply("@{nonexistent}"));
  }

  @Test
  public void testUpperHeadersLowerVar() {
    VariablesManager vm = new VariablesManager(
      ImmutableMap.<String, String>builder().put("KEY1", "x").build(),
      getMock(Optional.of("simone")),
      MockedESContext.INSTANCE
    );
    assertEquals(Optional.of("x"), vm.apply("@{key1}"));
  }

  @Test
  public void testMessyOriginal() {
    VariablesManager vm = new VariablesManager(
      ImmutableMap.<String, String>builder().put("key1", "x").build(),
      getMock(Optional.of("simone")),
      MockedESContext.INSTANCE
    );
    assertEquals(Optional.of("@@@x"), vm.apply("@@@@{key1}"));
    assertEquals(Optional.of("@one@twox@three@@@"), vm.apply("@one@two@{key1}@three@@@"));
    assertEquals(Optional.of(".@one@two.x@three@@@"), vm.apply(".@one@two.@{key1}@three@@@"));
  }

  private __old_RequestContext getMock(Optional<String> userName) {
    __old_RequestContext rc = Mockito.mock(__old_RequestContext.class);
    when(rc.getLoggedInUser()).thenReturn(userName.map(__old_LoggedUser::new));
    return rc;
  }

}
