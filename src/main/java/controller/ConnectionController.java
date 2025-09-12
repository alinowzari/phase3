//package controller;
//
//import model.BendPoint;
//import model.Line;
//import model.SystemManager;
//import model.ports.InputPort;
//import model.ports.OutputPort;
//import view.GamePanel;
//
//import javax.swing.*;
//import javax.swing.event.MouseInputAdapter;
//import java.awt.*;
//import java.awt.event.MouseEvent;
//
///**
// * Handles:
// *  • Left-drag from an OutputPort → InputPort to create wires (budget-checked)
// *  • Right-click on a wire to show menu: Add bend / Remove line / abilities
// *  • Bend creation via 3-click FSM: middle, footA, footB
// *  • Right-click on a system to move it within a radius (budget-checked on release)
// *
// * Mutations go through GameCommands (no direct SystemManager mutation).
// */
//public class ConnectionController extends MouseInputAdapter {
//
//    /* ---------- modes ---------- */
//    private enum Mode { IDLE, WAIT_MIDDLE, WAIT_FOOT_A, WAIT_FOOT_B, DRAG_MIDDLE, MOVING_SYSTEM }
//
//    /* ---------- visuals / constraints ---------- */
//    private static final int SYS_W = 90, SYS_H = 70;     // system box size (match view)
//    private static final int MOVE_RADIUS = 120;          // system move clamp radius
//    private static final int HIT_TOL = 5;                // px wire hit tolerance
//    private static final int HANDLE_HIT = 6;             // px handle pick size
//
//    /* ---------- core refs ---------- */
//    private final SystemManager model;   // read-only queries (systems/lines)
//    private final GameCommand  cmds;    // all state mutations
//    private final GamePanel     canvas;
//
//    /* ---------- state ---------- */
//    private Mode mode = Mode.IDLE;
//
//    // wiring
//    private OutputPort dragSource = null;
//
//    // bend editing
//    private Line      editLine = null;
//    private BendPoint dragBend = null;
//    private Point     hMid, hA, hB;
//
//    // context/right-click
//    private model.System contextSystem = null;
//    private Point        lastContextPoint = null;
//
//    // system move
//    private model.System movingSystem = null;
//    private Point        moveAnchorCenter = null;
//    private Point        moveStartTopLeft = null;
//    private int          incidentLenBefore = 0;
//
//    // bend-middle drag snapshot
//    private Point midStartOnDrag = null;
//    private int   lenBeforeDrag  = 0;
//
//    /* ---------- menus ---------- */
//    private final JPopupMenu lineMenu;
//    private final JPopupMenu systemMenu;
//
//    public ConnectionController(GameCommand cmds, SystemManager model, GamePanel canvas) {
//        this.cmds   = cmds;
//        this.model  = model;
//        this.canvas = canvas;
//
//        this.lineMenu   = new JPopupMenu();
//        this.systemMenu = new JPopupMenu();
//        initLinePopup();
//        initSystemPopup();
//
//        canvas.addMouseListener(this);
//        canvas.addMouseMotionListener(this);
//    }
//
//    /* ===================== Menus ===================== */
//
//    private void initSystemPopup() {
//        JMenuItem move = new JMenuItem("Move system (within radius)");
//        move.addActionListener(e -> {
//            if (contextSystem == null) return;
//
//            if (!cmds.spendCoins(15)) {
//                JOptionPane.showMessageDialog(canvas,
//                        "Not enough coins to move this system.",
//                        "Insufficient coins", JOptionPane.WARNING_MESSAGE);
//                contextSystem = null;
//                return;
//            }
//
//            movingSystem     = contextSystem;
//            moveAnchorCenter = systemCenter(movingSystem);
//
//            // snapshot before drag for budget diff
//            moveStartTopLeft  = movingSystem.getLocation();
//            incidentLenBefore = 0;
//            for (Line l : model.allLines) {
//                if (movingSystem.getOutputPorts().contains(l.getStart())
//                        || movingSystem.getInputPorts().contains(l.getEnd())) {
//                    incidentLenBefore += l.lengthPx();
//                }
//            }
//
//            mode = Mode.MOVING_SYSTEM;
//            contextSystem = null;
//        });
//        systemMenu.add(move);
//    }
//
//    private void initLinePopup() {
//        JMenuItem bend    = new JMenuItem("Add bend…");
//        JMenuItem remove  = new JMenuItem("Remove line");
//        JMenuItem center  = new JMenuItem("Bring Back to Center (20s)");
//        JMenuItem zeroAcc = new JMenuItem("Zero Acceleration (20s)");
//
//        bend.addActionListener(e -> { if (editLine != null) mode = Mode.WAIT_MIDDLE; });
//
//        remove.addActionListener(e -> {
//            if (editLine == null) return;
//            // detach endpoints (UI choice), then command removes the line
//            editLine.getStart().setLine(null);
//            editLine.getEnd().setLine(null);
//            cmds.removeLine(editLine);
//            editLine = null;
//            mode = Mode.IDLE;
//            clearHandles();
//            canvas.repaint();
//        });
//
//        center.addActionListener(e -> {
//            if (editLine == null || lastContextPoint == null) return;
//            if (!cmds.spendCoins(10)) {
//                JOptionPane.showMessageDialog(canvas,
//                        "Not enough coins for Bring Back to Center.",
//                        "Insufficient coins", JOptionPane.WARNING_MESSAGE);
//                return;
//            }
//            // add a timed recenter trigger near the click
//            editLine.addChangeCenter(lastContextPoint);
//            canvas.repaint();
//        });
//
//        zeroAcc.addActionListener(e -> {
//            if (editLine == null || lastContextPoint == null) return;
//            if (!cmds.spendCoins(20)) {
//                JOptionPane.showMessageDialog(canvas,
//                        "Not enough coins for Zero Acceleration.",
//                        "Insufficient coins", JOptionPane.WARNING_MESSAGE);
//                return;
//            }
//            // add a timed zero-acceleration zone near the click
//            editLine.addZeroAccelPoint(lastContextPoint);
//            canvas.repaint();
//        });
//
//        lineMenu.add(bend);
//        lineMenu.add(remove);
//        lineMenu.add(center);
//        lineMenu.add(zeroAcc);
//    }
//
//    /* ===================== Mouse ===================== */
//
//    @Override
//    public void mousePressed(MouseEvent e) {
//        Point p = e.getPoint();
//
//        // Right-click: open context menu (system first, else line)
//        if (SwingUtilities.isRightMouseButton(e)) {
//            model.System sys = findSystemAt(p);
//            if (sys != null) {
//                lastContextPoint = p;
//                contextSystem = sys;
//                systemMenu.show(canvas, p.x, p.y);
//                return;
//            }
//            for (Line l : model.allLines) {
//                if (l.hit(p, HIT_TOL)) {
//                    editLine = l;
//                    lastContextPoint = p;
//                    lineMenu.show(canvas, p.x, p.y);
//                    return;
//                }
//            }
//        }
//
//        // Bend FSM consumes left-clicks while active
//        if (mode != Mode.IDLE && SwingUtilities.isLeftMouseButton(e)) {
//            handleBendClick(p);
//            return;
//        }
//
//        // Normal left-click: start wiring from an available output
//        if (SwingUtilities.isLeftMouseButton(e)) {
//            OutputPort port = findOutputAt(p);
//            if (port != null && port.getLine() == null) {
//                dragSource = port;
//                canvas.showPreview(dragSource.getCenter(), p);
//            } else {
//                dragSource = null;
//            }
//        }
//
//        // Grab handle to drag bend middle if close to last created middle
//        if (mode == Mode.IDLE &&
//                SwingUtilities.isLeftMouseButton(e) &&
//                hMid != null && hMid.distance(p) < HANDLE_HIT &&
//                editLine != null && dragBend != null) {
//
//            mode = Mode.DRAG_MIDDLE;
//            // snapshot baseline for budget check on release
//            midStartOnDrag = new Point(dragBend.getMiddle()); // copy
//            lenBeforeDrag  = lengthPx(editLine);
//        }
//    }
//
//    @Override
//    public void mouseDragged(MouseEvent e) {
//        if (mode == Mode.MOVING_SYSTEM && movingSystem != null && moveAnchorCenter != null) {
//            // clamp desired center to circle around anchor
//            Point desiredCenter = e.getPoint();
//            Point clamped = clampToCircle(desiredCenter, moveAnchorCenter, MOVE_RADIUS);
//            // convert center to top-left
//            Point newLoc = new Point(clamped.x - SYS_W/2, clamped.y - SYS_H/2);
//            movingSystem.setLocation(newLoc);
//            canvas.repaint();
//            return;
//        }
//
//        if (mode == Mode.IDLE && dragSource != null) {
//            canvas.showPreview(dragSource.getCenter(), e.getPoint());
//            return;
//        }
//
//        if (mode == Mode.DRAG_MIDDLE) {
//            hMid = e.getPoint();
//            if (dragBend != null) {
//                dragBend.setMiddle(hMid);
//                editLine.invalidateLengthCache();
//            }
//            canvas.setHandles(hMid, hA, hB);
//        }
//    }
//
//    @Override
//    public void mouseReleased(MouseEvent e) {
//        // Finish system move: budget check + commit or revert
//        if (mode == Mode.MOVING_SYSTEM) {
//            Point curTopLeft = movingSystem.getLocation();
//            int dx = curTopLeft.x - moveStartTopLeft.x;
//            int dy = curTopLeft.y - moveStartTopLeft.y;
//
//            int incidentAfter = 0;
//            for (Line l : model.allLines) {
//                boolean shiftsStart = movingSystem.getOutputPorts().contains(l.getStart());
//                boolean shiftsEnd   = movingSystem.getInputPorts().contains(l.getEnd());
//                if (!shiftsStart && !shiftsEnd) continue;
//
//                if (shiftsStart && !shiftsEnd) {
//                    incidentAfter += l.lengthIfShiftStartBy(dx, dy);
//                } else if (!shiftsStart && shiftsEnd) {
//                    incidentAfter += l.lengthIfShiftEndBy(dx, dy);
//                } else {
//                    // both endpoints attached to same moving system → translation keeps length
//                    incidentAfter += l.lengthPx();
//                }
//            }
//
//            int delta = incidentAfter - incidentLenBefore;
//
//            if (!cmds.canAffordDelta(delta)) {
//                // Over budget → revert position
//                movingSystem.setLocation(moveStartTopLeft);
//                JOptionPane.showMessageDialog(canvas,
//                        "Move exceeds wire budget. Reverted.",
//                        "Move blocked", JOptionPane.WARNING_MESSAGE);
//            } else {
//                // Commit delta to global used length
//                if (delta != 0) cmds.applyWireDelta(delta);
//            }
//
//            // cleanup
//            mode = Mode.IDLE;
//            movingSystem = null;
//            moveAnchorCenter = null;
//            moveStartTopLeft = null;
//            canvas.repaint();
//            return;
//        }
//
//        // Finish bend drag
//        if (mode == Mode.DRAG_MIDDLE) {
//            // compute new length
//            editLine.invalidateLengthCache();
//            int newLen = lengthPx(editLine);
//            int delta  = newLen - lenBeforeDrag;
//
//            if (delta > 0 && !cmds.canAffordDelta(delta)) {
//                // over budget → revert the middle point
//                dragBend.setMiddle(midStartOnDrag);
//                editLine.invalidateLengthCache();
//                JOptionPane.showMessageDialog(canvas,
//                        "Moving this bend exceeds wire budget; reverted.",
//                        "Bend blocked", JOptionPane.WARNING_MESSAGE);
//            } else {
//                if (delta != 0) cmds.applyWireDelta(delta);
//                editLine.invalidateLengthCache();
//            }
//
//            mode = Mode.IDLE;
//            clearHandles();
//            canvas.repaint();
//            return;
//        }
//
//        // If we were in any other non-idle mode (e.g., waiting for bend clicks), ignore
//        if (mode != Mode.IDLE) return;
//
//        // Finish wire creation
//        if (dragSource != null) {
//            canvas.hidePreview();
//            InputPort target = findInputAt(e.getPoint());
//
//            if (target != null && target.getType() == dragSource.getType()) {
//                // Budget check before actually creating the line
//                if (!cmds.canCreateWire(dragSource, target)) {
//                    JOptionPane.showMessageDialog(canvas,
//                            "Wire length budget exceeded.\nAvailable: "
//                                    + (int)(model.getWireBudgetPx() - model.getWireUsedPx()) + " px",
//                            "Cannot create wire", JOptionPane.WARNING_MESSAGE);
//                } else {
//                    Line wire = new Line(dragSource, target);
//                    dragSource.setLine(wire);
//                    target.setLine(wire);
//                    cmds.addLine(wire); // updates used length
//                }
//            }
//            dragSource = null;
//            canvas.repaint();
//        }
//    }
//
//    /* ===================== Bend FSM ===================== */
//
//    private void handleBendClick(Point p) {
//        switch (mode) {
//            case WAIT_MIDDLE -> {
//                hMid = p;   hA = hB = null;
//                canvas.setHandles(hMid, null, null);
//                mode = Mode.WAIT_FOOT_A;
//            }
//            case WAIT_FOOT_A -> {
//                hA = p;
//                canvas.setHandles(hMid, hA, null);
//                mode = Mode.WAIT_FOOT_B;
//            }
//            case WAIT_FOOT_B -> {
//                hB = p;
//
//                int before = lengthPx(editLine);
//                BendPoint added;
//                try {
//                    added = editLine.addBendPoint(hA, hMid, hB);
//                } catch (Exception ex) {
//                    JOptionPane.showMessageDialog(canvas, ex.getMessage(),
//                            "Cannot add bend", JOptionPane.ERROR_MESSAGE);
//                    clearHandles();
//                    mode = Mode.IDLE;
//                    return;
//                }
//
//                editLine.invalidateLengthCache();
//                int after = lengthPx(editLine);
//                int delta = after - before;
//
//                if (delta > 0 && !cmds.canAffordDelta(delta)) {
//                    // revert
//                    editLine.removeBendPoint(added);
//                    editLine.invalidateLengthCache();
//                    JOptionPane.showMessageDialog(canvas,
//                            "Bend would exceed wire budget by " + delta + " px.",
//                            "Bend blocked", JOptionPane.WARNING_MESSAGE);
//                    clearHandles();
//                    mode = Mode.IDLE;
//                    canvas.repaint();
//                    return;
//                }
//
//                if (delta != 0) cmds.applyWireDelta(delta);
//                dragBend = added;                 // allow immediate middle-drag if you want
//                canvas.setHandles(hMid, hA, hB);  // or clear if you don't want persistent dots
//                mode = Mode.IDLE;
//                canvas.repaint();
//            }
//            default -> { /* no-op */ }
//        }
//    }
//
//    /* ===================== Helpers ===================== */
//
//    private OutputPort findOutputAt(Point p) {
//        for (var sys : model.getAllSystems())
//            for (OutputPort op : sys.getOutputPorts())
//                if (op.contains(p)) return op;
//        return null;
//    }
//
//    private InputPort findInputAt(Point p) {
//        for (var sys : model.getAllSystems())
//            for (InputPort ip : sys.getInputPorts())
//                if (ip.contains(p)) return ip;
//        return null;
//    }
//
//    private void clearHandles() {
//        hMid = hA = hB = null;
//        dragBend = null;
//        canvas.clearHandles();
//    }
//
//    private model.System findSystemAt(Point p) {
//        for (var sys : model.getAllSystems()) {
//            Point loc = sys.getLocation();
//            Rectangle r = new Rectangle(loc.x, loc.y, SYS_W, SYS_H);
//            if (r.contains(p)) return sys;
//        }
//        return null;
//    }
//
//    private Point systemCenter(model.System s) {
//        Point loc = s.getLocation();
//        return new Point(loc.x + SYS_W/2, loc.y + SYS_H/2);
//    }
//
//    private static Point clampToCircle(Point p, Point c, int r) {
//        double dx = p.x - c.x, dy = p.y - c.y;
//        double d  = Math.hypot(dx, dy);
//        if (d == 0 || d <= r) return p;
//        double k = r / d;
//        return new Point((int)Math.round(c.x + dx*k),
//                (int)Math.round(c.y + dy*k));
//    }
//
//    private static int lengthPx(Line l) {
//        java.util.List<Point> pts = l.getPath(6);
//        if (pts == null || pts.size() < 2) return 0;
//        double s = 0;
//        for (int i = 0; i < pts.size() - 1; i++) s += pts.get(i).distance(pts.get(i+1));
//        return (int)Math.round(s);
//    }
//}
package controller;

import common.dto.AbilityType;
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
    private final GameCommand cmds;      // offline mutations
    private final GamePanel     canvas;

    // networking
    private final boolean       online;
    private final CommandSender sender;    // only used if online
    private final AtomicLong    seq = new AtomicLong();

    /* ---------- state ---------- */
    private Mode mode = Mode.IDLE;

    // wiring
    private OutputPort dragSource = null;

    // bend editing
    private Line      editLine = null;
    private BendPoint dragBend = null;
    private Point     hMid, hA, hB;

    // context/right-click
    private model.System contextSystem = null;
    private Point        lastContextPoint = null;

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
                // OFFLINE: charge upfront (client authority) then start drag
                if (!cmds.spendCoins(15)) {
                    JOptionPane.showMessageDialog(canvas,
                            "Not enough coins to move this system.",
                            "Insufficient coins", JOptionPane.WARNING_MESSAGE);
                    contextSystem = null;
                    return;
                }
            }
            // ONLINE: no local spending; server will validate on MoveSystemCmd

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
            if (editLine == null) return;

            int sA = editLine.getStart().getParentSystem().getId();
            int sB = editLine.getEnd().getParentSystem().getId();
            int outIdx = editLine.getStart().getParentSystem().getOutputPorts().indexOf(editLine.getStart());
            int inIdx  = editLine.getEnd().getParentSystem().getInputPorts().indexOf(editLine.getEnd());

            if (online) {
                // ONLINE: send command, do not detach locally
                sender.send(new RemoveLineCmd(seq.incrementAndGet(), sA, outIdx, sB, inIdx));
            } else {
                // OFFLINE: mutate as before
                editLine.getStart().setLine(null);
                editLine.getEnd().setLine(null);
                cmds.removeLine(editLine);
            }
            editLine = null;
            mode = Mode.IDLE;
            clearHandles();
            canvas.repaint();
        });

        center.addActionListener(e -> {
            if (editLine == null || lastContextPoint == null) return;

            int sA = editLine.getStart().getParentSystem().getId();
            int sB = editLine.getEnd().getParentSystem().getId();
            int outIdx = editLine.getStart().getParentSystem().getOutputPorts().indexOf(editLine.getStart());
            int inIdx  = editLine.getEnd().getParentSystem().getInputPorts().indexOf(editLine.getEnd());

            if (online) {
                sender.send(new UseAbilityCmd(
                        seq.incrementAndGet(), AbilityType.BRING_BACK_TO_CENTER,
                        sA, outIdx, sB, inIdx,
                        new PointDTO(lastContextPoint.x, lastContextPoint.y)
                ));
            } else {
                if (!cmds.spendCoins(10)) {
                    JOptionPane.showMessageDialog(canvas,
                            "Not enough coins for Bring Back to Center.",
                            "Insufficient coins", JOptionPane.WARNING_MESSAGE);
                    return;
                }
                editLine.addChangeCenter(lastContextPoint);
                canvas.repaint();
            }
        });

        zeroAcc.addActionListener(e -> {
            if (editLine == null || lastContextPoint == null) return;

            int sA = editLine.getStart().getParentSystem().getId();
            int sB = editLine.getEnd().getParentSystem().getId();
            int outIdx = editLine.getStart().getParentSystem().getOutputPorts().indexOf(editLine.getStart());
            int inIdx  = editLine.getEnd().getParentSystem().getInputPorts().indexOf(editLine.getEnd());

            if (online) {
                sender.send(new UseAbilityCmd(
                        seq.incrementAndGet(), AbilityType.ZERO_ACCEL,
                        sA, outIdx, sB, inIdx,
                        new PointDTO(lastContextPoint.x, lastContextPoint.y)
                ));
            } else {
                if (!cmds.spendCoins(20)) {
                    JOptionPane.showMessageDialog(canvas,
                            "Not enough coins for Zero Acceleration.",
                            "Insufficient coins", JOptionPane.WARNING_MESSAGE);
                    return;
                }
                editLine.addZeroAccelPoint(lastContextPoint);
                canvas.repaint();
            }
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

        // Right-click: open context menu (system first, else line)
        if (SwingUtilities.isRightMouseButton(e)) {
            model.System sys = findSystemAt(p);
            if (sys != null) {
                lastContextPoint = p;
                contextSystem = sys;
                systemMenu.show(canvas, p.x, p.y);
                return;
            }
            for (Line l : model.allLines) {
                if (l.hit(p, HIT_TOL)) {
                    editLine = l;
                    lastContextPoint = p;
                    lineMenu.show(canvas, p.x, p.y);
                    return;
                }
            }
        }

        // Bend FSM consumes left-clicks while active
        if (mode != Mode.IDLE && SwingUtilities.isLeftMouseButton(e)) {
            handleBendClick(p);
            return;
        }

        // Normal left-click: start wiring from an available output
        if (SwingUtilities.isLeftMouseButton(e)) {
            OutputPort port = findOutputAt(p);
            if (port != null && port.getLine() == null) {
                dragSource = port;
                canvas.showPreview(dragSource.getCenter(), p);
            } else {
                dragSource = null;
            }
        }

        // Grab handle to drag bend middle if close to last created middle
        if (mode == Mode.IDLE &&
                SwingUtilities.isLeftMouseButton(e) &&
                hMid != null && hMid.distance(p) < HANDLE_HIT &&
                editLine != null && dragBend != null) {

            mode = Mode.DRAG_MIDDLE;
            // snapshot baseline for budget check on release (offline)
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

            if (online) {
                // ONLINE: show visual preview only (don’t commit real model)
                // If your GamePanel supports a ghost, use it. As a simple fallback, move then revert on release.
                movingSystem.setLocation(newLoc);
            } else {
                // OFFLINE: move live (will commit/revert on release)
                movingSystem.setLocation(newLoc);
            }
            canvas.repaint();
            return;
        }

        if (mode == Mode.IDLE && dragSource != null) {
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
        // Finish system move
        if (mode == Mode.MOVING_SYSTEM) {
            if (online) {
                // ONLINE: compute target, revert local, send command
                Point curTopLeft = movingSystem.getLocation();
                // revert local visual to start; server will move it in next snapshot
                movingSystem.setLocation(moveStartTopLeft);

                sender.send(new MoveSystemCmd(
                        seq.incrementAndGet(),
                        movingSystem.getId(),
                        curTopLeft.x, curTopLeft.y
                ));
            } else {
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
                } else {
                    if (delta != 0) cmds.applyWireDelta(delta);
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

        // Finish bend drag (middle)
        if (mode == Mode.DRAG_MIDDLE) {
            if (online) {
                // ONLINE: send move-bend; do not mutate locally
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
            } else {
                // OFFLINE: budget-aware local commit/revert
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

        // If we were in any other non-idle mode, ignore
        if (mode != Mode.IDLE) return;

        // Finish wire creation
        if (dragSource != null) {
            canvas.hidePreview();
            InputPort target = findInputAt(e.getPoint());

            if (target != null && target.getType() == dragSource.getType()) {
                int sA = dragSource.getParentSystem().getId();
                int sB = target.getParentSystem().getId();
                int outIdx = dragSource.getParentSystem().getOutputPorts().indexOf(dragSource);
                int inIdx  = target.getParentSystem().getInputPorts().indexOf(target);

                if (online) {
                    sender.send(new AddLineCmd(seq.incrementAndGet(), sA, outIdx, sB, inIdx));
                } else {
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
}
