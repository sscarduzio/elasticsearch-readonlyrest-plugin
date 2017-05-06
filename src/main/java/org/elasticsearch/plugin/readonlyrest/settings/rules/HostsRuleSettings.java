package org.elasticsearch.plugin.readonlyrest.settings.rules;

import org.elasticsearch.plugin.readonlyrest.acl.domain.Value;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.IPMask;
import org.elasticsearch.plugin.readonlyrest.settings.ConfigMalformedException;
import org.elasticsearch.plugin.readonlyrest.settings.RawSettings;
import org.elasticsearch.plugin.readonlyrest.settings.RuleSettings;

import java.net.UnknownHostException;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

public class HostsRuleSettings implements RuleSettings {

  public static final String ATTRIBUTE_NAME = "hosts";
  public static final String ATTRIBUTE_ACCEPT_X_FORWARDED_FOR_HEADER = "accept_x-forwarded-for_header";

  private static final boolean DEFAULT_ACCEPT_X_FOWARDED_FOR = false;

  private final Set<Value<IPMask>> allowedAddresses;
  private final boolean acceptXForwardedForHeader;

  public static HostsRuleSettings fromBlockSettings(RawSettings blockSettings) {
    return new HostsRuleSettings(
        blockSettings.notEmptyListReq(ATTRIBUTE_NAME).stream()
            .map(obj -> Value.fromString((String) obj, ipMaskFromString))
            .collect(Collectors.toSet()),
        blockSettings.booleanOpt(ATTRIBUTE_ACCEPT_X_FORWARDED_FOR_HEADER).orElse(DEFAULT_ACCEPT_X_FOWARDED_FOR)
    );
  }

  private HostsRuleSettings(Set<Value<IPMask>> allowedAddresses, boolean acceptXForwardedForHeader) {
    this.allowedAddresses = allowedAddresses;
    this.acceptXForwardedForHeader = acceptXForwardedForHeader;
  }

  public Set<Value<IPMask>> getAllowedAddresses() {
    return allowedAddresses;
  }

  public boolean isAcceptXForwardedForHeader() {
    return acceptXForwardedForHeader;
  }

  private static Function<String, IPMask> ipMaskFromString = value -> {
    try {
      return IPMask.getIPMask(value);
    } catch (UnknownHostException e) {
      throw new ConfigMalformedException("Cannot create IP address from string: " + value);
    }
  };
}
