package org.elasticsearch.plugin.readonlyrest;

/**
 * Created by sscarduzio on 21/02/2016.
 */
public class SecurityPermissionException extends RuntimeException{
  SecurityPermissionException(String msg){
    super(msg);
  }
}
