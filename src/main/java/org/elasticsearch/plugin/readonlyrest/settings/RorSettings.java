package org.elasticsearch.plugin.readonlyrest.settings;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class RorSettings {

  private static final String ATTRIBUTE_NAME = "readonlyrest";

  private static final boolean DEFAULT_ENABLE = true;
  private static final String DEFAULT_FORBIDDEN_MESSAGE = "";
  private static final List<BlockSettings> DEFAULT_BLOCK_SETTINGS = Lists.newArrayList();

  private final boolean enable;
  private final String forbiddenMessage;
  private final List<BlockSettings> blocksSettings;

  static RorSettings from(RawSettings settings) {
    return new RorSettings(settings.inner(ATTRIBUTE_NAME));
  }

  @SuppressWarnings("unchecked")
  private RorSettings(RawSettings raw) {
    this.enable = raw.booleanOtp("enable").orElse(DEFAULT_ENABLE);
    this.forbiddenMessage = raw.stringOpt("response_if_req_forbidden").orElse(DEFAULT_FORBIDDEN_MESSAGE);
    this.blocksSettings = raw.listOpt(BlockSettings.ATTRIBUTE_NAME).orElse(DEFAULT_BLOCK_SETTINGS).stream()
        .map(block -> BlockSettings.from(
            new RawSettings((Map<String, ?>) block),
            LdapsSettings.from(raw)
        ))
        .collect(Collectors.toList());
  }

  public boolean isEnabled() {
    return enable;
  }

  public String getForbiddenMessage() {
    return forbiddenMessage;
  }

  public ImmutableList<BlockSettings> getBlocksSettings() {
    return ImmutableList.copyOf(blocksSettings);
  }
}
