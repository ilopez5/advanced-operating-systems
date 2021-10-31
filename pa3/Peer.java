import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.*;
import java.net.*;
import java.io.*;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchService;
import java.nio.file.WatchKey;
import java.nio.file.WatchEvent;
import java.time.Duration;
import java.time.Instant;

enum ConsistencyModel {
    PUSH,
    PULL
}

/**
 *  Peer -  this class defines a leaf peer in the P2P network. each peer can act
 *          as a server for other peers in addition to acting as a client for
 *          which users can interact with.
 *
 *          upon being run, this peer will spawn a PeerListener thread to provide
 *          the server functionality of recieving and handling incoming request.
 *
 *          additionally, it will automatically register all files in its
 *          given directory.
 *
 *          lastly, it will present a basic CLI for user interaction.
 *
 *          Accepted syntax:
 *              (peer-cli) => <command> <fileName>
 *
 *          e.g.,
 *              (peer-cli) => search Inception.mp4
 *
 *          Citations:
 *              [1] https://www.geeksforgeeks.org/socket-programming-in-java/
 *              [2] https://www.geeksforgeeks.org/multithreaded-servers-in-java/
 *              [3] https://stackoverflow.com/questions/3154488/how-do-i-iterate-through-the-files-in-a-directory-in-java
 *              [4] https://www.rgagnon.com/javadetails/java-detect-file-modification-event.html
 */
public class Peer {
    /* Peer metadata */
    private IPv4 address; // address:port
    private File fileDir; // directory containing peer's files
    private File config; // file containing static topology
    private ServerSocket server; // socket for other's to communicate with
    private int sequence; // keeps a local sequence number for message ids
    private int ttl; // time-to-live default for all messages
    private ConcurrentHashMap<String, Boolean> messageLog; // stores seen messages
    private final ReentrantLock downloadLock = new ReentrantLock();
    private ConsistencyModel model;

    // ServerPeer-related metadata
    private IPv4 superPeer; // address:port
    private Socket spSocket; // socket for communicating with SuperPeer
    private DataInputStream fromSuperPeer;
    private DataOutputStream toSuperPeer;

    // other
    private EventListener eventListener;
    private PeerListener peerListener;

    /* peer constructor */
    public Peer(String address, File fileDir, File config, int ttl) {
        try {
            this.messageLog = new ConcurrentHashMap<String, Boolean>();
            this.sequence = 0;
            this.address = new IPv4(address);
            this.fileDir = fileDir;
            this.config = config;
            this.ttl = ttl;

            this.parseConfig();

            // opens a ServerSocket for other peers to connect to
            this.server = new ServerSocket(this.address.getPort());
            this.server.setReuseAddress(true);

            /**
             *  PeerServer section:
             *      spawns a PeerListener thread that listens for any incoming
             *      peer connections. Upon recieving a connection, the PeerListener
             *      spawns a PeerHandler thread to deal with it.
             */
            this.peerListener = new PeerListener(this);
            this.peerListener.start();

            /**
             *  EventListener section:
             *      spawns an EventListener thread that watches for any changes
             *      to the given directory. if new file, registers it. if a file
             *      is deleted, deregisters it. if modified, update network
             */
            this.eventListener = new EventListener(this);
            this.eventListener.start();

            // look into file directory and store records of all my files
            this.registerDirectory();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     *  program usage:
     *          $ java Peer <address:port> <file-dir> <topology-config>
     *    e.g., $ java Peer 127.0.0.1:6000 files/peer6001 linear.config
     */
    public static void main(String[] args) {
        try {
            // parse program arguments and init an object of this class
            Peer peer = new Peer(
                args[0],
                new File(args[1]),
                new File(args[2]),
                10
            );

            // cli runs until program is interrupted or 'exit' is entered
            peer.cli().close();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /** cli - provides the command line interface for interactive user input */
    public Peer cli() {
        try {
            int rc;
            String line = null;
            Scanner sc = new Scanner(System.in);

            while (!"exit".equalsIgnoreCase(line)) {
                // print a prompt to the user, then read input
                System.out.print("(peer-cli) => ");
                line = sc.nextLine();

                // user input must have format: "command fileName"
                String[] userInput = line.split("[ \t]+");
                if (userInput.length == 2) {
                    // parse user input
                    String command = userInput[0];
                    String fileName = userInput[1];

                    // check that 'command' is supported.
                    switch (command) {
                        case "register":
                            // the "Message" for this command does not require an ID or TTL
                            this.toSuperPeer.writeUTF(String.format("register 0;0;%s;%s", fileName, this));
                            rc = this.fromSuperPeer.readInt();
                            if (rc > 0) { this.log(String.format("Registration failed. Recieved error code %d", rc)); }
                            break;
                        case "deregister":
                            // the "Message" for this command does not require an ID or TTL
                            this.toSuperPeer.writeUTF(String.format("deregister 0;0;%s;%s", fileName, this));
                            rc = this.fromSuperPeer.readInt();
                            if (rc > 0) { this.log(String.format("Deregistration failed. Recieved error code %d", rc)); }
                            break;
                        case "search":
                            // if peer already contains file, save a communication call by ignoring request.
                            if (new File(String.format("%s/%s", this.fileDir, fileName)).exists()) {
                                this.log(String.format("'%s' is already here. Ignoring.", fileName));
                            } else {
                                // increment sequence (post-increment)
                                this.query(this.sequence++, this.ttl, fileName);
                            }
                            break;
                        default:
                            this.log(String.format("Unknown command '%s'. Ignoring.", command));
                            break;
                    }
                }
            }
            sc.close();
            this.log("Quitting...");
        } catch (Exception e) {
            e.printStackTrace();
        }
        return this;
    }

    /** registerDirectory - goes through given directory and registers all the files within */
    public Peer registerDirectory() {
        try {
            // initial handshake
            this.toSuperPeer.writeUTF(this.toString());

            // register files in directory to our in-memory registry
            int rc;
            for (File file : this.fileDir.listFiles()) {
                if (file.isFile()) {
                    // register with SuperPeer, which acts as a PA1 Index
                    this.toSuperPeer.writeUTF(String.format("register 0;0;%s;%s", file.getName(), this));
                    rc = this.fromSuperPeer.readInt();
                    if (rc > 0) { this.log(String.format("Registration failed. Recieved error code %d", rc)); }
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return this;
    }

    /** download - given a message containing the Peer that has file, download it */
    public Peer download(Message message, IPv4 target) {
        try {
            Instant start, end;
            Duration duration;

            // open sockets to leaf peer
            Socket targetSocket = new Socket(target.getAddress(), target.getPort());
            DataInputStream targetIn = new DataInputStream(targetSocket.getInputStream());
            DataOutputStream targetOut = new DataOutputStream(targetSocket.getOutputStream());

            // handshake
            targetOut.writeUTF(this.toString());

            // send the request to target for the given file
            start = Instant.now();
            targetOut.writeUTF(String.format("obtain %s", message));

            // for writing to our own file directory
            DataOutputStream fileOut = new DataOutputStream(
                new FileOutputStream(
                    String.format("%s/%s", this.fileDir.getPath(), message.getFileName())
                )
            );

            // code for sending/receiving bytes over socket from adapted from: https://stackoverflow.com/questions/9520911/java-sending-and-receiving-file-byte-over-sockets
            this.log(String.format("Downloading '%s' from (%s)...", message.getFileName(), target));
            int count;
            byte[] buffer = new byte[8192];
            while ((count = targetIn.read(buffer)) > 0) {
                fileOut.write(buffer, 0, count-1);
            }

            end = Instant.now();
            duration = Duration.between(start, end);
            this.log(String.format("Download complete. (took %s)", this.elapsed(duration)));
            this.messageLog.put(message.getID(), true);

            // close sockets
            fileOut.close();
            targetSocket.close();
        } catch (Exception e) {
            this.log("Caught an exception, ruh roh");
            this.messageLog.put(message.toString(), false);
            e.printStackTrace();
        }
        return this;
    }

    /** upload - given a message and an output stream, writes to it */
    public Peer upload(String message, DataOutputStream out) {
        try {
            Message msg = new Message(message);
            DataInputStream in = new DataInputStream(
                new FileInputStream(
                    String.format("%s/%s", this.fileDir.getPath(), msg.getFileName())
                )
            );

            int count;
            byte[] buffer = new byte[8192]; // or 4096, or more
            while ((count = in.read(buffer)) > 0) {
                out.write(buffer, 0, count);
            }
            in.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return this;
    }

    /** query - creates a Message, sends to SuperPeer */
    public Peer query(int sequenceID, int ttl, String fileName) {
        try {
            String messageID = String.format("%s-%d", this, sequenceID);
            Message query = new Message(messageID, ttl, fileName);
            this.toSuperPeer.writeUTF(String.format("query %s", query));
        }
        catch (Exception e) {
            e.printStackTrace();
        }
        return this;
    }

    /** close - closes any sockets before Peer is terminated*/
    public Peer close() {
        try {
            this.spSocket.close();
            this.server.close();
            this.eventListener.observer.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return this;
    }

    /** log - helper method for logging messages with a descriptive prefix. */
    public Peer log(String message) {
        System.out.println(String.format("[P %s]: %s", this, message));
        return this;
    }

    /** parseConfig - determines SuperPeer and Consistency Model from config */
    public Peer parseConfig() {
        try {
            // determine who my SuperPeer is by reading config file
            Scanner sc = new Scanner(new FileInputStream(this.config));
            while (sc.hasNextLine()) {
                // parse and decompose line
                String[] line = sc.nextLine().split(" ");
                String type = line[0];
                switch (type) {
                    case "c":
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
                    case "p":
                        // only care about p lines
                        IPv4 superPeer = new IPv4(line[1]);
                        IPv4 peer = new IPv4(line[2]);

                        if (this.equals(peer)) {
                            // this is us!
                            this.superPeer = superPeer;
                            this.spSocket = new Socket(
                                this.superPeer.getAddress(),
                                this.superPeer.getPort()
                            );
                            this.fromSuperPeer = new DataInputStream(this.spSocket.getInputStream());
                            this.toSuperPeer = new DataOutputStream(this.spSocket.getOutputStream());
                            break;
                        }
                        break;
                    case "s":
                        // only applies to SuperPeer so we do nothing
                        break;
                    default:
                        this.log(String.format("Unknown type '%s'. Ignoring...", type));
                        this.model = ConsistencyModel.PUSH;
                        break;
                }
            }
            sc.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return this;
    }

    /** elapsed - print the elapsed time with appropriate units */
    public String elapsed(Duration elapsed) {
        if (elapsed.toMillis() < 1) {
            return String.format("%d ns", elapsed.toNanos());
        } else if (elapsed.toSeconds() < 1) {
            return String.format("%d ms", elapsed.toMillis());
        } else {
            return String.format("%d s", elapsed.toSeconds());
        }
    }

    /** toString - serializes the Peer */
    public String toString() {
        return this.address.toString();
    }

    /** equals - compares this Peer's PeerMetadata with another's */
    public boolean equals(IPv4 other) {
        return this.address.equals(other);
    }

    /** hasDownloaded - checks if this Peer has already downloaded file related to this message */
    public boolean hasDownloaded(String messageID) {
        return this.messageLog.getOrDefault(messageID, false);
    }



    /**
     *  Nested Classes:
     *
     *      PeerListener - this listens on the ServerSocket port and accepts requests
     *                  from peers. on receiving an incoming request, spins up
     *                  a PeerHandler thread to parse and execute that request.
     *
     *      PeerHandler - this handles the requests for each peer that communicates
     *                  with it. Upon completion, the socket connection is closed.
     *
     *      EventListener - this watches the peer's file directory for any events
     *                  where a file is created or deleted.
     *                      Creation -> registered with SuperPeer
     *                      Deleted  -> deregistered from the SuperPeer.
     */
    public class PeerListener extends Thread {
        /* reference to the Peer or SuperPeer this listener works for */
        private Peer peer;

        /* constructor(s) */
        public PeerListener(Peer peer) {
            this.peer = peer;
        }

        public void run() {
            try {
                peer.log(String.format("Listening on %s...", peer));
                while (true) {
                    // accept an incoming connection
                    Socket ps = this.peer.server.accept();
                    PeerHandler ph = new PeerHandler(this.peer, ps);
                    ph.start();
                }
            } catch (Exception e) {
                this.peer.log("Closing PeerListener.");
            }
        }
    }

    private class PeerHandler extends Thread {
        /* reference to peer, and to the socket of the connecting peer */
        private Peer peer;
        private Socket peerSocket;

        /* constructor */
        public PeerHandler(Peer peer, Socket socket) {
            this.peer = peer;
            this.peerSocket = socket;
        }

        public void run() {
            try {
                // open input/output streams for communication
                DataInputStream fromPeer = new DataInputStream(this.peerSocket.getInputStream());
                DataOutputStream toPeer = new DataOutputStream(this.peerSocket.getOutputStream());

                // handshake
                String peer = fromPeer.readUTF();

                // receive peer request and parse
                String[] request = fromPeer.readUTF().split("[ \t]+");
                String command = request[0];
                String args = request[1];

                // handle request
                switch (command) {
                    case "queryhit":
                        // SuperPeer just forwarded a query hit message to you
                        //      syntax: 'queryhit <msgid;ttl;fileName;ip:port> <ip:port>'
                        Message msg = new Message(args);
                        this.peer.log(String.format("received 'queryhit %s %s' from %s", args, request[2], peer));
                        this.peer.downloadLock.lock();
                        if (!this.peer.hasDownloaded(msg.getID())) {
                            // we have not downloaded the file yet
                            this.peer.download(msg, new IPv4(request[2]));
                        }
                        this.peer.downloadLock.unlock();
                        break;
                    case "obtain":
                        // another peer wants this file
                        //  syntax: 'obtain fileName'
                        this.peer.upload(args, toPeer);
                        break;
                    default:
                        this.peer.log(String.format("Received unknown command '%s'. Ignoring.", command));
                        break;
                }
                this.peerSocket.close();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private class EventListener extends Thread {
        /* reference to the peer this listener works for */
        private Peer peer;
        private WatchService observer;

        /* constructor */
        public EventListener(Peer peer) {
            try {
                this.peer = peer;
                // get peer directory as a Path object.
                Path dir = Paths.get(peer.fileDir.toString());
                // initialize a new watch service for this given directory.
                this.observer = dir.getFileSystem().newWatchService();
                // register the types of events we care about with this watch service.
                dir.register(
                    this.observer,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_DELETE,
                    StandardWatchEventKinds.ENTRY_MODIFY
                );
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        public void run() {
            try {
                while (true) {
                    // accept/take a new event
                    WatchKey watchKey = observer.take();
                    List<WatchEvent<?>> events = watchKey.pollEvents();

                    // iterate through the events
                    for (WatchEvent<?> event : events) {
                        if (event.kind() == StandardWatchEventKinds.ENTRY_DELETE) {
                            // a file was deleted so we deregister
                            this.peer.toSuperPeer.writeUTF(String.format("deregister 0;0;%s;%s", event.context().toString(), this.peer));
                            this.peer.fromSuperPeer.readInt();
                        } else if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE) {
                            // a file was deleted so we deregister
                            this.peer.toSuperPeer.writeUTF(String.format("register 0;0;%s;%s", event.context().toString(), this.peer));
                            this.peer.fromSuperPeer.readInt();
                        } else {
                            // file was modified
                            if (this.peer.model == ConsistencyModel.PUSH) {
                                // only broadcast invalidate message when the model is push.
                                // TODO
                            }
                        }
                    }
                    watchKey.reset();
                }
            } catch (Exception e) {
                this.peer.log("Closing Watchdog.");
            }
        }
    }
}
