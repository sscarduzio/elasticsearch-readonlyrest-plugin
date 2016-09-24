package org.elasticsearch.plugin.readonlyrest;

import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.inject.Singleton;
import org.elasticsearch.common.settings.Settings;

/**
 * ConfigurationHelper
 *
 * @author <a href="mailto:scarduzio@gmail.com">Simone Scarduzio</a>
 * @see <a href="https://github.com/sscarduzio/elasticsearch-readonlyrest-plugin/">Github Project</a>
 */

@Singleton
public class ConfigurationHelper {
    public boolean enabled;
    public String forbiddenResponse;
    public boolean sslEnabled;
    public String sslKeyStoreFile;
    public String sslKeyPassword;
    public String sslKeyStorePassword;

    @Inject
    public ConfigurationHelper(Settings settings) {

        Settings s = settings.getByPrefix("readonlyrest.");

        enabled = s.getAsBoolean("enable", false);
        forbiddenResponse = s.get("response_if_req_forbidden", "Forbidden").trim();

        // -- SSL
        sslEnabled = s.getAsBoolean("ssl.enable", false);
        sslKeyStoreFile = s.get("ssl.keystore_file");
        sslKeyStorePassword = s.get("ssl.keystore_pass");
        sslKeyPassword = s.get("ssl.key_pass", sslKeyStorePassword); // fallback

    }

    public static boolean isNullOrEmpty(String s) {
        return s == null || s.trim().length() == 0;
    }
}
