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
    private String address;
    private int port; // refers to server port. if ambiguous, will rename

    /* constructor */
    public PeerMetadata(String address, int port) {
        this.address = address;
        this.port = port;
    }

    /* getters */
    public String getAddress() {
        return this.address;
    }

    public int getPort() {
        return this.port;
    }

    /* helper methods */

    /**
     *  equals - compares the equality of two PeerMetadata objects using both
     *          their address and port number (since those are gauranteed to be
     *          unique)
     */
    public boolean equals(PeerMetadata other) {
        return this.address.equals(other.getAddress()) && this.port == other.getPort();
    }

    /**
     *  toString - serializes the PeerMetadata object into a string.
     */
    public String toString() {
        return String.format("%s:%d", this.address, this.port);
    }

    /**
     *  parseString - static method for deserializing a PeerMetadata string into
     *              an actual object of the class.
     */
    public static PeerMetadata parseString(String peer) {
        String[] components = peer.split(":");
        return new PeerMetadata(components[0], Integer.parseInt(components[1]));
    }
}
