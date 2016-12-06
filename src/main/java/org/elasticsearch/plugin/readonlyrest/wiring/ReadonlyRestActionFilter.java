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

import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.ActionRequest;
import org.elasticsearch.action.ActionResponse;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.support.ActionFilter;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.settings.Settings;

import java.util.Set;

import static java.util.Collections.emptySet;

/**
 * Created by sscarduzio on 17/11/2016.
 */
public class ReadonlyRestActionFilter extends ActionFilter.Simple {
  private Set<String> blockedActions = emptySet();

  @Inject
  public ReadonlyRestActionFilter(Settings settings) {
    super(settings);
  }


  @Override
  protected boolean apply(String action, ActionRequest<?> request, ActionListener<?> listener) {
    // Here I can get the action, indices
    System.out.println("actionfilter: " + Thread.currentThread().getName() + " method: " + ThreadRepo.request.get().method());
    System.out.println(request);
//    for( Method m : listener.getClass().getDeclaredMethods()){
//      m.setAccessible(true);
//      System.out.println(m.getName());
//    }
    if (request instanceof SearchRequest ) {
      SearchRequest sr = ((SearchRequest) request);
      System.out.println(sr.remoteAddress());
    }
//    else throw new ElasticsearchException("force exception on [" + action + "]");

    return true;
  }

  @Override
  protected boolean apply(String action, ActionResponse response, ActionListener<?> listener) {
    return true;
  }

  @Override
  public int order() {
    return 0;
  }

}
