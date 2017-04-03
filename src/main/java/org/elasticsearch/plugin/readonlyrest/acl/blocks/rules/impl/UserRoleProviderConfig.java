package org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl;

import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.ConfigMalformedException;
import org.elasticsearch.plugin.readonlyrest.utils.ConfigReaderHelper;

import java.net.URI;
import java.util.function.Function;

import static org.elasticsearch.plugin.readonlyrest.utils.ConfigReaderHelper.requiredAttributeValue;

public class UserRoleProviderConfig {

  public String getName() {
    return name;
  }

  public enum TokenPassingMethod {
    QUERY, HEADER
  }

  private static String ATTRIBUTE_NAME = "name";
  private static String ATTRIBUTE_ROLE_ENDPOINT = "role_endpoint";
  private static String ATTRIBUTE_AUTH_TOKEN_NAME = "auth_token_name";
  private static String ATTRIBUTE_AUTH_TOKEN_PASSED_AS = "auth_token_passed_as";
  private static String ATTRIBUTE_RESPONSE_ROLES_JSON_PATH = "response_roles_json_path";

  private final String name;
  private final URI endpoint;
  private final String authTokenName;
  private final TokenPassingMethod passingMethod;
  private final String responseRolesJsonPath;

  private UserRoleProviderConfig(String name,
                                 URI endpoint,
                                 String authTokenName,
                                 TokenPassingMethod passingMethod,
                                 String responseRolesJsonPath) {
    this.name = name;
    this.endpoint = endpoint;
    this.authTokenName = authTokenName;
    this.passingMethod = passingMethod;
    this.responseRolesJsonPath = responseRolesJsonPath;
  }

  public static UserRoleProviderConfig fromSettings(Settings settings) throws ConfigMalformedException {
    return new UserRoleProviderConfig(
        requiredAttributeValue(ATTRIBUTE_NAME, settings),
        requiredAttributeValue(ATTRIBUTE_ROLE_ENDPOINT, settings, ConfigReaderHelper.toUri()),
        requiredAttributeValue(ATTRIBUTE_AUTH_TOKEN_NAME, settings),
        requiredAttributeValue(ATTRIBUTE_AUTH_TOKEN_PASSED_AS, settings, fromStringToTokenPassingMethod()),
        requiredAttributeValue(ATTRIBUTE_RESPONSE_ROLES_JSON_PATH, settings)
    );
  }

  public URI getEndpoint() {
    return endpoint;
  }

  public String getAuthTokenName() {
    return authTokenName;
  }

  public TokenPassingMethod getPassingMethod() {
    return passingMethod;
  }

  public String getResponseRolesJsonPath() {
    return responseRolesJsonPath;
  }

  private static Function<String, TokenPassingMethod> fromStringToTokenPassingMethod() {
    return value -> {
      switch (value) {
        case "QUERY_PARAM":
          return TokenPassingMethod.QUERY;
        case "HEADER":
          return TokenPassingMethod.HEADER;
        default:
          throw new ConfigMalformedException("Unknown token passing method: '" + value + "'");
      }
    };
  }
}
