/*
 *    This file is part of ReadonlyREST.
 *
 *    ReadonlyREST is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU General Public License as published by
 *    the Free Software Foundation, either version 3 of the License, or
 *    (at your option) any later version.
 *
 *    ReadonlyREST is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU General Public License for more details.
 *
 *    You should have received a copy of the GNU General Public License
 *    along with ReadonlyREST.  If not, see http://www.gnu.org/licenses/
 */

package org.elasticsearch.plugin.readonlyrest.acl;

import com.google.common.collect.ImmutableList;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.plugin.readonlyrest.requestcontext.RequestContext;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.Block;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.BlockExitResult;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.RulesFactory;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.UserRuleFactory;
import org.elasticsearch.plugin.readonlyrest.acl.domain.Verbosity;
import org.elasticsearch.plugin.readonlyrest.ESContext;
import org.elasticsearch.plugin.readonlyrest.acl.definitions.DefinitionsFactory;
import org.elasticsearch.plugin.readonlyrest.settings.RorSettings;
import org.elasticsearch.plugin.readonlyrest.utils.FuturesSequencer;

import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import static org.elasticsearch.plugin.readonlyrest.Constants.ANSI_RED;
import static org.elasticsearch.plugin.readonlyrest.Constants.ANSI_RESET;

/**
 * Created by sscarduzio on 13/02/2016.
 */

public class ACL {

  private final Logger logger;
  private final RorSettings settings;
  // list because it preserves the insertion order
  private final ImmutableList<Block> blocks;

  public ACL(RorSettings settings, ESContext context) {
    this.settings = settings;
    this.logger = context.logger(getClass());
    final UserRuleFactory userRuleFactory = new UserRuleFactory(context);
    final DefinitionsFactory definitionsFactory = new DefinitionsFactory(userRuleFactory, context);
    final RulesFactory rulesFactory = new RulesFactory(definitionsFactory, userRuleFactory, context);
    this.blocks = ImmutableList.copyOf(
        settings.getBlocksSettings().stream()
            .map(blockSettings -> {
              Block block = new Block(blockSettings, rulesFactory, context);
              logger.info("ADDING #" + blockSettings.getName() + ":\t" + block.toString());
              return block;
            })
            .collect(Collectors.toList())
    );
  }

  public CompletableFuture<BlockExitResult> check(RequestContext rc) {
    logger.debug("checking request:" + rc.getId());
    return FuturesSequencer.runInSeqUntilConditionIsUndone(
        blocks.iterator(),
        block -> {
          rc.reset();
          return block.check(rc);
        },
        (block, checkResult) -> {
          if (checkResult.isMatch()) {
            if (Verbosity.INFO.equals(block.getVerbosity())) {
              logger.info("request: " + rc + " matched block: " + checkResult);
            }
            if (checkResult.getBlock().getPolicy().equals(BlockPolicy.ALLOW)) {
              rc.commit();
            }
            return true;
          } else {
            return false;
          }
        },
        nothing -> {
          if (Verbosity.INFO.equals(settings.getVerbosity())) {
            logger.info(ANSI_RED + " no block has matched, forbidding by default: " + rc + ANSI_RESET);
          }
          return BlockExitResult.noMatch();
        }
    );
  }

  public RorSettings getSettings() {
    return settings;
  }

  public boolean doesRequirePassword() {
    return blocks.stream().anyMatch(Block::isAuthHeaderAccepted) && settings.isPromptForBasicAuth();
  }
}
