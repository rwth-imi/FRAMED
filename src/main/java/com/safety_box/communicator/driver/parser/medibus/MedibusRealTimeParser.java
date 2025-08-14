package com.safety_box.communicator.driver.parser.medibus;

import com.safety_box.communicator.driver.parser.Parser;
import io.vertx.core.Context;
import io.vertx.core.Vertx;

public class MedibusRealTimeParser extends Parser<byte[]> {
  @Override
  public void init(Vertx vertx, Context context) {
    super.init(vertx, context);
  }
  @Override
  public void parse(byte[] message) {

  }
}
