package org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl;

import com.google.common.collect.Lists;
import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.plugin.readonlyrest.ConfigurationHelper;
import org.elasticsearch.plugin.readonlyrest.acl.RequestContext;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.Rule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.RuleExitResult;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.RuleNotConfiguredException;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Created by sscarduzio on 20/02/2016.
 */
public class IndicesRule extends Rule {

  private final static ESLogger logger = Loggers.getLogger(IndicesRule.class);

  protected List<String> indicesToMatch = Lists.newArrayList();
  protected List<Pattern> indicesWithWildcards = Lists.newArrayList();

  public IndicesRule(Settings s) throws RuleNotConfiguredException {
    super(s);
    String[] a = s.getAsArray(KEY);

    if (a == null || a.length == 0) {
      throw new RuleNotConfiguredException();
    }

    for (int i = 0; i < a.length; i++) {
      a[i] = normalizePlusAndMinusIndex(a[i]);
      if (ConfigurationHelper.isNullOrEmpty(a[i])) {
        continue;
      }
      if (a[i].contains("*")) {
        // Patch the simple star wildcard to become a regex: ("*" -> ".*")
        String regex = ("\\Q" + a[i] + "\\E").replace("*", "\\E.*\\Q");

        // Pre-compile the regex pattern matcher to validate the regex
        // AND faster matching later on.
        indicesWithWildcards.add(Pattern.compile(regex));

        // Let's match this also literally
        indicesToMatch.add(a[i]);
      } else {
        // A plain word can be matched as string
        indicesToMatch.add(a[i].trim());
      }
    }
  }

  /**
   * Returns null if the index is not worth processing because it's invalid or starts with "-"
   */
  String normalizePlusAndMinusIndex(String s) {
    // Ignore the excluded indices
    if (s.startsWith("-")) {
      return null;
    }
    // Call included indices with their name
    if (s.startsWith("+")) {
      if (s.length() == 1) {
        logger.warn("invalid index! " + s);
        return null;
      }
      return s.substring(1, s.length());
    }
    return s;
  }

  @Override
  public RuleExitResult match(RequestContext rc) {

    String[] indices = rc.getIndices();

    if (indices == null || indices.length == 0) {
      logger.warn("didn't find any index for this request: " + rc.getRequest().method() + " " + rc.getRequest().rawPath());
      return NO_MATCH;
    }

    for (String i : indices) {
      String idx = normalizePlusAndMinusIndex(i);
      if(idx == null) {
        continue;
      }
      // Try to match plain strings first
      if (indicesToMatch.contains(idx)) {
        return MATCH;
      }

      for (Pattern p : indicesWithWildcards) {
        Matcher m = p.matcher(idx);
        if(m == null) {
          continue;
        }
        if (m.find()) {
          return MATCH;
        }
      }
    }

    logger.debug("This request uses the indices '" + Arrays.toString(indices) + "' and none of them is on the list.");
    return NO_MATCH;
  }

}
