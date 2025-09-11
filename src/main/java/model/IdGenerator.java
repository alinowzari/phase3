package model;
public final class IdGenerator {
    private int next = 1;
    public int nextPacketId() { return next++; }
}
