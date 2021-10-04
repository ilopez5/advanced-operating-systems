import java.util.concurrent.*;
import java.util.*;
import java.net.*;
import java.io.*;

/**
 *  Index - this class runs and maintains an in-memory registry that links
 *          files to the peers that contain them. Additionally, this class offers
 *          an API for which peers can register new files, deregister old ones,
 *          and search for files.
 *          These communications occur over TCP using Sockets. Thus, most
 *          communications are simply UTF string messages being passed back and
 *          forth.
 *
 *          Citations:
 *              some code adopted from https://www.geeksforgeeks.org/multithreaded-servers-in-java/
 */
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
     *  listen - waits for incoming connections. Upon receiving one, it spawns a
     *          IndexHandler thread to handle this request/connection. This means
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
    public int register(String fileName, PeerMetadata fileMeta) {
        try {
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
            return 0;
        } catch (Exception e) {
            e.printStackTrace();
            return 1;
        }
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
            System.out.println(String.format("[Index]: Found '%s' in registry. Returning its Peer List.", fileName));
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
    public int deregister(String fileName, PeerMetadata peer) {
        try {
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
            return 0;
        } catch (Exception e) {
            e.printStackTrace();
            return 1;
        }
    }

    /**
     *  Nested Class(es):
     *
     *      IndexHandler - This class handles communication with an incoming
     *                  peer request. It ruins on its own thread, receives
     *                  the request over TCP, parses it, executes it, and
     *                  returns a response (if necessary).
     */
    private class IndexHandler extends Thread {
        /* references to this peer and the socket for the incoming peer */
        private Index index;
        private Socket peerSocket; // used for the index to communicate with it
        private PeerMetadata peer; // contains peerID and peerServerPort

        /* constructor */
        public IndexHandler(Index index, Socket socket) {
            this.index = index;
            this.peerSocket = socket;
        }

        public void run() {
            try {
                // dataIn is for requests received FROM THE PEER
                // dataOut is for responses we send TO THE PEER
                DataInputStream fromPeer = new DataInputStream(peerSocket.getInputStream());
                DataOutputStream toPeer = new DataOutputStream(peerSocket.getOutputStream());

                // first task: get peer id and address/port
                this.peer = new PeerMetadata(fromPeer.readInt(), fromPeer.readInt());
                System.out.print(String.format("[Index]: Registered Peer %d at 127.0.0.1:%d.\n",
                                this.peer.getID(), this.peer.getServerPort()));

                // parse a command from peer
                while (true) {
                    // receive a command in the form of "command fileName"
                    //      e.g., "register Moana.txt"
                    String[] request = fromPeer.readUTF().split("[ \t]+");
                    if (request.length > 0) {
                        String command = request[0];
                        String fileName = request[1];

                        // handle command appropriately
                        int rc;
                        switch (command) {
                            case "register":
                                rc = this.index.register(fileName, this.peer);
                                toPeer.writeInt(rc);
                                break;
                            case "search":
                                String response = this.index.search(fileName).toString();
                                toPeer.writeUTF(response);
                                break;
                            case "deregister":
                                rc = this.index.deregister(fileName, this.peer);
                                toPeer.writeInt(rc);
                                break;
                            default:
                                System.out.println(String.format("[Index]: Received unknown command '%s'. Ignoring.", command));
                                break;
                        }
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }
}