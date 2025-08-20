package com.safety_box.communicator.driver.protocol.medibus;

import com.fazecast.jSerialComm.SerialPortDataListener;
import com.fazecast.jSerialComm.SerialPortEvent;
import com.safety_box.communicator.driver.protocol.ProtocolVerticle;
import com.fazecast.jSerialComm.SerialPort;
import com.safety_box.communicator.driver.utils.DataUtils;
import com.safety_box.communicator.driver.utils.DataConstants;
import io.vertx.core.*;
import io.vertx.core.json.JsonObject;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;

public class MedibusProtocolVerticle extends ProtocolVerticle {

  private Boolean slowData;

  private enum State {
    IDLE, INITIALIZING, IDENTIFYING, CONFIGURING, ACTIVE, REALTIME, TERMINATING

  }
  private State currentState = State.IDLE;

  private String portName;
  private int baudRate;
  private int dataBits;
  private int stopBits;
  private int bufferSize;
  private int waveFormType;
  private boolean realTime;
  private String multiplier;
  private SerialPort serialPort;
  private MedibusFramer framer;

  private static final Logger logger = Logger.getLogger(MedibusProtocolVerticle.class.getName());


  @Override
  public void init(Vertx vertx, Context context) {
    super.init(vertx, context);
    this.deviceID = config.getString("id");
    this.portName = config.getString("portName");
    this.baudRate = config.getInteger("baudRate");
    this.dataBits = config.getInteger("dataBits");
    this.bufferSize = config.getInteger("bufferSize");
    this.realTime = config.getBoolean("realTime");
    this.waveFormType = config.getInteger("waveFormType");
    this.slowData = config.getBoolean("slowData");
    this.multiplier = config.getString("multiplier");
  }

  @Override
  public Future<?> start() throws Exception {
    this.serialPort = SerialPort.getCommPort(portName);
    serialPort.setBaudRate(baudRate);
    serialPort.setParity(SerialPort.EVEN_PARITY);
    serialPort.setNumDataBits(dataBits);
    serialPort.setNumStopBits(stopBits);
    serialPort.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 1000, 1000);
    serialPort.setFlowControl(SerialPort.FLOW_CONTROL_DISABLED);
    serialPort.setRTS();
    serialPort.setDTR();

    this.framer = new MedibusFramer(this::handleResponse, vertx, deviceID);
    logger.fine("Trying to initialize communication...");

    if (serialPort.openPort()) {
      connect();

    }
    return super.start();
  }

  @Override
  public Future<?> stop() throws Exception {
    disconnect();
    return super.stop();
  }


  @Override
  public void connect() {
    listenToSerial();
    sendICC();
  }

  @Override
  public void disconnect() {
    currentState = State.TERMINATING;
    logger.fine("Sending command: poll_request_stop_com");
    sendCommand(DataConstants.poll_request_stop_com);
  }


  private void readData(){
    byte[] buffer = new byte[serialPort.bytesAvailable()];
    int numRead = serialPort.readBytes(buffer, buffer.length);
    if (numRead > 0) {
      for (int i = 0; i < numRead; i++) {
        framer.createFrameListFromByte(buffer[i]);
      }
    }
  }

  private void writeData(byte[] data) {
    vertx.eventBus().publish("Oxylog-3000-Plus-00", new JsonObject().put("data", data));
  }

  private void listenToSerial() {
    serialPort.addDataListener(new SerialPortDataListener() {
      @Override
      public int getListeningEvents() {
        return SerialPort.LISTENING_EVENT_DATA_AVAILABLE;
      }

      @Override
      public void serialEvent(SerialPortEvent event) {
        if (event.getEventType() != SerialPort.LISTENING_EVENT_DATA_AVAILABLE) return;

       readData();
      }
    });
  }

  private void handleResponse(byte[] packetBuffer) {
    String response = new String(packetBuffer, StandardCharsets.US_ASCII);
    if (response.length() < 2) {
      logger.warning("Received response too short: " + response);
      return;
    }

    writeData(packetBuffer);
    String echo = response.substring(0, 2);
    logger.fine("Handling response with echo: " + DataUtils.stringToHex(echo) + " in state " + currentState);

    switch (currentState) {
      case INITIALIZING -> {
        switch (echo) {
          case "\u001bQ" -> {
            logger.fine("ICC command received.");
            commandEchoResponse(DataConstants.poll_request_icc_msg);
            logger.fine("Sending command: poll_request_deviceid");
            sendCommand(DataConstants.poll_request_deviceid);
            currentState = State.IDENTIFYING;
          }
          case "\u0001Q" -> {
            logger.fine("ICC response received. Transitioning to IDENTIFYING.");
            logger.fine("Sending command: poll_request_deviceid");
            sendCommand(DataConstants.poll_request_deviceid);
            currentState = State.IDENTIFYING;
          }
        }
      }
      case IDENTIFYING -> {
        switch (echo) {
          case "\u001bR" -> {
            logger.fine("Device ID request received. Sending Device ID.");
            sendDeviceID();
          }
          case "\u001b\u0030" -> {
            logger.fine("NOP request received.");
            logger.fine("Sending command: poll_request_deviceid");
            sendCommand(DataConstants.poll_request_deviceid);
          }
          case "\u0001R" -> {
            logger.fine("Device ID response received.");
            if (realTime) {
              logger.fine("Realtime enabled. Transitioning to CONFIGURING.");
              currentState = State.CONFIGURING;
              vertx.setTimer(200, id -> {
                sendCommand(DataConstants.poll_request_real_time_data_config);
              });
            } else {
              logger.fine("Transitioning to ACTIVE.");
              currentState = State.ACTIVE;
              transitToActive();
            }
          }
          default -> {
            if (echo.startsWith("\u001b")) {
              byte[] echoResponse = echo.substring(1).getBytes(StandardCharsets.US_ASCII);
              commandEchoResponse(echoResponse);
            }
          }
        }
      }

      case CONFIGURING -> {
        switch (echo) {
          case "\u001bQ" -> {
            logger.fine("ICC command received. Returning to INITIALIZING.");
            currentState = State.INITIALIZING;
            commandEchoResponse(DataConstants.poll_request_icc_msg);
          }
          case "\u001b\u0030" -> {
            logger.fine("NOP request received.");
            commandEchoResponse(DataConstants.poll_request_no_operation);
          }
          case "\u0001S" -> {
            logger.fine("Realtime config received. Sending transmission config.");
            logger.fine("Sending command: poll_configure_real_time_transmission");
            readRealtimeConfigResponse(packetBuffer);
            configureRealtimeTransmission();
          }
          case "\u0001T" -> {
            logger.fine("Realtime transmission configured. Transitioning to ACTIVE.");
            currentState = State.ACTIVE;
            logger.fine("Sending Sync-Command to enable datastreams.");
            setConfiguredDataStreams(false);
            transitToActive();
          }
          default -> {
            logger.warning("Unknown response in CONFIGURING: " + DataUtils.stringToHex(echo));
          }
        }
      }

      case ACTIVE -> {
        switch (echo) {
          case "\u0001\u0030" -> {
            logger.fine("NOP response received.");
            if (this.slowData) {
              logger.fine("Sending command: poll_request_config_measured_data_codepage1");
              sendCommand(DataConstants.poll_request_config_measured_data_codepage1);
            }
          }
          case "\u001b\u0030" -> {
            logger.fine("NOP request received.");
            commandEchoResponse(DataConstants.poll_request_no_operation);
            if (this.slowData) {
              logger.fine("Sending command: poll_request_config_measured_data_codepage1");
              sendCommand(DataConstants.poll_request_config_measured_data_codepage1);
            }
          }
          case "\u001bV" -> {
            logger.fine("Realtime config changed. Reconfiguring.");
            setConfiguredDataStreams(true);
            currentState = State.CONFIGURING;
            logger.fine("Sending command: poll_request_real_time_data_config");
            sendCommand(DataConstants.poll_request_real_time_data_config);
          }
          case "\u001bQ" -> {
            logger.fine("ICC command received. Returning to INITIALIZING.");
            currentState = State.INITIALIZING;
            commandEchoResponse(DataConstants.poll_request_icc_msg);
          }
          case "\u0001$" -> { // Data response cp1
            logger.log(Level.FINE, "Received: Data CP1 response");
            logger.fine("Sending command: poll_request_config_measured_data_codepage2");
            sendCommand(DataConstants.poll_request_config_measured_data_codepage2);}
          case "\u0001+" -> { // Data response cp2
            logger.log(Level.FINE, "Received: Data CP2 response");
            logger.fine("Sending command: poll_request_device_settings");
            sendCommand(DataConstants.poll_request_device_settings);
          }
          case "\u0001)" -> { // Data response device settings
            logger.log(Level.FINE, "Received: Data device settings response");
            logger.fine("Sending command: poll_request_text_messages");
            sendCommand(DataConstants.poll_request_text_messages);
          }
          case "\u0001*" -> { // Data response text messages
            logger.log(Level.FINE, "Received: Data text messages response");
            logger.fine("Sending command: poll_request_config_alarms_codepage1");
            sendCommand(DataConstants.poll_request_config_alarms_codepage1);
          }
          case "\u0001'" -> { // Alarm response cp1
            logger.log(Level.FINE, "Received: Alarm CP1 response");
            logger.fine("Sending command: poll_request_config_alarms_codepage2");
            sendCommand(DataConstants.poll_request_config_alarms_codepage2);
          }
          case "\u0001." -> { // Alarm response cp2
            logger.log(Level.FINE, "Received: Alarm CP2 response");
            logger.fine("Sending command: poll_request_config_measured_data_codepage1");
            sendCommand(DataConstants.poll_request_config_measured_data_codepage1);
          }
          default -> {
            logger.warning("Unknown response in ACTIVE: " + DataUtils.stringToHex(echo));
            if (echo.startsWith("\u001b")) {
              byte[] echoResponse = echo.substring(1).getBytes(StandardCharsets.US_ASCII);
              commandEchoResponse(echoResponse);
            }
          }
        }
      }

      default -> {
        switch (echo) {
          case "\u0001\u0030" -> {
            logger.fine("NOP response received.");
            commandEchoResponse(DataConstants.poll_request_no_operation);
          }
          case "\u001b\u0030" -> {
            logger.fine("NOP request received.");
            commandEchoResponse(DataConstants.poll_request_no_operation);
          }
          default -> {
            logger.warning("Unhandled state or echo: " + currentState + " / " + DataUtils.stringToHex(echo));
          }
        }
      }
    }
  }

  private void transitToActive() {
    if (this.slowData) {
      logger.fine("Slow Data transmission configured.");
      logger.fine("Sending command: poll_request_config_measured_data_codepage1");
      sendCommand(DataConstants.poll_request_config_measured_data_codepage1);
    } else {
      logger.fine("Slow Data transmission not configured.");
      logger.fine("Keeping connection alive by NOP");
      vertx.setPeriodic(2000, id -> sendCommand(DataConstants.poll_request_no_operation));
    }
  }

  public void readRealtimeConfigResponse(byte[] packetData) {
    // Store configuration values
    ByteBuffer bb = ByteBuffer.wrap(packetData);

    bb.position(2); // skip the packet header

    while (bb.remaining() >= 23) {
      byte[] dataCode = new byte[2];
      bb.get(dataCode);
      String dataCodeString = new String(dataCode).trim().replaceAll("\\s+", "");
      byte[] interval = new byte[8];
      bb.get(interval);
      String intervalString = new String(interval).trim().replaceAll("\\s+", "");
      byte[] minValue = new byte[5];
      bb.get(minValue);
      String minValueString = new String(minValue).trim().replaceAll("\\s+", "");
      byte[] maxValue = new byte[5];
      bb.get(maxValue);
      String maxValueString = new String(maxValue).trim().replaceAll("\\s+", "");
      byte[] maxBinValue = new byte[3];
      bb.get(maxBinValue);
      String maxBinValueString = new String(maxBinValue).trim().replaceAll("\\s+", "");

      JsonObject rtConfig = new JsonObject();
      rtConfig.put("dataCode", dataCodeString);
      rtConfig.put("Interval", Integer.parseInt(intervalString));
      rtConfig.put("minValue", Integer.parseInt(minValueString));
      rtConfig.put("maxValue", Integer.parseInt(maxValueString));
      rtConfig.put("maxBinValue", Integer.parseInt(maxBinValueString, 16));

      vertx.eventBus().publish(deviceID+"_rt", rtConfig);
    }
  }

  private void sendICC() {
    logger.fine("Sending command: poll_request_icc_msg");
    sendCommand(DataConstants.poll_request_icc_msg); // ICC
    currentState = State.INITIALIZING;
  }

  public void configureRealtimeTransmission() {
    if (this.waveFormType == 0) return; // config set to "No waveform data"
    ArrayList<Byte> tempTxBuffList = new ArrayList<>();
    ArrayList<Byte> waveFormTypeList = DataUtils.createWaveFormTypeList(this.waveFormType);

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

    logger.log(Level.FINE, "Send: Configure realtime transmission (command)");
    sendCommand(finalBuffer);
  }

  private void sendCommand(byte[] commandBytes) {
    if (commandBytes.length == 0) {
      return;
    }

    byte[] inputBuffer = new byte[commandBytes.length + 1]; //
    inputBuffer[0] = DataConstants.BOFCOMCHAR;
    System.arraycopy(commandBytes, 0, inputBuffer, 1, commandBytes.length);

    byte computedChecksum = DataUtils.computeChecksum(inputBuffer);
    byte[] checksumBytes = String.format("%02X", computedChecksum).getBytes(StandardCharsets.US_ASCII);
    byte[] finalMessage = DataUtils.concatBuffer(inputBuffer, checksumBytes);

    try {
      serialPort.writeBytes(finalMessage, finalMessage.length, 0);
    } catch (Exception e) {
      System.err.println("Error writing to serial port: " + e.getMessage());
    }
  }

  public void commandEchoResponse(byte[] commandBuffer) {
    byte[] inputBuffer = new byte[commandBuffer.length + 2]; // +2 for BOF and checksum
    System.arraycopy(commandBuffer, 0, inputBuffer, 1, commandBuffer.length);
    inputBuffer[0] = DataConstants.BOFRESPCHAR;

    byte checksumComputed = DataUtils.computeChecksum(inputBuffer);

    String checksumToAsciiHex = String.format("%02x", checksumComputed).toUpperCase();
    byte[] checksumAsciiHexBytes = checksumToAsciiHex.getBytes(StandardCharsets.US_ASCII);

    byte[] finalTxBuff = DataUtils.concatBuffer(inputBuffer, checksumAsciiHexBytes);

    try {
      this.serialPort.writeBytes(finalTxBuff, finalTxBuff.length,0);
    } catch (Exception ex) {
      System.err.println("Error opening/writing to serial port :: " + ex.getMessage());
    }
  }

  public void sendDeviceID() {
    byte[] deviceIDCommandResponse = { 0x52 };
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

    commandEchoResponse(txBuffer);
  }

  public void setConfiguredDataStreams(boolean disable){
    if (this.waveFormType == 0) return;
    setDataStreams(DataConstants.SC_DATASTREAM_1_4, disable);
    if (this.waveFormType == 4) {
      setDataStreams(DataConstants.SC_DATASTREAM_5_8, disable);
      setDataStreams(DataConstants.SC_DATASTREAM_9_12, disable);
    }
  }

  public void setDataStreams(byte syncCommand, boolean disable) {
    // enable or disable data streams
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

    this.serialPort.writeBytes(finalBuffer, finalBuffer.length, 0);
  }

}
