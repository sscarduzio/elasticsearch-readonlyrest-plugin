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
