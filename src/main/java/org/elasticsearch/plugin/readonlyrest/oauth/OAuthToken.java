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

package org.elasticsearch.plugin.readonlyrest.oauth;

import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;

import org.apache.logging.log4j.Logger;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.plugin.readonlyrest.oauth.jiron.Jiron;
import org.elasticsearch.plugin.readonlyrest.oauth.jiron.JironException;
import org.elasticsearch.plugin.readonlyrest.oauth.jiron.JironIntegrityException;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class OAuthToken {

	private String alg;
	private String jti;
	private Date exp;
	private int nbf;
	private Date iat;
	private String iss;
	private String aud;
	private String sub;
	private String typ;
	private String azp;
	private int auth_time;
	private String session_state;
	private String acr;
	private String client_session;
	private ArrayList<String> allowed_origins;
	private ArrayList<String> roles;
	private String name;
	private String preferred_username;
	private boolean isValid;
	private String header;
	private String payload;
	private String signature;
	private String publicKey;

	private final Logger logger = Loggers.getLogger(getClass());

	public OAuthToken() {
	}

	public String getAlg() {
		return alg;
	}

	public void setAlg(String alg) {
		this.alg = alg;
	}

	public String getJti() {
		return jti;
	}

	public void setJti(String jti) {
		this.jti = jti;
	}

	public Date getExp() {
		return exp;
	}

	public void setExp(Date exp) {
		this.exp = exp;
	}

	public int getNbf() {
		return nbf;
	}

	public void setNbf(int nbf) {
		this.nbf = nbf;
	}

	public Date getIat() {
		return iat;
	}

	public void setIat(Date iat) {
		this.iat = iat;
	}

	public String getIss() {
		return iss;
	}

	public void setIss(String iss) {
		this.iss = iss;
	}

	public String getAud() {
		return aud;
	}

	public void setAud(String aud) {
		this.aud = aud;
	}

	public String getSub() {
		return sub;
	}

	public void setSub(String sub) {
		this.sub = sub;
	}

	public String getTyp() {
		return typ;
	}

	public void setTyp(String typ) {
		this.typ = typ;
	}

	public String getAzp() {
		return azp;
	}

	public void setAzp(String azp) {
		this.azp = azp;
	}

	public int getAuth_time() {
		return auth_time;
	}

	public void setAuth_time(int auth_time) {
		this.auth_time = auth_time;
	}

	public String getSession_state() {
		return session_state;
	}

	public void setSession_state(String session_state) {
		this.session_state = session_state;
	}

	public String getAcr() {
		return acr;
	}

	public void setAcr(String acr) {
		this.acr = acr;
	}

	public String getClient_session() {
		return client_session;
	}

	public void setClient_session(String client_session) {
		this.client_session = client_session;
	}

	public ArrayList<String> getAllowed_origins() {
		return allowed_origins;
	}

	public void setAllowed_origins(ArrayList<String> allowed_origins) {
		this.allowed_origins = allowed_origins;
	}

	public ArrayList<String> getRoles() {
		return roles;
	}

	public void setRoles(ArrayList<String> roles) {
		this.roles = roles;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getPreferred_username() {
		return preferred_username;
	}

	public void setPreferred_username(String preferred_username) {
		this.preferred_username = preferred_username;
	}

	public boolean isValid() {
		return isValid;
	}

	public void setValid(boolean isValid) {
		this.isValid = isValid;
	}

	public String getHeader() {
		return header;
	}

	public String getPayload() {
		return payload;
	}

	public String getSignature() {
		return signature;
	}

	public OAuthToken parseEncryptedJWT(String jwt, String secret) {
		String token;
		try {
			token = Jiron.unseal(jwt, secret, Jiron.DEFAULT_ENCRYPTION_OPTIONS, Jiron.DEFAULT_INTEGRITY_OPTIONS);
		} catch (JironException | JironIntegrityException e) {
			logger.error("Error while deciphering token " + e.getMessage());
			return null;
		}
		return parseDecryptedJWT(token);
	}

	public OAuthToken parseDecryptedJWT(String decryptedCookie) {
		String[] cookie = decryptedCookie.split("\\:");
		String token = cookie[1];
		token = token.substring(1, token.length());
		String[] jwtParts = token.split("\\.");
		if (jwtParts.length == 3) {
			String header = jwtParts[0];
			this.header = header;
			String payload = jwtParts[1];
			this.payload = payload;
			String RSASignature = jwtParts[2];	
			this.signature = RSASignature.substring(0, RSASignature.indexOf("\""));
			try {
				parseHeader(header);
				parsePayload(payload);
			} catch (UnsupportedEncodingException e) {
				logger.error("Error while base64 decoding the token " + e.getMessage());
				return null;
			}
		}
		return this;
	}

	private void parseHeader(String header) throws JSONException, UnsupportedEncodingException {
		logger.debug("BEGIN parsing OAuth token header");

		byte[] decodedBytes = Base64.getDecoder().decode(header);
		JSONObject obj = new JSONObject(new String(decodedBytes, "UTF-8"));
		if (obj == null)
			return;
		this.setAlg(obj.getString("alg"));

		logger.debug("END parsing OAuth token payload");
	}

	private void parsePayload(String payload) throws UnsupportedEncodingException {
		logger.debug("BEGIN parsing OAuth token payload");
		JSONObject obj = null;

		byte[] decodedBytes = Base64.getDecoder().decode(payload);
		obj = new JSONObject(new String(decodedBytes, "UTF-8"));
		// *1000L because unix timestamp are in second
		// and java Date timestamps are in ms
		if (obj == null)
			return;
		try {
			this.setExp(new Date(obj.getLong("exp") * 1000L));
			this.setAud(obj.getString("aud"));
			this.setAzp(obj.getString("azp"));
			JSONArray rolesJson = new JSONArray();
			if (this.aud != null) {
				rolesJson = obj.getJSONObject("resource_access").getJSONObject(this.aud).getJSONArray("roles");
			} else if (this.azp != null) {
				rolesJson = obj.getJSONObject("resource_access").getJSONObject(this.azp).getJSONArray("roles");
			}
			ArrayList<String> rolesList = new ArrayList<String>();
			rolesJson.forEach(role -> {
				rolesList.add((String) role);
				// System.out.println(role);
			});
			this.setRoles(rolesList);
			this.setJti(obj.getString("jti"));
			this.setNbf(obj.getInt("nbf"));
			this.setIat(new Date(obj.getLong("nbf") * 1000L));
			this.setIss(obj.getString("iss"));
			this.setSub(obj.getString("sub"));
			this.setTyp(obj.getString("typ"));
			this.setSession_state(obj.getString("session_state"));
			this.setClient_session(obj.getString("client_session"));
			this.setName(obj.getString("name"));
			this.setPreferred_username(obj.getString("preferred_username"));
		} catch (JSONException ex) {
			logger.error("Error while parsing json OAuth Token " + ex.getLocalizedMessage());
		}
		logger.debug("END parsing OAuth token payload");
	}

	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder();
		sb.append("{Token Id: " + this.jti + "}\n");
		sb.append("{Expiration date: " + this.exp.toString() + "}\n");
		sb.append("User roles: " + this.roles);
		sb.append("[");
		this.roles.forEach(role -> {
			sb.append(role);
			if (this.roles.indexOf(role) <= this.roles.size())
				sb.append(",");
		});
		sb.append("]");
		return sb.toString();
	}

	public String getPublicKey() {
		return publicKey;
	}

	public void setPublicKey(String publicKey) {
		this.publicKey = publicKey;
	}
}
