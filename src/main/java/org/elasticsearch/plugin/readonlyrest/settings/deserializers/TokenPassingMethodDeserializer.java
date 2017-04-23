package org.elasticsearch.plugin.readonlyrest.settings.deserializers;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import org.elasticsearch.plugin.readonlyrest.settings.UserGroupsProviderSettings;

import java.io.IOException;

public class TokenPassingMethodDeserializer extends StdDeserializer<UserGroupsProviderSettings.TokenPassingMethod> {

  protected TokenPassingMethodDeserializer() {
    super(UserGroupsProviderSettings.TokenPassingMethod.class);
  }

  @Override
  public UserGroupsProviderSettings.TokenPassingMethod deserialize(JsonParser jp, DeserializationContext ctxt)
      throws IOException, JsonProcessingException {
    String value = jp.readValueAs(String.class);
    switch (value) {
      case "QUERY_PARAM":
        return UserGroupsProviderSettings.TokenPassingMethod.QUERY;
      case "HEADER":
        return UserGroupsProviderSettings.TokenPassingMethod.HEADER;
      default:
        throw UnrecognizedPropertyException.from(jp, "Unknown: [" + value + "]");
    }

  }
}
