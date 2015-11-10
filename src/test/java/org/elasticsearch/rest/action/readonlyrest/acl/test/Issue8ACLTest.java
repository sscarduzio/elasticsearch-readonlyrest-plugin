package org.elasticsearch.rest.action.readonlyrest.acl.test;

import com.google.common.base.Charsets;
import org.elasticsearch.common.logging.ESLoggerFactory;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.rest.action.readonlyrest.acl.ACL;
import org.elasticsearch.rest.action.readonlyrest.acl.RuleConfigurationError;
import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Paths;

public class Issue8ACLTest {

  @Test(expected=RuleConfigurationError.class)
  public final void testRuleConfigurationError() throws Throwable{
    byte[] encoded = Files.readAllBytes(Paths.get(System.getProperty("user.dir") + "/src/test/issue8.yml"));
    String str = Charsets.UTF_8.decode(ByteBuffer.wrap(encoded)).toString();
    Settings s = Settings.builder().loadFromSource(str).build();
    new ACL(ESLoggerFactory.getLogger(ACL.class.getName()), s);
  }

}
