/*
 * This file is part of ReadonlyREST.
 *
 *     ReadonlyREST is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     ReadonlyREST is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with ReadonlyREST.  If not, see <http://www.gnu.org/licenses/>.
 *
 */

package org.elasticsearch.plugin.readonlyrest;

import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.inject.Singleton;
import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.plugin.readonlyrest.acl.RuleConfigurationError;

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
  public final Settings settings;
  public String sslKeyAlias;
  public String sslCertChainPem;
  public String sslPrivKeyPem;

  @Inject
  public ConfigurationHelper(Settings settings) {
    this.settings = settings;
    ESLogger logger = Loggers.getLogger(getClass());

    Settings s = settings.getByPrefix("readonlyrest.");
    verbosity = s.get("verbosity", "info");
    enabled = s.getAsBoolean("enable", false);

    forbiddenResponse = s.get("response_if_req_forbidden", "Forbidden").trim();

    // -- SSL
    sslEnabled = s.getAsBoolean("ssl.enable", false);
    if (sslEnabled) {
      logger.info("SSL: Enabled");
    }
    else {
      logger.info("SSL: Disabled");
    }
    sslKeyStoreFile = s.get("ssl.keystore_file");
    sslKeyStorePassword = s.get("ssl.keystore_pass");
    sslKeyPassword = s.get("ssl.key_pass"); // fallback
    sslKeyAlias = s.get("ssl.key_alias");
    sslPrivKeyPem = s.get("ssl.privkey_pem");
    sslCertChainPem = s.get("ssl.certchain_pem");

  }

  public static ConfigurationHelper parse(Settings s) {
    try {
      return new ConfigurationHelper(s);
    } catch (Exception e) {
      throw new RuleConfigurationError("cannot parse settings", e);
    }
  }


}