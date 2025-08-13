package com.safety_box.communicator.driver.utils;


import java.nio.charset.StandardCharsets;

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

  public static byte[] hexToBytes(String hex) {
    String[] parts = hex.split(" ");
    byte[] bytes = new byte[parts.length];
    for (int i = 0; i < parts.length; i++) {
      bytes[i] = (byte) Integer.parseInt(parts[i], 16);
    }
    return bytes;
  }

  public static String stringToHex(String bytesAsString) {
    byte[] bytes = bytesAsString.getBytes(StandardCharsets.US_ASCII);

    StringBuilder sb = new StringBuilder();
    for (byte b : bytes) {
      sb.append(String.format("%02X ", b));
    }
    return sb.toString().trim();
  }
}

