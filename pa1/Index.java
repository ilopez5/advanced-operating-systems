import java.util.*;
import java.net.*;
import java.io.*;

public class Index extends Thread {
    /* index metadata */
    private int indexID;
    private String address = "127.0.0.1";
    private int port = 5000;
    private ServerSocket server = null;

    /* constructor */
    public Index(int indexID, String address, int port) {
        this.indexID = indexID;
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
            System.out.println(String.format("(index %d): Listening on %s:%d...", 
                        this.indexID, this.address, this.port));
            this.server.accept();
            // TODO: implement logic
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
    public int getIndexID() {
        return this.indexID;
    }

    public String getAddress() {
        return this.address;
    }

    public int getPort() {
        return this.port;
    }
    
    /* methods */
    public void registry(int peerID, String fileName) {
        System.out.println(String.format("(index %d): registry() is not yet implemented!", this.indexID));
        return;
    }

    public void search(String fileName) {
        System.out.println(String.format("(index %d): search() is not yet implemented!", this.indexID));
        return;
    }

    public void deregister(int peerId, String fileName) {
        System.out.println(String.format("(index %d): deregister() is not yet implemented!", this.indexID));
        return;
    }
}

