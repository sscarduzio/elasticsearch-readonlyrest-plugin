package org.elasticsearch.plugin.readonlyrest;

/**
 * Created by sscarduzio on 21/02/2016.
 */
public class SecurityPermissionException extends RuntimeException{
  public SecurityPermissionException(String msg, Throwable cause){
    super(msg, cause);
  }
}
