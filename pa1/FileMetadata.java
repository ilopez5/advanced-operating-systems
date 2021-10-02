public class FileMetadata {
    /* metadata */
    private int peerID;
    private int peerPort;

    /* constructor */
    public FileMetadata(int peerID, int port) {
        this.peerID = peerID;
        this.peerPort = port;
    }

    /* getters */
    public int getPeerID() {
        return this.peerID;
    }

    public int getPeerPort() {
        return this.peerPort;
    }
}
