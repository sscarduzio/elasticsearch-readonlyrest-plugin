package org.elasticsearch.rest.action.readonlyrest.acl.test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Paths;

import org.elasticsearch.common.base.Charsets;
import org.elasticsearch.common.logging.ESLoggerFactory;
import org.elasticsearch.common.settings.ImmutableSettings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.rest.RestRequest.Method;
import org.elasticsearch.rest.action.readonlyrest.acl.ACL;
import org.elasticsearch.rest.action.readonlyrest.acl.ACLRequest;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

public class ACLTest {

  @BeforeClass
  public static void setUpBeforeClass() throws Exception {
  }

  private ACL acl;

  @Before
  public void setUp() throws Exception {
    try {
      byte[] encoded = Files.readAllBytes(Paths.get(System.getProperty("user.dir") + "/src/test/three_rules.yml"));
      String str = Charsets.UTF_8.decode(ByteBuffer.wrap(encoded)).toString();
      Settings s = ImmutableSettings.builder().loadFromSource(str).build();
      acl = new ACL(ESLoggerFactory.getLogger(ACL.class.getName()), s);
    }
    catch (IOException e) {
      e.printStackTrace();
    }
 
  }

  @After
  public void tearDown() throws Exception {
  }

  @Test
  public final void testACL() {
  }

  @Test
  public final void testCheck() {
    ACLRequest ar = new ACLRequest("http://es/index1/_search?q=item.name:fishingpole&size=200", "1.1.1.1", 0, Method.GET);
    Assert.assertNull(acl.check(ar));
    ar = new ACLRequest("http://es/index1/_search?q=item.name:fishingpole&size=200", "1.1.1.1", 0, Method.POST);
    Assert.assertNotNull(acl.check(ar));;
  }

}
