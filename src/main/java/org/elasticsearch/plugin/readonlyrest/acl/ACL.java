package org.elasticsearch.plugin.readonlyrest.acl;

import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.Block;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.BlockExitResult;
import org.elasticsearch.rest.RestChannel;
import org.elasticsearch.rest.RestRequest;

import java.util.ArrayList;
import java.util.Map;
import java.util.TreeMap;

/**
 * Created by sscarduzio on 13/02/2016.
 */
public class ACL {
  private final ESLogger logger = Loggers.getLogger(this.getClass());
  // Array list because it preserves the insertion order
  private ArrayList<Block> blocks = new ArrayList<>();
  private final static String PREFIX = "readonlyrest.access_control_rules";
  private boolean basicAuthConfigured = false;

  public boolean isBasicAuthConfigured() {
    return basicAuthConfigured;
  }


  public ACL(Settings s) {
    Map<String, Settings> g = s.getGroups(PREFIX);
    // Maintaining the order is not guaranteed, moving everything to tree map!
    TreeMap<String, Settings> tmp = new TreeMap<>();
    tmp.putAll(g);
    g = tmp;
    for (String k : g.keySet()) {
      Block block = new Block(g.get(k), logger);
      blocks.add(block);
      if(block.isAuthHeaderAccepted()){
        basicAuthConfigured = true;
      }
      logger.info("ADDING " + block.toString());
    }
  }

  public BlockExitResult check(RestRequest request, RestChannel channel) {
    for (Block b : blocks) {
      BlockExitResult result = b.check(request, channel);
      if (result.isMatch()) {
        logger.info("Block has rejected: " + result);
        return result;
      }
    }
    return BlockExitResult.NO_MATCH;
  }
}
