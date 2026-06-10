package com.framed.communicator.driver.protocol.medibus.utils;


import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class DataUtils {
  private DataUtils() {
    throw new IllegalStateException("Utility class");
  }
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

  public static byte[] getDataStreamConfigBuffer(byte syncCommand, boolean disable) {
    byte syncByte = (byte) 0xD0;
    byte syncArgument = (byte) 0xCF;
    if (disable) {
      syncArgument = (byte) 0xC0;
    }
    byte endSyncByte = (byte) 0xC0;

    ArrayList<Byte> tempTxBuffList = new ArrayList<>();

    tempTxBuffList.add(syncByte);
    tempTxBuffList.add(syncCommand);
    tempTxBuffList.add(syncArgument);
    tempTxBuffList.add(endSyncByte);
    tempTxBuffList.add(endSyncByte);

    byte[] finalBuffer = new byte[tempTxBuffList.size()];
    for (int i = 0; i < tempTxBuffList.size(); i++) {
      finalBuffer[i] = tempTxBuffList.get(i);
    }
    return finalBuffer;
  }

  public static byte [] getRealtimeConfigMessage(List<Byte> waveFormTypeList, List<Byte> tempTxBuffList, String multiplier) {
    byte[] rtdListArray = new byte[waveFormTypeList.size()];
    for (int i = 0; i < waveFormTypeList.size(); i++) {
      rtdListArray[i] = waveFormTypeList.get(i);
    }
    for (byte b : DataConstants.poll_configure_real_time_transmission) {
      tempTxBuffList.add(b);
    }

    for (byte b : rtdListArray) {
      String rtdToAsciiHex = String.format("%02x", b).toUpperCase();
      byte[] rtdAsciiHexBytes = rtdToAsciiHex.getBytes(StandardCharsets.US_ASCII);
      byte[] multiplierHexBytes = multiplier.getBytes(StandardCharsets.US_ASCII);

      for (byte c : rtdAsciiHexBytes) {
        tempTxBuffList.add(c);
      }
      for (byte c : multiplierHexBytes) {
        tempTxBuffList.add(c);
      }
    }

    byte[] finalBuffer = new byte[tempTxBuffList.size()];
    for (int i = 0; i < tempTxBuffList.size(); i++) {
      finalBuffer[i] = tempTxBuffList.get(i);
    }
    return finalBuffer;
  }

  public static List<Byte> createWaveFormTypeList(int waveType) {
    ArrayList<Byte> waveTypesEnums = new ArrayList<>();
    switch (waveType) {
      case 0:
        break;
      case 1:
        waveTypesEnums.add((byte) 0x00); // PAW
        waveTypesEnums.add((byte) 0x01); // Flow
        waveTypesEnums.add((byte) 0x06); // CO2 Concentration mmHg
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
      default:
        throw new IllegalArgumentException("There is no waveform of type %d. Check you Config!".formatted(waveType));
    }
    return waveTypesEnums;
  }

  public static byte[] createDevIDMessage() {
    byte[] deviceIDCommandResponse = {0x52};
    byte[] devID = "0161".getBytes(StandardCharsets.US_ASCII);
    byte[] devName = "'SafetyBox'".getBytes(StandardCharsets.US_ASCII);
    byte[] devRevision = "01.03".getBytes(StandardCharsets.US_ASCII);
    byte[] medibusVer = ":06.00".getBytes(StandardCharsets.US_ASCII);

    byte[] txBuffer = new byte[
      deviceIDCommandResponse.length +
        devID.length +
        devName.length +
        devRevision.length +
        medibusVer.length
      ];

    System.arraycopy(deviceIDCommandResponse, 0, txBuffer, 0, deviceIDCommandResponse.length);
    System.arraycopy(devID, 0, txBuffer, deviceIDCommandResponse.length, devID.length);
    System.arraycopy(devName, 0, txBuffer, deviceIDCommandResponse.length + devID.length, devName.length);
    System.arraycopy(devRevision, 0, txBuffer, deviceIDCommandResponse.length + devID.length + devName.length, devRevision.length);
    System.arraycopy(medibusVer, 0, txBuffer, deviceIDCommandResponse.length + devID.length + devName.length + devRevision.length, medibusVer.length);
    return txBuffer;
  }

}

