package org.elasticsearch.plugin.readonlyrest;

import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.common.settings.Settings;

/**
 * ConfigurationHelper
 * 
 * @author <a href="mailto:scarduzio@gmail.com">Simone Scarduzio</a>
 * @see <a href="https://github.com/sscarduzio/elasticsearch-readonlyrest-plugin/">Github Project</a>
 */

public class ConfigurationHelper {

  /**
   * YML prefix of this plugin inside elasticsearch.yml
   */
  private final static String ES_YAML_CONF_PREFIX = "readonlyrest.";
  private final static String K_RESP_REQ_FORBIDDEN = "response_if_req_forbidden";
  final public boolean enabled;
  final public String forbiddenResponse;

  
  public ConfigurationHelper(Settings settings, ESLogger logger) {
    Settings s = settings.getByPrefix(ES_YAML_CONF_PREFIX);

    // Load configuration
    
    if (!s.getAsBoolean("enable", false)) {
      logger.info("Readonly Rest plugin is installed, but not enabled");
      this.enabled = false;
    }
    else{
      this.enabled = true;
    }
    String t = s.get(K_RESP_REQ_FORBIDDEN);
    if(t != null) {
      t = t.trim();
    }
    if(isNullOrEmpty(t)){
      this.forbiddenResponse = null;
    }
    else{
      this.forbiddenResponse = t;
    }

  }
  
  public static boolean isNullOrEmpty(String s){
    return s == null || s.trim().length() == 0;
  }
}
