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

package org.elasticsearch.plugin.readonlyrest;

import org.elasticsearch.action.ActionModule;
import org.elasticsearch.http.HttpServerModule;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.rest.RestModule;

public class ReadonlyRestPlugin extends Plugin {

  @Override
  public String name() {
    return "readonlyrest";
  }

  @Override
  public String description() {
    return "Reject attempts to change data, so we can expose this REST API to clients";
  }

  public void onModule(RestModule module) {
    module.addRestAction(ReadonlyRestAction.class);
  }

  public void onModule(HttpServerModule module) {
    module.setHttpServerTransport(SSLTransport.class, this.getClass().getSimpleName());
  }

  public void onModule(final ActionModule module) {
    module.registerFilter(IndexLevelActionFilter.class);
  }

}
