package tech.beshu.ror.es;

import org.elasticsearch.action.support.ActionFilter;
import org.elasticsearch.plugins.ActionPlugin;

import java.util.Collections;
import java.util.List;

public class TestActionPlugin implements ActionPlugin {

  @Override
  public List<ActionFilter> getActionFilters() {
    return Collections.emptyList();
  }
}
