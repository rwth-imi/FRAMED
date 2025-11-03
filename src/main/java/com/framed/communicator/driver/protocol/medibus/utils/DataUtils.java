package com.framed.communicator.driver.protocol.medibus.utils;


import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

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

  public static ArrayList<Byte> createWaveFormTypeList(int waveType) {
    ArrayList<Byte> waveTypesEnums = new ArrayList<>();
    switch (waveType) {
      case 0:
        break;
      case 1:
        waveTypesEnums.add((byte) 0x00); // PAW
        waveTypesEnums.add((byte) 0x01); // Flow
        break;
      case 2:
        waveTypesEnums.add((byte) 0x02); // Pleth
        waveTypesEnums.add((byte) 0x05); // O2 Concentration
        waveTypesEnums.add((byte) 0x06); // CO2 Concentration mmHg
        waveTypesEnums.add((byte) 0x0A); // Concentration of primary agent percent
        break;
      case 3:
        waveTypesEnums.add((byte) 0x1C); // Tracheal pressure
        waveTypesEnums.add((byte) 0x1E); // Desflurane concentration percent
        break;
      case 4:
        waveTypesEnums.add((byte) 0x00); // PAW
        waveTypesEnums.add((byte) 0x01); // Flow
        waveTypesEnums.add((byte) 0x02); // Pleth
        waveTypesEnums.add((byte) 0x05); // O2 Concentration
        waveTypesEnums.add((byte) 0x06); // CO2 Concentration mmHg
        waveTypesEnums.add((byte) 0x0A); // Concentration of primary agent percent
        waveTypesEnums.add((byte) 0x1C); // Tracheal pressure
        waveTypesEnums.add((byte) 0x1E); // Desflurane concentration percent
        break;
    }
    return waveTypesEnums;
  }
}

