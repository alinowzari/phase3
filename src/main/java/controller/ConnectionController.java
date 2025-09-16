package controller;

import common.dto.AbilityType;
import common.dto.LineDTO;
import common.dto.PointDTO;
import common.dto.cmd.*;
import controller.commands.CommandSender;
import controller.commands.GameCommand;
import model.BendPoint;
import model.Line;
import model.SystemManager;
import model.ports.InputPort;
import model.ports.OutputPort;
import view.GamePanel;

import javax.swing.*;
import javax.swing.event.MouseInputAdapter;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Dual-mode controller:
 *  - Offline: mutate local model via GameCommand (old behavior)
 *  - Online:  send typed commands to server; do NOT mutate local model
 */
public class ConnectionController extends MouseInputAdapter {

    /* ---------- modes ---------- */
    private enum Mode { IDLE, WAIT_MIDDLE, WAIT_FOOT_A, WAIT_FOOT_B, DRAG_MIDDLE, MOVING_SYSTEM }

    /* ---------- visuals / constraints ---------- */
    private static final int SYS_W = 90, SYS_H = 70;
    private static final int MOVE_RADIUS = 120;
    private static final int HIT_TOL = 5;
    private static final int HANDLE_HIT = 6;

    /* ---------- core refs ---------- */
    private final SystemManager model;     // read-only for hit tests / IDs
    private final GameCommand   cmds;      // offline mutations
    private final GamePanel     canvas;

    // networking
    private final boolean       online;
    private final CommandSender sender;    // only used if online
    private final AtomicLong    seq = new AtomicLong();

    /* ---------- state ---------- */
    private Mode mode = Mode.IDLE;

    // OFFLINE wire drag
    private OutputPort dragSource = null;

    // ONLINE wire drag
    private Point dragStartPoint = null;                // where mouse pressed
    private GamePanel.PortPick startPick = null;        // picked output (systemId, portIndex, x, y)
    private Point onlineStartCenter = null;             // exact start center for ghost // ★ FIX

    // bend editing
    private Line      editLine = null;
    private BendPoint dragBend = null;
    private Point     hMid, hA, hB;

    // context/right-click
    private model.System contextSystem = null;
    private Point        lastContextPoint = null;
    private LineDTO      selectedOnlineLine = null;

    // system move
    private model.System movingSystem = null;
    private Point        moveAnchorCenter = null;
    private Point        moveStartTopLeft = null;
    private int          incidentLenBefore = 0;

    // bend-middle drag snapshot
    private Point midStartOnDrag = null;
    private int   lenBeforeDrag  = 0;

    /* ---------- menus ---------- */
    private final JPopupMenu lineMenu;
    private final JPopupMenu systemMenu;

    /** @param online true→send commands to server; false→mutate locally via cmds */
    public ConnectionController(boolean online,
                                CommandSender sender,
                                GameCommand cmds,
                                SystemManager model,
                                GamePanel canvas) {
        this.online = online;
        this.sender = sender;
        this.cmds   = cmds;
        this.model  = model;
        this.canvas = canvas;

        this.lineMenu   = new JPopupMenu();
        this.systemMenu = new JPopupMenu();
        initLinePopup();
        initSystemPopup();

        canvas.addMouseListener(this);
        canvas.addMouseMotionListener(this);
    }

    /* ===================== Menus ===================== */

    private void initSystemPopup() {
        JMenuItem move = new JMenuItem("Move system (within radius)");
        move.addActionListener(e -> {
            if (contextSystem == null) return;

            if (!online) {
                if (!cmds.spendCoins(15)) {
                    JOptionPane.showMessageDialog(canvas,
                            "Not enough coins to move this system.",
                            "Insufficient coins", JOptionPane.WARNING_MESSAGE);
                    contextSystem = null;
                    return;
                }
            }
            // ONLINE: server will validate

            movingSystem     = contextSystem;
            moveAnchorCenter = systemCenter(movingSystem);

            // snapshot before drag for budget diff (offline only)
            moveStartTopLeft  = movingSystem.getLocation();
            incidentLenBefore = 0;
            for (Line l : model.allLines) {
                if (movingSystem.getOutputPorts().contains(l.getStart())
                        || movingSystem.getInputPorts().contains(l.getEnd())) {
                    incidentLenBefore += l.lengthPx();
                }
            }

            mode = Mode.MOVING_SYSTEM;
            contextSystem = null;
        });
        systemMenu.add(move);
    }

    private void initLinePopup() {
        JMenuItem bend    = new JMenuItem("Add bend…");
        JMenuItem remove  = new JMenuItem("Remove line");
        JMenuItem center  = new JMenuItem("Bring Back to Center (20s)");
        JMenuItem zeroAcc = new JMenuItem("Zero Acceleration (20s)");

        bend.addActionListener(e -> { if (editLine != null) mode = Mode.WAIT_MIDDLE; });

        remove.addActionListener(e -> {
            if (online) {
                if (selectedOnlineLine == null) return;
                sender.send(new RemoveLineCmd(
                        seq.incrementAndGet(),
                        selectedOnlineLine.fromSystemId(),
                        selectedOnlineLine.fromOutputIndex(),
                        selectedOnlineLine.toSystemId(),
                        selectedOnlineLine.toInputIndex()
                ));
                selectedOnlineLine = null;
                mode = Mode.IDLE;
                clearHandles();
                canvas.repaint();
                return;
            }
            // OFFLINE
            if (editLine == null) return;
            editLine.getStart().setLine(null);
            editLine.getEnd().setLine(null);
            cmds.removeLine(editLine);
            editLine = null;
            mode = Mode.IDLE;
            clearHandles();
            canvas.repaint();
        });

        center.addActionListener(e -> {
            if (online) {
                if (selectedOnlineLine == null || lastContextPoint == null) return;
                sender.send(new UseAbilityCmd(
                        seq.incrementAndGet(), AbilityType.BRING_BACK_TO_CENTER,
                        selectedOnlineLine.fromSystemId(),
                        selectedOnlineLine.fromOutputIndex(),
                        selectedOnlineLine.toSystemId(),
                        selectedOnlineLine.toInputIndex(),
                        new PointDTO(lastContextPoint.x, lastContextPoint.y)
                ));
                return;
            }
            // OFFLINE
            if (editLine == null || lastContextPoint == null) return;
            if (!cmds.spendCoins(10)) {
                JOptionPane.showMessageDialog(canvas,
                        "Not enough coins for Bring Back to Center.",
                        "Insufficient coins", JOptionPane.WARNING_MESSAGE);
                return;
            }
            editLine.addChangeCenter(lastContextPoint);
            canvas.repaint();
        });

        zeroAcc.addActionListener(e -> {
            if (online) {
                if (selectedOnlineLine == null || lastContextPoint == null) return;
                sender.send(new UseAbilityCmd(
                        seq.incrementAndGet(), AbilityType.ZERO_ACCEL,
                        selectedOnlineLine.fromSystemId(),
                        selectedOnlineLine.fromOutputIndex(),
                        selectedOnlineLine.toSystemId(),
                        selectedOnlineLine.toInputIndex(),
                        new PointDTO(lastContextPoint.x, lastContextPoint.y)
                ));
                return;
            }
            // OFFLINE
            if (editLine == null || lastContextPoint == null) return;
            if (!cmds.spendCoins(20)) {
                JOptionPane.showMessageDialog(canvas,
                        "Not enough coins for Zero Acceleration.",
                        "Insufficient coins", JOptionPane.WARNING_MESSAGE);
                return;
            }
            editLine.addZeroAccelPoint(lastContextPoint);
            canvas.repaint();
        });

        lineMenu.add(bend);
        lineMenu.add(remove);
        lineMenu.add(center);
        lineMenu.add(zeroAcc);
    }

    /* ===================== Mouse ===================== */

    @Override
    public void mousePressed(MouseEvent e) {
        Point p = e.getPoint();

        // ----- Right-click: open context menu (system first, else line) -----
        if (SwingUtilities.isRightMouseButton(e)) {
            model.System sys = findSystemAt(p);
            if (sys != null) {
                lastContextPoint = p;
                contextSystem = sys;
                systemMenu.show(canvas, p.x, p.y);
                return;
            }
            // ONLINE: pick line from snapshot HUD
            if (online) {
                var ld = canvas.pickLineAt(p, HIT_TOL);
                if (ld != null) {
                    selectedOnlineLine = ld;
                    lastContextPoint = p;
                    lineMenu.show(canvas, p.x, p.y);
                    return;
                }
            }
            // OFFLINE: fallback to model lines
            for (Line l : model.allLines) {
                if (l.hit(p, HIT_TOL)) {
                    editLine = l;
                    lastContextPoint = p;
                    lineMenu.show(canvas, p.x, p.y);
                    return;
                }
            }
        }

        // ----- Bend FSM consumes left-clicks while active -----
        if (mode != Mode.IDLE && SwingUtilities.isLeftMouseButton(e)) {
            handleBendClick(p);
            return;
        }

        // ----- Left-click: start wiring -----
        if (SwingUtilities.isLeftMouseButton(e)) {
            if (online) {
                // ONLINE start: try HUD picker, then fallback to geometry
                startPick = canvas.pickOutputAt(p);
                if (startPick == null) startPick = pickOutputFallback(p);   // <<< add this

                if (startPick != null) {
                    onlineStartCenter = new Point(startPick.x(), startPick.y());
                    dragStartPoint = p;
                    canvas.showPreview(onlineStartCenter, p);
                } else {
                    dragStartPoint = null;
                    onlineStartCenter = null;
                    canvas.hidePreview();
                }
                return; // do not fall through to offline
            }

            // OFFLINE start
            OutputPort port = findOutputAt(p);
            if (port != null && port.getLine() == null) {
                dragSource = port;
                canvas.showPreview(dragSource.getCenter(), p);
            } else {
                dragSource = null;
            }
        }

        // ----- Grab middle-handle to drag last created bend (OFFLINE only) -----
        if (!online &&
                mode == Mode.IDLE &&
                SwingUtilities.isLeftMouseButton(e) &&
                hMid != null && hMid.distance(p) < HANDLE_HIT &&
                editLine != null && dragBend != null) {

            mode = Mode.DRAG_MIDDLE;
            midStartOnDrag = new Point(dragBend.getMiddle());
            lenBeforeDrag  = lengthPx(editLine);
        }
    }

    @Override
    public void mouseDragged(MouseEvent e) {
        if (mode == Mode.MOVING_SYSTEM && movingSystem != null && moveAnchorCenter != null) {
            Point desiredCenter = e.getPoint();
            Point clamped = clampToCircle(desiredCenter, moveAnchorCenter, MOVE_RADIUS);
            Point newLoc = new Point(clamped.x - SYS_W/2, clamped.y - SYS_H/2);

            // ONLINE: just visual preview (revert on release)
            movingSystem.setLocation(newLoc);

            // OFFLINE: same move; commit/revert in release
            canvas.repaint();
            return;
        }

        // ONLINE ghost update // ★ FIX
        if (online && onlineStartCenter != null) {
            canvas.showPreview(onlineStartCenter, e.getPoint());
            return;
        }

        // OFFLINE ghost update
        if (!online && mode == Mode.IDLE && dragSource != null) {
            canvas.showPreview(dragSource.getCenter(), e.getPoint());
            return;
        }

        if (mode == Mode.DRAG_MIDDLE) {
            hMid = e.getPoint();
            if (!online && dragBend != null) {
                dragBend.setMiddle(hMid);
                editLine.invalidateLengthCache();
            }
            canvas.setHandles(hMid, hA, hB);
        }
    }

    @Override
    public void mouseReleased(MouseEvent e) {

        // ----- ONLINE: finish wire creation (independent of dragSource) ----- // ★ FIX
        if (online && onlineStartCenter != null) {
            var to = canvas.pickInputAt(e.getPoint());
            if (to == null) to = pickInputFallback(e.getPoint());   // <<< add this

            if (startPick != null && to != null) {
                sender.send(new AddLineCmd(
                        seq.incrementAndGet(),
                        startPick.systemId(), startPick.portIndex(),
                        to.systemId(),       to.portIndex()
                ));
            }
            canvas.hidePreview();
            startPick = null;
            onlineStartCenter = null;
            dragStartPoint = null;
            canvas.repaint();
            return;
        }

        // ----- Finish system move -----
        if (mode == Mode.MOVING_SYSTEM) {
            if (online) {
                Point curTopLeft = movingSystem.getLocation();
                // revert local; server will send final pos
                movingSystem.setLocation(moveStartTopLeft);

                sender.send(new MoveSystemCmd(
                        seq.incrementAndGet(),
                        movingSystem.getId(),
                        curTopLeft.x, curTopLeft.y
                ));
            }
            else {
                // OFFLINE: budget check + commit or revert
                Point curTopLeft = movingSystem.getLocation();
                int dx = curTopLeft.x - moveStartTopLeft.x;
                int dy = curTopLeft.y - moveStartTopLeft.y;

                int incidentAfter = 0;
                for (Line l : model.allLines) {
                    boolean shiftsStart = movingSystem.getOutputPorts().contains(l.getStart());
                    boolean shiftsEnd   = movingSystem.getInputPorts().contains(l.getEnd());
                    if (!shiftsStart && !shiftsEnd) continue;

                    if (shiftsStart && !shiftsEnd) incidentAfter += l.lengthIfShiftStartBy(dx, dy);
                    else if (!shiftsStart && shiftsEnd) incidentAfter += l.lengthIfShiftEndBy(dx, dy);
                    else incidentAfter += l.lengthPx();
                }

                int delta = incidentAfter - incidentLenBefore;
                if (!cmds.canAffordDelta(delta)) {
                    movingSystem.setLocation(moveStartTopLeft);
                    JOptionPane.showMessageDialog(canvas,
                            "Move exceeds wire budget. Reverted.",
                            "Move blocked", JOptionPane.WARNING_MESSAGE);
                } else if (delta != 0) {
                    cmds.applyWireDelta(delta);
                }
            }

            // cleanup
            mode = Mode.IDLE;
            movingSystem = null;
            moveAnchorCenter = null;
            moveStartTopLeft = null;
            canvas.repaint();
            return;
        }

        // ----- Finish bend drag (middle) -----
        if (mode == Mode.DRAG_MIDDLE) {
            if (online) {
                if (editLine != null && dragBend != null) {
                    int sA = editLine.getStart().getParentSystem().getId();
                    int sB = editLine.getEnd().getParentSystem().getId();
                    int outIdx = editLine.getStart().getParentSystem().getOutputPorts().indexOf(editLine.getStart());
                    int inIdx  = editLine.getEnd().getParentSystem().getInputPorts().indexOf(editLine.getEnd());
                    int bendIdx = editLine.getBendPoints().indexOf(dragBend);

                    sender.send(new MoveBendCmd(
                            seq.incrementAndGet(),
                            sA, outIdx, sB, inIdx, bendIdx,
                            new PointDTO(hMid.x, hMid.y)
                    ));
                }
            }
            else {
                editLine.invalidateLengthCache();
                int newLen = lengthPx(editLine);
                int delta  = newLen - lenBeforeDrag;

                if (delta > 0 && !cmds.canAffordDelta(delta)) {
                    dragBend.setMiddle(midStartOnDrag);
                    editLine.invalidateLengthCache();
                    JOptionPane.showMessageDialog(canvas,
                            "Moving this bend exceeds wire budget; reverted.",
                            "Bend blocked", JOptionPane.WARNING_MESSAGE);
                } else {
                    if (delta != 0) cmds.applyWireDelta(delta);
                    editLine.invalidateLengthCache();
                }
            }

            mode = Mode.IDLE;
            clearHandles();
            canvas.repaint();
            return;
        }

        // ----- OFFLINE: finish wire creation -----
        if (!online && dragSource != null) {
            canvas.hidePreview();
            InputPort target = findInputAt(e.getPoint());

            if (target != null && target.getType() == dragSource.getType()) {
                if (!cmds.canCreateWire(dragSource, target)) {
                    JOptionPane.showMessageDialog(canvas,
                            "Wire length budget exceeded.\nAvailable: "
                                    + (int)(model.getWireBudgetPx() - model.getWireUsedPx()) + " px",
                            "Cannot create wire", JOptionPane.WARNING_MESSAGE);
                } else {
                    Line wire = new Line(dragSource, target);
                    dragSource.setLine(wire);
                    target.setLine(wire);
                    cmds.addLine(wire); // updates used length
                }
            }
            dragSource = null;
            canvas.repaint();
        }
    }

    /* ===================== Bend FSM ===================== */

    private void handleBendClick(Point p) {
        switch (mode) {
            case WAIT_MIDDLE -> {
                hMid = p;   hA = hB = null;
                canvas.setHandles(hMid, null, null);
                mode = Mode.WAIT_FOOT_A;
            }
            case WAIT_FOOT_A -> {
                hA = p;
                canvas.setHandles(hMid, hA, null);
                mode = Mode.WAIT_FOOT_B;
            }
            case WAIT_FOOT_B -> {
                hB = p;

                if (editLine == null) { clearHandles(); mode = Mode.IDLE; return; }

                int sA = editLine.getStart().getParentSystem().getId();
                int sB = editLine.getEnd().getParentSystem().getId();
                int outIdx = editLine.getStart().getParentSystem().getOutputPorts().indexOf(editLine.getStart());
                int inIdx  = editLine.getEnd().getParentSystem().getInputPorts().indexOf(editLine.getEnd());

                if (online) {
                    sender.send(new AddBendCmd(
                            seq.incrementAndGet(),
                            sA, outIdx, sB, inIdx,
                            new PointDTO(hA.x, hA.y),
                            new PointDTO(hMid.x, hMid.y),
                            new PointDTO(hB.x, hB.y)
                    ));
                    clearHandles();
                    mode = Mode.IDLE;
                    canvas.repaint();
                } else {
                    int before = lengthPx(editLine);
                    BendPoint added;
                    try {
                        added = editLine.addBendPoint(hA, hMid, hB);
                    } catch (Exception ex) {
                        JOptionPane.showMessageDialog(canvas, ex.getMessage(),
                                "Cannot add bend", JOptionPane.ERROR_MESSAGE);
                        clearHandles();
                        mode = Mode.IDLE;
                        return;
                    }
                    editLine.invalidateLengthCache();
                    int after = lengthPx(editLine);
                    int delta = after - before;

                    if (delta > 0 && !cmds.canAffordDelta(delta)) {
                        editLine.removeBendPoint(added);
                        editLine.invalidateLengthCache();
                        JOptionPane.showMessageDialog(canvas,
                                "Bend would exceed wire budget by " + delta + " px.",
                                "Bend blocked", JOptionPane.WARNING_MESSAGE);
                        clearHandles();
                        mode = Mode.IDLE;
                        canvas.repaint();
                        return;
                    }

                    if (delta != 0) cmds.applyWireDelta(delta);
                    dragBend = added;
                    canvas.setHandles(hMid, hA, hB);
                    mode = Mode.IDLE;
                    canvas.repaint();
                }
            }
            default -> { /* no-op */ }
        }
    }

    /* ===================== Helpers ===================== */

    private OutputPort findOutputAt(Point p) {
        for (var sys : model.getAllSystems())
            for (OutputPort op : sys.getOutputPorts())
                if (op.contains(p)) return op;
        return null;
    }

    private InputPort findInputAt(Point p) {
        for (var sys : model.getAllSystems())
            for (InputPort ip : sys.getInputPorts())
                if (ip.contains(p)) return ip;
        return null;
    }

    private void clearHandles() {
        hMid = hA = hB = null;
        dragBend = null;
        canvas.clearHandles();
        selectedOnlineLine = null;
    }

    private model.System findSystemAt(Point p) {
        for (var sys : model.getAllSystems()) {
            Point loc = sys.getLocation();
            Rectangle r = new Rectangle(loc.x, loc.y, SYS_W, SYS_H);
            if (r.contains(p)) return sys;
        }
        return null;
    }

    private Point systemCenter(model.System s) {
        Point loc = s.getLocation();
        return new Point(loc.x + SYS_W/2, loc.y + SYS_H/2);
    }

    private static Point clampToCircle(Point p, Point c, int r) {
        double dx = p.x - c.x, dy = p.y - c.y;
        double d  = Math.hypot(dx, dy);
        if (d == 0 || d <= r) return p;
        double k = r / d;
        return new Point((int)Math.round(c.x + dx*k),
                (int)Math.round(c.y + dy*k));
    }

    private static int lengthPx(Line l) {
        java.util.List<Point> pts = l.getPath(6);
        if (pts == null || pts.size() < 2) return 0;
        double s = 0;
        for (int i = 0; i < pts.size() - 1; i++) s += pts.get(i).distance(pts.get(i+1));
        return (int)Math.round(s);
    }
    private GamePanel.PortPick pickOutputFallback(Point p) {
        for (var sys : model.getAllSystems()) {
            var outs = sys.getOutputPorts();
            for (int i = 0; i < outs.size(); i++) {
                var op = outs.get(i);
                // Don't over-validate client-side; server will reject if busy/wrong.
                if (op.contains(p)) {
                    Point c = op.getCenter();
                    return new GamePanel.PortPick(sys.getId(), i, c.x, c.y);
                }
            }
        }
        return null;
    }

    private GamePanel.PortPick pickInputFallback(Point p) {
        for (var sys : model.getAllSystems()) {
            var ins = sys.getInputPorts();
            for (int i = 0; i < ins.size(); i++) {
                var ip = ins.get(i);
                if (ip.contains(p)) {
                    Point c = ip.getCenter();
                    return new GamePanel.PortPick(sys.getId(), i, c.x, c.y);
                }
            }
        }
        return null;
    }
}