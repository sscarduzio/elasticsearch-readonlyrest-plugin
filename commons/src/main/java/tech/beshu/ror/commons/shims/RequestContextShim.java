package tech.beshu.ror.commons.shims;

import java.util.Date;

public interface RequestContextShim {
  Date getTimestamp();
  String getId();
}
