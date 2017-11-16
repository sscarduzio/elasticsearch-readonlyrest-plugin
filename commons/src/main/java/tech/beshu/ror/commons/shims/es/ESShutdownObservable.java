package tech.beshu.ror.commons.settings;

import java.util.Observable;

public class ESShutdownObservable extends Observable{

  public void shutDown(){
    setChanged();
    notifyObservers();
  }
}
