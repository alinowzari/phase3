package server;

import net.Wire;
import net.Wire.Envelope;

import java.io.PrintWriter;

final class NetIO {
    static void send(Session s, Envelope e) {
        synchronized (s.sendLock) {
            PrintWriter pw = s.out;
            if (pw == null) return;         // safe guard on races
            pw.print(Wire.encode(e));
            pw.flush();
        }
    }
}
