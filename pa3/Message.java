/**
 *  Message class - the primary serialization protocol for this P2P network.
 */
public class Message {
    private String messageID;
    private int ttl;
    private FileInfo file;
    private IPv4 sender;

    /* constructor(s) */
    public Message(String message) {
        String[] msg = message.split(";");
        this.messageID = msg[0];
        this.ttl = Integer.parseInt(msg[1]);
        this.file = new FileInfo(msg[2]);
        this.sender = new IPv4(msg[3]);
    }
    public Message(FileInfo fileInfo, IPv4 sender) {
        this.messageID = "0";
        this.ttl = 0;
        this.file = fileInfo;
        this.sender = sender;
    }

    public Message(String messageID, int ttl, FileInfo fileInfo, IPv4 sender) {
        this.messageID = messageID;
        this.ttl = ttl;
        this.file = fileInfo;
        this.sender = sender;
    }
    public Message(String messageID, int ttl, FileInfo fileInfo, String senderAddress, int senderPort) {
        this.messageID = messageID;
        this.ttl = ttl;
        this.file = fileInfo;
        this.sender = new IPv4(senderAddress, senderPort);
    }

    /* getters */
    public String getID() { return this.messageID; }
    public String getFileName() { return this.file.getName(); } // for convenience
    public IPv4 getSender() { return this.sender; }
    public FileInfo getFileInfo() { return this.file; }
    public int getTTL() { return this.ttl; }

    public Message decrementTTL() {
        this.ttl -= 1;
        return this;
    }

    public String toString() {
        return String.format("%s;%d;%s;%s", this.messageID, this.ttl, this.file, this.sender);
    }
}
