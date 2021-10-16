/**
 *  PeerMetadata -  this class encapsulates peer-related metadata such as a
 *                  peer's address and (server) port. Wrapping such metadata into a
 *                  class helps in case the functionality of this network grows
 *                  and peers gain more information, avoiding much code rework.
 *
 *                  Note: this can also be used for SuperPeers since they are,
 *                          in essence, peers themselves.
 */
public class PeerMetadata {
    /* metadata */
    private String address; // IPv4 address
    private int port; // refers to server port

    /* constructor */
    public PeerMetadata(String address, int port) {
        this.address = address;
        this.port = port;
    }

    /* getters */
    public String getAddress() { return this.address; }

    public int getPort() { return this.port; }

    public String getFullAddress() { return String.format("%s:%d", this.address, this.port); }

    /** equals - compares the equality of two PeerMetadata objects */
    public boolean equals(PeerMetadata other) {
        return this.address.equals(other.getAddress()) && this.port == other.getPort();
    }

    /** toString - serializes the PeerMetadata object */
    public String toString() {
        return this.getFullAddress();
    }

    /** parse - deserializes a PeerMetadata object */
    public static PeerMetadata parse(String peer) {
        String[] components = peer.split(":");
        return new PeerMetadata(components[0], Integer.parseInt(components[1]));
    }
}
