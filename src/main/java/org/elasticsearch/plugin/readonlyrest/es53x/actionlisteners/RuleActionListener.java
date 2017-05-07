package org.elasticsearch.plugin.readonlyrest.es53x.actionlisteners;

import org.elasticsearch.action.ActionRequest;
import org.elasticsearch.action.ActionResponse;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.BlockExitResult;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.AsyncRuleAdapter;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.Rule;
import org.elasticsearch.plugin.readonlyrest.requestcontext.RequestContext;

import java.util.Objects;

public abstract class RuleActionListener<T extends Rule> {

  private final Class<T> ruleClass;

  public RuleActionListener(Class<T> ruleClass) {
    this.ruleClass = ruleClass;
  }

  /**
   * This hook is only called if the result is a match.
   *
   * @param result   BlockExitResult rules block exit result
   * @param rc       RequestContext
   * @param ar       ActionRequest
   * @param response ActionResponse
   * @return should continue to process the handlers' pipeline
   */
  public boolean onResponse(Rule rule, BlockExitResult result, RequestContext rc, ActionRequest ar, ActionResponse response) {
    return onResponse(result, rc, ar, response, extractFrom(rule));
  }

  protected abstract boolean onResponse(BlockExitResult result,
                                        RequestContext rc,
                                        ActionRequest ar,
                                        ActionResponse response,
                                        T rule);

  /**
   * This hook is called before throwing the exception e.
   * <p>
   * Either rethrow or throw a new exception.
   * #XXX DANGER: if you return false without throwing or writing to the channel, the connection will hang!
   *
   * @param result BlockExitResult rules block exit result
   * @param rc     RequestContext
   * @param ar     ActionRequest
   * @param e      The occurred exception
   * @return should continue to process the handlers' pipeline
   */
  public boolean onFailure(Rule rule, BlockExitResult result, RequestContext rc, ActionRequest ar, Exception e) {
    return onFailure(result, rc, ar, e, extractFrom(rule));
  }

  protected abstract boolean onFailure(BlockExitResult result, RequestContext rc, ActionRequest ar, Exception e, T rule);

  @SuppressWarnings("unchecked")
  private T extractFrom(Rule rule) {
    Rule innerRule = rule instanceof AsyncRuleAdapter
        ? ((AsyncRuleAdapter) rule).getUnderlying()
        : rule;

    if (Objects.equals(innerRule.getClass(), ruleClass)) {
      return (T) innerRule;
    } else {
      throw new IllegalStateException("Class '" + innerRule.getClass().getName() + "' is not equal to '" + ruleClass
          + "' class. Check " + RuleActionListenersProvider.class.getName() + " class implementation - it must be" +
          "a programmer mistake");
    }
  }
}
