package org.elasticsearch.plugin.readonlyrest;

import org.elasticsearch.common.inject.Module;
import org.elasticsearch.plugins.AbstractPlugin;
import org.elasticsearch.rest.RestModule;
import org.elasticsearch.rest.action.readonlyrest.ReadonlyRestAction;

public class ReadonlyRestPlugin extends AbstractPlugin {

    public String name() {
        return "readonlyrest";
    }

    public String description() {
        return "Reject attempt to change data, so we can expose this REST API to clients";
    }

    @Override public void processModule(Module module) {
        if (module instanceof RestModule) {
        	  ((RestModule) module).addRestAction(ReadonlyRestAction.class);
        }
    }
}
