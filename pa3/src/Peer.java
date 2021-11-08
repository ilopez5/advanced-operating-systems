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
 *              [5] https://stackoverflow.com/questions/9520911/java-sending-and-receiving-file-byte-over-sockets
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
    private ConsistencyChecker controller;

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

            if (!this.peerDir.exists()) { this.peerDir.mkdir(); }
            if (!this.ownedDir.exists()) { this.ownedDir.mkdir(); }
            if (!this.downloadsDir.exists()) { this.downloadsDir.mkdir(); }

            this.parseConfig();

            // opens a ServerSocket for other peers to connect to
            this.server = new ServerSocket(this.address.getPort());
            this.server.setReuseAddress(true);

            /** spawns a PeerListener thread that listens for any incoming peer connections */
            this.peerListener = new PeerListener(this);
            this.peerListener.start();

            /** spawns an EventListener thread that watches for any filesystem changes */
            this.eventListener = new EventListener(this);
            this.eventListener.start();

            if (this.model == ConsistencyModel.PULL) {
                /** spawns a ConsistencyChecker to ensure coherence for files */
                this.controller = new ConsistencyChecker(this);
                this.controller.start();
            }

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
                    case "print":
                        this.info();
                        break;
                    case "register":
                        this.register(new FileInfo(fileName, this.address));
                        break;
                    case "deregister":
                        this.deregister(fileName);
                        break;
                    case "search":
                        this.query(fileName);
                        break;
                    case "refresh":
                        this.refresh(fileName);
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
            if (this.equals(fi.getOwner()) && this.model == ConsistencyModel.PUSH) {
                // we are the owner and model is PUSH means we must broadcast an invalidate
                this.invalidate(fileName);
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
            if (new File(this.ownedDir, fileName).exists()) {
                this.log(String.format("'%s' is already here. Ignoring.", fileName));
                return this;
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

    /** invalidate - broadcasts an invalidate message when an owned file is modified/deleted */
    public Peer invalidate(String fileName) {
        try {
            Message invalidate = new Message(
                this.generateMessageID(),
                this.ttl,
                new FileInfo(fileName, this.address),
                this.address
            );
            this.toSuperPeer.writeUTF(String.format("invalidate %s", invalidate));
        } catch (Exception e) {
            e.printStackTrace();
        }
        return this;
    }

    /** info - prints out peer information */
    public Peer info() {
        this.log(
                "Printing Metadata..." +
                "\n            IPv4: " + this +
                "\n  Root Directory: " + this.peerDir +
                "\n     Owned Files: /owned" +
                "\nDownloaded Files: /downloads" +
                "\n          Config: " + this.config +
                "\n             TTL: " + this.ttl +
                "\n             TTR: " + this.ttr +
                "\nMsg Sequence No.: " + this.sequence +
                "\n   File Registry: " + this.fileRegistry
        );
        return this;
    }

    /** refresh - file is no longer registered, need to redownload. alias of query */
    public Peer refresh(String fileName) {
        // we only care about PULL model for this command
        if (this.model == ConsistencyModel.PUSH) {
            this.log("Cant refresh in a PUSH topology");
            return this;
        }
        return this.query(fileName);
    }

    /** download - given a message containing the Peer that has file, download it */
    public Peer download(Message message, IPv4 target) {
        try {
            // open sockets to leaf peer
            Socket targetSocket = new Socket(target.getAddress().toString(), target.getPort());
            DataInputStream targetIn = new DataInputStream(targetSocket.getInputStream());
            DataOutputStream targetOut = new DataOutputStream(targetSocket.getOutputStream());

            // handshake
            targetOut.writeUTF(this.toString());

            // send the request to target for the given file
            targetOut.writeUTF(String.format("obtain %s", message));

            // for writing to our own file directory
            DataOutputStream fileOut = new DataOutputStream(
                new FileOutputStream(new File(this.downloadsDir, message.getFileName()))
            );

            // if (this.model == ConsistencyModel.PULL) {
                // need get origin server and other related info (e.g., ttr)
                FileInfo fi = new FileInfo(targetIn.readUTF());
                this.fileRegistry.put(fi.getName(), fi);
            // }

            this.log(String.format("Downloading '%s' from (%s)...", message.getFileName(), target));

            Instant start = Instant.now();
            int count;
            byte[] buffer = new byte[8192];
            while ((count = targetIn.read(buffer)) > 0) {
                fileOut.write(buffer, 0, count-1);
            }
            Duration duration = Duration.between(start, Instant.now());

            this.log(String.format("Download complete. (took %s)", this.elapsed(duration)));
            // mark this message id as having been successfully downloaded
            this.messageLog.put(message.getID(), true);

            // close sockets
            fileOut.close();
            targetSocket.close();
        } catch (IOException e) {
            this.log("Exception occured.");
            this.messageLog.put(message.getID(), false);
            e.printStackTrace();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return this;
    }

    /** upload - given a message and an output stream, writes to it */
    public Peer upload(Message msg, DataOutputStream toPeer) {
        try {
            this.log(String.format("Uploading '%s'", msg.getFileName()));
            DataInputStream dataIn = new DataInputStream(
                new FileInputStream(this.findFile(msg.getFileName()))
            );

            // if (this.model == ConsistencyModel.PULL) {
                // need to send file info
                FileInfo fi = this.fileRegistry.get(msg.getFileName());
                toPeer.writeUTF(fi.toString());
            // }

            // download file in chunks
            int count;
            byte[] buffer = new byte[8192]; // or 4096, or more
            while ((count = dataIn.read(buffer)) > 0) {
                toPeer.write(buffer, 0, count);
            }
            dataIn.close();
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
            if (this.model == ConsistencyModel.PULL) {
                this.controller.interrupt();
            }
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
    private String generateMessageID() { return String.format("%s-%d", this, ++this.sequence); }

    /** findFile - searchs for file in owned/ and downloads/ directories */
    public File findFile(String fileName) {
        File owned = new File(this.ownedDir, fileName);
        if (owned.isFile()) { return owned; }
        return new File(this.downloadsDir, fileName);
    }

    /** equals - compares this Peer's IPv4 with another's */
    public boolean equals(IPv4 other) { return this.address.equals(other); }

    /** hasDownloaded - checks if this Peer has already downloaded file related to this message */
    public boolean hasDownloaded(String messageID) { return this.messageLog.getOrDefault(messageID, false); }

    /** isOwner - checks if this peer is the owner (origin server) of file */
    public boolean isOwner(String fileName) {
        return this.equals(this.fileRegistry.get(fileName).getOwner());
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
     *
     *      ConsistencyChecker - pull model only. periodically checks files that
     *                  are not owned by this peer, given enough time has passed
     *                  (using the ttr). acts accordingly.
     */
    private class PeerListener extends Thread {
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
        /* reference to this peer */
        private Peer peer;
        /* socket of the connecting peer */
        private Socket peerSocket;
        DataInputStream fromPeer;
        DataOutputStream toPeer;

        /* constructor */
        public PeerHandler(Peer peer, Socket socket) {
            try {
                this.peer = peer;
                this.peerSocket = socket;
                // open input/output streams for communication
                this.fromPeer = new DataInputStream(this.peerSocket.getInputStream());
                this.toPeer = new DataOutputStream(this.peerSocket.getOutputStream());
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        public void run() {
            try {
                // receive handshake
                String caller = fromPeer.readUTF();
                this.peer.log(String.format("Connected with (%s)", caller));


                // receive peer request and parse
                String[] request = fromPeer.readUTF().split("[ \t]+");
                String command = request[0];
                String args = request[1];

                // handle request
                FileInfo received, master;
                Message msg;
                switch (command) {
                    case "invalidate":
                        // our SuperPeer is telling us to invalidate this file
                        this.peer.log(String.format("received '%s %s'", command, args));
                        msg = new Message(args);
                        this.peer.fileRegistry.remove(msg.getFileName());
                        new File(this.peer.downloadsDir, msg.getFileName()).delete();
                        break;
                    case "queryhit":
                        // SuperPeer just forwarded a queryhit message to you
                        this.peer.log(String.format("received '%s %s %s'", command, args, request[2]));
                        msg = new Message(args);

                        // while being thread-safe, attempt to download file
                        this.peer.downloadLock.lock();
                        if (!this.peer.hasDownloaded(msg.getID())) {
                            // we have not downloaded the file yet
                            this.peer.download(msg, new IPv4(request[2]));
                            this.peer.register(new FileInfo(msg.getFileName(), new IPv4(request[2])));
                        }
                        this.peer.downloadLock.unlock();
                        break;
                    case "obtain":
                        // another peer wants this file
                        this.peer.log(String.format("received '%s %s'", command, args));
                        msg = new Message(args);
                        this.peer.upload(msg, this.toPeer);
                        break;
                    case "status":
                        received = new FileInfo(args);
                        if (!this.peer.fileRegistry.containsKey(received.getName())) {
                            toPeer.writeUTF("deleted");
                            break;
                        }
                        master = this.peer.fileRegistry.get(received.getName());
                        if (received.getVersion() != master.getVersion()) {
                            toPeer.writeUTF("outdated");
                        } else {
                            toPeer.writeUTF("uptodate");
                        }
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

    private class ConsistencyChecker extends Thread {
        /* reference to this peer */
        private Peer peer;
        private HashMap<String, Instant> history;

        public ConsistencyChecker(Peer peer) {
            this.peer = peer;
            this.history = new HashMap<String, Instant>();
        }

        public void run() {
            try {
                while (true) {
                    // periodically check every 30 seconds
                    sleep(30000);

                    for (FileInfo fi : this.peer.fileRegistry.values()) {
                        if (!this.peer.isOwner(fi.getName()) && this.hasExpiredTTR(fi.getName())) {
                            // we are not owner, file is considered valid, and ttr is expired => must verify
                            IPv4 origin = fi.getOwner();
                            Socket originSocket = new Socket(origin.getAddress().toString(), origin.getPort());
                            DataInputStream originIn = new DataInputStream(originSocket.getInputStream());
                            DataOutputStream originOut = new DataOutputStream(originSocket.getOutputStream());

                            // handshake
                            originOut.writeUTF(this.toString());

                            // request status of this fileinfo
                            originOut.writeUTF(String.format("status %s", fi));

                            String response = originIn.readUTF();
                            this.peer.log(String.format("{VC} -> status of '%s': %s", fi.getName(), response));
                            switch (response) {
                                case "deleted":
                                    // origin server deleted the file, so we do too
                                    this.peer.log("Origin server has deleted this file. Deregistering.");
                                    this.peer.deregister(fi.getName());
                                    this.history.remove(fi.getName());
                                    new File(this.peer.downloadsDir, fi.getName()).delete();
                                    break;
                                case "outdated":
                                    // set to invalid but do not delete file
                                    this.peer.log(String.format("'%s' is out of date. Deregistering. ('refresh %s' to redownload)", fi.getName(), fi.getName()));
                                    this.peer.deregister(fi.getName());
                                    this.history.remove(fi.getName());
                                    break;
                                case "uptodate":
                                    // all good, just update the time we have seen this
                                    this.history.put(fi.getName(), Instant.now());
                                    break;
                                default:
                                    this.peer.log(String.format("Unknown response '%s'. Ignoring.", response));
                            }
                            originSocket.close();
                        }
                    }
                }
            } catch (Exception e) {
                this.peer.log("Closing ConsistencyChecker...");
            }
        }

        /** hasExpiredTTR - determines whether a file has an expired TTR or not */
        public boolean hasExpiredTTR(String fileName) {
            Instant lastChecked = this.getLastChecked(fileName);
            // null means first time, so we check anyways
            if (lastChecked != null) {
                Duration duration = Duration.between(lastChecked, Instant.now());
                if (duration.toMinutes() < this.peer.ttr) {
                    // been longer than ttr, time to refresh
                    return false;
                }
            }
            return true;
        }

        /** getLastChecked - determines last time a file was checked */
        public Instant getLastChecked(String fileName) {
            if (this.history.containsKey(fileName)) {
                return this.history.get(fileName);
            } else {
                return null;
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
                FileInfo fi;
                String fileName;
                while (true) {
                    // accept/take a new event
                    WatchKey watchKey = observer.take();
                    List<WatchEvent<?>> events = watchKey.pollEvents();

                    // iterate through the events
                    for (WatchEvent<?> event : events) {
                        // grab file name
                        fileName = event.context().toString();

                        if (event.kind() == StandardWatchEventKinds.ENTRY_DELETE) {
                            // file has been deleted, so we deregister
                            this.peer.deregister(fileName);
                            if (this.peer.model == ConsistencyModel.PUSH) {
                                this.peer.invalidate(fileName);
                            }
                        } else if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE) {
                            // file has been created, this peer is the owner
                            fi = new FileInfo(
                                fileName,
                                new IPv4(this.peer.toString())
                            );
                            this.peer.register(fi);
                        } else {
                            // file was modified, increment version
                            fi = this.peer.fileRegistry.get(fileName);
                            fi.incrementVersion();
                            this.peer.fileRegistry.put(fileName, fi);

                            if (this.peer.model == ConsistencyModel.PUSH) {
                                // need to invalidate globally
                                this.peer.invalidate(fileName);
                            }
                        }
                    }
                    watchKey.reset();
                }
            } catch (Exception e) {
                this.peer.log("Closing Watchdog...");
            }
        }
    }
}

enum ConsistencyModel {
    PUSH,
    PULL
}