public class Message {
    private String messageID;
    private int ttl;
    private String fileName;
    private String address;
    private int port;

    /* constructor(s) */
    public Message(String messageID, int ttl, String fileName) {
        this.messageID = messageID;
        this.ttl = ttl;
        this.fileName = fileName;
    }

    public Message(String messageID, int ttl, String fileName, String address, int port) {
        this.messageID = messageID;
        this.ttl = ttl;
        this.fileName = fileName;
        this.address = address;
        this.port = port;
    }

    /* getters and setters */
    public String getID() {
        return this.messageID;
    }

    public int getTTL() {
        return this.ttl;
    }

    public String getFileName() {
        return this.fileName;
    }

    public String getAddress() {
        return this.address;
    }

    public int getPort() {
        return this.port;
    }

    public String getFullAddress() {
        return String.format("%s:%d", this.address, this.port);
    }

    public Message setFullAddress(String fullAddress) {
        this.address = fullAddress.split(":")[0];
        this.port = Integer.parseInt(fullAddress.split(":")[1]);
        return this;
    }

    public Message decrementTTL() {
        this.ttl -= 1;
        return this;
    }

    /* helper methods */
    public String toString() {
        if (this.address.isEmpty()) {
            return String.format("%s;%d;%s", this.messageID, this.ttl, this.fileName);
        }
        return String.format("%s;%d;%s;%s:%d",
            this.messageID, this.ttl, this.fileName, this.address, this.port);
    }

    public static Message parse(String message) {
        String[] msg = message.split(";");
        if (msg.length > 3) {
            return new Message(msg[0], Integer.parseInt(msg[1]), msg[2], msg[3], Integer.parseInt(msg[4]));
        }
        return new Message(msg[0], Integer.parseInt(msg[1]), msg[2]);
    }
}
