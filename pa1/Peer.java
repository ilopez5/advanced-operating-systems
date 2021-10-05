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
 *  Peer - this class defines each peer which make up a P2P network. Each peer
 *          can act as a server for other peers in addition to acting as a client.
 *          Upon being run, this peer will spawn a PeerListener thread to accept
 *          and handle incoming connections from other peers. Additionally, it
 *          will automatically, and non-recursively, register all files in its
 *          given directory. Lastly, it will present a basic CLI for user interaction.
 *
 *          Accepted syntax:
 *              (p2p) :: command fileName
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
    /* peer metadata */
    private int peerID;
    private File fileDirectory;
    private Socket indexSocket;
    private ServerSocket server;
    private DataInputStream fromIndex;
    private DataOutputStream toIndex;

    /* peer constructor */
    public Peer(int peerID, File fileDirectory, int indexPort) {
        this.peerID = peerID;
        this.fileDirectory = fileDirectory;
        try {
            // opens a ServerSocket for other peers to connect to
            this.server = new ServerSocket(0);
            this.server.setReuseAddress(true);
            System.out.println(String.format("[Peer %d]: Listening at 127.0.0.1:%d", this.peerID, this.server.getLocalPort()));

            // opens data/input streams to the server socket
            this.indexSocket = new Socket("127.0.0.1", indexPort);
            this.fromIndex = new DataInputStream(indexSocket.getInputStream());
            this.toIndex = new DataOutputStream(indexSocket.getOutputStream());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void main(String[] args) {
        // parse program arguments and init an object of this class
        Peer peer = new Peer(
            Integer.parseInt(args[0]),
            new File(args[1]),
            Integer.parseInt(args[2])
        );

        try {
            // register my peer id, my (server) port, and my initial files
            // to Index server.
            peer.register();

            /**
             *  PeerServer section:
             *      spawns a PeerListener thread that listens for any incoming
             *      peer connections. Upon recieving a connection, the PeerListener
             *      spawns a PeerHandler thread to deal with it.
             */
            PeerListener pl = peer.new PeerListener(peer);
            pl.start();

            /**
             *  EventListener section:
             *      spawns an EventListener thread that watches for any changes
             *      to the given directory. If new file, registers it. If a file
             *      is deleted, deregisters it. Nothing if a file is modified (TBD)
             */
            EventListener el = peer.new EventListener(peer);
            el.start();

            /**
             *  User CLI Section:
             *      runs until program is interrupted or 'exit' is entered
             */
            peer.cli();

            // clean up
            peer.indexSocket.close();
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
                            this.toIndex.writeUTF(line);
                            rc = this.fromIndex.readInt();
                            end = Instant.now();
                            timeElapsed = Duration.between(start, end);
                            System.out.println(String.format("[Peer %d]: '%s' took %s", this.peerID, command, this.pretty(timeElapsed)));
                            if (rc > 0) {
                                System.out.println(String.format("[Peer %d]: Received error code %d", this.peerID, rc));
                            }
                            break;
                        case "search":
                            // if this peer already contains file, save a communication
                            // call by doing nothing.
                            if (new File(String.format("%s/%s", this.fileDirectory, fileName)).exists()) {
                                System.out.println(String.format("[Peer %d]: '%s' is already here. Ignoring.", this.peerID, fileName));
                                continue;
                            }

                            // send command to Index, receive response which should
                            // be a serialized list of PeerMetadata objects.
                            start = Instant.now();
                            this.toIndex.writeUTF(line);
                            String response = this.fromIndex.readUTF();
                            end = Instant.now();
                            timeElapsed = Duration.between(start, end);
                            System.out.println(String.format("[Peer %d]: Search complete. (took %s)", this.peerID, this.pretty(timeElapsed)));

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
                                        Socket targetSocket = new Socket("127.0.0.1", target.getServerPort());
                                        DataInputStream targetIn = new DataInputStream(targetSocket.getInputStream());
                                        DataOutputStream targetOut = new DataOutputStream(targetSocket.getOutputStream());

                                        // send the request to target for the given file
                                        start = Instant.now();
                                        targetOut.writeUTF(String.format("retrieve %s", fileName));

                                        // for writing to our own file directory
                                        DataOutputStream fileOut = new DataOutputStream(
                                            new FileOutputStream(
                                                String.format("%s/%s", this.fileDirectory.getPath(), fileName)
                                            )
                                        );

                                        // code for sending/receiving bytes over socket from adapted from:
                                        //      https://stackoverflow.com/questions/9520911/java-sending-and-receiving-file-byte-over-sockets
                                        System.out.println(String.format("[Peer %d]: Downloading '%s' from Peer %d...", this.peerID, fileName, target.getID()));
                                        int count;
                                        byte[] buffer = new byte[8192];
                                        while ((count = targetIn.read(buffer)) > 0) {
                                            fileOut.write(buffer, 0, count-1);
                                        }
                                        end = Instant.now();
                                        timeElapsed = Duration.between(start, end);
                                        System.out.println(String.format("[Peer %d]: Download complete. (took %s)", this.peerID, this.pretty(timeElapsed)));

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
                            System.out.println(String.format("[Peer %d]: Unknown command '%s'. Ignoring.", this.peerID, command));
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
     *  register - performs an initial handshake with the Index server, giving
     *              it this peer's ID and port. Then, it registers all files in
     *              the given directory.
     */
    private void register() {
        try {
            // 1. initial handshake
            this.toIndex.writeInt(this.peerID);
            this.toIndex.writeInt(this.server.getLocalPort());

            // 2. registers all files in given directory
            int rc;
            File[] files = this.fileDirectory.listFiles();
            for (File file : files) {
                if (file.isFile()) {
                    // register file with Index server
                    System.out.println(String.format("[Peer %d]: Registering file '%s'", this.peerID, file.getName()));
                    this.toIndex.writeUTF(String.format("register %s", file.getName()));
                    rc = this.fromIndex.readInt();
                    if (rc > 0) {
                        System.out.println(String.format("[Peer %d]: Got error code %d", rc));
                    }
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
                    String.format("%s/%s", this.fileDirectory.getPath(), fileName)
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
     *  Nested Classes:
     *
     *      PeerListener - This listens on a ServerSocket port and accepts requests
     *                  from peers. On receiving an incoming request, spins up
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
    private class PeerListener extends Thread {
        /* reference to the peer this listener works for */
        private Peer peer;

        /* constructor */
        public PeerListener(Peer peer) {
            this.peer = peer;
        }

        public void run() {
            try {
                while (true) {
                    // accept an incoming connection from other peers
                    Socket peerSocket = this.peer.server.accept();
                    PeerHandler ph = new PeerHandler(peerSocket, this.peer);
                    ph.start();
                }
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
            }
        }
    }

    private class PeerHandler extends Thread {
        /* reference to peer, and to the socket of the connecting peer */
        private Socket peerSocket;
        private Peer peer;

        /* constructor */
        public PeerHandler(Socket socket, Peer peer) {
            this.peerSocket = socket;
            this.peer = peer;
        }

        public void run() {
            try {
                // open input/output streams for communication
                DataInputStream dataIn = new DataInputStream(this.peerSocket.getInputStream());
                DataOutputStream dataOut = new DataOutputStream(this.peerSocket.getOutputStream());

                // receive peer request and parse
                String[] request = dataIn.readUTF().split("[ \t]+");
                String command = request[0];
                String fileName = request[1];

                // handle request (only 'retrieve' for now)
                switch (command) {
                    case "retrieve":
                        this.peer.retrieve(fileName, dataOut);
                        break;
                    default:
                        System.out.println(String.format("[Peer %d]: Received unknown command '%s'. Ignoring.", peer.peerID, command));
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
            this.peer = peer;
            try {
                // get peer directory as a Path object.
                Path dir = Paths.get(peer.fileDirectory.toString());
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
                            this.peer.toIndex.writeUTF(String.format("deregister %s", event.context().toString()));
                            this.peer.fromIndex.readInt();
                        } else if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE) {
                            // a file was created so we register it
                            this.peer.toIndex.writeUTF(String.format("register %s", event.context().toString()));
                            this.peer.fromIndex.readInt();
                        } else {
                            // a file was just modified -> NOT YET IMPLEMENTED
                        }
                    }
                    watchKey.reset();
                } catch (SocketException e) {
                    System.out.println("[EventListener]: Socket closed. Watch thread exiting.");
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
    }
}
