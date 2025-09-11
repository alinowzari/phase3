package model;

import java.awt.*;
//import java.lang.System;
import model.systems.*;
import model.System;
import java.util.List;

public abstract class Port {
    protected Type type;
    protected System parentSystem;
    protected Point place;
    protected Line line;
    private static final int HIT_RADIUS = 12;
    public Port(System system, Point point) {
        parentSystem = system;
        place = point;
    }
    public void setCenter(Point point) {place = point;}
    public Point getCenter() {return place;}
    public Type getType() {return type;}
    public void setLine(Line line) {this.line = line;}
    public Line getLine() {return line;}
    public System getParentSystem() {return parentSystem;}
    public boolean contains(Point testPoint) {
        Point c = getCenter();
        return c.distance(testPoint) <= HIT_RADIUS;
    }

}
