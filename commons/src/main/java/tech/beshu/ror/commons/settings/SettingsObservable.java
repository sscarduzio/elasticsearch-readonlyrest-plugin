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

package tech.beshu.ror.commons.settings;

import cz.seznam.euphoria.shaded.guava.com.google.common.util.concurrent.FutureCallback;
import tech.beshu.ror.commons.shims.es.ESContext;
import tech.beshu.ror.commons.shims.es.ESVersion;
import tech.beshu.ror.commons.shims.es.LoggerShim;

import java.nio.file.Path;
import java.util.Map;
import java.util.Observable;

abstract public class SettingsObservable extends Observable {

  public static final String SETTINGS_NOT_FOUND_MESSAGE = "no settings found in index";
  protected RawSettings current;
  private boolean printedInfo = false;

  public void updateSettings(RawSettings newSettings) {
    this.current = newSettings;
    setChanged();
    notifyObservers();
  }

  abstract protected Path getConfigPath();

  public RawSettings getCurrent() {
    return current;
  }

  // protected abstract void writeToIndex();

  protected abstract boolean isClusterReady();


  protected abstract Map<String, ?> getNodeSettings();

  protected abstract LoggerShim getLogger();

  protected abstract RawSettings getFromIndex();

  protected abstract void writeToIndex(RawSettings rawSettings, FutureCallback f);


  public void refreshFromIndex() {
    try {
      getLogger().debug("[CLUSTERWIDE SETTINGS] checking index..");
      RawSettings fromIndex = getFromIndex();

      if (!fromIndex.asMap().equals(current.asMap())) {
        updateSettings(fromIndex);
        getLogger().info("[CLUSTERWIDE SETTINGS] good settings found in index, overriding local YAML file");
      }
    } catch (Throwable t) {
      if (SETTINGS_NOT_FOUND_MESSAGE.equals(t.getMessage())) {
        if (!printedInfo) {
          getLogger().info("[CLUSTERWIDE SETTINGS] index settings not found. Will keep on using the local YAML file. " +
                             "Learn more about clusterwide settings at https://readonlyrest.com/pro.html ");
        }
        printedInfo = true;
      }
      else {
        t.printStackTrace();
      }
    }
  }

  public void forceRefresh() {
    setChanged();
    notifyObservers();
  }

  public void refreshFromStringAndPersist(RawSettings newSettings, FutureCallback fut) {
    RawSettings oldSettings = current;
    current = newSettings;
    try {
      forceRefresh();
      writeToIndex(newSettings, fut);
    } catch (Throwable t) {
      current = oldSettings;
      throw new SettingsMalformedException(t.getMessage());
    }
  }

  public void pollForIndex(ESContext context) {
    if (context.getVersion().before(ESVersion.V_5_0_0)) {
      return;
    }
    new SettingsPoller(this, context, 1, 5, true).poll();
  }

}
