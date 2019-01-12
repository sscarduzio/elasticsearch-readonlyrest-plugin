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

package tech.beshu.ror.unit.acl;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import tech.beshu.ror.unit.acl.blocks.__old_Block;
import tech.beshu.ror.unit.acl.blocks.__old_BlockExitResult;
import tech.beshu.ror.unit.acl.blocks.rules.__old_Rule;
import tech.beshu.ror.unit.acl.blocks.rules.RulesFactory;
import tech.beshu.ror.unit.acl.blocks.rules.UserRuleFactory;
import tech.beshu.ror.unit.acl.blocks.rules.impl.FilterSyncRule;
import tech.beshu.ror.unit.acl.definitions.DefinitionsFactory;
import tech.beshu.ror.commons.Constants;
import tech.beshu.ror.commons.ResponseContext;
import tech.beshu.ror.commons.ResponseContext.FinalState;
import tech.beshu.ror.commons.SecurityPermissionException;
import tech.beshu.ror.commons.Verbosity;
import tech.beshu.ror.commons.shims.es.__old_ACLHandler;
import tech.beshu.ror.commons.shims.es.ESContext;
import tech.beshu.ror.commons.shims.es.LoggerShim;
import tech.beshu.ror.commons.shims.request.RequestInfoShim;
import tech.beshu.ror.httpclient.HttpMethod;
import tech.beshu.ror.requestcontext.__old_RequestContext;
import tech.beshu.ror.requestcontext.SerializationTool;
import tech.beshu.ror.settings.RorSettings;
import tech.beshu.ror.utils.FuturesSequencer;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Created by sscarduzio on 13/02/2016.
 */

public class __old_ACL {

  private final LoggerShim logger;
  // list because it preserves the insertion order
  private final ImmutableList<__old_Block> blocks;

  private final UserRuleFactory userRuleFactory;

  private final DefinitionsFactory definitionsFactory;
  private final RorSettings rorSettings;
  private final SerializationTool serTool;
  private final boolean involvesFilter;
  private ESContext context;

  public __old_ACL(ESContext context) {
    serTool = context.getSettings().isAuditorCollectorEnabled() ? new SerializationTool(context) : null;
    this.rorSettings = new RorSettings(context.getSettings().getRaw());
    this.logger = context.logger(getClass());
    this.context = context;
    this.userRuleFactory = new UserRuleFactory(context, this);
    this.definitionsFactory = new DefinitionsFactory(context, this);
    final RulesFactory rulesFactory = new RulesFactory(definitionsFactory, userRuleFactory, context);

    this.blocks = ImmutableList.copyOf(
      rorSettings.getBlocksSettings().stream()
        .map(blockSettings -> {
          try {
            __old_Block block = new __old_Block(blockSettings, rulesFactory, context);
            logger.info("ADDING BLOCK:\t" + block.toString());
            return block;
          } catch (Throwable t) {
            logger.error("> Impossible to add block to __old_ACL: " + blockSettings.getName() +
              " Reason: [" + t.getClass().getSimpleName() + "] " + t.getMessage(), t);
            t.printStackTrace();
            if (t.getCause() != null) {
              logger.error("caused by " + t.getCause().getClass().getSimpleName() + " " + t.getCause().getMessage());
            }
            return null;
          }
        })
        .filter(Objects::nonNull)
        .collect(Collectors.toList())
    );

    this.involvesFilter = blocks
      .stream()
      .anyMatch(b ->
        b.getSettings()
          .getRules()
          .stream()
          .anyMatch(r -> FilterSyncRule.Settings.ATTRIBUTE_NAME.equals(r.getName()))
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

  public ESContext getContext() {
    return context;
  }

  private void doLog(ResponseContext res) {

    FinalState fState = res.finalState();
    boolean skipLog = fState.equals(FinalState.ALLOWED) &&
      !Verbosity.INFO.equals(res.getVerbosity());

    if (skipLog) {
      return;
    }

    String color;
    switch (fState) {
      case FORBIDDEN:
        color = Constants.ANSI_PURPLE;
        break;
      case ALLOWED:
        color = Constants.ANSI_CYAN;
        break;
      case ERRORED:
        color = Constants.ANSI_RED;
        break;
      case NOT_FOUND:
        color = Constants.ANSI_YELLOW;
        break;
      default:
        color = Constants.ANSI_WHITE;
    }
    StringBuilder sb = new StringBuilder();
    sb
      .append(color)
      .append(fState.name())
      .append(" by ")
      .append(res.getReason())
      .append(" req=")
      .append(res.getRequestContext())
      .append(" ")
      .append(Constants.ANSI_RESET);

    logger.info(sb.toString());

    // Audit logs in index
    if (context.getSettings().isAuditorCollectorEnabled()) {
      context.submit(serTool.mkIndexName(), res.getRequestContext().getId(), serTool.toJson(res));
    }
  }

  public void check(RequestInfoShim rInfo, __old_ACLHandler h) {
    __old_RequestContext rc = mkRequestContext(rInfo);

    // Run the blocks through
    doCheck(rc)

      // Handle different exceptions meanings
      .exceptionally(throwable -> {
        if (h.isNotFound(throwable)) {
          logger.warn("Resource not found! ID: " + rc.getId() + "  " + throwable.getCause().getMessage());
          h.onNotFound(throwable);
          doLog(new ResponseContext(FinalState.NOT_FOUND, rc, throwable, null, "not found", false));

          return null;
        }
        throwable.printStackTrace();
        h.onErrored(throwable);
        doLog(new ResponseContext(FinalState.ERRORED, rc, throwable, null, "error", false));
        return null;
      })

      // FINAL stage: either is match or it isn't.
      .thenApply(result -> {
        assert result != null;

        // NO MATCH
        if (!result.isMatch()) {
          h.onForbidden();
          doLog(new ResponseContext(FinalState.FORBIDDEN, rc, null, null, "default", false));
          return null;
        }

        // MATCH AN ALLOW BLOCK
        if (__old_BlockPolicy.ALLOW.equals(result.getBlock().getPolicy())) {
          h.onAllow(result, rc);
          doLog(new ResponseContext(FinalState.ALLOWED, rc, null, result.getBlock().getVerbosity(), result.getBlock().toString(), true));
          return null;
        }

        // MATCH A FORBIDDEN BLOCK
        else {
          doLog(new ResponseContext(FinalState.FORBIDDEN, rc, null, result.getBlock().getVerbosity(), result.getBlock().toString(), true));
          h.onForbidden();
          return null;
        }
      })
      .exceptionally(th -> {
        th.printStackTrace();
        doLog(new ResponseContext(FinalState.ERRORED, rc, th, null, "error", false));
        h.onErrored(th);
        return null;
      });
  }

  private CompletableFuture<__old_BlockExitResult> doCheck(__old_RequestContext rc) {
    logger.debug("checking request:" + rc.getId());
    return FuturesSequencer.runInSeqUntilConditionIsUndone(
      blocks.iterator(),
      block -> {
        rc.reset();
        return block.check(rc);
      },
      (block, checkResult) -> {

        if (checkResult.isMatch()) {
          boolean isAllowed = checkResult.getBlock().getPolicy().equals(__old_BlockPolicy.ALLOW);
          if (isAllowed) {
            rc.commit();
          }
          return true;
        }
        return false;
      },
      nothing -> __old_BlockExitResult.noMatch()

    );
  }

  private __old_RequestContext mkRequestContext(RequestInfoShim rInfo) {
    return new __old_RequestContext("rc", context) {

      @Override
      public Set<String> extractSnapshots() {
        return rInfo.extractSnapshots();
      }

      @Override
      protected void writeSnapshots(Set<String> newSnapshots) {
        rInfo.writeSnapshots(newSnapshots);
      }

      @Override
      public Set<String> extractRepositories() {
        return rInfo.extractRepositories();
      }

      @Override
      protected void writeRepositories(Set<String> newRepos) {
        rInfo.writeRepositories(newRepos);
      }

      @Override
      public Set<String> getExpandedIndices(Set<String> i) {
        return rInfo.getExpandedIndices(i);
      }

      @Override
      public Set<String> getAllIndicesAndAliases() {
        return rInfo.extractAllIndicesAndAliases();
      }

      @Override
      protected Boolean extractDoesInvolveIndices() {
        return rInfo.involvesIndices();
      }

      @Override
      protected void commitResponseHeaders(Map<String, String> hmap) {
        // Setting headers with null value makes a mess in Netty
        hmap.values().removeAll(Collections.singleton(null));
        rInfo.writeResponseHeaders(hmap);
      }

      @Override
      public String getAction() {
        return rInfo.extractAction();
      }

      @Override
      public String getId() {
        return rInfo.extractId();
      }

      @Override
      public String getContent() {
        return rInfo.extractContent();
      }

      @Override
      public Integer getContentLength() {
        return rInfo.extractContentLength();
      }

      @Override
      public HttpMethod getMethod() {
        return HttpMethod.fromString(rInfo.extractMethod())
          .orElseThrow(() -> context.rorException("unrecognised HTTP method " + rInfo.extractMethod()));
      }

      @Override
      public String getMethodString() {
        return getMethod().name();
      }

      @Override
      public Optional<String> getLoggedInUserName() {
        return getLoggedInUser().map(u -> u.getId());
      }

      @Override
      public String getUri() {
        return rInfo.extractURI();
      }

      @Override
      public String getHistoryString() {
        return Joiner.on(", ").join(getHistory());
      }

      @Override
      public String getType() {
        return rInfo.extractType();
      }

      @Override
      public Long getTaskId() {
        return rInfo.extractTaskId();
      }

      @Override
      public String getRemoteAddress() {
        return rInfo.extractRemoteAddress();
      }

      @Override
      public String getLocalAddress() {
        return rInfo.extractLocalAddress();
      }

      @Override
      protected Map<String, String> extractRequestHeaders() {
        return rInfo.extractRequestHeaders();
      }

      @Override
      protected String extractContextHeader(String key) {
        return rInfo.consumeThreadContextHeader(key);
      }

      @Override
      protected void writeContextHeader(String key, String value) {
        rInfo.writeToThreadContextHeader(key, value);
      }

      @Override
      public void writeIndices(Set<String> newIndices) {
        rInfo.writeIndices(newIndices);
      }

      @Override
      protected Set<String> extractIndices() {
        return rInfo.extractIndices();
      }

      @Override
      public Boolean extractIsReadRequest() {
        return rInfo.extractIsReadRequest();
      }

      @Override
      protected Boolean extractIsCompositeRequest() {
        return rInfo.extractIsCompositeRequest();
      }
    };
  }

  public boolean responseOkHook(__old_RequestContext rc, Object blockExitResult, Object actionRsponse) {
    __old_BlockExitResult result = (__old_BlockExitResult) blockExitResult;

    for (__old_Rule r : result.getBlock().getRules()) {
      //#TODO check response listeners of rules
    }

    return true; // #TODO or false
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
    return blocks.stream().anyMatch(__old_Block::isAuthHeaderAccepted) && rorSettings.isPromptForBasicAuth();
  }

  public boolean involvesFilter() {
    return involvesFilter;
  }
}
