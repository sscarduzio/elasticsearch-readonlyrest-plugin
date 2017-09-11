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

package org.elasticsearch.plugin.readonlyrest.configuration;

import org.elasticsearch.plugin.readonlyrest.ESContext;

import java.util.Map;

/**
 * Created by sscarduzio on 25/06/2017.
 */
public interface SettingsManager {
  Map<String, ?> getCurrentSettings(String path);
  
  Map<String, ?> reloadSettingsFromIndex();

  boolean isClusterReady();

  ESContext getContext();
}
