package tech.beshu.ror.es.utils;

import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.ActionResponse;
import org.elasticsearch.action.support.ActionFilter;
import org.elasticsearch.action.support.ActionFilterChain;
import org.elasticsearch.common.component.AbstractComponent;
import org.elasticsearch.common.settings.Settings;

public abstract class IndexLevelActionFilterJavaHelper extends AbstractComponent implements ActionFilter {

  public IndexLevelActionFilterJavaHelper(Settings settings) {
    super(settings);
  }

  @Override
  public <Response extends ActionResponse> void apply(String action,
                                                      Response response,
                                                      ActionListener<Response> listener,
                                                      ActionFilterChain<?, Response> chain) {
    chain.proceed(action, response, listener);
  }
}
