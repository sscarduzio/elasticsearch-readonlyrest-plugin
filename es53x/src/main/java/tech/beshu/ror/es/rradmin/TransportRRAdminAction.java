/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package tech.beshu.ror.es.rradmin;

import cz.seznam.euphoria.shaded.guava.com.google.common.util.concurrent.FutureCallback;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.support.ActionFilters;
import org.elasticsearch.action.support.HandledTransportAction;
import org.elasticsearch.client.node.NodeClient;
import org.elasticsearch.cluster.metadata.IndexNameExpressionResolver;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.TransportService;
import tech.beshu.ror.commons.settings.RawSettings;
import tech.beshu.ror.commons.settings.SettingsUtils;
import tech.beshu.ror.es.SettingsObservableImpl;

import static tech.beshu.ror.commons.Constants.REST_CONFIGURATION_FILE_PATH;
import static tech.beshu.ror.commons.Constants.REST_CONFIGURATION_PATH;
import static tech.beshu.ror.commons.Constants.REST_REFRESH_PATH;

public class TransportRRAdminAction extends HandledTransportAction<RRAdminRequest, RRAdminResponse> {


  private final NodeClient client;

  private final SettingsObservableImpl settingsObservable;

  @Inject
  public TransportRRAdminAction(Settings settings, ThreadPool threadPool, TransportService transportService,
                                ActionFilters actionFilters, IndexNameExpressionResolver indexNameExpressionResolver,
                                NodeClient client, SettingsObservableImpl settingsObservable) {
    super(settings, RRAdminAction.NAME, threadPool, transportService, actionFilters, indexNameExpressionResolver,
          RRAdminRequest::new
    );
    this.client = client;
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
          settingsObservable.refreshFromStringAndPersist(new RawSettings(SettingsUtils.extractYAMLfromJSONStorage(body)), new FutureCallback() {
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
            String currentSettingsJSON = settingsObservable.getFromFile().yaml();
            listener.onResponse(new RRAdminResponse(currentSettingsJSON));
          } catch (Exception e) {
            listener.onFailure(e);
          }
          return;
        }
        if (REST_CONFIGURATION_PATH.equals(normalisePath(path))) {
          String currentSettingsYAML = SettingsUtils.toJsonStorage(settingsObservable.getCurrent().yaml());
          System.out.println(currentSettingsYAML);
          listener.onResponse(new RRAdminResponse(currentSettingsYAML));
          return;
        }
      }

      listener.onFailure(new Exception("Didn't find anything to handle this request"));

    } catch (Exception e) {
      listener.onResponse(new RRAdminResponse(e));
    }

  }
}
