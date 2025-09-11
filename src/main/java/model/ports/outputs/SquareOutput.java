package model.ports.outputs;

import model.ports.OutputPort;

import java.awt.*;

import static model.Type.SQUARE;
import model.System;


public class SquareOutput extends OutputPort {
    public SquareOutput(System system, Point location) {
        super(system, location);
        type=SQUARE;
    }
}
