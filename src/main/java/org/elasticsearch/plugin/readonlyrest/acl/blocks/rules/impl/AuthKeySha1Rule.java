package org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl;

import com.google.common.base.Charsets;
import com.google.common.hash.Hashing;
import org.elasticsearch.ElasticsearchParseException;
import org.elasticsearch.common.Base64;
import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.RuleNotConfiguredException;

import java.io.IOException;

/**
 * Created by sscarduzio on 13/02/2016.
 */
public class AuthKeySha1Rule extends AuthKeyRule {

  private final static ESLogger logger = Loggers.getLogger(AuthKeySha1Rule.class);

  public AuthKeySha1Rule(Settings s) throws RuleNotConfiguredException {
    super(s);
    try {
      authKey = new String(Base64.decode(authKey),Charsets.UTF_8);
    } catch (IOException e) {
      throw new ElasticsearchParseException("cannot parse configuration for: " + this.KEY);
    }
  }

  @Override
  protected boolean checkEqual(String provided) {
    try {
      String decodedProvided = new String(Base64.decode(provided), Charsets.UTF_8);
      String shaProvided = Hashing.sha1().hashString(decodedProvided, Charsets.UTF_8).toString();
      return authKey.equals(shaProvided);
    } catch (IOException e) {
      return false;
    }
  }
}
