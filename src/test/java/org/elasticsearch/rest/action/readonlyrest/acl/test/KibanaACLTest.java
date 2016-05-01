package org.elasticsearch.rest.action.readonlyrest.acl.test;

import org.elasticsearch.plugin.readonlyrest.acl.ACL;
import org.elasticsearch.plugin.readonlyrest.acl.RequestContext;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.Block;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.BlockExitResult;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class KibanaACLTest {
  private static ACL acl;

  @BeforeClass
  public static void setUpBeforeClass() throws Exception {
    acl = ACLTest.mkACL("/src/test/kibana_test_rules.yml");
  }

  @Test
  public final void testKibanaROClusterAction() throws Throwable {
    RequestContext rc = ACLTest.mockReq("xyz", "1.1.1.1", "", "", 0, null, null, new String[]{"random-idx"}, "cluster:monitor/health");
    BlockExitResult res = acl.check(rc);
    assertTrue(res.isMatch());
    assertTrue(res.getBlock().getPolicy() == Block.Policy.ALLOW);
    assertEquals(res.getBlock().getName(), "1");
  }

  @Test
  public final void testKibanaROreadAction() throws Throwable {
    RequestContext rc = ACLTest.mockReq("xyz", "1.1.1.1", "", "", 0, null, null, new String[]{"random-idx"}, "indices:admin/get");
    BlockExitResult res = acl.check(rc);
    assertTrue(res.isMatch());
    assertTrue(res.getBlock().getPolicy() == Block.Policy.ALLOW);
    assertEquals(res.getBlock().getName(), "1");
  }
  @Test
  public final void testKibanaROwriteKibanaDevNull() throws Throwable {
    RequestContext rc = ACLTest.mockReq("xyz", "1.1.1.1", "", "", 0, null, null, new String[]{".kibana-devnull"}, "indices:data/write/update");
    BlockExitResult res = acl.check(rc);
    assertTrue(res.isMatch());
    assertTrue(res.getBlock().getPolicy() == Block.Policy.ALLOW);
    assertEquals(res.getBlock().getName(), "1");
  }

 @Test
  public final void testKibanaROwriteAction_FORBID() throws Throwable {
    RequestContext rc = ACLTest.mockReq("xyz", "1.1.1.1", "", "", 0, null, null, new String[]{"random-idx"}, "indices:data/write/update");
    BlockExitResult res = acl.check(rc);
    assertFalse(res.isMatch());
  }

  @Test
  public final void testKibanaRWreadAction() throws Throwable {
    RequestContext rc = ACLTest.mockReq("xyz", "2.2.2.2", "", "", 0, null, null, new String[]{"random-idx"}, "indices:admin/get");
    BlockExitResult res = acl.check(rc);
    assertTrue(res.isMatch());
    assertTrue(res.getBlock().getPolicy() == Block.Policy.ALLOW);
    assertEquals(res.getBlock().getName(), "2");
  }
  @Test
  public final void testKibanaRWwriteAction() throws Throwable {
    RequestContext rc = ACLTest.mockReq("xyz", "2.2.2.2", "", "", 0, null, null, new String[]{"random-idx"}, "indices:data/write/update");
    BlockExitResult res = acl.check(rc);
    assertTrue(res.isMatch());
    assertTrue(res.getBlock().getPolicy() == Block.Policy.ALLOW);
    assertEquals(res.getBlock().getName(), "2");
  }
  @Test
  public final void testKibanaRWClusterAction() throws Throwable {
    RequestContext rc = ACLTest.mockReq("xyz", "2.2.2.2", "", "", 0, null, null, new String[]{"random-idx"}, "cluster:monitor/health");
    BlockExitResult res = acl.check(rc);
    assertTrue(res.isMatch());
    assertTrue(res.getBlock().getPolicy() == Block.Policy.ALLOW);
    assertEquals(res.getBlock().getName(), "2");
  }
  @Test
  public final void testKibanaRWClusterActionOnKibanaIdx() throws Throwable {
    RequestContext rc = ACLTest.mockReq("xyz", "2.2.2.2", "", "", 0, null, null, new String[]{".kibana"}, "cluster:monitor/health");
    BlockExitResult res = acl.check(rc);
    assertTrue(res.isMatch());
    assertTrue(res.getBlock().getPolicy() == Block.Policy.ALLOW);
    assertEquals(res.getBlock().getName(), "2");
  }

  @Test
  public final void testKibanaR0writeDashboard() throws Throwable {
    RequestContext rc = ACLTest.mockReq("xyz", "1.1.1.1", "", "", 0, null, null, new String[]{".kibana"}, "indices:data/write/update");
    BlockExitResult res = acl.check(rc);
    assertFalse(res.isMatch());
  }

  @Test
  public final void testKibanaRWwriteDashboard() throws Throwable {
    RequestContext rc = ACLTest.mockReq("xyz", "2.2.2.2", "", "", 0, null, null, new String[]{".kibana"}, "indices:data/write/update");
    BlockExitResult res = acl.check(rc);
    assertTrue(res.isMatch());
    assertTrue(res.getBlock().getPolicy() == Block.Policy.ALLOW);
    assertEquals(res.getBlock().getName(), "2");
  }

 @Test
  public final void testKibanaR0PlusWriteDashboard() throws Throwable {
    RequestContext rc = ACLTest.mockReq("xyz", "3.3.3.3", "", "", 0, null, null, new String[]{".kibana"}, "indices:data/write/update");
    BlockExitResult res = acl.check(rc);
    assertTrue(res.isMatch());
    assertTrue(res.getBlock().getPolicy() == Block.Policy.ALLOW);
    assertEquals(res.getBlock().getName(), "3");
  }

  @Test
  public final void testKibanaR0PlusWriteKibanaDevnull() throws Throwable {
    RequestContext rc = ACLTest.mockReq("xyz", "3.3.3.3", "", "", 0, null, null, new String[]{".kibana-devnull"}, "indices:data/write/update");
    BlockExitResult res = acl.check(rc);
    assertTrue(res.isMatch());
    assertTrue(res.getBlock().getPolicy() == Block.Policy.ALLOW);
    assertEquals(res.getBlock().getName(), "3");
  }

  @Test
  public final void testKibanaR0WriteKibanaDevnull() throws Throwable {
    RequestContext rc = ACLTest.mockReq("xyz", "3.3.3.3", "", "", 0, null, null, new String[]{".kibana-devnull"}, "indices:data/write/update");
    BlockExitResult res = acl.check(rc);
    assertTrue(res.isMatch());
    assertTrue(res.getBlock().getPolicy() == Block.Policy.ALLOW);
    assertEquals(res.getBlock().getName(), "3");
  }

  @Test
  public final void testKibanaR0WriteDashboardCustomKibanaIdx() throws Throwable {
    RequestContext rc = ACLTest.mockReq("xyz", "4.4.4.4", "", "", 0, null, null, new String[]{"custom-kibana-idx"}, "indices:data/write/update");
    BlockExitResult res = acl.check(rc);
    assertTrue(res.isMatch());
    assertTrue(res.getBlock().getPolicy() == Block.Policy.ALLOW);
    assertEquals(res.getBlock().getName(), "4");
  }

}