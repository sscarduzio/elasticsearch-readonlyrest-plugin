package org.elasticsearch.plugin.readonlyrest;

import org.elasticsearch.plugin.readonlyrest.settings.ESSettings;
import org.elasticsearch.plugin.readonlyrest.settings.RawSettings;
import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;

import static org.elasticsearch.plugin.readonlyrest.utils.containers.ContainerUtils.getResourceFile;

// todo: remove
public class YamlTest {

  private static File file = getResourceFile("/test.yml");

  @Test
  public void test() throws IOException, URISyntaxException {
    ESSettings settings = new ESSettings(RawSettings.fromFile(file));
    Assert.assertEquals("<h1>Forbidden</h1>", settings.getRorSettings().getForbiddenMessage());
  }
}
