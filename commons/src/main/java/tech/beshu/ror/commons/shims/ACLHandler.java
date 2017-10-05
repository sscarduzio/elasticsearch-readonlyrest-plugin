package tech.beshu.ror.commons.shims;

public interface ACLHandler {
  void onForbidden();
  void onAllow(Object blockExitResult);
  boolean isNotFound(Throwable t);
  void onNotFound(Throwable t);
  void onErrored(Throwable t);
}
