package model.ports.inputs;

import model.ports.InputPort;

import java.awt.*;

import static model.Type.TRIANGLE;
import model.System;


public class TriangleInput extends InputPort {
    public TriangleInput(System system, Point location) {
        super(system, location);
        type=TRIANGLE;
    }
}
