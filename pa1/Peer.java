import java.util.*;
import java.net.*;
import java.io.*;

public class Peer extends Thread {
    /* peer metadata */
    private int peerID;
    private String address = "127.0.0.1";
    private int port = 5001;
    private ServerSocket server = null;

    /* peer constructor */
    public Peer(int peerID, String address, int port) {
        this.peerID  = peerID;
        this.address = address;
        this.port    = port;

        try {
            this.server = new ServerSocket(port);
            this.server.setReuseAddress(true);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /* runs as a thread when .start() is called */
    public void run() {
        try {
            System.out.println(String.format("(peer %d): Listening on %s:%d...", 
                        this.peerID, this.address, this.port));
            this.server.accept();
            // TODO: spin up a thread to listen, then do other stuff
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (this.server != null) {
                try {
                    this.server.close();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
        
    }

    /* getters */
    public int getPeerID() {
        return this.peerID;
    }
    
    public String getAddress() {
        return this.address;
    }

    public int getPort() {
        return this.port;
    }

    /*
     *  retrieve - given a file name, will return/download that file to caller
     */
    public void retrieve (String fileName) {
        System.out.println(String.format("(peer %d): Received request for file %s", this.peerID, fileName));
    }
}
