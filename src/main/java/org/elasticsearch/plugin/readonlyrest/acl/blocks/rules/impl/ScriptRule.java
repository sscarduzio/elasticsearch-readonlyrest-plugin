package org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl;

import jdk.nashorn.api.scripting.ScriptObjectMirror;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.plugin.readonlyrest.acl.RequestContext;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.Rule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.RuleExitResult;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.RuleNotConfiguredException;

import javax.script.Invocable;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;

/**
 * Created by sscarduzio on 14/02/2016.
 */
public class ScriptRule extends Rule {

  private final static ESLogger logger = Loggers.getLogger(ScriptRule.class);

  private Invocable inv = null;

  public ScriptRule(Settings s) throws RuleNotConfiguredException {
    super(s);
    String source = s.get(KEY);
    if(Strings.isNullOrEmpty(source)) {
      throw new RuleNotConfiguredException();
    }
    ScriptEngineManager manager = new ScriptEngineManager();
    ScriptEngine engine = manager.getEngineByName("nashorn");
    try {
      // parse code
      ScriptObjectMirror som = (ScriptObjectMirror) engine.eval(source);
      if(!som.isFunction()) {
        logger.error("the javascript snippet should be a function. Found: " + source);
        throw new RuleNotConfiguredException();
      }
      logger.info("Parsed script source: " + source + " " + som.isFunction() );
      inv = (Invocable) engine;
    } catch (ScriptException e) {
      e.printStackTrace();
      throw new RuleNotConfiguredException();
    }
  }

  @Override
  public RuleExitResult match(RequestContext rc) {
    String cause = "script returned false";
    try {
      // call function from script file
      Boolean result = (Boolean) inv.invokeFunction("onRequest", rc);
      if (result) {
        return MATCH;
      }
    } catch (ScriptException e) {
      e.printStackTrace();
      cause = e.getMessage();
    } catch (NoSuchMethodException e) {
      e.printStackTrace();
      cause = e.getMessage();
    }
    logger.debug("OnRequest script did not match. Cause: " + cause);
    return NO_MATCH;
  }
}
