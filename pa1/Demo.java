import java.util.*;

public class Demo {

    public static void main(String[] args) {
        System.out.println("[DEMO] Starting...");
        System.out.println("[DEMO] Initializing Index and 3 Peers...");
        
        // new ServerThread().start();
        Index index = new Index(0, "127.0.0.1", 5000);
        Peer peer1  = new Peer(1, "127.0.0.1", 5001);
        Peer peer2  = new Peer(2, "127.0.0.1", 5002);
        Peer peer3  = new Peer(3, "127.0.0.1", 5003);

        index.start();
        peer1.start();
        peer2.start();
        peer3.start();

        System.out.println("[DEMO] Done.");
        return;
    }
}
