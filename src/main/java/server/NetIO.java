// server/NetIO.java
package server;

import net.Wire;
import net.Wire.Envelope;

final class NetIO {
    static void send(Session s, Envelope e) {
        if (s == null) return;
        String line = Wire.encode(e); // must include '\n'
        if ("SNAPSHOT".equals(e.t)) s.offerSnapshot(line);
        else                        s.offerPriority(line);
    }
}
