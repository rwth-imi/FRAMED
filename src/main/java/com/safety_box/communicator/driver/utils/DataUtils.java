package com.safety_box.communicator.driver.utils;


public class DataUtils {
  public static byte computeChecksum(byte[] data) {
    byte sum = 0;
    for (byte b : data) {
      sum += b;
    }
    return sum;
  }

  public static byte[] concatBuffer(byte[] inputBuffer, byte[] checksumAsciiHexBytes) {
    byte[] finalTxBuff = new byte[inputBuffer.length + checksumAsciiHexBytes.length + 1]; // +1 for EOF
    System.arraycopy(inputBuffer, 0, finalTxBuff, 0, inputBuffer.length);
    System.arraycopy(checksumAsciiHexBytes, 0, finalTxBuff, inputBuffer.length, checksumAsciiHexBytes.length);
    finalTxBuff[finalTxBuff.length - 1] = DataConstants.EOFCHAR;
    return finalTxBuff;
  }
}

