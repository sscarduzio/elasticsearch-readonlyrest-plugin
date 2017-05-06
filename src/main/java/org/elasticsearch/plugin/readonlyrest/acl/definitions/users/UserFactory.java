package org.elasticsearch.plugin.readonlyrest.acl.definitions.users;

import org.elasticsearch.plugin.readonlyrest.settings.definitions.UserSettings;

public interface UserFactory {
  User getUser(UserSettings settings);
}
