/*
 *    This file is part of ReadonlyREST.
 *
 *    ReadonlyREST is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU General Public License as published by
 *    the Free Software Foundation, either version 3 of the License, or
 *    (at your option) any later version.
 *
 *    ReadonlyREST is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU General Public License for more details.
 *
 *    You should have received a copy of the GNU General Public License
 *    along with ReadonlyREST.  If not, see http://www.gnu.org/licenses/
 */
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
