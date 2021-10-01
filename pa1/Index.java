import java.util.*;
import java.net.*;
import java.io.*;

public class Index extends Thread {
    /* index metadata */
    private int indexID;
    private String address = "127.0.0.1";
    private int port = 5000;
    private ServerSocket server = null;
    private HashMap<String, HashSet<Integer>> registry;

    /* constructor */
    public Index(int indexID, String address, int port) {
        this.indexID  = indexID;
        this.address  = address;
        this.port     = port;
        this.registry = new HashMap<String, HashSet<Integer>>();

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

    /*
     *  register - Accepts a 'peerID' and a 'fileName'. If 'fileName' is not
     *              in the registry, it is added with 'peerID' as its only
     *              peer. Else, 'peerID' is added to the existing peer list.
     */
    public void registry(int peerID, String fileName) {
        // check if this file has been registered before
        if (this.registry.containsKey(fileName)) {
            Set<Integer> fileSet = this.registry.get(fileName);
            fileSet.add(peerID);
        } else {
            // has not been registered, creating a new entry and adding peerID to it
            HashSet<Integer> fileSet = new HashSet<Integer>();
            fileSet.add(peerID);
            this.registry.put(fileName, fileSet);
        }
        System.out.println(String.format("(index %d): registered %s to peer %d", this.indexID, fileName, peerID));
    }

    /*
     *  search - Checks the registry for 'fileName'. If registry contains
     *          'fileName', return the peer list associated with it.
     *          Else, returns null.
     */
    public HashSet<Integer> search(String fileName) {
        // check if file has been registered before
        if (this.registry.containsKey(fileName)) {
            // return HashSet of peers if yes
            HashSet<Integer> peerList = this.registry.get(fileName);
            System.out.println(String.format("(index %d): found %s in registry.", this.indexID, fileName));
            return peerList;
        }
        return null;
    }

    /*
     *  deregister - Accepts a 'peerID' and a 'fileName'. Removes 'peerID' from
     *              the peer list associated with 'fileName'. If the peer list
     *              becomes empty, remove 'fileName' from the registry altogether.
     */
    public void deregister(int peerID, String fileName) {
        // only do work if file is registered
        if (this.registry.containsKey(fileName)) {
            HashSet<Integer> peerList = this.registry.get(fileName);
            peerList.remove(peerID);
            System.out.println(String.format("(index %d): deregistered peer %d from %s", this.indexID, peerID, fileName));
            if (peerList.size() == 0) {
                // last peer removed from file's peer list, remove file from registry
                this.registry.remove(fileName);
                System.out.println(String.format("(index %d): deregistered %s entirely", this.indexID, fileName));
            }
        }
    }
}

