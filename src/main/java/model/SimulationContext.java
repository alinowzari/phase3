package model;

import java.util.Random;

public final class SimulationContext {
    public final Random rng;
    public final IdGenerator ids = new IdGenerator();
    public double dtSeconds = 1.0/60.0;   // server tick (set in SystemManager.update)
    public float speedScale = 12f;        // replaces Packet.SPEED_SCALE
    public long  tick = 0;                // monotonically increasing frame counter

    // example: replace GameStatus.totalCoinCount with this (or keep your own bank class)
    public int coinBank = 0;

    public SimulationContext(long seed) { this.rng = new Random(seed); }
}
