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

package tech.beshu.ror.es.rradmin;

import com.google.common.util.concurrent.FutureCallback;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.support.ActionFilters;
import org.elasticsearch.action.support.HandledTransportAction;
import org.elasticsearch.cluster.metadata.IndexNameExpressionResolver;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.TransportService;
import tech.beshu.ror.es.ResponseActionListener;
import tech.beshu.ror.es.SettingsObservableImpl;
import tech.beshu.ror.settings.__old_RawSettings;
import tech.beshu.ror.settings.__old_SettingsUtils;

import static tech.beshu.ror.Constants.REST_CONFIGURATION_FILE_PATH;
import static tech.beshu.ror.Constants.REST_CONFIGURATION_PATH;
import static tech.beshu.ror.Constants.REST_METADATA_PATH;
import static tech.beshu.ror.Constants.REST_REFRESH_PATH;

public class TransportRRAdminAction extends HandledTransportAction<RRAdminRequest, RRAdminResponse> {

  private final SettingsObservableImpl settingsObservable;

  @Inject
  public TransportRRAdminAction(Settings settings, ThreadPool threadPool, TransportService transportService,
                                ActionFilters actionFilters, IndexNameExpressionResolver indexNameExpressionResolver,
                                SettingsObservableImpl settingsObservable) {
    super(settings, RRAdminAction.NAME, threadPool, transportService, actionFilters, indexNameExpressionResolver,
          RRAdminRequest::new
    );
    this.settingsObservable = settingsObservable;
  }

  private String normalisePath(String s) {
    return s.substring(0, s.length() - (s.endsWith("/") ? 1 : 0));
  }

  @Override
  protected void doExecute(RRAdminRequest request, ActionListener<RRAdminResponse> listener) {
    try {
      String method = request.getMethod().toUpperCase();
      String body = request.getContent();
      String path = request.getPath();

      if ("POST".equals(method)) {
        if (REST_REFRESH_PATH.equals(normalisePath(path))) {
          settingsObservable.refreshFromIndex();
          listener.onResponse(new RRAdminResponse("ok refreshed"));
          return;
        }
        if (REST_CONFIGURATION_PATH.equals(normalisePath(path))) {
          if (body.length() == 0) {
            listener.onFailure(new Exception("empty body"));
            return;
          }
          // Can throw SettingsMalformedException
          settingsObservable.refreshFromStringAndPersist(new __old_RawSettings(__old_SettingsUtils.extractYAMLfromJSONStorage(body), settingsObservable.getCurrent().getLogger()), new FutureCallback() {
            @Override
            public void onSuccess(Object result) {
              listener.onResponse(new RRAdminResponse("updated settings"));
            }

            @Override
            public void onFailure(Throwable t) {
              listener.onFailure(new Exception("could not update settings ", t));
            }
          });
          return;
        }
      }

      if ("GET".equals(method)) {
        if (REST_CONFIGURATION_FILE_PATH.equals(normalisePath(path))) {
          try {
            String currentSettingsYAML = settingsObservable.getFromFile().yaml();
            listener.onResponse(new RRAdminResponse(currentSettingsYAML));
          } catch (Exception e) {
            listener.onFailure(e);
          }
          return;
        }
        if (REST_CONFIGURATION_PATH.equals(normalisePath(path))) {
          String currentSettingsYAML =settingsObservable.getCurrent().yaml();
          listener.onResponse(new RRAdminResponse(currentSettingsYAML));
          return;
        }
        // This route just needs to exist
        if (REST_METADATA_PATH.equals(normalisePath(path))) {
          listener.onResponse(new RRAdminResponse("<placeholder>"));
          return;
        }
        if (REST_METADATA_PATH.equals(normalisePath(path))) {
          listener.onResponse(new RRAdminResponse("will be filled in " + ResponseActionListener.class.getSimpleName()));
          return;
        }
      }

      listener.onFailure(new Exception("Didn't find anything to handle this request"));

    } catch (Exception e) {
      listener.onResponse(new RRAdminResponse(e));
    }
  }
}
