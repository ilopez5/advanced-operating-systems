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
        System.out.println(String.format("[Index]: Listening on 127.0.0.1:%d...", index.server.getLocalPort()));
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
    public void register(String fileName, FileMetadata fileMeta) {
        // check if this file has been registered before
        if (this.registry.containsKey(fileName)) {
            // it exists, acquire its known peer list and add this peer to it
            HashSet<FileMetadata> filePeerList = this.registry.get(fileName);
            filePeerList.add(fileMeta);
        } else {
            // does not exist, create a new entry with this peer associated to it
            HashSet<FileMetadata> filePeerList = new HashSet<FileMetadata>();
            filePeerList.add(fileMeta);
            this.registry.put(fileName, filePeerList);
        }
        System.out.println(String.format("[Index]: Registered %s to Peer %d", fileName, fileMeta.getPeerID()));
    }

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
                System.out.print(String.format("[Index]: Registered Peer %d at 127.0.0.1:%d.", this.peerID, this.peerServerPort));

                // parse a command from peer
                while (true) {
                    // receive a command in the form of "command fileName"
                    //      e.g., "register Moana.txt"
                    String[] request = dataIn.readUTF().split("\s");
                    String command = request[0];
                    String fileName = request[1];
                    System.out.println(String.format("[Index]: Peer %d -> %s %s.", this.peerID, command, fileName));

                    // handle command appropriately
                    switch (command) {
                        case "register":
                            register(fileName, new FileMetadata(this.peerID, this.peerServerPort));
                            break;
                        case "search":
                            break;
                        case "deregister":
                            break;
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            } finally {

            }
        }
    }
}
// code adopted from https://www.geeksforgeeks.org/multithreaded-servers-in-java/
// https://stackoverflow.com/questions/3154488/how-do-i-iterate-through-the-files-in-a-directory-in-java