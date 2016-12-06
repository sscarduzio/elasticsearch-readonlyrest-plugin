/*
 * This file is part of ReadonlyREST.
 *
 *     ReadonlyREST is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     ReadonlyREST is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with ReadonlyREST.  If not, see <http://www.gnu.org/licenses/>.
 *
 */

package org.elasticsearch.plugin.readonlyrest.wiring;

import org.elasticsearch.action.support.ActionFilter;
import org.elasticsearch.common.network.NetworkModule;
import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.plugin.readonlyrest.ConfigurationHelper;
import org.elasticsearch.plugin.readonlyrest.IndexLevelActionFilter;
import org.elasticsearch.plugin.readonlyrest.SSLTransportNetty4;
import org.elasticsearch.plugins.ActionPlugin;
import org.elasticsearch.plugins.IngestPlugin;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.plugins.ScriptPlugin;
import org.elasticsearch.rest.RestHandler;

import java.util.Collections;
import java.util.List;

public class ReadonlyRestPlugin extends Plugin implements ScriptPlugin, ActionPlugin, IngestPlugin {
  @Override
  public List<Class<? extends ActionFilter>> getActionFilters() {
    return Collections.singletonList(IndexLevelActionFilter.class);
  }

  // This gets called by reflection by the framework. GO FIGURE.
  public void onModule(NetworkModule module) {
    module.registerHttpTransport("ssl_netty4", SSLTransportNetty4.class);
  }

  @Override
  public List<Class<? extends RestHandler>> getRestHandlers() {
    return Collections.singletonList(ReadonlyRestRestAction.class);
  }

  @Override
  public List<Setting<?>> getSettings() {
    return ConfigurationHelper.allowedSettings();
  }

//    @Override
//    public Map<String, Processor.Factory> getProcessors(Processor.Parameters parameters) {
//        return MapBuilder.<String, Processor.Factory>newMapBuilder()
//                .put(AwesomeProcessor.TYPE, new AwesomeProcessor.Factory())
//                .immutableMap();
//    }

}
