/**
 *  IPv4 -  this class holds an IPv4 address and port number. it is used for both
 *          Peer and SuperPeers.
 */
public class IPv4 {
    /* class members */
    private String address; // IPv4 address
    private int port; // refers to server port

    /* constructor(s) */
    public IPv4(String fullAddress) {
        this.address = fullAddress.split(":")[0];
        this.port = Integer.parseInt(fullAddress.split(":")[1]);
    }

    public IPv4(String address, int port) {
        this.address = address;
        this.port = port;
    }

    /* getters */
    public String getAddress() { return this.address; }
    public int getPort() { return this.port; }

    /** equals - compares the equality of two PeerMetadata objects */
    public boolean equals(IPv4 other) {
        return this.toString().equals(other.toString());
    }

    /** toString - serializes the PeerMetadata object */
    public String toString() {
        return String.format("%s:%d", this.address, this.port);
    }
}
