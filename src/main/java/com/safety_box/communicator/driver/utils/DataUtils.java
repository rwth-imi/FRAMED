package com.safety_box.communicator.driver.utils;


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

  public static ArrayList<Byte> createWaveFormTypeList(int waveType, ArrayList<Byte> waveForms) {
    ArrayList<Byte> waveTypesEnums = new ArrayList<>();
    switch (waveType) {
      case 0:
        break;
      case 1:
        waveTypesEnums.add((byte) DataConstants.MedibusXRealTimeData.Airway_pressure.ordinal());
        waveTypesEnums.add((byte) DataConstants.MedibusXRealTimeData.Flow_inspiratory_expiratory.ordinal());
        break;
      case 2:
        waveTypesEnums.add((byte) DataConstants.MedibusXRealTimeData.O2_saturation_pulse_Pleth.ordinal());
        waveTypesEnums.add((byte) DataConstants.MedibusXRealTimeData.O2_concentration_inspiratory_expiratory.ordinal());
        waveTypesEnums.add((byte) DataConstants.MedibusXRealTimeData.CO2_concentration_mmHg.ordinal());
        waveTypesEnums.add((byte) DataConstants.MedibusXRealTimeData.Concentration_of_primary_agent_inspiratory_expiratory_Percent.ordinal());
        break;
      case 3:
        waveTypesEnums.add((byte) DataConstants.MedibusXRealTimeData.Tracheal_pressure.ordinal());
        waveTypesEnums.add((byte) DataConstants.MedibusXRealTimeData.Inspiratory_device_flow.ordinal());
        break;
      case 4:
        waveTypesEnums.add((byte) DataConstants.MedibusXRealTimeData.Airway_pressure.ordinal());
        waveTypesEnums.add((byte) DataConstants.MedibusXRealTimeData.Flow_inspiratory_expiratory.ordinal());
        waveTypesEnums.add((byte) DataConstants.MedibusXRealTimeData.Respiratory_volume_since_start_of_inspiration.ordinal());
        waveTypesEnums.add((byte) DataConstants.MedibusXRealTimeData.O2_saturation_pulse_Pleth.ordinal());
        waveTypesEnums.add((byte) DataConstants.MedibusXRealTimeData.O2_concentration_inspiratory_expiratory.ordinal());
        waveTypesEnums.add((byte) DataConstants.MedibusXRealTimeData.CO2_concentration_mmHg.ordinal());
        waveTypesEnums.add((byte) DataConstants.MedibusXRealTimeData.Concentration_of_primary_agent_inspiratory_expiratory_Percent.ordinal());
        waveTypesEnums.add((byte) DataConstants.MedibusXRealTimeData.Inspiratory_device_flow.ordinal());
        waveTypesEnums.add((byte) DataConstants.MedibusXRealTimeData.Tracheal_pressure.ordinal());
        break;
    }
    return waveTypesEnums;
  }
}

