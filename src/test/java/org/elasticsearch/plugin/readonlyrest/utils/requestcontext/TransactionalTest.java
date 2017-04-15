package org.elasticsearch.plugin.readonlyrest.utils.requestcontext;

import org.elasticsearch.plugin.readonlyrest.acl.requestcontext.RCUtils;
import org.elasticsearch.plugin.readonlyrest.acl.requestcontext.Transactional;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Created by sscarduzio on 14/04/2017.
 */
public class TransactionalTest {
  @Test
  public void testTransactional() {
    Transactional<String> tv = new Transactional<String>("tv") {

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

  @Test(expected = RCUtils.RRContextException.class)
  public void testThrowOnDoubleCommit() {
    Transactional<String> tv = new Transactional<String>("tv") {

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
    Transactional<String> tv = new Transactional<String>("tv") {

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

  @Test(expected = RCUtils.RRContextException.class)
  public void testDelegate() {
    Transactional<String> tv = new Transactional<String>("tv") {

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

    Transactional<Integer> tvi = new Transactional<Integer>("tvi") {

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
    Transactional<String> tv = new Transactional<String>("tv") {

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

    Transactional<Integer> tvi = new Transactional<Integer>("tvi") {

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
