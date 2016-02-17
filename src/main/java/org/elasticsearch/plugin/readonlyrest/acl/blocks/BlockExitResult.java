package org.elasticsearch.plugin.readonlyrest.acl.blocks;


/**
 * Created by sscarduzio on 14/02/2016.
 */
public class BlockExitResult {
  private final Block block;
  public static final BlockExitResult NO_MATCH = new BlockExitResult(null, false);
  private final Boolean match;

  BlockExitResult(Block block, Boolean match) {
    this.block = block;
    this.match = match;
  }

  public boolean isMatch() {
    // Cover the case of rule == null to allow the creation of a dummy NO_MATCH rule result
    return block != null && match;
  }

  public Block getBlock() {
    return block;
  }


  @Override
  public String toString() {
    return "{ block: " + block == null ? "<none>" : block.getName() + " match: " + isMatch() + "}";
  }

}
