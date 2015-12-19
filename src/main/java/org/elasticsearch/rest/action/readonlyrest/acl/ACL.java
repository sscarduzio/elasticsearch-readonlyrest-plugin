package org.elasticsearch.rest.action.readonlyrest.acl;

import java.util.Map;
import java.util.TreeMap;

import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.rest.action.readonlyrest.acl.Rule.Type;

public class ACL {

  private Settings            s;
  private ESLogger            logger;
  private TreeMap<Integer, Rule>           rules  = new TreeMap<>();
  private final static String PREFIX = "readonlyrest.access_control_rules";

  public ACL(ESLogger logger, Settings s) {
    this.logger = logger;
    this.s = s;
    readRules();
  }

  private void readRules() {
    Map<String, Settings> g = s.getGroups(PREFIX);
    // Maintaining the order is not guaranteed, moving everything to tree map!
    TreeMap<String, Settings> tmp = new TreeMap<>();
    tmp.putAll(g);
    g = tmp;
    int i = 0;
    for (String k : g.keySet()) {
      Rule r = Rule.build(g.get(k));
      rules.put(i++,r);
      logger.info(r.toString());
    }

  }

  /**
   * Check the request against configured ACL rules. This does not work with try/catch because stacktraces are expensive
   * for performance.
   * 
   * @param req the ACLRequest to be checked by the ACL rules.
   * @return null if request pass the rules or the name of the first violated rule
   */
  public String check(ACLRequest req) {
    for (Integer exOrder : rules.keySet()) {
      Rule rule = rules.get(exOrder);
      // The logic will exit at the first rule that matches the request
      boolean match = true;
      match &= rule.matchesAddress(req.getAddress());
      match &= rule.matchesApiKey(req.getApiKey());
      match &= rule.matchesMaxBodyLength(req.getBodyLength());
      match &= rule.matchesUriRe(req.getUri());
      match &= rule.mathesMethods(req.getMethod());

      if (match) {
        logger.debug("MATCHED \n RULE:" + rule + "\n" +" RQST: " + req );
        return rule.type.equals(Type.FORBID) ? rule.name : null;
      }
    }
    return "request matches no rules, forbidden by default: req: " + req.getUri() + " - method: " + req.getMethod() + " - origin addr: " + req.getAddress() + " - api key: " + req.getApiKey();
  }

}
