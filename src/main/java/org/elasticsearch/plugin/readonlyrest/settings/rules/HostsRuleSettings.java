package org.elasticsearch.plugin.readonlyrest.settings.rules;

import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.IPMask;
import org.elasticsearch.plugin.readonlyrest.settings.ConfigMalformedException;
import org.elasticsearch.plugin.readonlyrest.settings.RawSettings;
import org.elasticsearch.plugin.readonlyrest.settings.RuleSettings;

import java.net.UnknownHostException;
import java.util.Set;
import java.util.stream.Collectors;

public class HostsRuleSettings implements RuleSettings {

  public static final String ATTRIBUTE_NAME = "hosts";
  public static final String ATTRIBUTE_ACCEPT_X_FORWARDED_FOR_HEADER = "accept_x-forwarded-for_header";

  private static final boolean DEFAULT_ACCEPT_X_FOWARDED_FOR = false;

  private final Set<IPMask> allowedAddresses;
  private final boolean acceptXForwardedForHeader;

  public static HostsRuleSettings fromBlockSettings(RawSettings blockSettings) {
    return new HostsRuleSettings(
        blockSettings.notEmptyListReq(ATTRIBUTE_NAME).stream()
            .map(obj -> HostsRuleSettings.fromString((String) obj))
            .collect(Collectors.toSet()),
        blockSettings.booleanOpt(ATTRIBUTE_ACCEPT_X_FORWARDED_FOR_HEADER).orElse(DEFAULT_ACCEPT_X_FOWARDED_FOR)
    );
  }

  private HostsRuleSettings(Set<IPMask> allowedAddresses, boolean acceptXForwardedForHeader) {
    this.allowedAddresses = allowedAddresses;
    this.acceptXForwardedForHeader = acceptXForwardedForHeader;
  }

  public Set<IPMask> getAllowedAddresses() {
    return allowedAddresses;
  }

  public boolean isAcceptXForwardedForHeader() {
    return acceptXForwardedForHeader;
  }

  private static IPMask fromString(String value) {
    try {
      return IPMask.getIPMask(value);
    } catch (UnknownHostException e) {
      throw new ConfigMalformedException("Cannot create IP address from string: " + value);
    }
  }
}
