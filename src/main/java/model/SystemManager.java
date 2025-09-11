package model;

import model.Loader.GameStatus;
import model.Loader.LayoutIO;
import model.packets.BigPacket;
import model.packets.BitPacket;
import model.packets.ProtectedPacket;
import model.packets.SecretPacket2;
import model.ports.InputPort;
import model.ports.OutputPort;
import model.systems.AntiTrojanSystem;
import model.systems.SpySystem;
import model.systems.VpnSystem;

import java.awt.Point;
import java.util.*;
import java.util.List;

public class SystemManager {

    // ---- Sim context (authoritative dt + RNG) ----
    private final SimulationContext ctx;
    private final Random rng;
    // ---- Gameplay state ----
    private final ArrayList<System>      systems     = new ArrayList<>();
    private final ArrayList<SpySystem>   spySystems  = new ArrayList<>();
    private final ArrayList<VpnSystem>   vpnSystems  = new ArrayList<>();
    public  final ArrayList<Packet>      allPackets  = new ArrayList<>();
    public  final ArrayList<Line>        allLines    = new ArrayList<>();
    private final HashMap<Integer, ArrayList<BitPacket>> bigPackets = new HashMap<>();
    private final Set<Integer>           packetIds   = new HashSet<>();

    // ---- Win/level bookkeeping ----
    private final GameStatus gameStatus;
    private final float      maxLineLength;
    private float            usedLineLength = 0;
    private boolean          isReady        = false;
    private boolean          launched       = false;
    private boolean          isLevelPassed  = false;
    private boolean          winCommitted   = false;
    private int              firstCountPacket = 0;
    private int              receivedPacket   = 0;
    private String           levelName;

    // ---- Collision + spatial hashing ----
    private static final int  CELL = 32;
    private final Map<Long, ArrayList<Packet>> grid = new HashMap<>();
    private static long key(int cx, int cy) { return (((long) cx) << 32) ^ (cy & 0xffffffffL); }

    // ---- Off-wire culling ----
    private static final int   PORT_SAFE_PX   = 18;
    private static final float OFFWIRE_FACTOR = 1.2f;
    private static final int   OFFWIRE_GRACE_FRAMES = 4;
    private final Map<Integer, Integer> offwireFrames = new HashMap<>();

    // ---- Misc constants ----
    private static final int  EFFECT_RADIUS_PX = 10;
    private static final int  SAFE_RADIUS      = 35;

    // ---- Coins ----
    public int coinCount = 0;

    // ---- ctor ----
    public SystemManager(GameStatus gameStatus, String levelName) { this(gameStatus, levelName, 42L); }
    public SystemManager(GameStatus gameStatus, String levelName, long seed) {
        this.rng = new java.util.Random(levelName.hashCode());
        this.ctx = new SimulationContext(seed);
        this.gameStatus = gameStatus;
        this.levelName  = levelName;
        this.maxLineLength = (gameStatus != null) ? gameStatus.getWireLength(levelName) : 0;
        this.winCommitted  = (gameStatus != null) && gameStatus.isLevelPassed(levelName);
    }

    // ---- Accessors ----
    public SimulationContext ctx() { return ctx; }
    public Random rng() { return ctx.rng; }
    public ArrayList<System> getAllSystems() { return systems; }
    public ArrayList<SpySystem> getAllSpySystems() { return spySystems; }
    public ArrayList<VpnSystem> getAllVpnSystems() { return vpnSystems; }
    public HashMap<Integer, ArrayList<BitPacket>> getBigPackets() { return bigPackets; }
    public boolean isReady()    { return isReady; }
    public boolean isLaunched() { return launched; }
    public void    launchPackets() { launched = true; }
    public void    addCoin(int plus){ coinCount += plus; }
    public void    addToFirstCountPacket(){ firstCountPacket++; }
    public void    addToReceivedPacket(){  receivedPacket++; }
    public void    setLevelName(String name){ this.levelName = name; }

    // ---- Systems/lines/packets management ----
    public void addSystem(System system) {
        systems.add(system);
        if (system instanceof SpySystem s)  spySystems.add(s);
        if (system instanceof VpnSystem v)  vpnSystems.add(v);
    }
    public void removeSystem(System system) {
        Iterator<Line> it = allLines.iterator();
        while (it.hasNext()) {
            Line line = it.next();
            boolean incident = system.getInputPorts().contains(line.getEnd())
                    || system.getOutputPorts().contains(line.getStart());
            if (incident) {
                Packet mp = line.getMovingPacket();
                if (mp != null) removePacket(mp);
                it.remove();
            }
        }
        systems.remove(system);
        if (system instanceof SpySystem s)  spySystems.remove(s);
        if (system instanceof VpnSystem v) { vpnSystems.remove(v); handleVpnDestruction(v.getId()); }
    }

    public void addLine(Line line) { allLines.add(line); usedLineLength += line.lengthPx(); }
    public void removeLine(Line line) { usedLineLength -= line.lengthPx(); if (usedLineLength < 0) usedLineLength = 0; allLines.remove(line); }

    public void addPacket(Packet p) {
        if (packetIds.add(p.getId())) {
            allPackets.add(p);
            if (p instanceof BigPacket big) bigPackets.put(big.getId(), big.split());
        }
    }
    public void removePacket(Packet packet) {
        allPackets.remove(packet);
        for (System system : systems) if (system.getPackets().contains(packet)) system.removePacket(packet);
    }

    public void handleVpnDestruction(int vpnId) {
        for (System sys : systems) {
            ListIterator<Packet> it = sys.getPackets().listIterator();
            while (it.hasNext()) {
                Packet p = it.next(); Packet inner = null;
                if (p instanceof ProtectedPacket<?> prot && prot.getSystemId() == vpnId) inner = prot.unwrap();
                else if (p instanceof SecretPacket2<?> s2 && s2.getSystemId() == vpnId) inner = s2.unwrap();
                if (inner != null) it.set(inner);
            }
        }
        ListIterator<Packet> pit = allPackets.listIterator();
        while (pit.hasNext()) {
            Packet p = pit.next(); Packet inner = null;
            if (p instanceof ProtectedPacket<?> prot && prot.getSystemId() == vpnId) inner = prot.unwrap();
            else if (p instanceof SecretPacket2<?> s2 && s2.getSystemId() == vpnId) inner = s2.unwrap();
            if (inner != null) pit.set(inner);
        }
    }

    // ---- Frame/tick update (authoritative) ----
    public void update(float dt) {
        ctx.dtSeconds = dt;
        ctx.tick++;

        if (receivedPacket >= (firstCountPacket / 2)) isLevelPassed = true;

        // 1) lines & motion
        for (Line l : new ArrayList<>(allLines)) {
            l.tickDownEffects();

            Packet pkt = l.getMovingPacket();
            if (pkt == null) continue;

            Point pos = pkt.getScreenPosition();
            if (pos != null) {
                if (l.nearZeroAccel(pos, EFFECT_RADIUS_PX)) {
                    int frames20s = (int)Math.ceil(20.0 / ctx.dtSeconds);
                    pkt.suppressAccelerationForFrames(frames20s);
                }
                if (l.nearRecenter(pos, EFFECT_RADIUS_PX)) pkt.resetCenterDrift();
            }
            pkt.advance(dt);
        }

        // 2) per-packet timed effects
        for (Packet p : new ArrayList<>(allPackets)) p.tickDownTimedEffects();

        // 3) collisions
        checkCollisions();

        // 4) launch/send from systems once wiring is valid
        boolean everyOutputWired = systems.stream().allMatch(this::allOutputsConnected);
        boolean everyInputWired  = systems.stream().allMatch(this::allInputsConnected);
        boolean clearsCentres    = wiringClearsSystemCentres();
        isReady = clearsCentres && everyOutputWired && everyInputWired;

        if (launched && isReady) {
            for (System sys : systems) {
                if (!sys.getPackets().isEmpty()) sys.sendPacket();
                if (sys instanceof AntiTrojanSystem ats) ats.cleanTrojan(dt); // dt-based, no nanoTime
            }
        }

        // 5) end-of-run check
        if (allIdle()) {
            if (isLevelPassed) commitLevelWinIfNeeded();
            else java.lang.System.out.println("you lose");
        }
    }

    // ---- Collision handling (unchanged in spirit) ----
    public void checkCollisions() {
        final ArrayList<Packet> list   = new ArrayList<>(allPackets);
        final ArrayList<Packet> moving = new ArrayList<>(list.size());
        for (Packet p : list) if (p != null && p.isMoving && p.getLine() != null && p.getScreenPosition() != null) moving.add(p);
        cullOffWire(moving);
        if (moving.size() < 2) return;

        rebuildGrid(moving);
        int[] off = {-1,0,1};

        for (var e : grid.entrySet()) {
            long k = e.getKey(); int cx = (int)(k >> 32), cy = (int)(k & 0xffffffffL);
            for (int dx : off) for (int dy : off) {
                ArrayList<Packet> bucket = grid.get(key(cx+dx, cy+dy));
                if (bucket == null) continue;

                for (Packet a : e.getValue()) {
                    Point ca = a.getScreenPosition(); if (ca == null) continue;
                    int ra = a.collisionRadius();
                    for (Packet b : bucket) {
                        if (a.getId() >= b.getId()) continue;
                        Point cb = b.getScreenPosition(); if (cb == null) continue;
                        int rb = b.collisionRadius();

                        int dxp = ca.x - cb.x, dyp = ca.y - cb.y, sum = ra + rb;
                        if (dxp*dxp + dyp*dyp > sum*sum) continue;

                        List<Point> A = worldHitMap(a), B = worldHitMap(b);
                        boolean hit = polygonsIntersect(A,B) || pointInPolygon(A.get(0),B) || pointInPolygon(B.get(0),A);
                        if (!hit) continue;

                        a.incNoise(); b.incNoise();
                        Point impact = new Point((ca.x + cb.x)/2, (ca.y + cb.y)/2);
                        a.applyImpactImpulse(impact, 1f);
                        b.applyImpactImpulse(impact, 1f);

                        float fdt = (float) ctx.dtSeconds;
                        a.immediateImpactStep(fdt);
                        b.immediateImpactStep(fdt);

                        double nx = ca.x - cb.x, ny = ca.y - cb.y;
                        double len = Math.hypot(nx, ny);
                        if (len < 1e-3) { nx = 1; ny = 0; len = 1; }
                        nx /= len; ny /= len;

                        final double SHIFT = 2.0;
                        Point pa = a.getPoint(), pb = b.getPoint();
                        if (pa != null) a.setPoint(new Point((int)Math.round(pa.x + nx*SHIFT), (int)Math.round(pa.y + ny*SHIFT)));
                        if (pb != null) b.setPoint(new Point((int)Math.round(pb.x - nx*SHIFT), (int)Math.round(pb.y - ny*SHIFT)));
                    }
                }
            }
        }
    }

    private void rebuildGrid(List<Packet> moving) {
        grid.clear();
        for (Packet p : moving) {
            Point c = p.getScreenPosition(); if (c == null) continue;
            int r = p.collisionRadius();
            int minCx = Math.floorDiv(c.x - r, CELL), maxCx = Math.floorDiv(c.x + r, CELL);
            int minCy = Math.floorDiv(c.y - r, CELL), maxCy = Math.floorDiv(c.y + r, CELL);
            for (int cx = minCx; cx <= maxCx; cx++)
                for (int cy = minCy; cy <= maxCy; cy++)
                    grid.computeIfAbsent(key(cx, cy), k -> new ArrayList<>()).add(p);
        }
    }

    private static List<Point> worldHitMap(Packet p) {
        List<Point> local = p.hitMapLocal();
        Point c = p.getScreenPosition();
        ArrayList<Point> world = new ArrayList<>(local.size());
        for (Point q : local) world.add(new Point(c.x + q.x, c.y + q.y));
        return world;
    }

    private static boolean polygonsIntersect(List<Point> A, List<Point> B) {
        int na = A.size(), nb = B.size();
        for (int ia = 0; ia < na; ia++) {
            Point a0 = A.get(ia), a1 = A.get((ia + 1) % na);
            for (int ib = 0; ib < nb; ib++) {
                Point b0 = B.get(ib), b1 = B.get((ib + 1) % nb);
                if (segmentsIntersect(a0, a1, b0, b1)) return true;
            }
        }
        return false;
    }
    private static boolean segmentsIntersect(Point a, Point b, Point c, Point d) {
        int o1 = orient(a, b, c), o2 = orient(a, b, d), o3 = orient(c, d, a), o4 = orient(c, d, b);
        if (o1 != o2 && o3 != o4) return true;
        if (o1 == 0 && onSegment(a, b, c)) return true;
        if (o2 == 0 && onSegment(a, b, d)) return true;
        if (o3 == 0 && onSegment(c, d, a)) return true;
        if (o4 == 0 && onSegment(c, d, b)) return true;
        return false;
    }
    private static int  orient(Point a, Point b, Point c) {
        long v = (long)(b.x - a.x) * (c.y - a.y) - (long)(b.y - a.y) * (c.x - a.x);
        return (v > 0) ? 1 : (v < 0 ? -1 : 0);
    }
    private static boolean onSegment(Point a, Point b, Point p) {
        return Math.min(a.x, b.x) <= p.x && p.x <= Math.max(a.x, b.x) &&
                Math.min(a.y, b.y) <= p.y && p.y <= Math.max(a.y, b.y) &&
                orient(a, b, p) == 0;
    }
    private static boolean pointInPolygon(Point p, List<Point> poly) {
        boolean inside = false; int n = poly.size();
        for (int i = 0, j = n - 1; i < n; j = i++) {
            Point pi = poly.get(i), pj = poly.get(j);
            boolean intersect = ((pi.y > p.y) != (pj.y > p.y)) &&
                    (p.x < (long)(pj.x - pi.x) * (p.y - pi.y) / (double)(pj.y - pi.y) + pi.x);
            if (intersect) inside = !inside;
        }
        return inside;
    }

    // wiring / readiness
    private boolean allOutputsConnected(System s) { for (OutputPort op : s.getOutputPorts()) if (op.getLine() == null) return false; return true; }
    private boolean allInputsConnected (System s) { for (InputPort  ip : s.getInputPorts())  if (ip.getLine() == null) return false; return true; }

    private boolean wiringClearsSystemCentres() {
        for (System sys : systems) {
            Point c = new Point(sys.getLocation().x + 90/2, sys.getLocation().y + 70/2);
            for (Line l : allLines) {
                List<Point> pts = l.getPath(6);
                for (int i = 0; i < pts.size()-1; i++)
                    if (segmentDistance(c, pts.get(i), pts.get(i+1)) < SAFE_RADIUS) return false;
            }
        }
        return true;
    }
    private static double segmentDistance(Point p, Point a, Point b) {
        double dx = b.x - a.x, dy = b.y - a.y;
        if (dx == 0 && dy == 0) return p.distance(a);
        double t = ((p.x - a.x)*dx + (p.y - a.y)*dy) / (dx*dx + dy*dy);
        t = Math.max(0, Math.min(1, t));
        double projX = a.x + t*dx, projY = a.y + t*dy;
        return p.distance(projX, projY);
    }

    // off-wire culling (frame counters)
    private void cullOffWire(List<Packet> moving) {
        for (Packet p : moving) {
            Line  l = p.getLine(); Point c = p.getScreenPosition();
            if (l == null || c == null) continue;

            int id = p.getId();
            if (nearPort(l, c, PORT_SAFE_PX)) { offwireFrames.remove(id); continue; }

            double dist = distanceToPolyline(l, c);
            int baseR   = Math.max(6, p.collisionRadius());
            float thr   = OFFWIRE_FACTOR * baseR;

            if (dist > thr) {
                int n = offwireFrames.getOrDefault(id, 0) + 1;
                if (n >= OFFWIRE_GRACE_FRAMES) { packetDestroyed(p); offwireFrames.remove(id); }
                else offwireFrames.put(id, n);
            } else {
                offwireFrames.remove(id);
            }
        }
    }
    private static boolean nearPort(Line l, Point c, int safePx) {
        Point s = l.getStart().getCenter(), e = l.getEnd().getCenter();
        return (s != null && c.distance(s) <= safePx) || (e != null && c.distance(e) <= safePx);
    }
    private double distanceToPolyline(Line l, Point p) {
        List<Point> pts = l.getPath(6);
        if (pts == null || pts.size() < 2) return Double.POSITIVE_INFINITY;
        double best = Double.POSITIVE_INFINITY;
        for (int i = 0; i < pts.size() - 1; i++) best = Math.min(best, segmentDistance(p, pts.get(i), pts.get(i + 1)));
        return best;
    }

    // coins/layout/win
    public int  getTotalCoins() { return (gameStatus != null) ? gameStatus.getTotalCoin() : 0; }
    public boolean spendTotalCoins(int amount) {
        if (gameStatus == null) return false;
        int cur = gameStatus.getTotalCoin(); if (cur < amount) return false;
        gameStatus.setTotalCoin(cur - amount); return true;
    }

    private void commitLevelWinIfNeeded() {
        if (winCommitted) return; winCommitted = true;

        recomputeUsedWireLength();
        if (gameStatus != null) gameStatus.commitWin(levelName, coinCount);

        var cfgMgr    = config.ConfigManager.getInstance();
        var curConfig = cfgMgr.getConfig();
        var snap      = LayoutIO.snapshotToConfig(curConfig, this);
        LayoutIO.saveGameConfig(java.nio.file.Paths.get("gameConfig.json"), snap);

        var all = new ArrayList<>(cfgMgr.getAllLevels());
        int idx = java.util.stream.IntStream.range(0, all.size())
                .filter(i -> all.get(i).levelName().equals(curConfig.levelName()))
                .findFirst().orElse(-1);
        if (idx >= 0) {
            var updated = LayoutIO.propagateToNextLevels(all, idx, this);
            LayoutIO.saveLevelPack(java.nio.file.Paths.get("levels.json"), updated);
        }
        java.lang.System.out.println("you win");
    }

    private boolean allIdle() {
        for (Line l : allLines) if (l.getMovingPacket() != null) return false;
        for (System s : systems) if (!s.getPackets().isEmpty()) return false;
        return true;
    }

    // wire budget helpers
    public float getWireBudgetPx() { return maxLineLength; }
    public int   getWireUsedPx()   { return (int)usedLineLength; }
    public boolean canAffordDelta(int deltaPx) { return usedLineLength + deltaPx <= maxLineLength + 0.5f; }
    public void applyWireDelta(int deltaPx) { usedLineLength += deltaPx; if (usedLineLength < 0) usedLineLength = 0; }
    public void recomputeUsedWireLength() { int sum = 0; for (Line l : allLines) sum += l.lengthPx(); usedLineLength = sum; }
    public boolean canCreateWire(OutputPort a, InputPort b) { int need = Line.straightLength(a.getCenter(), b.getCenter()); return canAffordDelta(need); }
    public boolean tryCommitLineGeometryChange(Line line, int oldLen, int newLen) {
        float base = usedLineLength - oldLen; if (base + newLen > maxLineLength + 0.5f) return false;
        usedLineLength = base + newLen; return true;
    }

    public void packetDestroyed(Packet p) {
        Line l = p.getLine();
        if (l != null) {
            if (l.getMovingPacket() == p) l.removeMovingPacket();
            p.setLine(null);
        }
        for (System sys : systems) sys.removePacket(p);
        allPackets.remove(p);
    }
    public Random getRng() { return rng; }
}
