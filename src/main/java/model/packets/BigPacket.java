// src/model/packets/AbstractBigPacket.java
package model.packets;

import model.Packet;
import model.Port;
import model.Type;

import java.util.ArrayList;

public abstract class BigPacket extends Packet {

    protected BigPacket() {
        this.type         = Type.BIG;
    }

    @Override public void wrongPort(Port p) { /* no-op */ }
    public abstract int  getColorId();          // <── ADDED
    public abstract int  getOriginalSize();
    public abstract ArrayList<BitPacket> split();
}
