package org.elasticsearch.plugin.readonlyrest;

/**
 * Created by sscarduzio on 18/07/2017.
 */
public class ESVersion {

  public static final ESVersion V_5_0_0 = new ESVersion(5000099);

  public static final ESVersion V_5_1_1 = new ESVersion(5010199);

  public static final ESVersion V_5_2_0 = new ESVersion(5020099);

  public static final ESVersion V_5_3_0 = new ESVersion(5030099);

  public static final ESVersion V_5_4_0 = new ESVersion(5040099);


  private final int id;
  public ESVersion(int id) {
    this.id = id;
  }

  public boolean after(ESVersion ESVersion) {
    return ESVersion.id < this.id;
  }

  public boolean onOrAfter(ESVersion ESVersion) {
    return ESVersion.id <= this.id;
  }

  public boolean before(ESVersion ESVersion) {
    return ESVersion.id > this.id;
  }

  public boolean onOrBefore(ESVersion ESVersion) {
    return ESVersion.id >= this.id;
  }
}
