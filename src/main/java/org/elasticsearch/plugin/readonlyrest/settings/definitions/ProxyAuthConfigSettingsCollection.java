package org.elasticsearch.plugin.readonlyrest.settings.definitions;

import com.google.common.collect.Lists;
import org.elasticsearch.plugin.readonlyrest.settings.ConfigMalformedException;
import org.elasticsearch.plugin.readonlyrest.settings.RawSettings;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.jooq.lambda.Seq.seq;

public class ProxyAuthConfigSettingsCollection {

  private static final String ATTRIBUTE_NAME = "proxy_auth_configs";

  private final Map<String, ProxyAuthConfigSettings> proxyAuthConfigsSettingsMap;

  @SuppressWarnings("unchecked")
  public static ProxyAuthConfigSettingsCollection from(RawSettings data) {
    return data.notEmptyListOpt(ATTRIBUTE_NAME)
        .map(list ->
            list.stream()
                .map(l -> new ProxyAuthConfigSettings(new RawSettings((Map<String, ?>) l)))
                .collect(Collectors.toList())
        )
        .map(ProxyAuthConfigSettingsCollection::new)
        .orElse(new ProxyAuthConfigSettingsCollection(Lists.newArrayList()));
  }

  private ProxyAuthConfigSettingsCollection(List<ProxyAuthConfigSettings> proxyAuthConfigsSettings) {
    validate(proxyAuthConfigsSettings);
    this.proxyAuthConfigsSettingsMap = seq(proxyAuthConfigsSettings).toMap(ProxyAuthConfigSettings::getName, Function.identity());
  }

  public ProxyAuthConfigSettings get(String name) {
    if (!proxyAuthConfigsSettingsMap.containsKey(name))
      throw new ConfigMalformedException("Cannot find Proxy Auth Config definition with name '" + name + "'");
    return proxyAuthConfigsSettingsMap.get(name);
  }

  private void validate(List<ProxyAuthConfigSettings> proxyAuthConfigsSettings) {
    List<String> names = seq(proxyAuthConfigsSettings).map(ProxyAuthConfigSettings::getName).collect(Collectors.toList());
    if(names.stream().distinct().count() != names.size()) {
      throw new ConfigMalformedException("Duplicated Proxy Auth Config name in '" + ATTRIBUTE_NAME + "' section");
    }
  }
}
