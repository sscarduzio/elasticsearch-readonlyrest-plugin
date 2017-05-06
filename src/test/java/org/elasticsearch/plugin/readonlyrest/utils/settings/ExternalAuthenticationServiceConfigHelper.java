///*
// *    This file is part of ReadonlyREST.
// *
// *    ReadonlyREST is free software: you can redistribute it and/or modify
// *    it under the terms of the GNU General Public License as published by
// *    the Free Software Foundation, either version 3 of the License, or
// *    (at your option) any later version.
// *
// *    ReadonlyREST is distributed in the hope that it will be useful,
// *    but WITHOUT ANY WARRANTY; without even the implied warranty of
// *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// *    GNU General Public License for more details.
// *
// *    You should have received a copy of the GNU General Public License
// *    along with ReadonlyREST.  If not, see http://www.gnu.org/licenses/
// */
//package org.elasticsearch.plugin.readonlyrest.utils.settings;
//
//import org.elasticsearch.common.settings.Settings;
//import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.UserGroupProviderConfig;
//
//import java.net.URI;
//
//public class ExternalAuthenticationServiceConfigHelper {
//
//  public static Settings create(String name, URI endpoint, int successStatusCode) {
//    return Settings.builder()
//        .put("external_authentication_service_configs.0.name", name)
//        .put("external_authentication_service_configs.0.authentication_endpoint", endpoint.toString())
//        .put("external_authentication_service_configs.0.success_status_code", successStatusCode)
//        .build();
//  }
//}
