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

import org.junit.Test;
import tech.beshu.ror.mocks.MockedESContext;

import static org.junit.Assert.assertEquals;

/**
 * Created by sscarduzio on 14/04/2017.
 */
public class TransactionalTest {

  @Test
  public void testTransactional() {
    __old_Transactional<String> tv = new __old_Transactional<String>("tv", MockedESContext.INSTANCE) {

      @Override
      public String initialize() {
        return "first";
      }

      @Override
      public String copy(String initial) {
        return initial;
      }

      @Override
      public void onCommit(String value) {
        assertEquals("second", value);
      }
    };
    assertEquals("first", tv.get());
    assertEquals("first", tv.getInitial());
    tv.mutate("second");
    assertEquals("first", tv.getInitial());
    assertEquals("second", tv.get());
    tv.commit();
    assertEquals("second", tv.get());
    assertEquals("first", tv.getInitial());
  }

  @Test(expected = RuntimeException.class)
  public void testThrowOnDoubleCommit() {
    __old_Transactional<String> tv = new __old_Transactional<String>("tv", MockedESContext.INSTANCE) {

      @Override
      public String initialize() {
        return "first";
      }

      @Override
      public String copy(String initial) {
        return initial;
      }

      @Override
      public void onCommit(String value) {
        assertEquals("second", value);
      }
    };
    tv.commit();
    tv.commit();
  }

  @Test
  public void testReset() {
    __old_Transactional<String> tv = new __old_Transactional<String>("tv", MockedESContext.INSTANCE) {

      @Override
      public String initialize() {
        return "first";
      }

      @Override
      public String copy(String initial) {
        return initial;
      }

      @Override
      public void onCommit(String value) {
        assertEquals("second", value);
      }
    };

    tv.mutate("second");
    assertEquals("second", tv.get());
    tv.reset();
    assertEquals("first", tv.get());
    tv.mutate("third");
    assertEquals("third", tv.get());
  }

  @Test(expected = RuntimeException.class)
  public void testDelegate() {
    __old_Transactional<String> tv = new __old_Transactional<String>("tv", MockedESContext.INSTANCE) {

      @Override
      public String initialize() {
        return "first";
      }

      @Override
      public String copy(String initial) {
        return initial;
      }

      @Override
      public void onCommit(String value) {
      }
    };

    __old_Transactional<Integer> tvi = new __old_Transactional<Integer>("tvi", MockedESContext.INSTANCE) {

      @Override
      public Integer initialize() {
        return 1;
      }

      @Override
      public Integer copy(Integer initial) {
        return initial;
      }

      @Override
      public void onCommit(Integer value) {
        System.out.println("you should be reading this");
      }
    };

    tvi.delegateTo(tv);

    tv.mutate("second");
    tvi.mutate(3);
    tv.commit();
    tvi.commit();
  }

  @Test
  public void testDelegateReset() {
    __old_Transactional<String> tv = new __old_Transactional<String>("tv", MockedESContext.INSTANCE) {

      @Override
      public String initialize() {
        return "first";
      }

      @Override
      public String copy(String initial) {
        return initial;
      }

      @Override
      public void onCommit(String value) {
      }
    };

    __old_Transactional<Integer> tvi = new __old_Transactional<Integer>("tvi", MockedESContext.INSTANCE) {

      @Override
      public Integer initialize() {
        return 1;
      }

      @Override
      public Integer copy(Integer initial) {
        return initial;
      }

      @Override
      public void onCommit(Integer value) {
        System.out.println("you should be reading this");
      }
    };

    tvi.delegateTo(tv);

    tv.mutate("second");
    tvi.mutate(3);
    tvi.commit();

    tv.reset();
    tvi.commit();
  }
}
