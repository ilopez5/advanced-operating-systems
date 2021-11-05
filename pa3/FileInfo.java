/**
 *  FileInfo - this class holds file-related metadata for consistency purposes.
 */
public class FileInfo {
    public String name;
    public IPv4 owner;
    public int version;
    public boolean valid;

    public FileInfo(String fileName, IPv4 owner) {
        this.name = fileName;
        this.owner = owner;
        this.version = 1;
        this.valid = true;
    }

    public FileInfo(String fileInfo) {
        // deserializes a FileInfo string
    }

    public boolean isValid() {
        return this.valid;
    }

    public String toString() {
        return String.format("");
    }
}