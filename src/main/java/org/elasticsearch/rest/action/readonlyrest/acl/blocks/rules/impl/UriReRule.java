package org.elasticsearch.rest.action.readonlyrest.acl.blocks.rules.impl;

import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.rest.RestChannel;
import org.elasticsearch.rest.RestRequest;
import org.elasticsearch.rest.action.readonlyrest.ConfigurationHelper;
import org.elasticsearch.rest.action.readonlyrest.acl.RuleConfigurationError;
import org.elasticsearch.rest.action.readonlyrest.acl.blocks.rules.Rule;
import org.elasticsearch.rest.action.readonlyrest.acl.blocks.rules.RuleExitResult;
import org.elasticsearch.rest.action.readonlyrest.acl.blocks.rules.RuleNotConfiguredException;

import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Created by sscarduzio on 13/02/2016.
 */
public class UriReRule extends Rule {

  private Pattern uri_re = null;

  public UriReRule(Settings s) throws RuleNotConfiguredException {
    super(s);

    String tmp = s.get(KEY);
    if (!ConfigurationHelper.isNullOrEmpty(tmp)) {
      try{
        uri_re = Pattern.compile(tmp.trim());
      }
      catch (PatternSyntaxException e) {
        throw new RuleConfigurationError("invalid 'uri_re' regexp", e);
      }

    }
    else {
      throw new RuleNotConfiguredException();
    }
  }

  @Override
  public RuleExitResult match(RestRequest request, RestChannel channel) {
    if (uri_re == null) {
      return NO_MATCH;
    }
    return uri_re.matcher(request.uri()).find() ? MATCH : NO_MATCH;
  }
}
