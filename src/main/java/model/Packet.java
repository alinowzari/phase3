package model;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;

/** Base packet entity.
 *  - Keeps your movement state & impact FX.
 *  - Frame-based accel suppression (no System.nanoTime in the model).
 *  - Still abstract: concrete packets implement advance(dt).
 */
public abstract class Packet {

    // ----- identity / type -----
    IdGenerator idGenerator=new IdGenerator();            // keep for compatibility
    private int id;
    protected Type type;
    protected int  size;

    // ----- on-wire association (kept; your code uses it) -----
    protected Line line;
    protected boolean isMoving;
    private boolean  doneMovement;

    // ----- kinematics -----
    protected float  progress = 0.1f;          // 0..1 along current wire (compat)
    protected float  speed;
    protected float  acceleration;

    // frame-based acceleration suppression
    private float accelResume = 0f;
    private int   accelSuppressedFrames = 0;

    // ----- impact FX (visual/lateral drift you already use) -----
    protected int framesOnWire = 0;
    protected int noise;                        // approx screen-space
    protected float impactVX = 0f, impactVY = 0f;   // px/s lateral kick
    protected float impactDX = 0f, impactDY = 0f;   // accumulated lateral offset
    protected static final float IMPACT_DRAG = 8.0f;

    // ----- rendering positions (server computes, client draws) -----
    protected Point basePoint;                  // on-wire base
    protected Point point;                      // base + impact drift

    // ----- gameplay flags -----
    protected boolean trojan;

    // ----- ctor -----
    protected Packet() { this.id = idGenerator.nextPacketId();}

    // ======= abstract movement (subclasses implement) =======
    public abstract void advance(float dt);

    // ======= getters/setters =======
    public int  getId()          { return id; }
    public int  getSize()        { return size; }
    public Type getType()        { return type; }

    public void setPoint(Point p){ this.point = p; }
    public Point getPoint()      { return point; }
    public Point getScreenPosition() { return point; }

    public Line  getLine()       { return line; }
    public void  setLine(Line l) { this.line = l; }

    public boolean getDoneMovement(){ return doneMovement; }
    public void    doneMovement()   { this.doneMovement = true; }

    public void    isNotMoving()    { this.isMoving = false; }

    public void isTrojan()     { trojan = true; }
    public void isNotTrojan()  { trojan = false; }
    public boolean hasTrojan() { return trojan; }

    public float getProgress()            { return progress; }
    public void  setProgress(float value) { progress = value; }

    public float getSpeed()               { return speed; }
    public void  setSpeed(float s)        { speed = s; }

    public float getAcceleration()        { return acceleration; }
    public void  setAcceleration(float a) { acceleration = a; }

    // ======= frame-based timed effects (replaces nanoTime in model) =======
    /** Suppress acceleration for a number of frames (server tick frames). */
    public void suppressAccelerationForFrames(int frames) {
        if (frames <= 0) return;
        if (accelSuppressedFrames == 0) accelResume = acceleration;
        accelSuppressedFrames = Math.max(accelSuppressedFrames, frames);
        this.acceleration = 0f;
    }

    /** Call once per frame from SystemManager.update. */
    public void tickDownTimedEffects() {
        if (accelSuppressedFrames > 0 && --accelSuppressedFrames == 0) {
            this.acceleration = accelResume;
        }
    }

    /** Called when a Reset-Center effect is hit. Default: no-op. */
    public void resetCenterDrift() { /* hook for subclasses if needed */ }

    // ======= geometry helpers =======
    protected static Point lerp(Point a, Point b, float t) {
        return new Point(
                Math.round(a.x + (b.x - a.x) * t),
                Math.round(a.y + (b.y - a.y) * t)
        );
    }

    /** Sample along polyline by arc-length fraction t (0..1). */
    protected Point along(List<Point> pts, float t) {
        if (pts == null || pts.isEmpty()) return null;
        if (pts.size() < 2) return pts.get(0);

        double total = 0;
        double[] segLen = new double[pts.size() - 1];
        for (int i = 0; i < segLen.length; i++) {
            total += segLen[i] = pts.get(i).distance(pts.get(i + 1));
        }
        if (total <= 0) return pts.get(0);

        double goal = t * total, run = 0;
        for (int i = 0; i < segLen.length; i++) {
            if (run + segLen[i] >= goal) {
                double localT = (goal - run) / segLen[i];
                Point a = pts.get(i), b = pts.get(i + 1);
                int x = (int) Math.round(a.x + localT * (b.x - a.x));
                int y = (int) Math.round(a.y + localT * (b.y - a.y));
                return new Point(x, y);
            }
            run += segLen[i];
        }
        return pts.get(pts.size() - 1);
    }

    // ======= collision helpers you already use =======
    public List<Point> hitMapLocal() {
        int r = collisionRadius();
        ArrayList<Point> pts = new ArrayList<>(8);
        for (int i = 0; i < 8; i++) {
            double a = i * Math.PI / 4.0; // 0,45,90,...
            pts.add(new Point((int)Math.round(r * Math.cos(a)),
                    (int)Math.round(r * Math.sin(a))));
        }
        return pts;
    }
    /** default matches on-screen packet radius (PACKET_R ≈ 8) */
    public int collisionRadius() { return 8; }

    public int getNoise() { return noise; }
    public void incNoise() { if (noise < size) noise++; }

    // ======= impact drift (visual but kept in model for logic) =======
    public void applyImpactImpulse(Point impact, float strength) {
        if (impact == null || this.point == null) return;

        float dx = this.point.x - impact.x;
        float dy = this.point.y - impact.y;
        float len = (float) Math.hypot(dx, dy);
        if (len < 1e-3f) { dx = 1f; dy = 0f; len = 1f; }

        dx /= len; dy /= len;

        final float KICK = 18f;
        impactVX += dx * KICK * strength;
        impactVY += dy * KICK * strength;
    }

    /** Max visual drift from the on-wire base. Keep it small. */
    public float maxImpactOffset() {
        return Math.min(10f, 1.25f * collisionRadius());
    }

    protected Point composeImpact(Point base, float dt) {
        if (base == null) return this.point;

        // integrate lateral velocity → offset
        impactDX += impactVX * dt;
        impactDY += impactVY * dt;

        // clamp
        float maxOffset = maxImpactOffset();
        float offLen = (float) Math.hypot(impactDX, impactDY);
        if (offLen > maxOffset) {
            float k = maxOffset / offLen;
            impactDX *= k; impactDY *= k;
        }

        // exponential damping
        float decay = (float) Math.exp(-IMPACT_DRAG * dt);
        impactVX *= decay;
        impactVY *= decay;

        return new Point(
                Math.round(base.x + impactDX),
                Math.round(base.y + impactDY)
        );
    }
    public void beginTraversal(Line l, Point startPos) {
        line = l;
        isMoving = true;
        progress = 0f;
        // subclasses will override and clear their own cached paths
        resetPath();

        point = startPos;          // exact port centre
    }
    /** Small integration step so separation is visible this frame. */
    public void immediateImpactStep(float dt) {
        if (point != null) this.point = composeImpact(this.point, dt);
    }
    protected void resetPath(){}
    public abstract void wrongPort(Port p);
}
