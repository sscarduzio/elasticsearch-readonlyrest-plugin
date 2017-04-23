package org.elasticsearch.plugin.readonlyrest.settings.deserializers;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.deser.ResolvableDeserializer;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.deser.std.StringDeserializer;
import org.elasticsearch.plugin.readonlyrest.settings.LdapAuthenticationRuleSettings;

import java.io.IOException;

public class LdapAuthenticationRuleSettingsDeserializer
    extends StdDeserializer<LdapAuthenticationRuleSettings> implements ResolvableDeserializer {

  private static final long serialVersionUID = 7923585097068641765L;

  private final JsonDeserializer<?> defaultDeserializer;

  public LdapAuthenticationRuleSettingsDeserializer(JsonDeserializer<?> defaultDeserializer) {
    super(LdapAuthenticationRuleSettings.class);
    this.defaultDeserializer = defaultDeserializer;
  }

  @Override
  public LdapAuthenticationRuleSettings deserialize(JsonParser jp, DeserializationContext ctxt)
      throws IOException, JsonProcessingException {

    try {
      String deserializedValue = StringDeserializer.instance.deserialize(jp, ctxt);
      return deserializedValue == null
          ? (LdapAuthenticationRuleSettings) defaultDeserializer.deserialize(jp, ctxt)
          : new LdapAuthenticationRuleSettings(deserializedValue);
    } catch (JsonMappingException ex) {
      return (LdapAuthenticationRuleSettings) defaultDeserializer.deserialize(jp, ctxt);
    }
  }

  @Override
  public void resolve(DeserializationContext ctxt) throws JsonMappingException {
    ((ResolvableDeserializer) defaultDeserializer).resolve(ctxt);
  }

}