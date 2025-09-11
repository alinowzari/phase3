package model.ports.outputs;

import model.ports.OutputPort;

import java.awt.*;

import static model.Type.TRIANGLE;
import model.System;


public class TriangleOutput extends OutputPort {
    public TriangleOutput(System system, Point location) {
        super(system, location);
        type=TRIANGLE;
    }
}
