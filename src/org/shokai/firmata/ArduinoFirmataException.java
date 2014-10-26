package org.shokai.firmata;

public class ArduinoFirmataException extends Exception{
    /**
	 * Set default serial version UID
	 */
	private static final long serialVersionUID = 1L;

	public ArduinoFirmataException(String msg){
        super(msg);
    }
}