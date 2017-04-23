package org.elasticsearch.plugin.readonlyrest;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import org.elasticsearch.plugin.readonlyrest.settings.ESSettings;
import org.elasticsearch.plugin.readonlyrest.settings.deserializers.CustomDeserializersModule;
import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;

import static org.elasticsearch.plugin.readonlyrest.utils.containers.ContainerUtils.getResourceFile;

public class YamlTest {

  private static File file = getResourceFile("/groups_provider_authorization_test_elasticsearch.yml");

  @Test
  public void test() throws IOException, URISyntaxException {
    ObjectMapper mapper = new ObjectMapper(new YAMLFactory())
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        .registerModule(new Jdk8Module())
        .registerModule(new CustomDeserializersModule());
    ESSettings obj = mapper.readValue(file, ESSettings.class);
    obj.configure();

    Assert.assertEquals("<h1>Forbidden</h1>", obj.getRorSettings().getForbiddenMessage());
  }
}
