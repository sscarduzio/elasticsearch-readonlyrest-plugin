package org.elasticsearch.rest.action.readonlyrest.acl.test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Paths;

import com.google.common.base.Charsets;
import org.elasticsearch.common.Base64;
import org.elasticsearch.common.logging.ESLoggerFactory;
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
    private static ACL acl;

    @BeforeClass
    public static void setUpBeforeClass() throws Exception {
        try {
            byte[] encoded = Files.readAllBytes(Paths.get(System.getProperty("user.dir") + "/src/test/three_rules.yml"));
            String str = Charsets.UTF_8.decode(ByteBuffer.wrap(encoded)).toString();
            Settings s = Settings.builder().loadFromSource(str).build();
            acl = new ACL(ESLoggerFactory.getLogger(ACL.class.getName()), s);
        } catch (IOException e) {
            e.printStackTrace();
        }

    }

    @Before
    public void setUp() throws Exception {

    }

    @After
    public void tearDown() throws Exception {
    }

    @Test
    public final void testExternalGet() {
        ACLRequest ar = new ACLRequest("http://es/index1/_search?q=item.name:fishingpole&size=200", "1.1.1.1", "","", 0, Method.GET,null);
        Assert.assertNull(acl.check(ar));
    }

    @Test
    public final void testExternalPost() {
        Assert.assertNotNull(acl.check(new ACLRequest("http://es/index1/_search?q=item.name:fishingpole&size=200", "1.1.1.1", "","", 0, Method.POST, null)));
    }

    @Test
    public final void testExternalURIRegEx() {
        Assert.assertNotNull(acl.check(new ACLRequest("http://localhost:9200/reservedIdx/_search?q=item.name:fishingpole&size=200", "1.1.1.1", "","", 0, Method.GET, null)));
    }

    @Test
    public final void testExternalMatchAddress() {
        Assert.assertNull(acl.check(new ACLRequest("http://es/index1/_search?q=item.name:fishingpole&size=200", "127.0.0.1", "","", 0, Method.GET, null)));
    }

    @Test
    public final void testExternalWithBody() {
        Assert.assertNotNull(acl.check(new ACLRequest("http://es/index1/_search?q=item.name:fishingpole&size=200", "1.1.1.1", "","", 20, Method.GET, null)));
    }

    @Test
    public final void testExternalMethods() {
        Assert.assertNull(acl.check(new ACLRequest("http://es/index1/_search?q=item.name:fishingpole&size=200", "1.1.1.1", "", "", 0, Method.OPTIONS, null)));
    }

    @Test
    public final void testInternalMethods() {
        Assert.assertNull(acl.check(new ACLRequest("http://es/index1/_search?q=item.name:fishingpole&size=200", "127.0.0.1", "","", 0, Method.HEAD, null)));
    }

    @Test
    public final void testNetMask() {
        Assert.assertNull(acl.check(new ACLRequest("http://es/index1/_search?q=item.name:fishingpole&size=200", "192.168.1.5", "","", 0, Method.POST, null)));
    }

    @Test
    public final void testApiKey() {
        Assert.assertNull(acl.check(new ACLRequest("http://es/index1/_search?q=item.name:fishingpole&size=200", "1.1.1.1", "1234567890","", 0, Method.POST, null)));
    }

    @Test
    public final void testHttpBasicAuth() {
        String secret64 = Base64.encodeBytes("1234567890".getBytes(Charsets.UTF_8));
        Assert.assertNull(acl.check(new ACLRequest("http://es/index1/_search?q=item.name:fishingpole&size=200", "1.1.1.1","", secret64, 0, Method.POST, null)));
    }

    @Test
    public final void testXforwardedForHeader() {
        Assert.assertNull(acl.check(new ACLRequest("http://es/index1/_search?q=item.name:fishingpole&size=200", "1.1.1.1", "","", 0, Method.POST, "9.9.9.9")));
    }


}