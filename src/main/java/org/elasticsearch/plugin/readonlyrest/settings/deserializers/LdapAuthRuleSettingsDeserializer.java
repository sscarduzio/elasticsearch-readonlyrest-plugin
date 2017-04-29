package org.elasticsearch.plugin.readonlyrest.settings.deserializers;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.deser.ResolvableDeserializer;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import org.elasticsearch.plugin.readonlyrest.settings.rules.LdapAuthRuleSettings;

import java.io.IOException;

public class LdapAuthRuleSettingsDeserializer
    extends StdDeserializer<LdapAuthRuleSettings> implements ResolvableDeserializer {

  private final JsonDeserializer<LdapAuthRuleSettings> defaultDeserializer;

  public LdapAuthRuleSettingsDeserializer(JsonDeserializer<LdapAuthRuleSettings> defaultDeserializer) {
    super(LdapAuthRuleSettings.class);
    this.defaultDeserializer = defaultDeserializer;
  }

  @Override
  public LdapAuthRuleSettings deserialize(JsonParser jp, DeserializationContext ctxt)
      throws IOException, JsonProcessingException {

    LdapAuthRuleSettings settings = defaultDeserializer.deserialize(jp, ctxt);

    return settings;
  }

  @Override
  public void resolve(DeserializationContext ctxt) throws JsonMappingException {
    ((ResolvableDeserializer) defaultDeserializer).resolve(ctxt);
  }

}