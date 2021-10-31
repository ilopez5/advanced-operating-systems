import java.util.concurrent.*;
import java.util.*;
import java.net.*;
import java.io.*;

/**
 *  SuperPeer - this class defines SuperPeers, which are proxies to the entire
 *              network. Leaf peers, or just Peers, send queries to their respective
 *              SuperPeer, and expect to get back k query-hit messages, indicating
 *              which peers contain that message.
 *              All network communications occur over TCP using Sockets. Thus,
 *              most communications are simply UTF string messages being passed
 *              back and forth.
 *
 *          Citations:
 *              code adopted from PA1 (which cites its references)
 */
public class SuperPeer {
    /* SuperPeer metadata */
    private IPv4 address;
    private ServerSocket server; // for communicating TO this SuperPeer
    private ConcurrentHashMap<String, HashSet<IPv4>> registry;
    private List<String> history;
    private ConcurrentHashMap<String, IPv4> mappedHistory;
    private Set<String> neighbors; // for tracking SuperPeer neighbors
    private Set<String> leafs; // for tracking associated peers
    private File config;
    private int historySize = 50;
    private ConsistencyModel model;

    // colors
    public static final String ANSI_RESET = "\u001B[0m";
    public static final String ANSI_RED = "\u001B[31m";
    public static final String ANSI_GREEN = "\u001B[32m";
    public static final String ANSI_YELLOW = "\u001B[33m";
    public static final String ANSI_CYAN = "\u001B[36m";
    public static final String ANSI_WHITE = "\u001B[37m";

    /* constructor */
    public SuperPeer(String address, File config) {
        try {
            this.mappedHistory = new ConcurrentHashMap<String, IPv4>();
            this.neighbors = ConcurrentHashMap.newKeySet();
            this.registry = new ConcurrentHashMap<String, HashSet<IPv4>>();
            this.history = Collections.synchronizedList(new ArrayList<String>());
            this.address = new IPv4(address);
            this.config = config;
            this.leafs = ConcurrentHashMap.newKeySet();

            // create new socket for connections
            this.server = new ServerSocket(this.address.getPort());
            this.server.setReuseAddress(true);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     *  program usage:
     *          $ java SuperPeer <address:port> <topology-config>
     *    e.g., $ java SuperPeer 127.0.0.1:5000 linear.config
     */
    public static void main(String[] args) {
        // construct a new SuperPeer object
        SuperPeer superPeer = new SuperPeer(args[0], new File(args[1]));
        superPeer.initialize().listen();
    }

    /**
     *  initialize - reads a config file and stores relevant info to THIS SuperPeer
     *      Config Syntax: <type> <address:port> <address:port>
     *          - 'type' -> 's' or 'p' for SuperPeer and Peer, respectively
     *              - for type 's': the second peer is a SuperPeer neighbor
     *              - for type 'p': the second peer is a Peer for this SuperPeer
     */
    public SuperPeer initialize() {
        try {
            this.log(String.format("Listening on %s:%d...", this.getAddress(), this.getPort()));
            // read config file (e.g., all-to-all.config, linear.config)
            Scanner sc = new Scanner(new FileInputStream(this.config));

            // process each line
            while (sc.hasNextLine()) {
                // parse and decompose line
                String[] line = sc.nextLine().split(" ");
                String type = line[0];
                IPv4 peer;

                switch (type) {
                    case "c":
                        // this record is for consistency model
                        switch (line[1]) {
                            case "pull":
                                this.model = ConsistencyModel.PULL;
                                break;
                            case "push":
                                this.model = ConsistencyModel.PUSH;
                                break;
                            default:
                                this.log(String.format("Consistency model should be 'push' or 'pull', got '%s'. Defaulting to 'push'...", line[1]));
                                this.model = ConsistencyModel.PUSH;
                                break;
                        }
                        break;
                    case "s":
                        // SuperPeer definition: 'other' is neighbor to associate with this SuperPeer
                        peer = new IPv4(line[1]);
                        if (this.equals(peer)) {
                            this.neighbors.add(line[2]);
                        }
                        break;
                    case "p":
                        // peer definition: 'other' is peer to associate with this SuperPeer
                        peer = new IPv4(line[1]);
                        if (this.equals(peer)) {
                            this.leafs.add(line[2]);
                        }
                        break;
                    default:
                        this.log(String.format("Unknown type '%s'. Ignoring...", type));
                        break;
                }
            }
            sc.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return this;
    }

    /** register - accepts a peer and a fileName. creates or updates entry in registry */
    public int register(String fileName, IPv4 peer) {
        try {
            // check if this file has been registered before
            if (this.registry.containsKey(fileName)) {
                // it exists, acquire its known peer list and add this peer to it
                HashSet<IPv4> filePeerList = this.registry.get(fileName);
                filePeerList.add(peer);
            } else {
                // does not exist, create a new entry with this peer associated to it
                HashSet<IPv4> filePeerList = new HashSet<IPv4>();
                filePeerList.add(peer);
                this.registry.put(fileName, filePeerList);
            }
            this.log(String.format("%sRegistered%s '%s' to (Leaf %s).",
                            ANSI_GREEN, ANSI_RESET, fileName, peer));
            return 0;
        } catch (Exception e) {
            e.printStackTrace();
            return 1;
        }
    }

    /** deregister - accepts a peer and a fileName. updates or removes entry from registry */
    public int deregister(String fileName, IPv4 peer) {
        try {
            // only do work if file is registered
            if (this.registry.containsKey(fileName)) {
                HashSet<IPv4> filePeerList = this.registry.get(fileName);
                filePeerList.remove(peer);
                this.log(String.format("%sDeregistered%s (Leaf %s) from '%s'.",
                            ANSI_RED, ANSI_RESET, peer, fileName));
                if (filePeerList.size() == 0) {
                    // last peer removed from file's peer list, remove file from registry
                    this.registry.remove(fileName);
                    this.log(String.format("\t-> removing '%s' entirely.", fileName));
                }
            }
            return 0;
        } catch (Exception e) {
            e.printStackTrace();
            return 1;
        }
    }

    /**
     *  listen - waits for incoming connections, upon receiving one, spawns a
     *          PeerHandler thread to handle this specific request
     */
    public SuperPeer listen() {
        try {
            while (true) {
                // accept an incoming connection
                Socket peerSocket = this.server.accept();
                PeerHandler ph = new PeerHandler(this, peerSocket);
                ph.start();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return this;
    }

    /** log - helper method for logging messages with a descriptive prefix */
    public SuperPeer log(String message) {
        System.out.println(String.format("[%sSP %s%s]: %s",
                            ANSI_WHITE, this.address, ANSI_RESET, message));
        return this;
    }

    /** getAddress - returns IPv4 address of this SuperPeer */
    public String getAddress() {
        return this.address.getAddress();
    }

    /** getPort - returns server port of this SuperPeer */
    public int getPort() {
        return this.address.getPort();
    }

    /** equals - checks equality between this SuperPeer and another one */
    public boolean equals(IPv4 other) {
        return this.address.equals(other);
    }

    /** isLeaf - checks if a Peer (IPv4:port) is a leaf of this SuperPeer */
    public boolean isLeaf(IPv4 peer) {
        return this.leafs.contains(peer.toString());
    }

    /** isNeighbor - checks if a Peer (IPv4:port) is a SP Neighbor of this SuperPeer */
    public boolean isNeighbor(IPv4 peer) {
        return this.neighbors.contains(peer.toString());
    }

    /** hasFile - checks if a file is registered with this SuperPeer */
    public boolean hasFile(String fileName) {
        return this.registry.containsKey(fileName);
    }

    /** hasSeen - checks if this SuperPeer has seen this message */
    public boolean hasSeen(String messageID) {
        return this.history.contains(messageID);
    }

    /** record - logs message then keeps size in check */
    public SuperPeer record(String messageID, IPv4 peer) {
        this.log(String.format("New Message -> Recording '%s'...", messageID));
        this.history.add(messageID);
        this.mappedHistory.put(messageID, peer);
        if (this.history.size() > this.historySize) {
            // history got too large, remove oldest entry
            String mid = this.history.remove(0);
            this.mappedHistory.remove(mid);
        }
        return this;
    }

    /** toString - serializes the SuperPeer */
    public String toString() {
        return this.address.toString();
    }


    /**
     *  Nested Class:
     *
     *      PeerHandler - this class handles any communication from incoming
     *                  leaf Peer requests. it runs on its own thread,
     *                  receives the request over TCP, parses it, executes it,
     *                  and returns a response (if necessary). the connection
     *                  stays open to the leaf peer.
     */
    private class PeerHandler extends Thread {
        /* metadata */
        private SuperPeer superPeer; // reference to this SuperPeer
        private Socket peerSocket; // used to communicate with requester
        private DataInputStream fromPeer;
        private DataOutputStream toPeer;
        private IPv4 peer;

        /* constructor */
        public PeerHandler(SuperPeer superPeer, Socket socket) {
            this.superPeer = superPeer;
            this.peerSocket = socket;
        }

        public void run() {
            try {
                // open some data streams
                this.fromPeer = new DataInputStream(this.peerSocket.getInputStream());
                this.toPeer = new DataOutputStream(this.peerSocket.getOutputStream());

                // initial handshake
                String received = this.fromPeer.readUTF();
                this.superPeer.log(String.format("Handshake with (%s)", received));
                IPv4 peer = new IPv4(received);

                // check if this is a SuperPeer calling
                if (this.superPeer.isNeighbor(peer)) {
                    // this is a SuperPeer request, so we will handle separately then close the connection once complete.
                    this.handleSuperPeer(peer);
                    return;
                } else if (!this.superPeer.isLeaf(peer)) {
                    // error: some other SuperPeer's leaf node is contacting me.
                    this.superPeer.log("Received request from a foreign leaf peer. Stop.");
                    return;
                }

                this.peer = peer;
                this.superPeer.log(String.format("Connected with (Peer %s)", this.peer));

                // parse a command from peer in the form of "command fileName"
                //      e.g., "query msgid;ttl;filename;ipv4:port"
                while (true) {
                    String[] request = fromPeer.readUTF().split("[ \t]+");
                    String command = request[0];
                    Message message = new Message(request[1]);

                    int rc;
                    switch (command) {
                        case "register":
                            // received a register command from a leaf node
                            rc = this.superPeer.register(message.getFileName(), peer);
                            toPeer.writeInt(rc);
                            break;
                        case "deregister":
                            // receive a deregister command from a leaf node
                            rc = this.superPeer.deregister(message.getFileName(), peer);
                            toPeer.writeInt(rc);
                            break;
                        case "query":
                            // check if this query is directly from our leaf and if we have the file
                            if (this.superPeer.hasFile(message.getFileName())) {
                                try {
                                    // other leaf peers have this file
                                    HashSet<IPv4> leafs = this.superPeer.registry.get(message.getFileName());
                                    Socket reqSock = new Socket(peer.getAddress(), peer.getPort());
                                    DataOutputStream toRequester = new DataOutputStream(reqSock.getOutputStream());
                                    for (IPv4 l : leafs) {
                                        // send queryhit back to leaf requester, one for each leaf
                                        this.superPeer.log(String.format("(%s) has this file.", l));
                                        toRequester.writeUTF(String.format("queryhit %s %s", message, l));
                                    }
                                    reqSock.close();
                                } catch (Exception e) {
                                    this.superPeer.log(String.format("Connection failed with (Leaf %s)", peer));
                                }
                            }

                            // log this message as being received
                            this.superPeer.record(message.getID(), peer);

                            // next, forward message to other SuperPeers
                            if (message.getTTL() > 0) {
                                message.decrementTTL();
                                for (String n : this.superPeer.neighbors) {
                                    try {
                                        this.superPeer.log(String.format("Forwarding 'query %s %s' to (%s)", message, this.superPeer, n));
                                        IPv4 neighbor = new IPv4(n);
                                        // forward the message to each, with the TTL decremented
                                        Socket nSock = new Socket(neighbor.getAddress(), neighbor.getPort());
                                        DataOutputStream toNeighbor = new DataOutputStream(nSock.getOutputStream());
                                        // initial handshake
                                        toNeighbor.writeUTF(this.superPeer.toString());
                                        toNeighbor.writeUTF(
                                            String.format("query %s %s", message, this.superPeer)
                                        );
                                        nSock.close();
                                    } catch (Exception e) {
                                        this.superPeer.log(String.format("Failed to forward message to (%s)", n));
                                        continue;
                                    }
                                }
                            }
                            break;
                        default:
                            this.superPeer.log(String.format("Received unknown command '%s'. Ignoring.", command));
                            break;
                    }
                }
            } catch (EOFException e) {
                this.superPeer.log(String.format("Connection with (%s) closed.", this.peer));
                for (String file : this.superPeer.registry.keySet()) {
                    this.superPeer.deregister(file, this.peer);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        private void handleSuperPeer(IPv4 peer) {
            try {
                String[] request = this.fromPeer.readUTF().split("[ \t]+");
                String command = request[0];
                Message message = new Message(request[1]);
                IPv4 sender = new IPv4(request[2]);

                this.superPeer.log(String.format("Received '%s %s %s' from %s",
                                        command, message, sender, sender));

                switch (command) {
                    case "query":
                        // only do work if this message has not been seen before
                        if (!this.superPeer.hasSeen(message.getID())) {
                            // log it and make sure history size is okay
                            this.superPeer.record(message.getID(), sender);

                            // check if this file is in one of our leaf nodes
                            if (this.superPeer.hasFile(message.getFileName())) {
                                try {
                                    // other leaf peers have this file
                                    HashSet<IPv4> leafs = this.superPeer.registry.get(message.getFileName());
                                    Socket reqSock = new Socket(peer.getAddress(), peer.getPort());
                                    DataOutputStream toRequester = new DataOutputStream(reqSock.getOutputStream());

                                    toRequester.writeUTF(this.superPeer.toString());

                                    for (IPv4 l : leafs) {
                                        // send queryhit back to leaf requester, one for each leaf
                                        this.superPeer.log(String.format("Sending back 'queryhit %s %s' to %s.", message, l, peer));
                                        toRequester.writeUTF(String.format("queryhit %s %s", message, l));
                                    }
                                    reqSock.close();
                                } catch (Exception e) {
                                    this.superPeer.log(String.format("Failed to forward message back to (%s)", peer));
                                }
                            }

                            // next, forward message to other SuperPeers
                            if (message.getTTL() > 0) {
                                message.decrementTTL();
                                for (String n : this.superPeer.neighbors) {
                                    try {
                                        IPv4 neighbor = new IPv4(n);
                                        if (!neighbor.equals(sender)) {
                                            // we can save a communication cost by not forwarding this same
                                            // message back go the SuperPeer who sent it to us
                                            this.superPeer.log(String.format("Forwarding 'query %s %s' to (%s)", message, this.superPeer, n));
                                            // forward the message to each, with the TTL decremented
                                            Socket nSock = new Socket(neighbor.getAddress(), neighbor.getPort());
                                            DataOutputStream toNeighbor = new DataOutputStream(nSock.getOutputStream());
                                            toNeighbor.writeUTF(this.superPeer.toString());
                                            toNeighbor.writeUTF(
                                                String.format("query %s %s", message, this.superPeer)
                                            );
                                            nSock.close();
                                        }
                                    } catch (Exception e) {
                                        this.superPeer.log(String.format("Failed to forward message to (%s)", n));
                                    }
                                }
                            }
                        } else {
                            this.superPeer.log("Messaged already seen -> Dropped.");
                        }
                        break;
                    case "queryhit":
                        try {
                            IPv4 back = this.superPeer.mappedHistory.get(message.getID());
                            this.superPeer.log(String.format("forwarding back to (%s)", back));
                            Socket bSock = new Socket(back.getAddress(), back.getPort());
                            DataOutputStream toNeighbor = new DataOutputStream(bSock.getOutputStream());
                            // handshake
                            toNeighbor.writeUTF(this.superPeer.toString());
                            // send queryhit
                            toNeighbor.writeUTF(
                                String.format("queryhit %s %s", message, sender)
                            );
                            bSock.close();
                        } catch (Exception e) {
                            this.superPeer.log(String.format("Failed to forward message to (%s)", peer));
                        }
                        break;
                    default:
                        this.superPeer.log(String.format("Received unknown command '%s'. Ignoring.", command));
                        break;
                }
            } catch (IOException e) {
                this.superPeer.log("Got an error handling request from SuperPeer");
                e.printStackTrace();
            }
        }
    }
}