/*
 * This file is part of ReadonlyREST.
 *
 *     ReadonlyREST is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     ReadonlyREST is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with ReadonlyREST.  If not, see <http://www.gnu.org/licenses/>.
 *
 */

package org.elasticsearch.plugin.readonlyrest.acl;

import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.inject.Singleton;
import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.Block;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.BlockExitResult;

import java.util.ArrayList;
import java.util.Map;
import java.util.TreeMap;
import static org.elasticsearch.plugin.readonlyrest.ConfigurationHelper.*;

/**
 * Created by sscarduzio on 13/02/2016.
 */

@Singleton
public class ACL {
  private final ESLogger logger = Loggers.getLogger(getClass());
  // Array list because it preserves the insertion order
  private ArrayList<Block> blocks = new ArrayList<>();
  private final static String RULES_PREFIX = "readonlyrest.access_control_rules";
  private final static String USERS_PREFIX = "readonlyrest.users";
  private boolean basicAuthConfigured = false;

  @Inject
  public ACL(Settings s) {
    Map<String, Settings> g = s.getGroups(RULES_PREFIX);
    // Maintaining the order is not guaranteed, moving everything to tree map!
    TreeMap<String, Settings> tmp = new TreeMap<>();
    tmp.putAll(g);
    g = tmp;
    Map<String, Settings> users = s.getGroups(USERS_PREFIX);
    for (String k : g.keySet()) {
      Block block = new Block(g.get(k), new ArrayList<>(users.values()), logger);
      blocks.add(block);
      if (block.isAuthHeaderAccepted()) {
        basicAuthConfigured = true;
      }
      logger.info("ADDING " + block.toString());
    }
  }

  public boolean isBasicAuthConfigured() {
    return basicAuthConfigured;
  }

  public BlockExitResult check(RequestContext rc) {
    logger.debug("checking request:" + rc);
    for (Block b : blocks) {
      BlockExitResult result = b.check(rc);
      if (result.isMatch()) {
        logger.info("request: " + rc + " matched block: " + result);
        return result;
      }
    }
    logger.info(ANSI_RED + "no block has matched, forbidding by default: " + rc + ANSI_RESET);
    return BlockExitResult.NO_MATCH;
  }
}
