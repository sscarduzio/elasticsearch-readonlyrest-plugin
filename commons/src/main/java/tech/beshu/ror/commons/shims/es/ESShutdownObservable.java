package tech.beshu.ror.commons.shims.es;

import java.util.Observable;

public class ESShutdownObservable extends Observable{

  public void shutDown(){
    setChanged();
    notifyObservers();
  }
}
