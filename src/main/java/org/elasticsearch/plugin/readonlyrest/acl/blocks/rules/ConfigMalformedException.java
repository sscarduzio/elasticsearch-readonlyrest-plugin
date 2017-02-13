package org.elasticsearch.plugin.readonlyrest.acl.blocks.rules;

public class ConfigMalformedException extends RuntimeException {
    public ConfigMalformedException(String msg) {
        super(msg);
    }
}
