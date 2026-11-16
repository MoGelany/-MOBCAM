package com.jilani.mobcam;

/** One PTP connection to the camera — over USB-C or over Wi-Fi (PTP/IP). */
public interface PtpTransport {

    class Resp {
        public int code;
        public byte[] data;
    }

    /** Sends one PTP operation (with optional outgoing data) and returns the response + any incoming data. */
    Resp transact(int op, byte[] dataOut, int... params) throws Exception;

    void close();
}
