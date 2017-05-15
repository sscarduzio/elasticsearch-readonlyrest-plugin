package org.elasticsearch.plugin.readonlyrest.oauth.jiron;

public class JironException extends Exception {


	public JironException(String message, Throwable cause) {
		super(message, cause);
	}

	public JironException(String message) {
		super(message);
	}

	public JironException(Throwable cause) {
		super(cause);
	}

}
