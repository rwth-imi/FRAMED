package com.safety_box.communicator.driver.parser;

public interface Parser<T> {
  void parse(T[] message);
}
