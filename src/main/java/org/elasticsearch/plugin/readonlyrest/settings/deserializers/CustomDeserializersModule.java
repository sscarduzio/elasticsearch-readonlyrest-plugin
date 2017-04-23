package org.elasticsearch.plugin.readonlyrest.settings.deserializers;

import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.DeserializationConfig;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.deser.BeanDeserializerModifier;
import com.fasterxml.jackson.databind.module.SimpleModule;
import org.elasticsearch.plugin.readonlyrest.settings.LdapAuthenticationRuleSettings;
import org.elasticsearch.plugin.readonlyrest.settings.UserGroupsProviderSettings;

public class CustomDeserializersModule extends SimpleModule {

  public CustomDeserializersModule() {
    this.setDeserializerModifier(new BeanDeserializerModifier() {

      @Override
      public JsonDeserializer<?> modifyDeserializer(DeserializationConfig config,
                                                    BeanDescription beanDesc,
                                                    JsonDeserializer<?> deserializer) {
        if (beanDesc.getBeanClass() == LdapAuthenticationRuleSettings.class)
          return new LdapAuthenticationRuleSettingsDeserializer(deserializer);
        return deserializer;
      }
    });
    this.addDeserializer(UserGroupsProviderSettings.TokenPassingMethod.class, new TokenPassingMethodDeserializer());
  }

}

