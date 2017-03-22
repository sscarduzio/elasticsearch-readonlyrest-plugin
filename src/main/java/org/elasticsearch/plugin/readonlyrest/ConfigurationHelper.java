/*
 *    This file is part of ReadonlyREST.
 *
 *    ReadonlyREST is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU General Public License as published by
 *    the Free Software Foundation, either version 3 of the License, or
 *    (at your option) any later version.
 *
 *    ReadonlyREST is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU General Public License for more details.
 *
 *    You should have received a copy of the GNU General Public License
 *    along with ReadonlyREST.  If not, see http://www.gnu.org/licenses/
 */

package org.elasticsearch.plugin.readonlyrest;

import org.apache.logging.log4j.Logger;
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.ResourceNotFoundException;
import org.elasticsearch.action.get.GetResponse;
import org.elasticsearch.client.Client;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.inject.Singleton;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.plugin.readonlyrest.acl.ACL;

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

  static private final  Logger logger = Loggers.getLogger(ConfigurationHelper.class);

  private static ConfigurationHelper currentInstance;
  private final Client client;

  public boolean enabled;
  public String verbosity;
  public String forbiddenResponse;
  public boolean sslEnabled;
  public String sslKeyStoreFile;
  public String sslKeyPassword;
  public String sslKeyStorePassword;
  public boolean searchLoggingEnabled;
  public Settings settings;
  public String sslKeyAlias;
  public String sslCertChainPem;
  public String sslPrivKeyPem;
  public ACL acl;

  @Inject
  public ConfigurationHelper(Settings settings, Client client) {
    this.client = client;
    this.settings = settings;
    readSettings(settings);

    // Try to fetch from
    if (client != null) {
      try {
        updateSettingsFromIndex(client);
      } catch (IllegalStateException ise) {
        // Not ready yet.
        return;
      } catch (Exception e) {
        e.printStackTrace();
        logger.info("No cluster-wide settings found.. You need RedonlyREST Kibana plugin to make this work :) ");
      }
    }

  }

  public static ConfigurationHelper getInstance(Settings s, Client c) {
    if (currentInstance == null) {
      currentInstance = new ConfigurationHelper(s, c);
    }
    return currentInstance;
  }

  private static Setting<String> str(String name) {
    return new Setting<>(name, "", (value) -> value, Setting.Property.NodeScope);
  }

  private static Setting<Boolean> bool(String name) {
    return Setting.boolSetting(name, Boolean.FALSE, Setting.Property.NodeScope);
  }

  //  private static Setting<Integer> integ(String name) {
//    return Setting.intSetting(name, 0, Integer.MAX_VALUE, Setting.Property.NodeScope);
//  }
  private static Setting<Settings> grp(String name) {
    return Setting.groupSetting(name, new Setting.Property[]{Setting.Property.Dynamic, Setting.Property.NodeScope});
  }

//  private static Setting<List<String>> strA(String name) {
//    return Setting.listSetting(name, new ArrayList<>(), (s) -> s.toString(), Setting.Property.NodeScope);
//  }

  public static List<Setting<?>> allowedSettings() {
    String prefix = "readonlyrest.";
    String rule_prefix = prefix + "access_control_rules.";
    String users_prefix = prefix + "users.";
    String ldaps_prefix = prefix + "ldaps.";

    return Arrays.asList(
      bool(prefix + "enable"),
      str(prefix + "response_if_req_forbidden"),
      bool(prefix + "searchlog"),

      // SSL
      bool(prefix + "ssl.enable"),
      str(prefix + "ssl.keystore_file"),
      str(prefix + "ssl.keystore_pass"),
      str(prefix + "ssl.key_alias"),
      str(prefix + "ssl.key_pass"),
      str(prefix + "ssl.privkey_pem"),
      str(prefix + "ssl.certchain_pem"),

      grp(rule_prefix),
      grp(users_prefix),
      grp(ldaps_prefix)
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

  public void updateSettingsFromIndex(Client client) throws ResourceNotFoundException {
    GetResponse resp = client.prepareGet(".readonlyrest", "settings", "1").get();
    if (!resp.isExists()) {
      throw new ElasticsearchException("no settings found in index");
    }
    String yaml = (String) resp.getSource().get("settings");
    Settings settings = Settings.builder().loadFromSource(yaml).build();
    readSettings(settings);
  }

  private void readSettings(Settings settings) {
    Settings s = settings.getByPrefix("readonlyrest.");
    this.settings = settings;
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

    searchLoggingEnabled = s.getAsBoolean("searchlog", false);
    acl = new ACL(client, this);

    currentInstance = this;
  }

}