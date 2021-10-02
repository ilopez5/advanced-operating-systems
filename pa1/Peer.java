import java.util.*;
import java.net.*;
import java.io.*;

public class Peer {
    /* peer metadata */
    private int peerID;
    private ServerSocket server;
    private File fileDirectory;
    private int indexPort;

    /* peer constructor */
    public Peer(int peerID, File fileDirectory, int indexPort) {
        this.peerID = peerID;
        this.fileDirectory = fileDirectory;
        this.indexPort = indexPort;

        try {
            this.server = new ServerSocket(0);
            this.server.setReuseAddress(true);
            System.out.println(String.format("(Peer %d) Listening at 127.0.0.1:%d", this.peerID, this.server.getLocalPort()));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void main(String[] args) {
        // parse program arguments ugly
        int peerID = Integer.parseInt(args[0]);
        File fileDir = new File(args[1]);
        int indexPort = Integer.parseInt(args[2]);
        // init an object of this very class
        Peer peer = new Peer(peerID, fileDir, indexPort);

        try {
            // PeerListener is a secretary for handling any requests made by other peers
            PeerListener pl = new PeerListener(peer.server, peer.fileDirectory);
            pl.start();

            Socket indexSocket = new Socket("127.0.0.1", indexPort);
            // dataIn is for messages received FROM THE INDEX
            // dataOut is for requests we send TO THE INDEX
            DataInputStream dataIn = new DataInputStream(indexSocket.getInputStream());
            DataOutputStream dataOut = new DataOutputStream(indexSocket.getOutputStream());

            // first task: register my peer ID and (server) port number to Index server
            dataOut.writeInt(peerID);
            dataOut.writeInt(peer.server.getLocalPort());

            // second task: register all my files with Index
            File[] files = fileDir.listFiles();
            for  (File file : files) {
                if (file.isFile()) {
                    // register file with Index server
                    System.out.println(String.format("[Peer %d]: registering file '%s'", peerID, file.getName()));
                    dataOut.writeUTF(String.format("register %s", file.getName()));
                    // TODO: should I catch a response?
                }
            }

            // TODO: implement user interface (while should end on user input "exit" or something)
            // while (true) {
            //     // read in from std in
            //     // parse what the command is
            //     // send appropriately to Index server using dataOut
            //     // receive/handle/print response
            // }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            // indexSocket.close();
        }
    }

    /*
     *  retrieve - Given a 'fileName', will return/download that file to caller
     *              using a Stream Buffer.
     */
    public void retrieve (String fileName) {
        System.out.println(String.format("(Peer %d): Received request for file %s", this.peerID, fileName));
    }
}

class PeerListener extends Thread {
    private ServerSocket server;
    private File fileDirectory;

    /* peer constructor */
    public PeerListener(ServerSocket server, File fileDirectory) {
        this.server = server;
        this.fileDirectory = fileDirectory;
    }

    public void run() {
        try {
            while (true) {
                // accept an incoming connection from greedy hobbitses
                Socket peerToPeerSocket = this.server.accept();
                PeerHandler requestor = new PeerHandler(peerToPeerSocket, this.fileDirectory);
                requestor.start();
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (this.server != null) {
                try {
                    this.server.close();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
    }
}

/* deals with sending/receiving a message from peers */
class PeerHandler extends Thread {
    private Socket peerSocket;
    private File fileDirectory;

    // constructor
    public PeerHandler(Socket socket, File fileDirectory) {
        this.peerSocket = socket;
        this.fileDirectory = fileDirectory;
    }

    public void run() {
        try {
            // receive msg
            // parse it
            // handle it
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}