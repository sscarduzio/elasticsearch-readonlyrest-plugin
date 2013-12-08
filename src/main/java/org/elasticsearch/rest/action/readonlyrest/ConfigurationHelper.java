package org.elasticsearch.rest.action.readonlyrest;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

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
  private final static String ES_YML_CONF_PREFIX = "readonlyrest.";

  /**
   * Maps to "allow_localhost" in elasticsearch.yml
   */
  private static Boolean      allowLocalhost;

  /**
   * Maps to "whitelist" in elasticsearch.yml
   */
  private static Set<String>  whitelist;

  /**
   * Maps to "forbidden_uri_re" in elasticsearch.yml
   */
  private static Pattern      forbiddenUriRe     = null;

  /**
   * Maps to "barred_reason_string" in elasticsearch.yml
   */
  private String              barredReasonString = "";

  public ConfigurationHelper(Settings settings, ESLogger logger) {
    // Load configuration
    if (!settings.getAsBoolean(ES_YML_CONF_PREFIX + "enable", false)) {
      logger.info("Readonly Rest plugin is installed, but not enabled");
      return;
    }

    allowLocalhost = settings.getAsBoolean(ES_YML_CONF_PREFIX + "allow_localhost", true);
    if(allowLocalhost){
      logger.info("allowing all GET requests from: localhost");
    }

    String[] aTmp = settings.getAsArray(ES_YML_CONF_PREFIX + "whitelist");
    if (aTmp.length > 0 && aTmp[0] != null) {
      whitelist = new HashSet<String>(Arrays.asList(aTmp));
      StringBuilder sb = new StringBuilder();
      for (String string : aTmp) {
        sb.append(string + "\n");
      }
      logger.info("allowing all GET requests from: " + sb.toString());
    }
    String sTmp = settings.get(ES_YML_CONF_PREFIX + "forbidden_uri_re");
    if (sTmp != null && sTmp.trim().length() > 0) {
      try {
        forbiddenUriRe = Pattern.compile(sTmp);
        logger.info("forbid access to URIs matching custom regexp: " + sTmp);
      }
      catch (Throwable t) {
        logger.error("invalid regular expression provided as forbidden_uri_re: " + sTmp);
      }
    }
    sTmp = settings.get(ES_YML_CONF_PREFIX + "barred_reason_string");
    if (sTmp != null) {
      logger.info("Barring forbidden request with reason: " + sTmp);      
      barredReasonString = sTmp;
    }
  }
  
  /*
   * Getters
   */
  public Boolean isAllowLocalhost() {
    return allowLocalhost;
  }

  public Set<String> getWhitelist() {
    return whitelist;
  }

  public Pattern getForbiddenUriRe() {
    return forbiddenUriRe;
  }

  public String getBarredReasonString() {
    return barredReasonString;
  }

}
