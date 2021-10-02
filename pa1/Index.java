import java.util.concurrent.*;
import java.util.*;
import java.net.*;
import java.io.*;

public class Index {
    /* index metadata */
    private ServerSocket server;
    private ConcurrentHashMap<String, HashSet<FileMetadata>> registry;

    /* constructor */
    public Index() {
        // initialize index metadata
        this.registry = new ConcurrentHashMap<String, HashSet<FileMetadata>>();

        try {
            // create new socket for connections
            this.server = new ServerSocket(0);
            this.server.setReuseAddress(true);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void main(String[] args) {
        // constructs a new Index object
        Index index = new Index();
        System.out.println(String.format("Listening on 127.0.0.1:%d...", index.server.getLocalPort()));
        index.listen();
    }

    public void listen() {
        try {
            while (true) {
                // accept an incoming connection
                Socket peerSocket = this.server.accept();
                IndexHandler client = new IndexHandler(this.registry, peerSocket);
                // spawns a thread to handle THIS specific peer
                client.start();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /*
     *  register - Accepts a 'peerID' and a 'fileName'. If 'fileName' is not
     *              in the registry, it is added with 'peerID' as its only
     *              peer. Else, 'peerID' is added to the existing peer list.
     */
    // public void registry(int peerID, String fileName) {
    //     // check if this file has been registered before
    //     if (this.registry.containsKey(fileName)) {
    //         HashSet<Integer> filePeerList = this.registry.get(fileName);
    //         filePeerList.add(peerID);
    //     } else {
    //         // has not been registered, creating a new entry and adding peerID to it
    //         HashSet<Integer> filePeerList = new HashSet<Integer>();
    //         filePeerList.add(peerID);
    //         this.registry.put(fileName, filePeerList);
    //     }
    //     System.out.println(String.format("(index %d): registered %s to peer %d", this.indexID, fileName, peerID));
    // }

    // /*
    //  *  search - Checks the registry for 'fileName'. If registry contains
    //  *          'fileName', return the peer list associated with it.
    //  *          Else, returns null.
    //  */
    // public HashSet<Integer> search(String fileName) {
    //     // check if file has been registered before
    //     if (this.registry.containsKey(fileName)) {
    //         // return HashSet of peers if yes
    //         // TODO: should I return a copy? or a reference?
    //         HashSet<Integer> filePeerList = this.registry.get(fileName);
    //         System.out.println(String.format("(index %d): found %s in registry.", this.indexID, fileName));
    //         return filePeerList;
    //     }
    //     return null;
    // }

    // /*
    //  *  deregister - Accepts a 'peerID' and a 'fileName'. Removes 'peerID' from
    //  *              the peer list associated with 'fileName'. If the peer list
    //  *              becomes empty, remove 'fileName' from the registry altogether.
    //  */
    // public void deregister(int peerID, String fileName) {
    //     // only do work if file is registered
    //     if (this.registry.containsKey(fileName)) {
    //         HashSet<Integer> filePeerList = this.registry.get(fileName);
    //         filePeerList.remove(peerID);
    //         System.out.println(String.format("(index %d): deregistered peer %d from %s", this.indexID, peerID, fileName));
    //         if (filePeerList.size() == 0) {
    //             // last peer removed from file's peer list, remove file from registry
    //             this.registry.remove(fileName);
    //             System.out.println(String.format("(index %d): deregistered %s entirely", this.indexID, fileName));
    //         }
    //     }
    // }

    /* deals with sending/receiving a message from peers */
    private static class IndexHandler extends Thread {
        private Socket peerSocket;
        private ConcurrentHashMap<String, HashSet<FileMetadata>> registry;
        private int peerID;
        private int peerServerPort;

        // constructor
        public IndexHandler(ConcurrentHashMap<String, HashSet<FileMetadata>> registry, Socket socket) {
            this.peerSocket = socket;
            this.registry = registry;
        }

        public void run() {
            try {
                // dataIn is for requests received FROM THE PEER
                // dataOut is for responses we send TO THE PEER
                DataInputStream dataIn = new DataInputStream(peerSocket.getInputStream());
                DataOutputStream dataOut = new DataOutputStream(peerSocket.getOutputStream());

                // first task: get peer id and address/port
                this.peerID = dataIn.readInt();
                this.peerServerPort = dataIn.readInt();
                System.out.print(String.format("(index) registered peer %d at 127.0.0.1:%d", this.peerID, this.peerServerPort));

                // parse a command from peer
                while (true) {
                    // take any command and deal with it accordingly
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }
}
// code adopted from https://www.geeksforgeeks.org/multithreaded-servers-in-java/