package com.framed.communicator.driver.protocol.medibus;

import com.framed.communicator.driver.protocol.medibus.utils.DataUtils;
import com.framed.communicator.driver.protocol.medibus.utils.DataConstants;
import com.framed.core.EventBus;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;


public class MedibusFramer {

  private final EventBus eventBus;
  private final String deviceID;
  private boolean storeStartResp = false;
  private boolean storeStartCom = false;
  private boolean storeEnd = false;

  private final List<Byte> bRespList = new ArrayList<>();
  private final List<Byte> bComList = new ArrayList<>();
  private final List<Byte> bList = new ArrayList<>();
  private final List<Byte> bRTList = new ArrayList<>();

  private final Consumer<byte[]> frameHandler;

  public MedibusFramer(Consumer<byte[]> frameHandler, EventBus eventBus, String deviceID) {
    this.deviceID = deviceID;
    this.eventBus = eventBus;
    this.frameHandler = frameHandler;
  }

  public void createFrameListFromByte(byte bValue) {
    switch (bValue) {
      case DataConstants.BOFRESPCHAR:
        storeStartResp = true;
        storeEnd = false;
        bRespList.clear();
        bRespList.add(bValue);
        break;

      case DataConstants.BOFCOMCHAR:
        storeStartCom = true;
        storeEnd = false;
        bComList.clear();
        bComList.add(bValue);
        break;

      case DataConstants.EOFCHAR:
        if (storeStartCom && storeStartResp) {
          bList.addAll(bComList);
        } else if (storeStartCom) {
          bList.addAll(bComList);
        } else if (storeStartResp) {
          bList.addAll(bRespList);
        }

        bComList.clear();
        bRespList.clear();
        storeStartCom = false;
        storeStartResp = false;
        storeEnd = true;

        finalizeFrame();
        break;

      default:
        if ((bValue & DataConstants.RT_BYTE) == DataConstants.RT_BYTE) {
          eventBus.publish(deviceID+".real-time", bValue);
          bRTList.add(bValue);
        } else if (storeStartCom && !storeEnd) {
          bComList.add(bValue);
        } else if (storeStartResp && !storeEnd) {
          bRespList.add(bValue);
        }
        break;
    }
  }


private void finalizeFrame() {
  int frameLen = bList.size();
  if (frameLen < 3) {
    bList.clear();
    storeEnd = false;
    return;
  }

  byte[] bArray = new byte[frameLen];
  for (int i = 0; i < frameLen; i++) {
    bArray[i] = bList.get(i);
  }

  int userDataFrameLen = frameLen - 2;
  byte[] userDataArray = new byte[userDataFrameLen];
  System.arraycopy(bArray, 0, userDataArray, 0, userDataFrameLen);

  byte[] checksumArray = new byte[2];
  System.arraycopy(bArray, frameLen - 2, checksumArray, 0, 2);
  String checksumStr = new String(checksumArray, StandardCharsets.US_ASCII);

  byte checksumComputed = DataUtils.computeChecksum(userDataArray);
  String checksumComputedStr = String.format("%02X", checksumComputed & 0xFF).toUpperCase();

  if (checksumComputedStr.equals(checksumStr)) {
    frameHandler.accept(userDataArray);
  } else {
    System.err.println("Checksum Error");
  }

  bList.clear();
  storeEnd = false;
}
}
