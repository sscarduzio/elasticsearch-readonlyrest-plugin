package org.elasticsearch.plugin.readonlyrest.utils;

import static org.junit.Assert.*;

import java.util.Base64;
import java.util.Optional;

import org.elasticsearch.plugin.readonlyrest.utils.BasicAuthUtils;
import org.elasticsearch.plugin.readonlyrest.utils.BasicAuthUtils.BasicAuth;
import org.junit.Test;

public class BasicAuthUtilsTests {

	@Test
	public void basicAuthValidTest() {
		String user = "admin";
		String passwd = "passwd:";
		byte[] authToken = ((String) user + ":" + passwd).getBytes();
		String base64Value = new String(Base64.getEncoder().encodeToString(authToken));
		Optional<BasicAuth> basicAuth = BasicAuthUtils.getBasicAuthFromString(base64Value);
		
		assertTrue( user.equals(basicAuth.get().getUserName()));
		assertTrue( passwd.equals(basicAuth.get().getPassword()));
	}
	
	@Test(expected = IllegalArgumentException.class)
	public void basicAuthInvalidTest() {
		String user = "admin";
		String passwd = "";
		byte[] authToken = ((String) user + ":" + passwd).getBytes();
		String base64Value = new String(Base64.getEncoder().encodeToString(authToken));
		Optional<BasicAuth> basicAuth = BasicAuthUtils.getBasicAuthFromString(base64Value);
	}


}
