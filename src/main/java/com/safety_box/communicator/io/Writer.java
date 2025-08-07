package com.safety_box.communicator.io;

public abstract class Writer<T> {
  String path;
  public Writer(String path) {
    this.path = path;
  }

  public abstract void write(T[] data);
}
