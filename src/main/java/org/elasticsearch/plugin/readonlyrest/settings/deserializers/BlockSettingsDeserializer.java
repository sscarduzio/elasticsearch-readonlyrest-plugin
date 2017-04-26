package org.elasticsearch.plugin.readonlyrest.settings.deserializers;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.ObjectCodec;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.google.common.collect.Lists;
import org.elasticsearch.plugin.readonlyrest.settings.BlockSettings;
import org.elasticsearch.plugin.readonlyrest.settings.IndicesRuleSettings;
import org.elasticsearch.plugin.readonlyrest.settings.LdapAuthRuleSettings;
import org.elasticsearch.plugin.readonlyrest.settings.RuleSettings;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;

import static org.jooq.lambda.Seq.seq;
import static org.jooq.lambda.Unchecked.biFunction;

public class BlockSettingsDeserializer extends StdDeserializer<BlockSettings> {

  private static final String NAME = "name";

  private final Map<String, Class<? extends RuleSettings>> ruleDeserializers;

  protected BlockSettingsDeserializer(Map<String, Class<? extends RuleSettings>> ruleDeserializers) {
    super(BlockSettings.class);
    this.ruleDeserializers = ruleDeserializers;
  }

  @Override
  public BlockSettings deserialize(JsonParser p, DeserializationContext ctxt) throws IOException, JsonProcessingException {
    String name = p.nextFieldName();
    String name1 = p.readValueAs(String.class);
    String nameValue = p.readValueAs(String.class);

    String ldap = p.nextFieldName();
    String ldap1 = p.readValueAs(String.class);
    LdapAuthRuleSettings ldapValue = p.readValueAs(LdapAuthRuleSettings.class);

    String indices = p.nextFieldName();
    String indices1 = p.readValueAs(String.class);
    IndicesRuleSettings indicesRuleSettings = p.readValueAs(IndicesRuleSettings.class);

    return null;
//    JsonNode node = p.getCodec().readTree(p);
//    if(node == null || node.isNull()) return null;
//
//    String name = node.get(NAME).textValue();
//    if (name == null) {
//      throw JsonMappingException.from(p, "Required attribute '" + NAME + "' not found in '" + BlockSettings.ATTIBUTE_NAME + "' section");
//    }
//
//    return seq(node.fieldNames())
//        .filter(fieldName -> !Objects.equals(fieldName, NAME))
//        .foldLeft(new BlockSettings(name, Lists.newArrayList()), biFunction((acc, fieldName) -> {
//              Class<? extends RuleSettings> deserializerClass = ruleDeserializers.get(fieldName);
//              if(deserializerClass == null) {
//                throw JsonMappingException.from(p, "No deserializer found for '" + fieldName + "'");
//              }
//              RuleSettings ruleSettings = p.readValueAs(deserializerClass);
//              return acc.withRule(ruleSettings);
//            })
//        );
  }
}
