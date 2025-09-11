package model.ports.inputs;

import model.Type;
import model.ports.InputPort;
import java.awt.*;
import static model.Type.INFINITY;
import model.System;

public class InfinityInput extends InputPort {
    public InfinityInput(System system, Point location) {
        super(system, location);
        type = INFINITY;
    }
}
