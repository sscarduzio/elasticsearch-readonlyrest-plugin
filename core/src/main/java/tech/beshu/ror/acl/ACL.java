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

package tech.beshu.ror.acl;

import com.google.common.collect.ImmutableList;
import tech.beshu.ror.AuditSinkCore;
import tech.beshu.ror.acl.blocks.Block;
import tech.beshu.ror.acl.blocks.BlockExitResult;
import tech.beshu.ror.acl.blocks.rules.Rule;
import tech.beshu.ror.acl.blocks.rules.RulesFactory;
import tech.beshu.ror.acl.blocks.rules.UserRuleFactory;
import tech.beshu.ror.acl.definitions.DefinitionsFactory;
import tech.beshu.ror.commons.BasicSettings;
import tech.beshu.ror.commons.SecurityPermissionException;
import tech.beshu.ror.requestcontext.RequestContext;
import tech.beshu.ror.settings.RorSettings;
import tech.beshu.ror.commons.shims.ACLHandler;
import tech.beshu.ror.commons.shims.ESContext;
import tech.beshu.ror.commons.shims.LoggerShim;
import tech.beshu.ror.commons.shims.ResponseContext;
import tech.beshu.ror.utils.FuturesSequencer;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Created by sscarduzio on 13/02/2016.
 */

public class ACL {

  private final LoggerShim logger;
  private final BasicSettings settings;
  // list because it preserves the insertion order
  private final ImmutableList<Block> blocks;

  private final UserRuleFactory userRuleFactory;

  private final DefinitionsFactory definitionsFactory;
  private final RorSettings rorSettings;
  private AuditSinkCore audit;

  public ACL(BasicSettings settings, ESContext context, AuditSinkCore audit) {
    this.rorSettings = new RorSettings(settings.getRaw());
    this.settings = settings;
    this.logger = context.logger(getClass());

    this.userRuleFactory = new UserRuleFactory(context, this);
    this.definitionsFactory = new DefinitionsFactory(context, this);
    this.audit = audit;
    final RulesFactory rulesFactory = new RulesFactory(definitionsFactory, userRuleFactory, context);

    this.blocks = ImmutableList.copyOf(
      rorSettings.getBlocksSettings().stream()
        .map(blockSettings -> {
          try {
            Block block = new Block(blockSettings, rulesFactory, context);
            logger.info("ADDING BLOCK:\t" + block.toString());
            return block;
          } catch (Throwable t) {
            logger.error("Impossible to add block to ACL: " + blockSettings.getName() +
                           " Reason: [" + t.getClass().getSimpleName() + "] " + t.getMessage(), t);
            return null;
          }
        })
        .filter(Objects::nonNull)
        .collect(Collectors.toList())
    );
  }

  public static boolean shouldSkipACL(boolean chanNull, boolean reqNull) {

    // This was not a REST message
    if (reqNull && chanNull) {
      return true;
    }

    // Bailing out in case of catastrophical misconfiguration that would lead to insecurity
    if (reqNull != chanNull) {
      if (chanNull) {
        throw new SecurityPermissionException("Problems analyzing the channel object. " +
                                                "Have you checked the security permissions?", null);
      }
      if (reqNull) {
        throw new SecurityPermissionException("Problems analyzing the request object. " + "Have you checked the security permissions?", null);
      }
    }
    return false;
  }

  public void check(RequestContext rc, ACLHandler h) {
    doCheck(rc)
      .exceptionally(throwable -> {
        if (h.isNotFound(throwable)) {
          logger.warn("Resource not found! ID: " + rc.getId() + "  " + throwable.getCause().getMessage());
          h.onNotFound(throwable);
          audit.log(new ResponseContext(ResponseContext.FinalState.NOT_FOUND, rc, throwable, null, "not found", false), logger);

          return null;
        }
        throwable.printStackTrace();
        h.onErrored(throwable);
        audit.log(new ResponseContext(ResponseContext.FinalState.ERRORED, rc, throwable, null, "error", false), logger);
        return null;
      })
      .thenApply(result -> {
        assert result != null;

        if (result.isMatch() && BlockPolicy.ALLOW.equals(result.getBlock().getPolicy())) {
          h.onAllow(result);
          return null;
        }
        h.onForbidden();
        return null;
      });
  }

  public boolean responseOkHook(RequestContext rc, Object blockExitResult, Object actionRsponse) {
    BlockExitResult result = (BlockExitResult) blockExitResult;

    for (Rule r : result.getBlock().getRules()) {
      //#TODO check response listeners of rules
    }

    return true; // #TODO or false
  }

  private CompletableFuture<BlockExitResult> doCheck(RequestContext rc) {
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
          audit.log(
            new ResponseContext(
              isAllowed ? ResponseContext.FinalState.ALLOWED : ResponseContext.FinalState.FORBIDDEN,
              rc,
              null,
              checkResult.getBlock().getVerbosity(),
              checkResult.getBlock().toString(),
              true
            ),
            logger
          );
          if (isAllowed) {
            rc.commit();
          }
          return true;
        }
        return false;
      },
      nothing -> {
        audit.log(new ResponseContext(ResponseContext.FinalState.FORBIDDEN, rc, null, null, "default", false), logger);
        return BlockExitResult.noMatch();
      }
    )
      .exceptionally(t -> {
        //t.printStackTrace();
        return BlockExitResult.noMatch();
      });
  }

  public RorSettings getSettings() {
    return rorSettings;
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
