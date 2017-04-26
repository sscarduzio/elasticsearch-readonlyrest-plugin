package org.elasticsearch.plugin.readonlyrest.settings;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;

import java.util.LinkedHashMap;
import java.util.List;

public class RorSettings {

  private static final String ATTRIBUTE_NAME = "readonlyrest";

  private static final boolean DEFAULT_ENABLE = true;
  private static final String DEFAULT_FORBIDDEN_MESSAGE = "";
  private static final List<BlockSettings> DEFAULT_BLOCK_SETTINGS = Lists.newArrayList();  

  private final boolean enable;
  private final String forbiddenMessage;
  private final List<BlockSettings> blocksSettings;

  static RorSettings from(LinkedHashMap<?, ?> data) {
    Object ror = data.get(ATTRIBUTE_NAME);
    if(ror == null) throw new ConfigMalformedException("Not found required '" + ATTRIBUTE_NAME + "' attribute");
    return new RorSettings(new RawSettings((LinkedHashMap<?, ?>) ror));
  }

  private RorSettings(RawSettings raw) {
    this.enable = raw.booleanOtp("enable").orElse(DEFAULT_ENABLE);
    this.forbiddenMessage = raw.stringOpt("response_if_req_forbidden").orElse(DEFAULT_FORBIDDEN_MESSAGE);
    this.blocksSettings = DEFAULT_BLOCK_SETTINGS;

    LdapsSettings.from(raw);
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
