package tech.beshu.ror.utils.misc;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;

public class GsonHelper {
  public static Map<String, Object> deserializeJsonBody(String response) {
    Gson gson = new Gson();
    Type mapType = new TypeToken<HashMap<String, Object>>(){}.getType();
    return gson.fromJson(response, mapType);
  }

}
