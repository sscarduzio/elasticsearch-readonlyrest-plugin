package org.elasticsearch.rest.action.readonlyrest.acl.test;

import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.plugin.readonlyrest.acl.ACL;
import org.elasticsearch.plugin.readonlyrest.acl.RequestContext;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.Block;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.BlockExitResult;
import org.elasticsearch.rest.RestRequest;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ScriptACLTest {
  private static Settings s = null;
  private static RequestContext rc = null;

  @BeforeClass
  public static void setUpBeforeClass() throws Throwable {
    s = ACLTest.getSettings("/src/test/script_test_rules.yml");
    rc = ACLTest.mockReq("/path", "1.1.1.1", "", "", 0, RestRequest.Method.PUT, null, new String[]{"index1"}, "action");

  }

  private static ACL setScript(String script) {
    s = Settings.builder().put(s).put("readonlyrest.access_control_rules.0.script", script).build();
    return new ACL(s);
  }

  @Test
  public final void testActionIsRead() throws Throwable {
    BlockExitResult res = setScript("function onRequest(rc){" +
        "  return rc.getAction() == 'action';" +
        "  }").check(rc);
    assertTrue(res.isMatch());
    assertTrue(res.getBlock().getPolicy() == Block.Policy.ALLOW);
    assertEquals("1", res.getBlock().getName());
  }

  @Test
  public final void testOAIsRead() throws Throwable {
    BlockExitResult res = setScript("function onRequest(rc){" +
        "  return rc.getRemoteAddress() == '1.1.1.1'" +
        "  }").check(rc);
    assertTrue(res.isMatch());
    assertTrue(res.getBlock().getPolicy() == Block.Policy.ALLOW);
    assertEquals("1", res.getBlock().getName());
  }

  @Test
  public final void testIndicesIsRead() throws Throwable {
    BlockExitResult res = setScript("function onRequest(rc){" +
        "  return rc.getIndices().length == 1 && rc.getIndices()[0] == 'index1' " +
        "  }").check(rc);
    assertTrue(res.isMatch());
    assertTrue(res.getBlock().getPolicy() == Block.Policy.ALLOW);
    assertEquals("1", res.getBlock().getName());
  }

  @Test
  public final void testMethodIsRead() throws Throwable {
    BlockExitResult res = setScript("function onRequest(rc){" +
        "  return  rc.getRequest().method().toString() == 'PUT'" +
        "  }").check(rc);
    assertTrue(res.isMatch());
    assertTrue(res.getBlock().getPolicy() == Block.Policy.ALLOW);
    assertEquals("1", res.getBlock().getName());
  }

  @Test
  public final void testPathIsRead() throws Throwable {
    BlockExitResult res = setScript("function onRequest(rc){" +
        "  return rc.getRequest().path() == '/path'" +
        "  }").check(rc);

    assertTrue(res.isMatch());
    assertTrue(res.getBlock().getPolicy() == Block.Policy.ALLOW);
    assertEquals("1", res.getBlock().getName());
  }

  @Test
  public final void testContentIsRead() throws Throwable {
    BlockExitResult res = setScript("function onRequest(rc){" +
        "  return rc.getContent() == 'test'" +
        "  }").check(rc);
    assertTrue(res.isMatch());
    assertTrue(res.getBlock().getPolicy() == Block.Policy.ALLOW);
    assertEquals("1", res.getBlock().getName());
  }



}