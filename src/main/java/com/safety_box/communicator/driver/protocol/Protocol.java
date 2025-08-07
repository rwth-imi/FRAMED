package com.safety_box.communicator.driver.protocol;

public abstract class Protocol<T> {
  String deviceID;
  public Protocol(String deviceID) {
    this.deviceID = deviceID;
  }
  public abstract void connect();
  public abstract void disconnect();
  public abstract T readData();
  public abstract void writeData(T data);
}
