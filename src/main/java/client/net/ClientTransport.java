package client.net;

import net.Wire;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Thin socket wrapper: connect, read loop, send, close. */
public final class ClientTransport implements Closeable {
    private final String host;
    private final int port;

    private volatile boolean closing = false;
    private Socket socket;
    private BufferedReader in;
    private PrintWriter out;

    private ExecutorService readExec;

    public ClientTransport(String host, int port) {
        this.host = host; this.port = port;
    }

    public synchronized void connect(Consumer<Wire.Envelope> onEnvelope, Consumer<String> onError) throws IOException {
        if (socket != null && socket.isConnected() && !socket.isClosed()) return;

        closing = false;
        socket = new Socket();
        socket.setTcpNoDelay(true);
        socket.connect(new InetSocketAddress(host, port), 3000);

        in  = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);

        if (readExec == null || readExec.isShutdown()) {
            readExec = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "ClientRead");
                t.setDaemon(true);
                return t;
            });
        }
        readExec.execute(() -> readLoop(onEnvelope, onError));
    }

    private void readLoop(Consumer<Wire.Envelope> onEnvelope, Consumer<String> onError) {
        try {
            String line;
            while (!closing && in != null && (line = in.readLine()) != null) {
                onEnvelope.accept(Wire.decode(line));
            }
        } catch (IOException ioe) {
            if (!closing && onError != null) onError.accept("Disconnected: " + ioe.getMessage());
        } catch (Exception ex) {
            if (!closing && onError != null) onError.accept("Client error: " + ex.getMessage());
        } finally {
            try { close(); } catch (IOException ignore) {}
        }
    }

    public synchronized void send(Wire.Envelope e, Consumer<String> onError) {
        try {
            if (!closing && out != null) {
                out.print(Wire.encode(e));
                out.flush();
            }
        } catch (Exception ex) {
            if (!closing && onError != null) onError.accept("Send failed: " + ex.getMessage());
        }
    }

    public synchronized boolean isOpen() {
        return socket != null && socket.isConnected() && !socket.isClosed();
    }

    @Override
    public synchronized void close() throws IOException {
        closing = true;
        if (readExec != null) { readExec.shutdownNow(); readExec = null; }
        if (socket != null) try { socket.close(); } catch (Exception ignore) {}
        if (in != null) try { in.close(); } catch (Exception ignore) {}
        if (out != null) try { out.close(); } catch (Exception ignore) {}
        socket = null; in = null; out = null;
    }
}
