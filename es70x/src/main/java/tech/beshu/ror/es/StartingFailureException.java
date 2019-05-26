package tech.beshu.ror.es;

import org.elasticsearch.ElasticsearchException;
import tech.beshu.ror.boot.StartingFailure;

public class StartingFailureException extends ElasticsearchException {
  private StartingFailureException(String msg) {
    super(msg);
  }

  private StartingFailureException(String message, Throwable throwable) {
    super(message, throwable);
  }

  public static StartingFailureException from(StartingFailure failure) {
    if(failure.throwable().isDefined()) {
      return new StartingFailureException(failure.message(), failure.throwable().get());
    } else {
      return new StartingFailureException(failure.message());
    }
  }
}
