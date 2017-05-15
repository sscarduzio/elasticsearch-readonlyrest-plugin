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

package org.elasticsearch.plugin.readonlyrest.utils;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Map;

import org.apache.commons.codec.binary.Base64;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.plugin.readonlyrest.ConfigurationHelper;
import org.elasticsearch.plugin.readonlyrest.oauth.OAuthToken;

import com.google.common.base.Strings;

public class OAuthUtils {
	
    // Authorization: Basic QWxhZGRpbjpvcGVuIHNlc2FtZQ==
    public static String extractTokenFromHeader(String authorizationHeader) {
        if (authorizationHeader == null || authorizationHeader.trim().length() == 0 || !authorizationHeader.contains("Bearer "))
            return null;
        String interestingPart = authorizationHeader.split("Bearer")[1].trim();
        if (interestingPart.length() == 0) {
            return null;
        }
        return interestingPart;
    }
    
    public static String extractTokenFromCookie(String cookieHeader, String cookieName) {
        String token = cookieHeader;
        if (token == null || token.trim().length() == 0 || !token.contains(cookieName))
            return null;
        String interestingPart = token.substring(token.indexOf(cookieName));
        interestingPart = interestingPart.substring(interestingPart.indexOf("=") + 1);
        return interestingPart;
    }

    public static OAuthToken getOAuthToken(Map<String, String> headers, ConfigurationHelper conf) {
        String tokenCookie = extractTokenFromCookie(headers.get("Cookie"), conf.cookieName);
        String tokenHeader = extractTokenFromHeader(headers.get("Authorization"));
        OAuthToken oAuthToken = new OAuthToken();
        oAuthToken.setPublicKey(conf.tokenSecret);
        if (!Strings.isNullOrEmpty(tokenCookie)) {
            return oAuthToken.parseEncryptedJWT(tokenCookie, conf.cookieSecret);
        }
        else if (!Strings.isNullOrEmpty(tokenHeader)) {
            try {
                return oAuthToken.parseDecryptedJWT(tokenHeader);
            } catch (IllegalArgumentException e) {
                e.printStackTrace();
            }
        }
        return null;
    }
    
    public static boolean verifyTokenIntegrity(OAuthToken token, String tokenPublicKey) {
    	String header = token.getHeader();
    	String payload = token.getPayload();
    	String signature = token.getSignature();
    	String algo = token.getAlg();
    	if (algo.equals("RS256")) {
    		byte[] decoded = Base64.decodeBase64(tokenPublicKey);
    		X509EncodedKeySpec spec =
    	            new X509EncodedKeySpec(decoded);
    	    KeyFactory kf;
    	    try {
    	    	kf = KeyFactory.getInstance("RSA");
    			RSAPublicKey generatePublic = (RSAPublicKey) kf.generatePublic(spec);
    			byte[] contentBytes = String.format("%s.%s", header, payload).getBytes(StandardCharsets.UTF_8);
    		    byte[] signatureBytes = Base64.decodeBase64(signature);
    		    Signature s = Signature.getInstance("SHA256withRSA");
    		    s.initVerify(generatePublic);
    		    s.update(contentBytes);
    		    s.verify(signatureBytes);
    	    } catch (Exception e) {
    	    	e.printStackTrace();
    	    	return false;
    	    }
    	} else if (algo.equals("HS256")) {
    		// TODO
    	} // and so on
    		
    	return true;
    }
}


