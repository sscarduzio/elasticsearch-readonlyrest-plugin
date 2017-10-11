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

package tech.beshu.ror.es;

import org.elasticsearch.common.settings.Settings;
import tech.beshu.ror.commons.shims.es.LoggerShim;
import org.elasticsearch.tasks.Task;
import org.elasticsearch.tasks.TaskAwareRequest;
import org.elasticsearch.tasks.TaskManager;

import java.lang.reflect.Field;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.Set;

import static org.reflections.ReflectionUtils.getAllFields;

/**
 * Created by sscarduzio on 14/06/2017.
 */
public class TaskManagerWrapper extends TaskManager {

  public TaskManagerWrapper(Settings settings) {
    super(settings);
  }

  @Override
  public Task register(String type, String action, TaskAwareRequest request) {
    Task t = super.register(type, action, request);
    if (!action.startsWith("internal")) {
      ThreadRepo.taskId.set(t.getId());
    }
    return t;
  }

  public boolean injectIntoTransportService(Object o, LoggerShim logger) {

    final boolean[] res = {false};
    AccessController.doPrivileged((PrivilegedAction<Void>) () -> {
      @SuppressWarnings("unchecked")
      Set<Field> indexFields = getAllFields(
        o.getClass(),
        (Field field) -> field != null && field.getType().equals(TaskManager.class)
      );
      for (Field f : indexFields) {
        f.setAccessible(true);
        try {
          if (f.getType().equals(TaskManager.class)) {
            f.set(o, this);
          }
          res[0] = true;
        } catch (IllegalAccessException | IllegalArgumentException e) {
          logger.error("could not inject task manaager wrapper into transport service: " +
                         e.getMessage());
        }
      }
      return null;
    });
    return res[0];
  }

}
