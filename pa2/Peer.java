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
 *              (p2p) :: <command> <fileName>
 *
 *          e.g.,
 *              (p2p) :: search Inception.txt
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
    private Set<String> fileRegistry;

    // ServerPeer-related metadata
    private PeerMetadata superPeer;
    private Socket superPeerSocket;
    private DataInputStream fromSuperPeer;
    private DataOutputStream toSuperPeer;

    /* peer constructor */
    public Peer(String address, File fileDirectory, File config) {
        try {
            this.fileRegistry = ConcurrentHashMap.newKeySet();
            this.metadata = PeerMetadata.parseString(address);
            this.fileDir = fileDirectory;
            this.config = config;

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
            Peer peer = new Peer(args[0], new File(args[1]), new File(args[2]));

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
            // peer.cli();

            // clean up
            peer.superPeerSocket.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     *  cli - provides the command line interface (CLI) for user (interactive)
     *
     *
     */
    private void cli() {
        try {
            int rc;
            String line = null;
            Instant start, end;
            Duration timeElapsed;
            Scanner sc = new Scanner(System.in);

            while (!"exit".equalsIgnoreCase(line)) {
                // print a prompt to the user, then read input
                System.out.print("(p2p) :: ");
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
                        case "deregister":
                            // send command to Index
                            start = Instant.now();
                            this.toSuperPeer.writeUTF(line);
                            rc = this.fromSuperPeer.readInt();
                            end = Instant.now();
                            timeElapsed = Duration.between(start, end);
                            System.out.println(String.format("[Peer ]: '%s' took %s", command, this.pretty(timeElapsed)));
                            if (rc > 0) {
                                System.out.println(String.format("Received error code %d", rc));
                            }
                            break;
                        case "search":
                            // if this peer already contains file, save a communication
                            // call by doing nothing.
                            if (new File(String.format("%s/%s", this.fileDir, fileName)).exists()) {
                                System.out.println(String.format("'%s' is already here. Ignoring.", fileName));
                                continue;
                            }

                            // send command to Index, receive response which should
                            // be a serialized list of PeerMetadata objects.
                            start = Instant.now();
                            this.toSuperPeer.writeUTF(line);
                            String response = this.fromSuperPeer.readUTF();
                            end = Instant.now();
                            timeElapsed = Duration.between(start, end);
                            System.out.println(String.format("Search complete. (took %s)", this.pretty(timeElapsed)));

                            // clean up the serialized string into a String array
                            if (response.length() > 2) {
                                String[] peerList = response.substring(1, response.length()-1).split(", ");

                                // iterate through each peer and try to download file
                                // from them. if successful, we are finished.
                                for (String p : peerList) {
                                    try {
                                        // 'target' refers to the peer we are currently trying to communicate with.
                                        // we first deserialize that peer and open a socket to it.
                                        PeerMetadata target = PeerMetadata.parseString(p);
                                        Socket targetSocket = new Socket("127.0.0.1", target.getPort());
                                        DataInputStream targetIn = new DataInputStream(targetSocket.getInputStream());
                                        DataOutputStream targetOut = new DataOutputStream(targetSocket.getOutputStream());

                                        // send the request to target for the given file
                                        start = Instant.now();
                                        targetOut.writeUTF(String.format("retrieve %s", fileName));

                                        // for writing to our own file directory
                                        DataOutputStream fileOut = new DataOutputStream(
                                            new FileOutputStream(
                                                String.format("%s/%s", this.fileDir.getPath(), fileName)
                                            )
                                        );

                                        // code for sending/receiving bytes over socket from adapted from:
                                        //      https://stackoverflow.com/questions/9520911/java-sending-and-receiving-file-byte-over-sockets
                                        System.out.println(String.format("Downloading '%s' from Peer %d...", fileName, target));
                                        int count;
                                        byte[] buffer = new byte[8192];
                                        while ((count = targetIn.read(buffer)) > 0) {
                                            fileOut.write(buffer, 0, count-1);
                                        }
                                        end = Instant.now();
                                        timeElapsed = Duration.between(start, end);
                                        System.out.println(String.format("Download complete. (took %s)", this.pretty(timeElapsed)));

                                        // close sockets
                                        fileOut.close();
                                        targetSocket.close();
                                        break;
                                    } catch (Exception e) {
                                        e.printStackTrace();
                                    }
                                }
                            }
                            break;
                        default:
                            System.out.println(String.format("Unknown command '%s'. Ignoring.", command));
                            continue;
                    }
                }
            }

            // clean up
            sc.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     *  initialize - figures out the SuperPeer address:port and registers all
     *              files into an in-memory thread-safe hash set.
     */
    private void initialize() {
        try {
            // determine who my SuperPeer is by reading config file
            FileInputStream fis = new FileInputStream(this.config);
            Scanner sc = new Scanner(fis);
            while (sc.hasNextLine()) {
                // parse and decompose line
                String[] line = sc.nextLine().split(" ");
                String type = line[0];
                if (type.equals("p")) {
                    // only care about p lines
                    PeerMetadata superPeer = PeerMetadata.parseString(line[1]);
                    PeerMetadata peer = PeerMetadata.parseString(line[2]);

                    if (this.equals(peer)) {
                        // this is us!
                        this.superPeer = superPeer;
                        this.superPeerSocket = new Socket(
                            this.superPeer.getAddress(),
                            this.superPeer.getPort()
                        );
                        this.fromSuperPeer = new DataInputStream(this.superPeerSocket.getInputStream());
                        this.toSuperPeer = new DataOutputStream(this.superPeerSocket.getOutputStream());
                        break;
                    }
                }
            }
            sc.close();

            // register files in directory to our in-memory registry
            for (File file : this.fileDir.listFiles()) {
                if (file.isFile()) {
                    // register file with Index server
                    this.log(String.format("Registering file '%s'", file.getName()));
                    this.fileRegistry.add(file.getName());
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     *  retrieve - Given a 'fileName' and a DataOutputStream, will stream that
     *              file to caller.
     */
    public void retrieve (String fileName, DataOutputStream out) {
        try {
            DataInputStream in = new DataInputStream(
                new FileInputStream(
                    String.format("%s/%s", this.fileDir.getPath(), fileName)
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
    }

    /**
     *  pretty - helper function to print the elapsed time with appropriate units.
     */
    public String pretty(Duration elapsed) {
        if (elapsed.toMillis() < 1) {
            return String.format("%d ns", elapsed.toNanos());
        } else if (elapsed.toSeconds() < 1) {
            return String.format("%d ms", elapsed.toMillis());
        } else {
            return String.format("%d s", elapsed.toSeconds());
        }
    }

    /**
     *  log - helper method for logging messages with a descriptive prefix.
     */
    private void log(String message) {
        System.out.println(String.format("[P %s]: %s", this.metadata.toString(), message));
    }

    public boolean equals(PeerMetadata other) {
        return this.metadata.equals(other);
    }

    /**
     *  Nested Classes:
     *
     *      PeerListener - this listens on the ServerSocket port and accepts requests
     *                  from peers. on receiving an incoming request, spins up
     *                  a PeerHandler thread to parse and execute that request.
     *
     *      PeerHandler - This handles the requests for each peer that communicates
     *                  with it. Upon completion, the socket connection is closed.
     *
     *      EventListener - This watches the peer's file directory for any events
     *                  where a file is created, deleted, or modified. Upon a file
     *                  being created, it is registered with the Index. Upon a file
     *                  being deleted, it is deregistered from the Index. Modifications
     *                  are not within the scope of this assignment but it would
     *                  involve 'search'-ing the Index for the Peer list, then
     *                  updating them to the change, and possibly having each
     *                  one of them call 'retrieve' on this peer to get the most
     *                  up to date changes. This is an expensive operation.
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
                // String command = request[0];
                // String fileName = request[1];

                // // handle request (only 'retrieve' for now)
                // switch (command) {
                //     case "retrieve":
                //         this.peer.retrieve(fileName, toPeer);
                //         break;
                //     default:
                //         System.out.println(String.format("[Peer %d]: Received unknown command '%s'. Ignoring.", peer.peerID, command));
                //         break;
                // }
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
                            this.peer.fileRegistry.remove(event.context().toString());
                        } else if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE) {
                            // a file was deleted so we deregister
                            this.peer.fileRegistry.add(event.context().toString());
                        } else {
                            // a file was just modified -> NOT IMPLEMENTED
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
