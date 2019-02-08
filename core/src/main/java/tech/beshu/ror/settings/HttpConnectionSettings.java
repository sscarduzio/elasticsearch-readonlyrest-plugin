package tech.beshu.ror.settings;

import org.apache.http.client.config.RequestConfig;
import tech.beshu.ror.commons.settings.RawSettings;

import java.time.Duration;

public class HttpConnectionSettings {

    private static final String CONNECTION_TIMEOUT = "connection_timeout_in_sec";
    private static final String SOCKET_TIMEOUT = "socket_timeout_in_sec";
    private static final String CONNECTION_REQUEST_TIMEOUT = "connection_request_timeout_in_sec";
    private static final String CONNECTION_POOL_SIZE = "connection_pool_size";

    private static final int DEFAULT_CONNECTION_POOL_SIZE = 30;
    private static final Duration DEFAULT_CONNECTION_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration DEFAULT_SOCKET_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration DEFAULT_CONNECTION_REQUEST_TIMEOUT = Duration.ofSeconds(5);

    private final Duration connectTimeout;
    private final Duration socketTimeout;
    private final Integer connectionPoolSize;
    private final Duration connectionRequestTimeout;
    private final boolean validate;

    public HttpConnectionSettings(RawSettings settings, boolean validate) {
        this.connectionPoolSize = settings.intOpt(CONNECTION_POOL_SIZE).orElse(DEFAULT_CONNECTION_POOL_SIZE);
        this.connectionRequestTimeout = settings.intOpt(CONNECTION_REQUEST_TIMEOUT).map(Duration::ofSeconds).orElse(DEFAULT_CONNECTION_REQUEST_TIMEOUT);
        this.connectTimeout = settings.intOpt(CONNECTION_TIMEOUT).map(Duration::ofSeconds).orElse(DEFAULT_CONNECTION_TIMEOUT);
        this.socketTimeout = settings.intOpt(SOCKET_TIMEOUT).map(Duration::ofSeconds).orElse(DEFAULT_SOCKET_TIMEOUT);
        this.validate = validate;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public Duration getSocketTimeout() {
        return socketTimeout;
    }

    public Integer getConnectionPoolSize() {
        return connectionPoolSize;
    }

    public Duration getConnectionRequestTimeout() {
        return connectionRequestTimeout;
    }

    public boolean getValidateHttps() {
        return validate;
    }

    public RequestConfig toRequestConfig() {
        return RequestConfig.custom()
                .setSocketTimeout((int) this.getSocketTimeout().toMillis())
                .setConnectTimeout((int) this.getConnectTimeout().toMillis())
                .setConnectionRequestTimeout((int) this.getConnectionRequestTimeout().toMillis())
                .build();
    }
}
