package model.ports.inputs;

import model.ports.InputPort;
import java.awt.*;
import static model.Type.SQUARE;
import model.System;

public class SquareInput extends InputPort {
    public SquareInput(System system, Point location) {
        super(system, location);
        type=SQUARE;
    }
}
