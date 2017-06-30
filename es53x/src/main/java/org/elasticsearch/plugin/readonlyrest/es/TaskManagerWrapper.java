package org.elasticsearch.plugin.readonlyrest.es;

import org.apache.logging.log4j.Logger;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.tasks.Task;
import org.elasticsearch.tasks.TaskAwareRequest;
import org.elasticsearch.tasks.TaskManager;
import org.elasticsearch.transport.TransportService;

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
   if(!action.startsWith("internal")) {
     ThreadRepo.taskId.set(t.getId());
   }
    return t;
  }

  public  boolean injectIntoTransportService(Object o,  Logger logger) {

    final boolean[] res = {false};
    AccessController.doPrivileged((PrivilegedAction<Void>) () -> {
      @SuppressWarnings("unchecked")
      Set<Field> indexFields = getAllFields(
        o.getClass(),
        (Field field) -> field != null &&  field.getType().equals(TaskManager.class)
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
