import java.util.concurrent.*;
import java.util.*;
import java.net.*;
import java.io.*;

/**
 *  SuperPeer - this class defines SuperPeers, which are proxies to the entire
 *              network. Leaf peers, or just Peers, send queries to their respective
 *              SuperPeer, and expect to get back k query-hit messages, indicating
 *              which peers contain that message.
 *
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
    private Set<PeerMetadata> neighbors; // for tracking SuperPeer neighbors
    private Set<PeerMetadata> peers; // for tracking associated peers
    private File config;

    /* constructor */
    public SuperPeer(String address, File config) {
        try {
            this.peers = ConcurrentHashMap.newKeySet();
            this.neighbors = ConcurrentHashMap.newKeySet();
            this.metadata = PeerMetadata.parseString(address);
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

        // read config file to initialize
        superPeer.initialize();

        superPeer.log(String.format("Listening on %s:%d...",
                    superPeer.getAddress(), superPeer.getPort()));

        // begin listening for incoming requests
        superPeer.listen();
    }

    /**
     *  initialize - reads a config file following a specific format, and stores
     *              any information relevant to THIS SuperPeer.
     *
     *      Config Syntax: <type> <address:port> <address:port>
     *          - 'type' -> 's' or 'p' for SuperPeer and Peer, respectively
     *              - for type 's': the second peer is a SuperPeer neighbor
     *              - for type 'p': the second peer is a Peer for this SuperPeer
     */
    private void initialize() {
        try {
            // read config file (e.g., all-to-all.config, linear.config)
            FileInputStream fis = new FileInputStream(this.config);
            Scanner sc = new Scanner(fis);

            // process each line
            while (sc.hasNextLine()) {
                // parse and decompose line
                String[] line = sc.nextLine().split(" ");
                String type = line[0];
                PeerMetadata peer = PeerMetadata.parseString(line[1]);
                PeerMetadata other = PeerMetadata.parseString(line[2]);

                // handle accordingly
                switch (type) {
                    case "s":
                        // SuperPeer definition
                        if (this.equals(peer)) {
                            // 'other' is neighbor to associate with this SuperPeer
                            this.neighbors.add(other);
                        }
                        break;
                    case "p":
                        // peer definition
                        if (this.equals(peer)) {
                            // 'other' is peer to associate with this SuperPeer
                            this.peers.add(other);
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
    }

    /**
     *  listen - waits for incoming connections. upon receiving one, it spawns a
     *          PeerHandler thread to handle this specific request from a Peer
     *          or SuperPeer.
     */
    public void listen() {
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
    }

    /**
     *  log - helper method for logging messages with a descriptive prefix.
     */
    private void log(String message) {
        System.out.println(String.format("[SP %s]: %s", this.metadata.toString(), message));
    }

    /**
     *  getAddress - we store the IPv4 address in a PeerMetadata object. this
     *              method wraps the getAddress() method of that object. Forgive
     *              the added obfuscation for better readability.
     */
    public String getAddress() {
        return this.metadata.getAddress();
    }

    /**
     *  getPort - we store the exposed port in a PeerMetadata object. this
     *          method wraps the getPort() method of that object. Forgive
     *          the added obfuscation for better readability.
     */
    public int getPort() {
        return this.metadata.getPort();
    }

    /**
     *  equals - checks equality between this SuperPeer and another one by
     *          comparing their metadata (currently: address and port).
     */
    public boolean equals(PeerMetadata other) {
        return this.metadata.equals(other);
    }

    /**
     *  Nested Class(es):
     *
     *      PeerHandler - this class handles any communication from incoming
     *                  Peer or SuperPeer requests. it runs on its own thread,
     *                  receives the request over TCP, parses it, executes it,
     *                  and returns a response (if necessary).
     */
    private class PeerHandler extends Thread {
        /* references to this peer and the socket for the incoming peer */
        private SuperPeer superPeer;
        private Socket peerSocket; // used to communicate with requestor

        /* constructor */
        public PeerHandler(SuperPeer superPeer, Socket socket) {
            this.superPeer = superPeer;
            this.peerSocket = socket;
        }

        public void run() {
            try {
                // 'dataIn' is for requests received FROM THE PEER
                // 'dataOut' is for responses we send TO THE PEER
                DataInputStream fromPeer = new DataInputStream(this.peerSocket.getInputStream());
                DataOutputStream toPeer = new DataOutputStream(this.peerSocket.getOutputStream());

                // receive address:port for Peer's server socket
                PeerMetadata peer = PeerMetadata.parseString(fromPeer.readUTF());

                // parse a command from peer
                while (true) {
                    // receive a command in the form of "command fileName"
                    //      e.g., "search Moana.txt"
                    String[] request = fromPeer.readUTF().split("[ \t]+");
                    if (request.length > 0) {
    //                     String command = request[0];
    //                     String fileName = request[1];

    //                     // handle command appropriately
    //                     int rc;
    //                     switch (command) {
    //                         case "register":
    //                             rc = this.index.register(fileName, this.peer);
    //                             toPeer.writeInt(rc);
    //                             break;
    //                         case "search":
    //                             String response = this.index.search(fileName).toString();
    //                             toPeer.writeUTF(response);
    //                             break;
    //                         case "deregister":
    //                             rc = this.index.deregister(fileName, this.peer);
    //                             toPeer.writeInt(rc);
    //                             break;
    //                         default:
    //                             System.out.println(String.format("[Index]: Received unknown command '%s'. Ignoring.", command));
    //                             break;
    //                     }
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }
}