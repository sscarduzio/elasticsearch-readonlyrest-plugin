package org.elasticsearch.rest.action.readonlyrest.acl.test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Paths;

import com.google.common.base.Charsets;
import org.elasticsearch.common.logging.ESLoggerFactory;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.rest.RestRequest.Method;
import org.elasticsearch.rest.action.readonlyrest.acl.ACL;
import org.elasticsearch.rest.action.readonlyrest.acl.ACLRequest;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

public class MarvelPassthroughACL {
    private static ACL acl;

    @BeforeClass
    public static void setUpBeforeClass() throws Exception {
        try {
            byte[] encoded = Files.readAllBytes(Paths.get(System.getProperty("user.dir") + "/src/test/marvel_passthrough.yml"));
            String str = Charsets.UTF_8.decode(ByteBuffer.wrap(encoded)).toString();
            Settings s = Settings.builder().loadFromSource(str).build();
            acl = new ACL(ESLoggerFactory.getLogger(ACL.class.getName()), s);
        }
        catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Test
    public final void testInternalMethods(){
        Assert.assertNull(acl.check( new ACLRequest(".marvel-2015.11.10/_search", "127.0.0.1", "", "", 0, Method.POST, null)));
    }

}
