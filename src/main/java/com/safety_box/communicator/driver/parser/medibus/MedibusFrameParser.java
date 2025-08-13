package com.safety_box.communicator.driver.parser.medibus;

import com.safety_box.communicator.driver.protocol.medibus.MedibusProtocol;
import com.safety_box.communicator.driver.utils.DataUtils;
import com.safety_box.communicator.driver.utils.DataConstants;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.logging.Logger;


public class MedibusFrameParser {

  private boolean storeStartResp = false;
  private boolean storeStartCom = false;
  private boolean storeEnd = false;

  private final List<Byte> bRespList = new ArrayList<>();
  private final List<Byte> bComList = new ArrayList<>();
  private final List<Byte> bList = new ArrayList<>();
  private final List<Byte> bRTList = new ArrayList<>();

  private final Consumer<String> frameHandler;

  private static final Logger logger = Logger.getLogger(MedibusFrameParser.class.getName());


  public MedibusFrameParser(Consumer<String> frameHandler) {
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
          logger.info(String.format("Realtime byte received: 0x%02X", bValue));
          bRTList.add(bValue);
          if (bRTList.size() >= 2) {
            byte high = bRTList.remove(0);
            byte low = bRTList.remove(0);
            int value = ((high & 0x3F) << 6) | (low & 0x3F);
            logger.info("Parsed Realtime Value: " + value);
          }
          logger.info("Received REALTIME Data!!!!");
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
    String response = new String(userDataArray, StandardCharsets.US_ASCII);
    frameHandler.accept(response);
  } else {
    System.err.println("Checksum Error");
  }

  bList.clear();
  storeEnd = false;
}
}
