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

import org.apache.logging.log4j.Logger;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.inject.Singleton;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.plugin.readonlyrest.acl.RuleConfigurationError;

import java.util.Arrays;
import java.util.List;

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
  public final Settings settings;
  private final Logger logger;

  @Inject
  public ConfigurationHelper(Settings settings) {
    this.settings = settings;
    logger = Loggers.getLogger(getClass());

    Settings s = settings.getByPrefix("readonlyrest.");
    verbosity = s.get("verbosity", "info");
    enabled = s.getAsBoolean("enable", false);

    forbiddenResponse = s.get("response_if_req_forbidden", "Forbidden").trim();

    // -- SSL
    sslEnabled = s.getAsBoolean("ssl.enable", false);
    if (sslEnabled) {
      logger.info("SSL: Enabled");
    } else {
      logger.info("SSL: Disabled");
    }
    sslKeyStoreFile = s.get("ssl.keystore_file");
    sslKeyStorePassword = s.get("ssl.keystore_pass");
    sslKeyPassword = s.get("ssl.key_pass", sslKeyStorePassword); // fallback

  }

  public static ConfigurationHelper parse(Settings s) {
    try {
      return new ConfigurationHelper(s);
    } catch (Exception e) {
      throw new RuleConfigurationError("cannot parse settings", e);
    }
  }

  private static Setting<String> str(String name) {
    return new Setting<>(name, "", (value) -> value, Setting.Property.NodeScope);
  }

//  private static Setting<List<String>> strA(String name) {
//    return Setting.listSetting(name, new ArrayList<>(), (s) -> s.toString(), Setting.Property.NodeScope);
//  }

  private static Setting<Boolean> bool(String name) {
    return Setting.boolSetting(name, Boolean.FALSE, Setting.Property.NodeScope);
  }

  //  private static Setting<Integer> integ(String name) {
//    return Setting.intSetting(name, 0, Integer.MAX_VALUE, Setting.Property.NodeScope);
//  }
  private static Setting<Settings> grp(String name) {
    return Setting.groupSetting(name, new Setting.Property[]{Setting.Property.Dynamic, Setting.Property.NodeScope});
  }

  public static List<Setting<?>> allowedSettings() {
    String prefix = "readonlyrest.";
    String rule_prefix = prefix + "access_control_rules.";
    String users_prefix = prefix + "users.";

    return Arrays.asList(
        bool(prefix + "enable"),
        str(prefix + "response_if_req_forbidden"),

        // SSL
        bool(prefix + "ssl.enable"),
        str(prefix + "ssl.keystore_file"),
        str(prefix + "ssl.keystore_pass"),
        str(prefix + "ssl.key_pass"),

        grp(rule_prefix),
        grp(users_prefix)
        // Rules
//        str(rule_prefix + "name"),
//        str(rule_prefix + "accept_x-forwarded-for_header"),
//        str(rule_prefix + "auth_key"),
//        str(rule_prefix + "auth_key_sha1"),
//        str(rule_prefix + "uri_re"),
//        str(rule_prefix + "methods"),
//        integ(rule_prefix + "maxBodyLength"),
//        strA(rule_prefix + "indices"),
//        strA(rule_prefix + "hosts"),
//        strA(rule_prefix + "groups"),
//
//        // Users
//        str(users_prefix + "username"),
//        str(users_prefix + "auth_key"),
//        strA(users_prefix + "groups")

    );
  }

}