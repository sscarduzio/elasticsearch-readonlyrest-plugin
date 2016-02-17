package org.elasticsearch.plugin.readonlyrest.readonlyrest.acl;

/**
 * Created by sscarduzio on 12/07/2015.
 */
public class RuleConfigurationError extends RuntimeException {
    public RuleConfigurationError(String msg, Throwable cause) {
        super(msg, cause);
    }
}
