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
    private PeerMetadata metadata;
    private ServerSocket server; // for communicating TO this SuperPeer
    private ConcurrentHashMap<String, HashSet<PeerMetadata>> registry;
    private List<String> history;
    private ConcurrentHashMap<String, PeerMetadata> mappedHistory;
    private Set<String> neighbors; // for tracking SuperPeer neighbors
    private Set<String> leafs; // for tracking associated peers
    private File config;
    private int historySize = 50;

    /* constructor */
    public SuperPeer(String address, File config) {
        try {
            this.registry = new ConcurrentHashMap<String, HashSet<PeerMetadata>>();
            this.history = Collections.synchronizedList(new ArrayList<String>());
            this.mappedHistory = new ConcurrentHashMap<String, PeerMetadata>();
            this.leafs = ConcurrentHashMap.newKeySet();
            this.neighbors = ConcurrentHashMap.newKeySet();
            this.metadata = PeerMetadata.parse(address);
            this.config = config;

            // create new socket for connections
            this.server = new ServerSocket(this.metadata.getPort());
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
                PeerMetadata peer = PeerMetadata.parse(line[1]);
                String other = line[2];

                if (this.equals(peer)) {
                    switch (type) {
                        case "s":
                            // SuperPeer definition: 'other' is neighbor to associate with this SuperPeer
                            this.neighbors.add(other);
                            break;
                        case "p":
                            // peer definition: 'other' is peer to associate with this SuperPeer
                            this.leafs.add(other);
                            break;
                        default:
                            this.log(String.format("Unknown type '%s'. Ignoring...", type));
                            break;
                    }
                }
            }
            sc.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return this;
    }

    /** register - accepts a peer and a fileName. creates or updates entry in registry */
    public int register(String fileName, PeerMetadata peer) {
        try {
            // check if this file has been registered before
            if (this.registry.containsKey(fileName)) {
                // it exists, acquire its known peer list and add this peer to it
                HashSet<PeerMetadata> filePeerList = this.registry.get(fileName);
                filePeerList.add(peer);
            } else {
                // does not exist, create a new entry with this peer associated to it
                HashSet<PeerMetadata> filePeerList = new HashSet<PeerMetadata>();
                filePeerList.add(peer);
                this.registry.put(fileName, filePeerList);
            }
            this.log(String.format("Registered '%s' to (Leaf %s).", fileName, peer.getFullAddress()));
            return 0;
        } catch (Exception e) {
            e.printStackTrace();
            return 1;
        }
    }

    /** deregister - accepts a peer and a fileName. updates or removes entry from registry */
    public int deregister(String fileName, PeerMetadata peer) {
        try {
            // only do work if file is registered
            if (this.registry.containsKey(fileName)) {
                HashSet<PeerMetadata> filePeerList = this.registry.get(fileName);
                filePeerList.remove(peer);
                this.log(String.format("Deregistered (Leaf %s) from '%s'.", peer.toString(), fileName));
                if (filePeerList.size() == 0) {
                    // last peer removed from file's peer list, remove file from registry
                    this.registry.remove(fileName);
                    this.log(String.format("Deregistered '%s' entirely from Registry.", fileName));
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
        System.out.println(String.format("[SP %s]: %s", this.metadata.toString(), message));
        return this;
    }

    /** getAddress - returns IPv4 address of this SuperPeer */
    public String getAddress() {
        return this.metadata.getAddress();
    }

    /** getPort - returns server port of this SuperPeer */
    public int getPort() {
        return this.metadata.getPort();
    }

    /** equals - checks equality between this SuperPeer and another one */
    public boolean equals(PeerMetadata other) {
        return this.metadata.equals(other);
    }

    /** isLeaf - checks if a Peer (IPv4:port) is a leaf of this SuperPeer */
    public boolean isLeaf(PeerMetadata peer) {
        return this.leafs.contains(peer.toString());
    }

    /** isNeighbor - checks if a Peer (IPv4:port) is a SP Neighbor of this SuperPeer */
    public boolean isNeighbor(PeerMetadata peer) {
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
    public SuperPeer record(String messageID, PeerMetadata peer) {
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
        return this.metadata.toString();
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
        private PeerMetadata peer;

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
                PeerMetadata peer = PeerMetadata.parse(received);

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
                this.superPeer.log(String.format("Connected with Peer %s", this.peer));

                // parse a command from peer in the form of "command fileName"
                //      e.g., "query msgid;ttl;filename;ipv4:port"
                while (true) {
                    String[] request = fromPeer.readUTF().split("[ \t]+");
                    String command = request[0];
                    Message message = Message.parse(request[1]);

                    if (!command.equals("register") && !command.equals("deregister")) { //DEBUG
                        this.superPeer.log(String.format("Received '%s %s' from (Leaf %s).", command, message.toString(), this.peer.toString()));
                    }

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
                                    HashSet<PeerMetadata> leafs = this.superPeer.registry.get(message.getFileName());
                                    Socket reqSock = new Socket(peer.getAddress(), peer.getPort());
                                    DataOutputStream toRequester = new DataOutputStream(reqSock.getOutputStream());
                                    for (PeerMetadata l : leafs) {
                                        // send queryhit back to leaf requester, one for each leaf
                                        this.superPeer.log(String.format("(%s) has this file.", l.toString()));
                                        toRequester.writeUTF(String.format("queryhit %s %s", message, l));
                                    }
                                    reqSock.close();
                                } catch (Exception e) {
                                    this.superPeer.log(String.format("Connection failed with (Leaf %s)", peer.toString()));
                                }
                            }

                            // log this message as being received
                            this.superPeer.record(message.getID(), peer);

                            // next, forward message to other SuperPeers
                            if (message.getTTL() > 0) {
                                message.decrementTTL();
                                for (String n : this.superPeer.neighbors) {
                                    try {
                                        this.superPeer.log(String.format("Forwarding 'query %s %s' to (%s)", message.toString(), this.superPeer.toString(), n));
                                        PeerMetadata neighbor = PeerMetadata.parse(n);
                                        // forward the message to each, with the TTL decremented
                                        Socket nSock = new Socket(neighbor.getAddress(), neighbor.getPort());
                                        DataOutputStream toNeighbor = new DataOutputStream(nSock.getOutputStream());
                                        // initial handshake
                                        toNeighbor.writeUTF(this.superPeer.toString());
                                        toNeighbor.writeUTF(
                                            String.format("query %s %s", message.toString(), this.superPeer.toString())
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
            } catch (Exception e) {
                this.superPeer.log(String.format("Lost connection with (%s).", this.peer.toString()));
                e.printStackTrace();
            }
        }

        private void handleSuperPeer(PeerMetadata peer) {
            try {
                String[] request = this.fromPeer.readUTF().split("[ \t]+");
                String command = request[0];
                Message message = Message.parse(request[1]);
                PeerMetadata sender = PeerMetadata.parse(request[2]);

                this.superPeer.log(String.format("Received '%s %s %s' from %s",
                    command, message.toString(), sender.toString(), sender.toString()));

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
                                    HashSet<PeerMetadata> leafs = this.superPeer.registry.get(message.getFileName());
                                    Socket reqSock = new Socket(peer.getAddress(), peer.getPort());
                                    DataOutputStream toRequester = new DataOutputStream(reqSock.getOutputStream());

                                    toRequester.writeUTF(this.superPeer.toString());

                                    for (PeerMetadata l : leafs) {
                                        // send queryhit back to leaf requester, one for each leaf
                                        this.superPeer.log(String.format("Sending back 'queryhit %s %s' to %s.", message.toString(), l.toString(), peer.toString()));
                                        toRequester.writeUTF(String.format("queryhit %s %s", message.toString(), l.toString()));
                                    }
                                    reqSock.close();
                                } catch (Exception e) {
                                    this.superPeer.log(String.format("Failed to forward message back to (%s)", peer.toString()));
                                }
                            }

                            // next, forward message to other SuperPeers
                            if (message.getTTL() > 0) {
                                message.decrementTTL();
                                for (String n : this.superPeer.neighbors) {
                                    try {
                                        PeerMetadata neighbor = PeerMetadata.parse(n);
                                        if (!neighbor.equals(sender)) {
                                            // we can save a communication cost by not forwarding this same
                                            // message back go the SuperPeer who sent it to us
                                            this.superPeer.log(String.format("Forwarding 'query %s %s' to (%s)", message.toString(), this.superPeer.toString(), n));
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
                            PeerMetadata back = this.superPeer.mappedHistory.get(message.getID());
                            this.superPeer.log(String.format("forwarding back to (%s)", back.toString()));
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
                            this.superPeer.log(String.format("Failed to forward message to (%s)", peer.toString()));
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