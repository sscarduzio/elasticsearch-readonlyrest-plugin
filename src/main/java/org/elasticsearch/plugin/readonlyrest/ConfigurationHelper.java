package org.elasticsearch.plugin.readonlyrest;

import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.inject.Singleton;
import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.common.settings.Settings;

/**
 * ConfigurationHelper
 *
 * @author <a href="mailto:scarduzio@gmail.com">Simone Scarduzio</a>
 * @see <a href="https://github.com/sscarduzio/elasticsearch-readonlyrest-plugin/">Github Project</a>
 */

@Singleton
public class ConfigurationHelper {
  public static final String ANSI_RESET = "\u001B[0m";
  public static final String ANSI_BLACK = "\u001B[30m";
  public static final String ANSI_RED = "\u001B[31m";
  public static final String ANSI_GREEN = "\u001B[32m";
  public static final String ANSI_YELLOW = "\u001B[33m";
  public static final String ANSI_BLUE = "\u001B[34m";
  public static final String ANSI_PURPLE = "\u001B[35m";
  public static final String ANSI_CYAN = "\u001B[36m";
  public static final String ANSI_WHITE = "\u001B[37m";

  public final boolean enabled;
  public final String verbosity;
  public final String forbiddenResponse;
  public final boolean sslEnabled;
  public final String sslKeyStoreFile;
  public final String sslKeyPassword;
  public final String sslKeyStorePassword;
  private final ESLogger logger;

  @Inject
  public ConfigurationHelper(Settings settings) {
    logger = Loggers.getLogger(getClass());

    Settings s = settings.getByPrefix("readonlyrest.");
    verbosity = s.get("verbosity", "info");
    enabled = s.getAsBoolean("enable", false);
    forbiddenResponse = s.get("response_if_req_forbidden", "Forbidden").trim();

    // -- SSL
    sslEnabled = s.getAsBoolean("ssl.enable", false);
    if(!sslEnabled){
      logger.info("Readonly Rest plugin is installed, but not enabled");
    }
    sslKeyStoreFile = s.get("ssl.keystore_file");
    sslKeyStorePassword = s.get("ssl.keystore_pass");
    sslKeyPassword = s.get("ssl.key_pass", sslKeyStorePassword); // fallback

  }

  public static boolean isNullOrEmpty(String s) {
    return s == null || s.trim().length() == 0;
  }

}
