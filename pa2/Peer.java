import java.util.concurrent.*;
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
 *              (p2p-cli) => <command> <fileName>
 *
 *          e.g.,
 *              (p2p-cli) => search Inception.mp4
 *
 *          Citations:
 *              [1] https://www.geeksforgeeks.org/socket-programming-in-java/
 *              [2] https://www.geeksforgeeks.org/multithreaded-servers-in-java/
 *              [3] https://stackoverflow.com/questions/3154488/how-do-i-iterate-through-the-files-in-a-directory-in-java
 *              [4] https://www.rgagnon.com/javadetails/java-detect-file-modification-event.html
 */
public class Peer {
    /* Peer metadata */
    private PeerMetadata metadata;
    private File fileDir;
    private File config;
    private ServerSocket server;
    private Set<String> registry;
    private int sequence;
    private int ttl;
    private ConcurrentHashMap<String, Boolean> messageLog;

    // ServerPeer-related metadata
    private PeerMetadata superPeer;
    private Socket spSocket;
    private DataInputStream fromSuperPeer;
    private DataOutputStream toSuperPeer;

    /* peer constructor */
    public Peer(String address, File fileDirectory, File config, int ttl) {
        try {
            this.registry = ConcurrentHashMap.newKeySet();
            this.messageLog = new ConcurrentHashMap<String, Boolean>();
            this.metadata = PeerMetadata.parse(address);
            this.fileDir = fileDirectory;
            this.config = config;
            this.sequence = 0;
            this.ttl = ttl;

            // opens a ServerSocket for other peers to connect to
            this.server = new ServerSocket(this.metadata.getPort());
            this.server.setReuseAddress(true);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     *  program usage:
     *          $ java Peer <address:port> <file-dir> <topology-config>
     *    e.g., $ java Peer 127.0.0.1:6000 files/peer1/ linear.config
     */
    public static void main(String[] args) {
        try {
            // parse program arguments and init an object of this class
            Peer peer = new Peer(args[0], new File(args[1]), new File(args[2]), 10);

            // look into file directory and store records of all my files
            peer.initialize();

            /**
             *  EventListener section:
             *      spawns an EventListener thread that watches for any changes
             *      to the given directory. if new file, registers it. if a file
             *      is deleted, deregisters it. if modified, do nothing.
             */
            EventListener el = peer.new EventListener(peer);
            el.start();

            /**
             *  PeerServer section:
             *      spawns a PeerListener thread that listens for any incoming
             *      peer connections. Upon recieving a connection, the PeerListener
             *      spawns a PeerHandler thread to deal with it.
             */
            PeerListener pl = peer.new PeerListener(peer);
            pl.start();

            /**
             *  User CLI Section:
             *      runs until program is interrupted or 'exit' is entered
             */
            peer.cli();

            // clean up
            peer.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /** cli - provides the command line interface for interactive user input */
    public void cli() {
        try {
            String line = null;
            Scanner sc = new Scanner(System.in);

            while (!"exit".equalsIgnoreCase(line)) {
                // print a prompt to the user, then read input
                System.out.print("(p2p-cli) => ");
                line = sc.nextLine();

                // user input must have format: "command fileName"
                String[] userInput = line.split("[ \t]+");
                if (userInput.length == 2) {
                    // parse user input
                    String command = userInput[0];
                    String fileName = userInput[1];

                    // check that 'command' is supported.
                    switch (command) {
                        case "search":
                            // if this peer already contains file, save a communication call by doing nothing.
                            if (new File(String.format("%s/%s", this.fileDir, fileName)).exists()) {
                                this.log(String.format("'%s' is already here. Ignoring.", fileName));
                                continue;
                            }
                            this.query(this.sequence++, this.ttl, fileName);
                            break;
                        default:
                            this.log(String.format("Unknown command '%s'. Ignoring.", command));
                            break;
                    }
                }
            }

            // clean up
            sc.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /** initialize - finds SuperPeer, then registers all files */
    public void initialize() {
        this.getSuperPeer();
        this.register();
    }

    /** register - goes through given directory and registers all the files within */
    public Peer register() {
        try {
            // register files in directory to our in-memory registry
            int rc;
            for (File file : this.fileDir.listFiles()) {
                if (file.isFile()) {
                    this.log(String.format("Registering file '%s'", file.getName()));
                    // add file to Peer in-memory registry
                    this.registry.add(file.getName());
                    // register with SuperPeer, which acts as a PA1 Index.
                    this.toSuperPeer.writeUTF(
                        String.format("register %s %s", file.getName(), this.toString())
                    );
                    rc = this.fromSuperPeer.readInt();
                    if (rc > 0) {
                        this.log(String.format("Registration failed. Recieved error code %d", rc));
                    }
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return this;
    }

    /** download - given a message containing the Peer that has file, download it */
    private Peer download(Message message) {
        try {
            Instant start, end;
            Duration timeElapsed;

            // open sockets to leaf peer
            Socket targetSocket = new Socket(message.getAddress(), message.getPort());
            DataInputStream targetIn = new DataInputStream(targetSocket.getInputStream());
            DataOutputStream targetOut = new DataOutputStream(targetSocket.getOutputStream());

            // send the request to target for the given file
            start = Instant.now();
            targetOut.writeUTF(String.format("obtain %s", message.toString()));

            // for writing to our own file directory
            DataOutputStream fileOut = new DataOutputStream(
                new FileOutputStream(
                    String.format("%s/%s", this.fileDir.getPath(), message.getFileName())
                )
            );

            // code for sending/receiving bytes over socket from adapted from: https://stackoverflow.com/questions/9520911/java-sending-and-receiving-file-byte-over-sockets
            this.log(String.format("Downloading '%s' from Peer %d...", message.getFileName(), message.getFullAddress()));
            int count;
            byte[] buffer = new byte[8192];
            while ((count = targetIn.read(buffer)) > 0) {
                fileOut.write(buffer, 0, count-1);
            }
            this.messageLog.put(message.toString(), true);
            end = Instant.now();
            timeElapsed = Duration.between(start, end);
            this.log(String.format("Download complete. (took %s)", this.pretty(timeElapsed)));

            // close sockets
            fileOut.close();
            targetSocket.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return this;
    }

    /** upload - given a message and an output stream, writes to it */
    private Peer upload(String message, DataOutputStream out) {
        try {
            Message msg = Message.parse(message);
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
            String messageID = String.format("%s-%d", this.getFullAddress(), sequenceID);
            Message query = new Message(messageID, ttl, fileName);
            // send query to SuperPeer
            this.toSuperPeer.writeUTF(query.toString());
        } catch (IOException e) {
            e.printStackTrace();
        }
        return this;
    }


    /**
     * HELPER METHODS
     */


    /** close - closes any sockets before Peer is terminated*/
    public Peer close() {
        try {
            this.spSocket.close();
            this.server.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return this;
    }

    /** getSuperPeer - either returns an already determined SuperPeer, or finds it in config */
    public Peer getSuperPeer() {
        try {
            if (this.superPeer == null) {
                // determine who my SuperPeer is by reading config file
                Scanner sc = new Scanner(new FileInputStream(this.config));
                while (sc.hasNextLine()) {
                    // parse and decompose line
                    String[] line = sc.nextLine().split(" ");
                    String type = line[0];
                    if (type.equals("p")) {
                        // only care about p lines
                        PeerMetadata superPeer = PeerMetadata.parse(line[1]);
                        PeerMetadata peer = PeerMetadata.parse(line[2]);

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
                    }
                }
                sc.close();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return this;
    }

    /** log - helper method for logging messages with a descriptive prefix. */
    public Peer log(String message) {
        System.out.println(String.format("[P %s]: %s", this.getFullAddress(), message));
        return this;
    }

    /** equals - compares this Peer's PeerMetadata with another's */
    public boolean equals(PeerMetadata other) {
        return this.metadata.equals(other);
    }

    /** getFullAddress - returns full IPv4 address and port number */
    public String getFullAddress() {
        return this.metadata.getFullAddress();
    }

    /** pretty - print the elapsed time with appropriate units */
    public String pretty(Duration elapsed) {
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
        return this.metadata.toString();
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
                while (true) {
                    // accept an incoming connection
                    peer.log(String.format("Listening on %s...", peer.metadata.getFullAddress()));
                    Socket ps = this.peer.server.accept();
                    PeerHandler ph = new PeerHandler(this.peer, ps);
                    ph.start();
                }
            } catch (Exception e) {
                e.printStackTrace();
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

                // receive peer request and parse
                String[] request = fromPeer.readUTF().split("[ \t]+");
                String command = request[0];
                String args = request[1];

                // handle request
                switch (command) {
                    case "queryhit":
                        // SuperPeer just forwarded a query hit message to you
                        //  syntax: 'queryhit msgid;ttl;fileName;ip:port'
                        Message msg = Message.parse(args);
                        if (!peer.messageLog.get(msg.getMessageID())) {
                            // we have not downloaded the file yet
                            this.peer.download(msg);
                        }
                        break;
                    case "obtain":
                        // another peer wants this file
                        //  syntax: 'obtain fileName'
                        this.peer.upload(args, toPeer);
                        break;
                    default:
                        peer.log(String.format("Received unknown command '%s'. Ignoring.", command));
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
            while (true) {
                try {
                    // accept/take a new event
                    WatchKey watchKey = observer.take();
                    List<WatchEvent<?>> events = watchKey.pollEvents();

                    // iterate through the events
                    for (WatchEvent<?> event : events) {
                        if (event.kind() == StandardWatchEventKinds.ENTRY_DELETE) {
                            // a file was deleted so we deregister
                            this.peer.registry.remove(event.context().toString());
                            this.peer.toSuperPeer.writeUTF(String.format("deregister %s %s", event.context().toString(), this.peer));
                            this.peer.fromSuperPeer.readInt();
                        } else if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE) {
                            // a file was deleted so we deregister
                            this.peer.registry.add(event.context().toString());
                            this.peer.toSuperPeer.writeUTF(String.format("register %s %s", event.context().toString(), this.peer));
                            this.peer.fromSuperPeer.readInt();
                        }
                    }
                    watchKey.reset();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
    }
}
