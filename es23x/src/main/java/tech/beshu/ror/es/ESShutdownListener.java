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
package tech.beshu.ror.es;

import org.elasticsearch.common.component.AbstractLifecycleComponent;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.common.settings.Settings;

public class ESShutdownListener extends AbstractLifecycleComponent {
  ESLogger logger = Loggers.getLogger(getClass());

  @Inject
  public ESShutdownListener(Settings settings) {
    super(settings);
  }

  @Override
  protected void doStart() {
    logger.info("lifecyclelistener: START");
  }

  @Override
  protected void doStop() {
    logger.info("lifecyclelistener: STOP");

  }

  @Override
  protected void doClose() {
    logger.info("lifecyclelistener: CLOSE");
    ESContextImpl.shutDownObservable.shutDown();
  }
}
