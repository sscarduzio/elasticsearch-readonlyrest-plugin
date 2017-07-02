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
import org.elasticsearch.plugin.readonlyrest.ESContext;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.Block;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.BlockExitResult;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.RulesFactory;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.UserRuleFactory;
import org.elasticsearch.plugin.readonlyrest.acl.definitions.DefinitionsFactory;
import org.elasticsearch.plugin.readonlyrest.audit.AuditSinkShim;
import org.elasticsearch.plugin.readonlyrest.requestcontext.RequestContext;
import org.elasticsearch.plugin.readonlyrest.requestcontext.ResponseContext;
import org.elasticsearch.plugin.readonlyrest.settings.RorSettings;
import org.elasticsearch.plugin.readonlyrest.utils.FuturesSequencer;

import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import static org.elasticsearch.plugin.readonlyrest.requestcontext.ResponseContext.FinalState.ALLOWED;
import static org.elasticsearch.plugin.readonlyrest.requestcontext.ResponseContext.FinalState.FORBIDDEN;

/**
 * Created by sscarduzio on 13/02/2016.
 */

public class ACL {

  private final Logger logger;
  private final RorSettings settings;
  // list because it preserves the insertion order
  private final ImmutableList<Block> blocks;

  private final UserRuleFactory userRuleFactory;

  private final DefinitionsFactory definitionsFactory;
  private AuditSinkShim audit;

  public ACL(RorSettings settings, ESContext context, AuditSinkShim audit) {
    this.settings = settings;
    this.logger = context.logger(getClass());

    this.userRuleFactory = new UserRuleFactory(context, this);
    this.definitionsFactory = new DefinitionsFactory(context, this);
    this.audit = audit;
    final RulesFactory rulesFactory = new RulesFactory(definitionsFactory, userRuleFactory, context);

    this.blocks = ImmutableList.copyOf(
      settings.getBlocksSettings().stream()
        .map(blockSettings -> {
          Block block = new Block(blockSettings, rulesFactory, context);
          logger.info("ADDING BLOCK #" + blockSettings.getName() + ":\t" + block.toString());
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
          boolean isAllowed = checkResult.getBlock().getPolicy().equals(BlockPolicy.ALLOW);
          audit.log(new ResponseContext(isAllowed ? ALLOWED : FORBIDDEN, rc, null, checkResult), logger);
          if (isAllowed) {
            rc.commit();
          }
          return true;
        }
        audit.log(new ResponseContext( FORBIDDEN, rc, null, checkResult), logger);
        return false;
      },
      nothing -> BlockExitResult.noMatch()
    );
  }

  public RorSettings getSettings() {
    return settings;
  }

  public UserRuleFactory getUserRuleFactory() {
    return userRuleFactory;
  }

  public DefinitionsFactory getDefinitionsFactory() {
    return definitionsFactory;
  }

  public boolean doesRequirePassword() {
    return blocks.stream().anyMatch(Block::isAuthHeaderAccepted) && settings.isPromptForBasicAuth();
  }
}
