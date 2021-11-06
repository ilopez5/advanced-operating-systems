/**
 *  FileInfo - this class holds file-related metadata for consistency purposes.
 */
public class FileInfo {
    private String name;
    private IPv4 owner;
    private int version;
    private boolean valid;

    /** constructor(s) */
    public FileInfo(String fileInfo) {
        // deserializes a FileInfo string (delimiter = ',' (comma))
        String[] components = fileInfo.split(",");
        this.name = components[0];
        this.owner = new IPv4(components[1]);
        this.version = Integer.parseInt(components[2]);
        this.valid = Boolean.parseBoolean(components[3]);
    }
    public FileInfo(String fileName, IPv4 owner) {
        this.name = fileName;
        this.owner = owner;
        this.version = 1;
        this.valid = true;
    }

    /** getters and other helpful methods */
    public String getName() { return this.name; }
    public int getVersion() { return this.version; }
    public IPv4 getOwner() { return this.owner; }
    public boolean isValid() { return this.valid; }
    public FileInfo setValidity(boolean status) { this.valid = status; return this; }

    public String toString() {
        return String.format("%s,%s,%d,%s", this.name, this.owner, this.version, this.valid);
    }
}