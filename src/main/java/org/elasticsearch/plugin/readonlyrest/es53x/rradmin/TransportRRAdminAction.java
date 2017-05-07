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

package org.elasticsearch.plugin.readonlyrest.es53x.rradmin;

import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.support.ActionFilters;
import org.elasticsearch.action.support.HandledTransportAction;
import org.elasticsearch.client.node.NodeClient;
import org.elasticsearch.cluster.metadata.IndexNameExpressionResolver;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.plugin.readonlyrest.configuration.ConfigurationContentProvider;
import org.elasticsearch.plugin.readonlyrest.configuration.ReloadableConfiguration;
import org.elasticsearch.plugin.readonlyrest.es53x.ESClientConfigurationContentProvider;
import org.elasticsearch.plugin.readonlyrest.es53x.ReloadableConfigurationImpl;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.TransportService;

public class TransportRRAdminAction extends HandledTransportAction<RRAdminRequest, RRAdminResponse> {

  private final ConfigurationContentProvider client;
  private final ReloadableConfiguration reloadableConfiguration;

  @Inject
  public TransportRRAdminAction(Settings settings, ThreadPool threadPool, TransportService transportService,
                                ActionFilters actionFilters, IndexNameExpressionResolver indexNameExpressionResolver,
                                NodeClient client, ReloadableConfigurationImpl reloadableConfiguration) {
    super(settings, RRAdminAction.NAME, threadPool, transportService, actionFilters, indexNameExpressionResolver,
        RRAdminRequest::new);
    this.client = new ESClientConfigurationContentProvider(client);
    this.reloadableConfiguration = reloadableConfiguration;
  }

  @Override
  protected void doExecute(RRAdminRequest request, ActionListener<RRAdminResponse> listener) {
    try {
      reloadableConfiguration.reload(client);
      listener.onResponse(new RRAdminResponse(null));
    } catch (Exception e) {
      listener.onResponse(new RRAdminResponse(e));
    }

  }
}
