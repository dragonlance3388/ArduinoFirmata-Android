package org.shokai.firmata;

import java.io.IOException;
import java.util.List;

import android.content.Context;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;

import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;
import com.hoho.android.usbserial.util.HexDump;

public class ArduinoFirmata{
    public final static String VERSION = "0.2.0";
    public final static String TAG = "ArduinoFirmata";

    public static final byte INPUT  = 0;
    public static final byte OUTPUT = 1;
    public static final byte ANALOG = 2;
    public static final byte PWM    = 3;
    public static final byte SERVO  = 4;
    public static final byte SHIFT  = 5;
    public static final byte I2C    = 6;
    public static final boolean LOW   = false;
    public static final boolean HIGH  = true;
    private final byte MAX_DATA_BYTES  = 32;
    private final byte DIGITAL_MESSAGE = (byte)0x90;
    private final byte ANALOG_MESSAGE  = (byte)0xE0;
    private final byte REPORT_ANALOG   = (byte)0xC0;
    private final byte REPORT_DIGITAL  = (byte)0xD0;
    private final byte SET_PIN_MODE    = (byte)0xF4;
    private final byte REPORT_VERSION  = (byte)0xF9;
    private final byte SYSTEM_RESET    = (byte)0xFF;
    private final byte START_SYSEX     = (byte)0xF0;
    private final byte END_SYSEX       = (byte)0xF7;

    private UsbSerialDriver usb_driver;
    private UsbManager usb_manager;
    private UsbSerialPort usb_port;
    //private Context context;
    private Thread th_receive = null;
    private ArduinoFirmataEventHandler handler;
    private ArduinoFirmataDataHandler dataHandler;
    public void setEventHandler(ArduinoFirmataEventHandler handler){
        this.handler = handler;
    }
    public void setDataHandler(ArduinoFirmataDataHandler handler){
        this.dataHandler = handler;
    }

    private int waitForData = 0;
    private byte executeMultiByteCommand = 0;
    private byte multiByteChannel = 0;
    private byte[] storedInputData = new byte[MAX_DATA_BYTES];
    private boolean parsingSysex = false;
    private int sysexBytesRead = 0;
    private int[] digitalOutputData = { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0 };
    private int[] digitalInputData  = { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0 };
    private int[] analogInputData   = { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0 };
    private int majorVersion = 0;
    private int minorVersion = 0;
    
	public String MsgLog=""; 
	private int MsgLogCnt =0;
	
	public String GetLog(){
		return MsgLog;
	}

	public void SetLog(String Msg) {
		MsgLogCnt++;
		MsgLog += "\n" + MsgLogCnt + ":" + Msg;
		if (MsgLogCnt >= 10) {
			MsgLogCnt = 0;
			MsgLog = "";
		}
	}
	
    public String getBoardVersion(){
        return String.valueOf(majorVersion)+"."+String.valueOf(minorVersion);
    }

    public ArduinoFirmata(android.app.Activity context){
        //this.context = context;

        //this.usb = UsbSerialProber.acquire(manager);
        this.usb_manager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        List<UsbSerialDriver> availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(this.usb_manager);
        if (availableDrivers.isEmpty()) {
          return;
        }
        // Open a connection to the first available driver.
        this.usb_driver = availableDrivers.get(0);
    }

    public void connect() throws IOException, InterruptedException{
        if(this.usb_driver == null) throw new IOException("device not found");
        
     // Find all available drivers from attached devices.
        
        UsbDeviceConnection connection = this.usb_manager.openDevice(this.usb_driver.getDevice());
        if (connection == null) {
          // You probably need to call UsbManager.requestPermission(driver.getDevice(), ..)
          return;
        }
		// Read some data! Most have just one port (port 0).
		this.usb_port = this.usb_driver.getPorts().get(0);
        
        try{
            this.usb_port.open(connection);
            this.usb_port.setBaudRate(9600);
            Thread.sleep(3000);
        }
        catch(InterruptedException e){
            throw e;
        }
        catch(IOException e){
            throw e;
        }
        if(this.th_receive == null){
            this.th_receive = new Thread(new Runnable(){
                    public void run(){
                        while(isOpen()){
                            try{
                                byte buf[] = new byte[4096];
                                int size = usb_port.read(buf, 100);
                                //SetLog("Read:"+HexDump.dumpHexString(buf));
                                if(size > 0){
                                    for(int i = 0; i < size; i++){
                                        processInput(buf[i]);
                                    }
                                }
                                Thread.sleep(10);
                            }
                            catch(IOException e){
                                close();
                                if(handler!=null) handler.onClose();
                            }
                            catch(InterruptedException e){
                                if(handler!=null) handler.onError(e.toString());
                            }
                        }
                    }
                });
            this.th_receive.start();
        }

        
        for (byte i = 0; i < 6; i++) {
        	byte[] writeData = {(byte)(REPORT_ANALOG | i), 1};
            write(writeData);
        }
        for (byte i = 0; i < 15; i++) {
        	byte[] writeData = {(byte)(REPORT_DIGITAL | i), 1};
            write(writeData);
        }
    }

    public boolean isOpen(){
        return this.usb_port != null;
    }

    public boolean close(){
        try{
            this.usb_port.close();
            this.usb_port = null;
            return true;
        }
        catch(IOException e){
            if(handler!=null) handler.onError(e.toString());
            return false;
        }
    }

    public void write(byte[] writeData){
        try{
            if(this.isOpen()){ 
				this.usb_port.write(writeData, 100);
				SetLog("Write:" + HexDump.dumpHexString(writeData));
			}
        }
        catch(IOException e){
            this.close();
            if(handler!=null) handler.onClose();
        }
    }

    public void write(byte writeData){
        byte[] _writeData = {(byte)writeData};
        write(_writeData);
    }

    public void reset(){
        write(SYSTEM_RESET);
    }

    public void sysex(byte command, byte[] data){
        // http://firmata.org/wiki/V2.1ProtocolDetails#Sysex_Message_Format
        if(data.length > 32) return;
        byte[] writeData = new byte[data.length+3];
        writeData[0] = START_SYSEX;
        writeData[1] = command;
        for(int i = 0; i < data.length; i++){
            writeData[i+2] = (byte)(data[i] & 127); // 7bit
        }
        writeData[writeData.length-1] = END_SYSEX;
        write(writeData);
    }

    public boolean digitalRead(int pin) {
        return ((digitalInputData[pin >> 3] >> (pin & 0x07)) & 0x01) > 0;
    }

    public int analogRead(int pin) {
        return analogInputData[pin];
    }

    public void pinMode(int pin, byte mode) {
        byte[] writeData = {SET_PIN_MODE, (byte)pin, mode};
        write(writeData);
    }

    public void digitalWrite(int pin, boolean value) {
        byte portNumber = (byte)((pin >> 3) & 0x0F);
        if (!value) digitalOutputData[portNumber] &= ~(1 << (pin & 0x07));
        else digitalOutputData[portNumber] |= (1 << (pin & 0x07));
        byte[] writeData = {
            SET_PIN_MODE, (byte)pin, OUTPUT,
            (byte)(DIGITAL_MESSAGE | portNumber),
            (byte)(digitalOutputData[portNumber] & 0x7F),
            (byte)(digitalOutputData[portNumber] >> 7)
        };
        write(writeData);
    }

    public void analogWrite(int pin, int value) {
        byte[] writeData = {
            SET_PIN_MODE, (byte)pin, PWM,
            (byte)(ANALOG_MESSAGE | (pin & 0x0F)),
            (byte)(value & 0x7F),
            (byte)(value >> 7)
        };
        write(writeData);
    }

    public void servoWrite(int pin, int angle){
        byte[] writeData = {
            SET_PIN_MODE, (byte)pin, SERVO,
            (byte)(ANALOG_MESSAGE | (pin & 0x0F)),
            (byte)(angle & 0x7F),
            (byte)(angle >> 7)
        };
        write(writeData);
    }

    private void setDigitalInputs(int portNumber, int portData) {
        digitalInputData[portNumber] = portData;
    }

    private void setAnalogInput(int pin, int value) {
        analogInputData[pin] = value;
    }

    private void setVersion(int majorVersion, int minorVersion) {
        this.majorVersion = majorVersion;
        this.minorVersion = minorVersion;
    }

    private void processInput(byte inputData){
        byte command;
        if(parsingSysex){
            if(inputData == END_SYSEX){
                parsingSysex = false;
                byte sysexCommand = storedInputData[0];
                byte[] sysexData = new byte[sysexBytesRead-1];
                System.arraycopy(storedInputData, 1, sysexData, 0, sysexBytesRead-1);
                if(dataHandler != null) dataHandler.onSysex(sysexCommand, sysexData);
            }
            else{
                if(sysexBytesRead < storedInputData.length){
                    storedInputData[sysexBytesRead] = inputData;
                    sysexBytesRead++;
                }
            }
        }
        else if(waitForData > 0 && inputData < 128){
            waitForData--;
            storedInputData[waitForData] = inputData;
            if(executeMultiByteCommand != 0 && waitForData == 0){
                switch(executeMultiByteCommand){
                case DIGITAL_MESSAGE:
                    setDigitalInputs(multiByteChannel, (storedInputData[0] << 7) + storedInputData[1]);
                    break;
                case ANALOG_MESSAGE:
                    setAnalogInput(multiByteChannel, (storedInputData[0] << 7) + storedInputData[1]);
                    break;
                case REPORT_VERSION:
                    setVersion(storedInputData[1], storedInputData[0]);
                    break;
                }
            }
        }
        else {
            if(inputData < 0xF0){
                command = (byte)(inputData & 0xF0);
                multiByteChannel = (byte)(inputData & 0x0F);
            }
            else{
                command = inputData;
            }
            switch(command){
            case START_SYSEX:
                parsingSysex = true;
                sysexBytesRead = 0;
                break;
            case DIGITAL_MESSAGE:
            case ANALOG_MESSAGE:
            case REPORT_VERSION:
                waitForData = 2;
                executeMultiByteCommand = command;
                break;
            }
        }
    }

}