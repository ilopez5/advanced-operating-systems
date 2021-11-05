import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.*;
import java.nio.file.*;
import java.util.*;
import java.time.*;
import java.net.*;
import java.io.*;

/**
 *  Peer -  this class defines a leaf peer in the P2P network. each peer can act
 *          as a server for other peers in addition to acting as a client for
 *          which users can interact with.
 *
 *          upon being run, this peer will:
 *              1. spawn a PeerListener thread to provide server functionality
 *              2. automatically register all files in its given directory.
 *              3. present a basic CLI for user interaction.
 *
 *          Accepted syntax:
 *              (peer-cli) => <command> <fileName>
 *        e.g., (peer-cli) => search Inception.mp4
 *
 *          Citations:
 *              [1] https://www.geeksforgeeks.org/socket-programming-in-java/
 *              [2] https://www.geeksforgeeks.org/multithreaded-servers-in-java/
 *              [3] https://stackoverflow.com/questions/3154488/how-do-i-iterate-through-the-files-in-a-directory-in-java
 *              [4] https://www.rgagnon.com/javadetails/java-detect-file-modification-event.html
 */
public class Peer {
    /* identification */
    private IPv4 address; // address:port

    /* filesystem-related */
    private File peerDir; // peer's root directory (INFO: might be redundant)
    private File ownedDir; // fileDir/owned directory containing owned files
    private File downloadsDir; // fileDir/downloads directory containing downloaded files
    private File config; // file containing static topology

    /* server-related */
    private ServerSocket server; // socket for other's to communicate with

    /* message-related */
    private int sequence; // keeps a local sequence number for message ids
    private int ttl; // time-to-live default for all messages
    private ConcurrentHashMap<String, Boolean> messageLog; // stores seen messages
    private final ReentrantLock downloadLock = new ReentrantLock();

    /* consistency-related */
    private ConsistencyModel model; // stores a 'push' or 'pull' consistency model
    private int ttr; // ('pull' model only) stores time-to-refresh
    private ConcurrentHashMap<String, FileInfo> fileRegistry;

    /* superpeer-related */
    private IPv4 superPeer; // address:port
    private Socket spSocket; // socket for communicating with SuperPeer
    private DataInputStream fromSuperPeer;
    private DataOutputStream toSuperPeer;

    /* nested class references */
    private EventListener eventListener;
    private PeerListener peerListener;

    /* peer constructor */
    public Peer(String address, File fileDir, File config, int ttl) {

        try {
            this.address = new IPv4(address);
            this.peerDir = fileDir;
            this.ownedDir = new File(this.peerDir, "owned");
            this.downloadsDir = new File(this.peerDir, "downloads");
            this.config = config;
            this.sequence = 0;
            this.ttl = ttl;
            this.messageLog = new ConcurrentHashMap<String, Boolean>();
            this.fileRegistry = new ConcurrentHashMap<String, FileInfo>();

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
     *    e.g., $ java Peer 127.0.0.1:6001 files/peer6001 linear.simple.config
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
            FileInfo fi;
            String line = null;
            Scanner sc = new Scanner(System.in);

            while (!"exit".equalsIgnoreCase(line)) {
                // print a prompt to the user, then read input
                System.out.print("(peer-cli) => ");
                line = sc.nextLine();

                // user input must have format: "command fileName"
                String[] userInput = line.split("[ \t]+");
                if (userInput.length != 2) { continue; }

                // parse user input
                String command = userInput[0];
                String fileName = userInput[1];

                // check that 'command' is supported.
                switch (command) {
                    case "register":
                        this.register(new FileInfo(fileName, this.address));
                        break;
                    case "deregister":
                        this.deregister(fileName);
                        break;
                    case "search":
                        // if peer already contains file, save a communication call by ignoring request.
                        if (new File(String.format("%s/%s", this.peerDir, fileName)).exists()) {
                            this.log(String.format("'%s' is already here. Ignoring.", fileName));
                        }
                        this.query(fileName);
                        break;
                    default:
                        this.log(String.format("Unknown command '%s'. Ignoring.", command));
                        break;
                }
            }
            sc.close();
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            this.log("Quitting...");
        }
        return this;
    }

    /** parseConfig - determines SuperPeer and Consistency Model from config */
    private Peer parseConfig() {
        try {
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
                                this.ttr = Integer.parseInt(line[2]);
                                break;
                            case "push":
                                this.model = ConsistencyModel.PUSH;
                                break;
                            default:
                                this.log(String.format("Unknown model '%s'. Defaulting to 'push'...", line[1]));
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
                        break;
                }
            }
            sc.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return this;
    }

    /** registerDirectory - goes through given directory and registers all the files within */
    private Peer registerDirectory() {
        try {
            this.handshake();

            // register files in owned directory to SuperPeer
            for (File file : this.ownedDir.listFiles()) {
                if (file.isFile()) {
                    this.register(new FileInfo(file.getName(), this.address));
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return this;
    }

    /** handshake - performs the required initial handshake with SuperPeer */
    public Peer handshake() {
        try {
            // initial handshake
            this.toSuperPeer.writeUTF(this.toString());
        } catch (Exception e) {
            e.printStackTrace();
        }
        return this;
    }

    /** register - registers an owned file with SuperPeer */
    public Peer register(FileInfo file) {
        try {
            Message msg = new Message(file, this.address);
            // add to Peer registry
            this.fileRegistry.putIfAbsent(file.getName(), file);
            // send to SuperPeer
            this.toSuperPeer.writeUTF(String.format("register %s", msg));
            int rc = this.fromSuperPeer.readInt();
            if (rc > 0) { this.log(String.format("Registration failed. Recieved error code %d", rc)); }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return this;
    }

    /** deregister - deregisters a file from SuperPeer and does more depending on consistency model */
    public Peer deregister(String fileName) {
        try {
            // only care about files we have
            if (!this.fileRegistry.containsKey(fileName)) {
                return this;
            }

            // remove from Peer registry
            FileInfo fi = this.fileRegistry.remove(fileName);

            // assemble new message and deregister from SuperPeer
            Message msg = new Message(fi, this.address);
            this.toSuperPeer.writeUTF(String.format("deregister %s", msg));
            int rc = this.fromSuperPeer.readInt();
            if (rc > 0) { this.log(String.format("Deregistration failed. Recieved error code %d", rc)); }
            if (this.isOwner(fi) && this.model == ConsistencyModel.PUSH) {
                // we are the owner and model is PUSH means we must broadcast!
                // TODO: broadcast
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return this;
    }

    /** query - creates a Message, sends to SuperPeer */
    public Peer query(String fileName) {
        try {
            // only do work if we do not have this file
            if (new File(this.ownedDir, fileName).exists() || new File(this.downloadsDir, fileName).exists()) {
                this.log(String.format("'%s' is already here. Ignoring.", fileName));
            }

            // assemble a query Message
            // note: 'owner' here means nothing since by definition we are 'querying' for this file
            Message query = new Message(
                this.generateMessageID(),
                this.ttl,
                new FileInfo(fileName, this.address),
                this.address
            );
            this.toSuperPeer.writeUTF(String.format("query %s", query));
        } catch (Exception e) {
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
                    String.format("%s/%s", this.downloadsDir.getPath(), message.getFileName())
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
                    String.format("%s/%s", this.peerDir.getPath(), msg.getFileName())
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
    public String toString() { return this.address.toString(); }

    /** generateMessageID - creates Message ID using socket address and sequence number */
    private String generateMessageID() {
        return String.format("%s-%d", this, ++this.sequence);
    }

    /** equals - compares this Peer's PeerMetadata with another's */
    public boolean equals(IPv4 other) { return this.address.equals(other); }

    /** hasDownloaded - checks if this Peer has already downloaded file related to this message */
    public boolean hasDownloaded(String messageID) {
        return this.messageLog.getOrDefault(messageID, false);
    }

    /** isOwner - checks if this peer is the owner (origin server) of file */
    public boolean isOwner(FileInfo f) { return this.address.equals(f.getOwner()); }


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
        /* reference to the Peer this listener works for */
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
                this.peer.log("Closing PeerListener...");
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
                Path dir = Paths.get(peer.ownedDir.toString());
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
                IPv4 p;
                FileInfo fi;
                while (true) {
                    // accept/take a new event
                    WatchKey watchKey = observer.take();
                    List<WatchEvent<?>> events = watchKey.pollEvents();

                    // iterate through the events
                    for (WatchEvent<?> event : events) {
                        if (event.kind() == StandardWatchEventKinds.ENTRY_DELETE) {
                            this.peer.deregister(event.context().toString());
                        } else if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE) {
                            p = new IPv4(this.peer.toString());
                            fi = new FileInfo(event.context().toString(), p);
                            this.peer.register(fi);
                        } else {
                            // file was modified
                            if (this.peer.model == ConsistencyModel.PUSH) {
                                // broadcast invalidate message since model is PUSH
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

enum ConsistencyModel {
    PUSH,
    PULL
}