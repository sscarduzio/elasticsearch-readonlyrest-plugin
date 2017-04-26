package org.elasticsearch.plugin.readonlyrest.settings;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Duration;
import java.util.Optional;

public class LdapSettings extends Settings {

  @JsonProperty("name")
  private String name;

  @JsonProperty("host")
  private String host;

  @JsonProperty("port")
  private int port = 389;

  @JsonProperty("ssl_enabled")
  private boolean isSslEnabled = true;

  @JsonProperty("ssl_trust_all_certs")
  private boolean trustAllCertificates = false;

  @JsonProperty("bind_dn")
  private Optional<String> bindDn = Optional.empty();

  @JsonProperty("bind_password")
  private Optional<String> bindPassword = Optional.empty();

  @JsonProperty("search_user_base_DN")
  private String searchUserBaseDn;

  @JsonProperty("user_id_attribute")
  private String userIdAttribute = "uid";

  @JsonProperty("search_groups_base_DN")
  private String searchGroupBaseDn;

  @JsonProperty("unique_member_attribute")
  private String uniqueMemberAttribute = "uniqueMember";

  @JsonProperty("connection_pool_size")
  private int connectionPoolSize = 30;

  @JsonProperty("connection_timeout_in_sec")
  private int connectionTimeoutInSec = 1;

  @JsonProperty("request_timeout_in_sec")
  private int requestTimeoutInSec = 1;

  @JsonProperty("cache_ttl_in_sec")
  private int cacheTtlInSec = 0;

  static LdapSettings from(RawSettings data) {
    return null;
  }

  public String getName() {
    return name;
  }

  public int getPort() {
    return port;
  }

  public String getHost() {
    return host;
  }

  public boolean isSslEnabled() {
    return isSslEnabled;
  }

  public boolean isTrustAllCertificates() {
    return trustAllCertificates;
  }

  public Optional<SearchingUserSettings> getSearchingUserSettings() {
    return bindDn.flatMap(b -> bindPassword.map(p -> new SearchingUserSettings(b, p)));
  }

  public String getSearchUserBaseDn() {
    return searchUserBaseDn;
  }

  public String getUserIdAttribute() {
    return userIdAttribute;
  }

  public String getSearchGroupBaseDn() {
    return searchGroupBaseDn;
  }

  public String getUniqueMemberAttribute() {
    return uniqueMemberAttribute;
  }

  public int getConnectionPoolSize() {
    return connectionPoolSize;
  }

  public Duration getConnectionTimeout() {
    return Duration.ofSeconds(connectionTimeoutInSec);
  }

  public Duration getRequestTimeoutInSec() {
    return Duration.ofSeconds(requestTimeoutInSec);
  }

  public Duration getCacheTtlInSec() {
    return Duration.ofSeconds(cacheTtlInSec);
  }

  @Override
  protected void validate() {
    if(name == null) {
      throw new ConfigMalformedException("'name' was not defined in one of ldaps definition");
    }
    if(host == null) {
      throw new ConfigMalformedException("'host' was not defined in one of ldaps definition");
    }
    if(searchUserBaseDn == null) {
      throw new ConfigMalformedException("'search_user_base_DN' was not defined in one of ldaps definition");
    }
    if ((bindDn.isPresent() && !bindPassword.isPresent()) ||
        (!bindDn.isPresent() && bindPassword.isPresent())) {
      throw new ConfigMalformedException("LDAP definition malformed - must configure both params [bind_dn, bind_password]");
    }
  }

  public class SearchingUserSettings {
    private final String dn;
    private final String password;

    SearchingUserSettings(String dn, String password) {
      this.dn = dn;
      this.password = password;
    }

    public String getDn() {
      return dn;
    }

    public String getPassword() {
      return password;
    }
  }
}
