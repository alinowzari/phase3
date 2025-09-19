package client.net;

import common.NetSnapshotDTO;
import common.util.Hex;
import net.Wire;

import java.util.Base64;

/**
 * Stateless-ish dispatcher. Interacts with the runtime via the provided hooks.
 */
public final class MessageDispatcher {

    public interface Runtime {
        // logging
        void log(String s);
        void error(String s);
        void opponentLeft(String msg);
        void resetSnapshotOrdering();
        // session & keys
        void setSid(String sid);
        String sid();
        void setPhase(String phase);
        void setMySide(String side);
        void saveResumeToken(String token);
        String resumeToken();

        void initHmacKey(byte[] key);
        void adoptChain(long lastSeq, byte[] lastMac);
        void resetBaseline();
        long lastSeq();
        byte[] lastMac();

        // journal & chain maintenance
        void compactJournal(long lastSeqAck);

        // actions
        void startHeartbeat();
        void joinQueueLevel(String levelOrNull);
        void onSnapshot(common.NetSnapshotDTO dto);
        void onStart(String side);
        boolean wantResume();
    }


    public void handle(Wire.Envelope env, Runtime rt, java.util.function.Consumer<Wire.Envelope> sendFn) {
        switch (env.t) {
            case "HELLO_S" -> {
                rt.setSid(env.sid);
                rt.log("[HELLO_S] sid=" + env.sid + " data=" + env.data);

                String hmacB64  = env.data != null ? env.data.path("hmacKey").asText("")        : "";
                String tokenStr = env.data != null ? env.data.path("reconnectToken").asText("") : "";

                try {
                    if (!hmacB64.isEmpty()) {
                        rt.initHmacKey(Base64.getDecoder().decode(hmacB64));
                    } else if (!tokenStr.isEmpty()) {
                        var sha = java.security.MessageDigest.getInstance("SHA-256");
                        rt.initHmacKey(sha.digest(tokenStr.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                    } else {
                        rt.initHmacKey(new byte[0]);
                    }
                } catch (Exception e) {
                    rt.log("[HELLO_S] key init warning: " + e.getMessage());
                    rt.initHmacKey(new byte[0]);
                }

                if (rt.wantResume() && rt.resumeToken() != null && !rt.resumeToken().isBlank()) {
                    var d = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
                    d.put("token", rt.resumeToken());
                    d.put("lastSeq", rt.lastSeq());
                    d.put("lastMac", Hex.encode(rt.lastMac()));
                    sendFn.accept(Wire.of("RESUME", rt.sid(), d));
                    rt.log("[RESUME] token=" + rt.resumeToken() + " lastSeq=" + rt.lastSeq());
                } else {
                    rt.saveResumeToken(tokenStr);
                    rt.resetBaseline();
                    //new
                    rt.resetSnapshotOrdering();
                    rt.startHeartbeat();
                    rt.joinQueueLevel(null); // runtime substitutes desired level
                }
            }
            case "START" -> {
                String side = env.data.path("side").asText("?");
                String lvl  = env.data.path("level").asText("?");
                String ph   = env.data.path("state").asText("BUILD");
                rt.setMySide(side);
                rt.setPhase(ph);
                rt.log("[START] side=" + side + " level=" + lvl + " state=" + ph);
                rt.onStart(side);
            }
            case "SNAPSHOT" -> {
                NetSnapshotDTO dto = Wire.read(env.data, NetSnapshotDTO.class);
                // NEW: adopt side if not already set
                try {
                    if (dto != null && dto.info() != null) {
                        String s = dto.info().side();
                        if (s != null && !s.isBlank()) {
                            rt.setMySide(s);  // ensures client.getSide() is "A"/"B" before UI adapts
                        }
                        rt.setPhase(dto.info().state().name());
                    }
                } catch (Exception ignore) {}
                rt.onSnapshot(dto);
            }

            case "CMD_ACK" -> {
                long seqAck   = env.data != null ? env.data.path("seq").asLong(-1) : -1;
                boolean dup   = env.data != null && env.data.path("dup").asBoolean(false);
                // We don’t need 'accepted' here to advance; runtime decides policy.
                if (seqAck >= 0 && !dup) {
                    rt.compactJournal(seqAck);
                }
            }
            case "RESUMED" -> {
                long serverLastSeq = env.data != null ? env.data.path("serverLastSeq").asLong(-1) : -1;
                String lastMacHex  = env.data != null ? env.data.path("serverLastMac").asText("") : "";
                String boundSid    = env.data != null ? env.data.path("sid").asText(rt.sid())     : rt.sid();
                String tokenStr    = env.data != null ? env.data.path("reconnectToken").asText(""): "";

                byte[] lastMac = lastMacHex.isEmpty() ? new byte[32] : Hex.decode(lastMacHex);
                rt.setSid(boundSid);
                rt.saveResumeToken(tokenStr.isBlank() ? rt.resumeToken() : tokenStr);
                rt.adoptChain(serverLastSeq, lastMac);
                //new
                rt.resetSnapshotOrdering();
                rt.startHeartbeat();
                rt.log("[RESUMED] boundSid=" + boundSid + " lastSeq=" + serverLastSeq);
            }
            case "ERROR" -> {
                String code = env.data != null ? env.data.path("code").asText("") : "";
                String msg  = env.data != null ? env.data.path("msg").asText("unknown") : "unknown";
                if ("opponent_left".equals(code) || "disconnect".equals(code)) {
                    try { ((client.GameClient)rt).inMatch = false; } catch (Throwable ignore) {}
                    rt.opponentLeft(msg);
                } else {
                    rt.error(env.data != null ? env.data.toString() : "unknown");
                }
            }
            default -> rt.log("[MSG " + env.t + "] " + env.data);
        }
    }
}
