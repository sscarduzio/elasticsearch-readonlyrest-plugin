package org.elasticsearch.plugin.readonlyrest.settings.deserializers;

import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.DeserializationConfig;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.deser.BeanDeserializerModifier;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.google.common.collect.ImmutableMap;
import org.elasticsearch.plugin.readonlyrest.settings.BlockSettings;
import org.elasticsearch.plugin.readonlyrest.settings.IndicesRuleSettings;
import org.elasticsearch.plugin.readonlyrest.settings.LdapAuthRuleSettings;
import org.elasticsearch.plugin.readonlyrest.settings.LdapAuthenticationRuleSettings;
import org.elasticsearch.plugin.readonlyrest.settings.UserGroupsProviderSettings;

public class CustomDeserializersModule extends SimpleModule {

  public CustomDeserializersModule() {
    this.setDeserializerModifier(new BeanDeserializerModifier() {

      @SuppressWarnings("unchecked")
      @Override
      public JsonDeserializer<?> modifyDeserializer(DeserializationConfig config,
                                                    BeanDescription beanDesc,
                                                    JsonDeserializer<?> deserializer) {
        if (beanDesc.getBeanClass() == LdapAuthenticationRuleSettings.class)
          return new LdapAuthenticationRuleSettingsDeserializer(deserializer);
        if (beanDesc.getBeanClass() == LdapAuthRuleSettings.class)
          return new LdapAuthRuleSettingsDeserializer((JsonDeserializer<LdapAuthRuleSettings>) deserializer);
        return deserializer;
      }
    });
    this.addDeserializer(UserGroupsProviderSettings.TokenPassingMethod.class, new TokenPassingMethodDeserializer());
    this.addDeserializer(BlockSettings.class, new BlockSettingsDeserializer(ImmutableMap.of(
        LdapAuthRuleSettings.ATTRIBUTE_NAME, LdapAuthRuleSettings.class,
        IndicesRuleSettings.ATTRIBUTE_NAME, IndicesRuleSettings.class
    )));
  }

}

