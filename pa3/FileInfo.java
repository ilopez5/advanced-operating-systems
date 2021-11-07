/**
 *  FileInfo - this class holds file-related metadata for consistency purposes.
 */
public class FileInfo {
    private String name;
    private IPv4 owner;
    private int version;

    /** constructor(s) */
    public FileInfo(String fileInfo) {
        // deserializes a FileInfo string (delimiter = ',' (comma))
        String[] components = fileInfo.split(",");
        this.name = components[0];
        this.owner = new IPv4(components[1]);
        this.version = Integer.parseInt(components[2]);
    }
    public FileInfo(String fileName, IPv4 owner) {
        this.name = fileName;
        this.owner = owner;
        this.version = 1;
    }

    /** getters and other helpful methods */
    public String getName() { return this.name; }
    public int getVersion() { return this.version; }
    public IPv4 getOwner() { return this.owner; }
    public FileInfo incrementVersion() { this.version++; return this; }

    public String toString() {
        return String.format("%s,%s,%d", this.name, this.owner, this.version);
    }
}