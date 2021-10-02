public class PeerMetadata {
    /* metadata */
    private int peerID;
    private int peerServerPort;

    /* constructor */
    public PeerMetadata(int peerID, int port) {
        this.peerID = peerID;
        this.peerServerPort = port;
    }

    /* getters */
    public int getID() {
        return this.peerID;
    }

    public int getServerPort() {
        return this.peerServerPort;
    }

    public String toString() {
        return String.format("(%d, %d)", this.peerID, this.peerServerPort);
    }
}
