package model;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;

public class BendPoint {
    private final Point start;            // foot on the straight segment
    private       Point middle;           // draggable control point
    private final Point end;              // other foot

    /* ready-made when the caller already knows all 3 points */
    public BendPoint(Point start, Point middle, Point end) {
        this.start  = start;
        this.middle = middle;
        this.end    = end;
    }

    /* getters / setter for middle */
    public Point getStart()  { return start; }
    public Point getEnd()    { return end;   }
    public Point getMiddle() { return middle;}
    public void  setMiddle(Point m){ this.middle = m; }

    /* ----------------------------------------------------------
     *  Quadratic Bézier sampling:  returns N interior points
     *  (neither endpoint is included – callers add them already)
     * ---------------------------------------------------------- */
    public List<Point> sampleCurve(int samples) {
        List<Point> out = new ArrayList<>(samples);
        for (int i = 1; i <= samples; i++) {
            double t = i / (samples + 1.0);           // e.g. 1/6, …, 5/6
            out.add(bezier(t));
        }
        return out;
    }
    private Point bezier(double t) {
        double u = 1 - t;
        double x = u*u*start.x + 2*u*t*middle.x + t*t*end.x;
        double y = u*u*start.y + 2*u*t*middle.y + t*t*end.y;
        return new Point((int)Math.round(x), (int)Math.round(y));
    }
}
