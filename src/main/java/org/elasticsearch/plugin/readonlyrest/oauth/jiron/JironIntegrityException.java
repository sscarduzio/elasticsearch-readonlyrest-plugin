package org.elasticsearch.plugin.readonlyrest.oauth.jiron;

public class JironIntegrityException extends Exception {

	private String token;

	public JironIntegrityException(String token,String message, Throwable cause) {
		super(message + ", token:" + token, cause);
		this.token = token;
	}

	public JironIntegrityException(String token,String message) {
		super(message + ", token:" + token);
		this.token = token;
	}

	public JironIntegrityException(String token,Throwable cause) {
		super(cause + ", token:" + token);
		this.token = token;
	}

	public String getToken() {
		return token;
	}
	
	

}
