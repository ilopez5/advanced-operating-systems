import java.util.*;
import java.net.*;
import java.io.*;

public class Peer {
    /* peer metadata */
    private int peerID;
    private ServerSocket server;
    private File fileDirectory;
    private int indexPort;
    private DataInputStream dataIn;
    private DataOutputStream dataOut;

    /* peer constructor */
    public Peer(int peerID, File fileDirectory, int indexPort) {
        this.peerID = peerID;
        this.fileDirectory = fileDirectory;
        this.indexPort = indexPort;
        try {
            this.server = new ServerSocket(0);
            this.server.setReuseAddress(true);
            System.out.println(String.format("[Peer %d]: Listening at 127.0.0.1:%d", this.peerID, this.server.getLocalPort()));
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
            // PeerListener is a secretary for handling any requests made by other peers
            PeerListener pl = peer.new PeerListener(peer);
            pl.start();

            Socket indexSocket = new Socket("127.0.0.1", peer.indexPort);
            // dataIn is for messages received FROM THE INDEX
            // dataOut is for requests we send TO THE INDEX
            peer.dataIn = new DataInputStream(indexSocket.getInputStream());
            peer.dataOut = new DataOutputStream(indexSocket.getOutputStream());

            // egister my peerID, (server) port, and files to Index server
            peer.register();

            // User CLI - runs until program is interrupted or 'exit' is entered
            Scanner sc = new Scanner(System.in);
            String line = null;

            while (!"exit".equalsIgnoreCase(line)) {
                // print a prompt to the user
                System.out.print("(p2p) :: ");
                // read input from user
                line = sc.nextLine();
                String[] userInput = line.split("[ \t]+");
                if (userInput.length == 2) {
                    // parse user input
                    String command = userInput[0];
                    String fileName = userInput[1];

                    // only send information when 2 args passed matching format
                    //     "command fileName", and with 'command' being an accepted command.
                    switch (command) {
                        case "register":
                        case "search":
                        case "deregister":
                            // good command/syntax, send to server.
                            peer.dataOut.writeUTF(line);
                            break;
                        default:
                            System.out.println(String.format("[Peer %d]: Received unknown command '%s'. Ignoring.", peer.peerID, command));
                            continue;
                    }

                    // capture response
                    String response = peer.dataIn.readUTF();

                    int rc;
                    switch (command) {
                        case "register":
                        case "deregister":
                            // for now, we just check response code and print if
                            // there is an error. future work might have more
                            // attempts being made.
                            rc = peer.dataIn.readInt();
                            if (rc > 0) {
                                System.out.println(String.format("[Peer %d]: Received error code %d", peer.peerID, rc));
                            }
                            break;
                        case "search":
                            String[] peerList = response.substring(1, response.length()-1).split(", ");
                            for (String p : peerList) {
                                // 'target' refers to the peer we are currently trying to communicate with
                                PeerMetadata target = PeerMetadata.parseString(p);
                                Socket targetSocket = new Socket("127.0.0.1", target.getServerPort());
                                DataInputStream targetIn = new DataInputStream(targetSocket.getInputStream());
                                DataOutputStream targetOut = new DataOutputStream(targetSocket.getOutputStream());

                                // make contact
                                targetOut.writeUTF(String.format("retrieve %s", fileName));
                                // TODO: what is response format?
                            }

                            break;
                        default:
                            break;
                    }
                }
            }

            // closing the scanner object
            sc.close();
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
        }
    }

    /**
     *  register - performs an initial handshake with the Index server, giving
     *              it this peer's ID and port. Then, it registers all files in
     *              the given directory.
     */
    public void register() {
        try {
            // 1. initial handshake
            this.dataOut.writeInt(this.peerID);
            this.dataOut.writeInt(this.server.getLocalPort());

            // 2. registers all files in given directory
            File[] files = this.fileDirectory.listFiles();
            for (File file : files) {
                if (file.isFile()) {
                    // register file with Index server
                    System.out.println(String.format("[Peer %d]: Registering file '%s'", this.peerID, file.getName()));
                    this.dataOut.writeUTF(String.format("register %s", file.getName()));
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     *  retrieve - Given a 'fileName', will return/download that file to caller
     *              using a Stream Buffer.
     */
    public void retrieve (String fileName) {

        System.out.println(String.format("[Peer %d]: Received request for file %s", this.peerID, fileName));
    }

    /**
     *  Nested Classes:
     *
     *      PeerListener - This listens on a ServerSocket port and accepts requests
     *                  from peers. On receiving an incoming request, spins up
     *                  a PeerHandler thread to parse and execute these requests.
     *
     *      PeerHandler - This handles the requests for each peer that communicates
     *                  with it. In practice, there is one PeerHandler for every
     *                  peer that communicates with this peer server. This does
     *                  not scale but works for the purposes of this assignment.
     *                  Future work for sure.
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
                while (true) {
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
                            this.peer.retrieve(fileName);
                            break;
                        default:
                            System.out.println(String.format("[Peer %d]: Received unknown command '%s'. Ignoring.", peer.peerID, command));
                            // dataOut.writeUTF("error: unknown command");
                            break;
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
            }
        }
    }
}



