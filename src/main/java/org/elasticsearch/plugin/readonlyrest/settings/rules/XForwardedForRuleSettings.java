package org.elasticsearch.plugin.readonlyrest.settings.rules;

import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.IPMask;
import org.elasticsearch.plugin.readonlyrest.settings.ConfigMalformedException;
import org.elasticsearch.plugin.readonlyrest.settings.RuleSettings;

import java.net.UnknownHostException;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class XForwardedForRuleSettings implements RuleSettings {

  public static final String ATTRIBUTE_NAME = "x_forwarded_for";

  private Set<IPMask> allowedAddresses;

  public static XForwardedForRuleSettings from(List<String> addresses) {
    return new XForwardedForRuleSettings(addresses.stream()
        .map(XForwardedForRuleSettings::fromString)
        .collect(Collectors.toSet()));
  }

  private XForwardedForRuleSettings(Set<IPMask> allowedAddresses) {
    this.allowedAddresses = allowedAddresses;
  }

  public Set<IPMask> getAllowedAddresses() {
    return allowedAddresses;
  }

  private static IPMask fromString(String value) {
    try {
      return IPMask.getIPMask(value);
    } catch (UnknownHostException e) {
      throw new ConfigMalformedException("Cannot create IP address from string: " + value);
    }
  }
}
