package tech.beshu.ror.utils.httpclient;

import org.apache.http.conn.ssl.TrustStrategy;

import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

public class TrustAllCertificatesStrategy implements TrustStrategy {

    public static final tech.beshu.ror.utils.httpclient.TrustAllCertificatesStrategy INSTANCE = new tech.beshu.ror.utils.httpclient.TrustAllCertificatesStrategy();

    @Override
    public boolean isTrusted(
            final X509Certificate[] chain, final String authType) throws CertificateException {
        return true;
    }

}
