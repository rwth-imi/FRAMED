package com.safety_box.communicator.driver.protocol.simulation;

import com.safety_box.communicator.driver.protocol.Protocol;

public class SimulatorProtocol extends Protocol {

  public SimulatorProtocol(String deviceID) {
    super(deviceID);
  }
  @Override
  public void connect() {

  }

  @Override
  public void disconnect() {

  }

  @Override
  public Object readData() {
    return null;
  }

  @Override
  public void writeData(Object data) {

  }
}
