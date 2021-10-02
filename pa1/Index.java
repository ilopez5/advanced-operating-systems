import java.util.concurrent.*;
import java.util.*;
import java.net.*;
import java.io.*;

public class Index {
    /* index metadata */
    private ServerSocket server;
    private ConcurrentHashMap<String, HashSet<PeerMetadata>> registry;

    /* constructor */
    public Index() {
        // initialize index metadata
        this.registry = new ConcurrentHashMap<String, HashSet<PeerMetadata>>();

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

    /*
     *  listen - spawns a thread to listen to this given peer socket. This means
     *          there will be a thread for each peer in the P2P network. This is
     *          not scalable, but works for the purposes of this assignment, where
     *          we are running with less than 5 peers.
     */
    public void listen() {
        try {
            while (true) {
                // accept an incoming connection
                Socket peerSocket = this.server.accept();
                IndexHandler client = new IndexHandler(this, peerSocket);
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
    public void register(String fileName, PeerMetadata fileMeta) {
        // check if this file has been registered before
        if (this.registry.containsKey(fileName)) {
            // it exists, acquire its known peer list and add this peer to it
            HashSet<PeerMetadata> filePeerList = this.registry.get(fileName);
            filePeerList.add(fileMeta);
        } else {
            // does not exist, create a new entry with this peer associated to it
            HashSet<PeerMetadata> filePeerList = new HashSet<PeerMetadata>();
            filePeerList.add(fileMeta);
            this.registry.put(fileName, filePeerList);
        }
        System.out.println(String.format("[Index]: Registered '%s' to Peer %d", fileName, fileMeta.getID()));
    }

    /*
     *  search - Checks the registry for 'fileName'. If registry contains
     *          'fileName', return the peer list associated with it.
     *          Else, returns null.
     */
    public HashSet<PeerMetadata> search(String fileName) {
        // check if file has been registered before
        if (this.registry.containsKey(fileName)) {
            // return HashSet of peers if yes
            HashSet<PeerMetadata> filePeerList = this.registry.get(fileName);
            System.out.println(String.format("[Index]: Found '%s' in registry.", fileName));
            return filePeerList;
        }
        System.out.println(String.format("[Index]: Did not '%s' in registry.", fileName));
        return new HashSet<PeerMetadata>();
    }

    /*
     *  deregister - Accepts a 'peerID' and a 'fileName'. Removes 'peerID' from
     *              the peer list associated with 'fileName'. If the peer list
     *              becomes empty, remove 'fileName' from the registry altogether.
     */
    public void deregister(String fileName, PeerMetadata peer) {
        // only do work if file is registered
        if (this.registry.containsKey(fileName)) {
            HashSet<PeerMetadata> filePeerList = this.registry.get(fileName);
            filePeerList.remove(peer);
            System.out.println(String.format("[Index]: Deregistered Peer %d from '%s'.", peer.getID(), fileName));
            if (filePeerList.size() == 0) {
                // last peer removed from file's peer list, remove file from registry
                this.registry.remove(fileName);
                System.out.println(String.format("[Index]: Deregistered '%s' entirely from Registry.", fileName));
            }
        }
    }

    /* deals with sending/receiving a message from peers */
    private static class IndexHandler extends Thread {
        private Index index;
        private Socket peerSocket; // used for the index to communicate with it
        private PeerMetadata peer; // contains peer ID and peerServerPort

        // constructor
        public IndexHandler(Index index, Socket socket) {
            this.index = index;
            this.peerSocket = socket;
        }

        public void run() {
            try {
                // dataIn is for requests received FROM THE PEER
                // dataOut is for responses we send TO THE PEER
                DataInputStream dataIn = new DataInputStream(peerSocket.getInputStream());
                DataOutputStream dataOut = new DataOutputStream(peerSocket.getOutputStream());

                // first task: get peer id and address/port
                this.peer = new PeerMetadata(dataIn.readInt(), dataIn.readInt());
                System.out.print(String.format("[Index]: Registered Peer %d at 127.0.0.1:%d.\n",
                                this.peer.getID(), this.peer.getServerPort()));

                // parse a command from peer
                while (true) {
                    // receive a command in the form of "command fileName"
                    //      e.g., "register Moana.txt"
                    String[] request = dataIn.readUTF().split("[ \t]+");
                    if (request.length == 0) {
                        break;
                    }
                    String command = request[0];
                    String fileName = request[1];

                    // handle command appropriately
                    switch (command) {
                        case "register":
                            this.index.register(fileName, this.peer);
                            break;
                        case "search":
                            HashSet<PeerMetadata> peerList = this.index.search(fileName);
                            dataOut.writeUTF(peerList.toString());
                            break;
                        case "deregister":
                            this.index.deregister(fileName, this.peer);
                            break;
                        default:
                            System.out.println(String.format("[Index]: Received unknown command '%s'. Ignoring.", command));
                            break;
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                // close sockets probably
            }
        }
    }
}
// code adopted from https://www.geeksforgeeks.org/multithreaded-servers-in-java/
// https://stackoverflow.com/questions/3154488/how-do-i-iterate-through-the-files-in-a-directory-in-java