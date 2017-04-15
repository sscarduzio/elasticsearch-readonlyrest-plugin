package org.elasticsearch.plugin.readonlyrest.acl.requestcontext;

import com.google.common.collect.Sets;
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.MatcherWithWildcards;

import java.util.regex.Pattern;

/**
 * Created by sscarduzio on 14/04/2017.
 */
public class RCUtils {
  /*
    * A regular expression to match the various representations of "localhost"
    */

  static final String LOCALHOST = "127.0.0.1";
  private static final Pattern localhostRe = Pattern.compile("^(127(\\.\\d+){1,3}|[0:]+1)$");
  private static MatcherWithWildcards readRequestMatcher = new MatcherWithWildcards(Sets.newHashSet(
      "cluster:monitor/*",
      "cluster:*get*",
      "cluster:*search*",
      "indices:admin/aliases/exsists",
      "indices:admin/aliases/get",
      "indices:admin/exists*",
      "indices:admin/get*",
      "indices:admin/mappings/fields/get*",
      "indices:admin/mappings/get*",
      "indices:admin/refresh*",
      "indices:admin/types/exists",
      "indices:admin/validate/*",
      "indices:data/read/*"
  ));

  static boolean isReadRequest(String action) {
    return readRequestMatcher.match(action);
  }

  static boolean isLocalHost(String remoteHost) {
    return localhostRe.matcher(remoteHost).find();
  }

  public static class RRContextException extends ElasticsearchException {
    public RRContextException(String reason) {
      super(reason);
    }

    public RRContextException(String reason, Throwable cause) {
      super(reason, cause);
    }
  }

}
