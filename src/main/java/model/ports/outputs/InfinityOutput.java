package model.ports.outputs;

import model.Port;
import model.ports.OutputPort;

import java.awt.*;

import static model.Type.INFINITY;
import model.System;


public class InfinityOutput extends OutputPort {
    public InfinityOutput(System system, Point location) {
        super(system, location);
        type=INFINITY;
    }
}
