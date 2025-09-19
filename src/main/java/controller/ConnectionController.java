package controller;

import common.AbilityType;
import common.LineDTO;
import common.PointDTO;
import controller.actions.BuildActions;
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

/**
 * Unified connection controller (online/offline) using BuildActions.
 * - All game mutations go through BuildActions (online or offline impl).
 * - Controller keeps gesture logic, previews, selections, and small UI.
 *
 * Threading: called on Swing EDT.
 */
public class ConnectionController extends MouseInputAdapter {

    /* ---------- modes ---------- */
    private enum Mode { IDLE, WAIT_MIDDLE, WAIT_FOOT_A, WAIT_FOOT_B, DRAG_MIDDLE, MOVING_SYSTEM }

    /* ---------- visuals / constraints ---------- */
    private static final int SYS_W = 90, SYS_H = 70;
    private static final int MOVE_RADIUS = 120;
    private static final int HIT_TOL = 5;
    private static final int HANDLE_HIT = 6;
    private int selectedBendIdx = -1;
    /* ---------- core refs ---------- */
    private final SystemManager model;      // read-only for hit-tests / IDs
    private final BuildActions  actions;    // unified online/offline ops
    private final GamePanel     canvas;
    private final boolean       online;     // preview policy: revert locally in online

    /* ---------- state ---------- */
    private Mode mode = Mode.IDLE;

    // unified wire drag (both modes)
    private GamePanel.PortPick startPick = null; // picked output (systemId, portIndex, x, y)
    private Point              wireStartCenter = null;

    // bend editing
    private Line      editLine = null;      // offline-selected model line
    private BendPoint dragBend = null;
    private Point     hMid, hA, hB;
    private Point     midStartOnDrag = null;
    private int       lenBeforeDrag  = 0;

    // context/right-click
    private model.System contextSystem = null;
    private Point        lastContextPoint = null;
    private LineDTO      selectedOnlineLine = null; // online HUD pick

    // system move
    private model.System movingSystem = null;
    private Point        moveAnchorCenter = null;
    private Point        moveStartTopLeft = null;

    /* ---------- menus ---------- */
    private final JPopupMenu lineMenu;
    private final JPopupMenu systemMenu;

    /** @param online true→server authoritative (preview only); false→local model authoritative */
    public ConnectionController(boolean online,
                                BuildActions actions,
                                SystemManager model,
                                GamePanel canvas) {
        this.online  = online;
        this.actions = actions;
        this.model   = model;
        this.canvas  = canvas;

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

            movingSystem     = contextSystem;
            moveAnchorCenter = systemCenter(movingSystem);
            moveStartTopLeft = movingSystem.getLocation();

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

        // ✔ enable FSM for online (selectedOnlineLine) or offline (editLine)
        bend.addActionListener(e -> {
            // Works for BOTH modes; offline can come from HUD or model geometry
            if (selectedOnlineLine != null || editLine != null) {
                mode = Mode.WAIT_MIDDLE;
                canvas.setHandles(null, null, null); // fresh handles
            }
        });

        remove.addActionListener(e -> {
            // Prefer HUD-selected line IDs; else derive from local editLine.
            if (selectedOnlineLine != null) {
                var r = actions.tryRemoveLine(
                        selectedOnlineLine.fromSystemId(),
                        selectedOnlineLine.fromOutputIndex(),
                        selectedOnlineLine.toSystemId(),
                        selectedOnlineLine.toInputIndex()
                );
                if (!online && r != BuildActions.OpResult.OK) showWarn("Remove failed (" + r + ").");
            } else if (editLine != null) {
                int sA = editLine.getStart().getParentSystem().getId();
                int sB = editLine.getEnd().getParentSystem().getId();
                int outIdx = editLine.getStart().getParentSystem().getOutputPorts().indexOf(editLine.getStart());
                int inIdx  = editLine.getEnd().getParentSystem().getInputPorts().indexOf(editLine.getEnd());
                var r = actions.tryRemoveLine(sA, outIdx, sB, inIdx);
                if (!online && r != BuildActions.OpResult.OK) showWarn("Remove failed (" + r + ").");
            }
            // cleanup selection/handles either way
            selectedOnlineLine = null;
            editLine = null;
            clearHandles();
            mode = Mode.IDLE;
            canvas.repaint();
        });

        center.addActionListener(e -> {
            LineIds ids = currentLineIds();
            if (ids == null || lastContextPoint == null) return;
            var r = actions.useAbility(
                    AbilityType.BRING_BACK_TO_CENTER,
                    ids.fromSys, ids.fromOut, ids.toSys, ids.toIn,
                    new PointDTO(lastContextPoint.x, lastContextPoint.y)
            );
            if (!online && r != BuildActions.OpResult.OK) showWarn("Ability failed (" + r + ").");
        });

        zeroAcc.addActionListener(e -> {
            LineIds ids = currentLineIds();
            if (ids == null || lastContextPoint == null) return;
            var r = actions.useAbility(
                    AbilityType.ZERO_ACCEL,
                    ids.fromSys, ids.fromOut, ids.toSys, ids.toIn,
                    new PointDTO(lastContextPoint.x, lastContextPoint.y)
            );
            if (!online && r != BuildActions.OpResult.OK) showWarn("Ability failed (" + r + ").");
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

            if (!online) {
                for (Line l : model.getAllLines()) {
                    if (l.hit(p, HIT_TOL)) {
                        editLine = l;
                        // NEW: preselect nearest bend and show handles (if any)
                        BendPoint nb = findNearestBendMiddle(editLine, p, /*tolPx=*/10);
                        if (nb != null) {
                            dragBend = nb;
                            hMid = new Point(nb.getMiddle());
                            hA   = new Point(nb.getStart());
                            hB   = new Point(nb.getEnd());
                            canvas.setHandles(hMid, hA, hB);
                        } else {
                            // clear if we clicked a segment with no bend nearby
                            dragBend = null;
                            canvas.clearHandles();
                        }
                        lastContextPoint = p;
                        lineMenu.show(canvas, p.x, p.y);
                        return;
                    }
                }
            }


            // HUD (DTO) pick as a fallback (works both modes)
// HUD (DTO) pick as a fallback (works both modes)
            var ld = canvas.pickLineAt(p, HIT_TOL);
            if (ld != null) {
                selectedOnlineLine = ld;
                lastContextPoint = p;

                // Resolve to local Line so we can find bend points (works online/offline)
                Line local = resolveLocalLineFromIds(
                        ld.fromSystemId(), ld.fromOutputIndex(),
                        ld.toSystemId(),   ld.toInputIndex());
                if (local != null) {
                    editLine = local;
                    BendPoint nb = findNearestBendMiddle(editLine, p, /*tolPx=*/10);
                    if (nb != null) {
                        dragBend = nb;
                        hMid = new Point(nb.getMiddle());
                        hA   = new Point(nb.getStart());   // ⚠ make sure to use FootA/FootB
                        hB   = new Point(nb.getEnd());
                        canvas.setHandles(hMid, hA, hB);
                    } else {
                        dragBend = null;
                        canvas.clearHandles();
                    }
                }
                lineMenu.show(canvas, p.x, p.y);
                return;
            }



        }

        // ----- Bend FSM consumes left-clicks while active -----
        if (mode != Mode.IDLE && SwingUtilities.isLeftMouseButton(e)) {
            handleBendClick(p);
            return;
        }
// ----- Bend direct-grab (ONLINE): left-click near bend middle to start drag -----
        if (online && SwingUtilities.isLeftMouseButton(e) && mode == Mode.IDLE) {
            LineDTO ld = canvas.pickLineAt(p, HIT_TOL);
            if (ld != null) {
                selectedOnlineLine = ld;
                // Pick bend from DTO (authoritative geometry)
                selectedBendIdx = nearestBendIdxFromDTO(ld, p, /*tolPx=*/HANDLE_HIT + 4);
                if (selectedBendIdx >= 0) {
                    mode = Mode.DRAG_MIDDLE;
                    midStartOnDrag = (hMid != null) ? new Point(hMid) : null; // UI-only baseline
                    lenBeforeDrag  = 0; // not used online
                    canvas.repaint();
                    return;
                } else {
                    // no bend under cursor; fall through to wiring
                    editLine = null; dragBend = null;
                }
            }
            // If you still want to support old local-probe fallback, keep it below, but it won’t help online.
        }

        if (!online && SwingUtilities.isLeftMouseButton(e) && mode == Mode.IDLE) {
            // Prefer already-selected line, else probe all lines under cursor
            Line probe = (editLine != null) ? editLine : null;
            if (probe == null) {
                for (Line l : model.getAllLines()) {
                    if (l.hit(p, HIT_TOL)) { probe = l; break; }
                }
            }
            if (probe != null) {
                BendPoint nb = findNearestBendMiddle(probe, p, /*tolPx=*/HANDLE_HIT + 4);
                if (nb != null) {
                    editLine = probe;
                    dragBend = nb;
                    hMid = new Point(nb.getMiddle());
                    hA   = new Point(nb.getStart());
                    hB   = new Point(nb.getEnd());
                    canvas.setHandles(hMid, hA, hB);

                    // Instantly enter drag mode
                    mode = Mode.DRAG_MIDDLE;
                    midStartOnDrag = new Point(dragBend.getMiddle());
                    lenBeforeDrag  = lengthPx(editLine);
                    return; // don't fall through to wiring
                }
            }
        }
        // ----- Bend direct-grab (ONLINE): left-click near bend middle to start drag -----
        if (online && SwingUtilities.isLeftMouseButton(e) && mode == Mode.IDLE) {
            // Prefer a HUD-picked line under the cursor; else reuse current selection if any
            LineDTO ld = canvas.pickLineAt(p, HIT_TOL);
            Line probe = null;
            if (ld != null) {
                selectedOnlineLine = ld;
                probe = resolveLocalLineFromIds(
                        ld.fromSystemId(), ld.fromOutputIndex(),
                        ld.toSystemId(),   ld.toInputIndex());
            } else if (editLine != null) {
                probe = editLine;
            }

            if (probe != null) {
                BendPoint nb = findNearestBendMiddle(probe, p, /*tolPx=*/HANDLE_HIT + 4);
                if (nb != null) {
                    editLine = probe;
                    dragBend = nb;
                    hMid = new Point(nb.getMiddle());
                    hA   = new Point(nb.getStart());
                    hB   = new Point(nb.getEnd());
                    canvas.setHandles(hMid, hA, hB);

                    mode = Mode.DRAG_MIDDLE;
                    midStartOnDrag = new Point(dragBend.getMiddle()); // snapshot middle for index + potential revert
                    lenBeforeDrag  = lengthPx(editLine);
                    return; // don't fall through to wiring
                }
            }
        }

        if (SwingUtilities.isLeftMouseButton(e)) {
            startPick = pickOutput(p);
            if (startPick != null) {
                wireStartCenter = new Point(startPick.x(), startPick.y());
                canvas.showPreview(wireStartCenter, p);
            } else {
                wireStartCenter = null;
                canvas.hidePreview();
            }
        }

        // ----- Grab middle-handle to drag a bend (OFFLINE preview only) -----
        if (mode == Mode.IDLE &&
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

            // visual preview (reverted in online on release)
            movingSystem.setLocation(newLoc);
            canvas.repaint();
            return;
        }

        // Ghost update for wire drag
        if (wireStartCenter != null) {
            canvas.showPreview(wireStartCenter, e.getPoint());
            return;
        }
        if (mode == Mode.DRAG_MIDDLE) {
            hMid = e.getPoint();

            if (!online && dragBend != null && editLine != null) {
                // OFFLINE: live-shape update for smooth feedback
                dragBend.setMiddle(hMid);
                editLine.invalidateLengthCache();
            }
            // ONLINE: snapshot drives wires; we still move the handle smoothly
            canvas.setHandles(hMid, hA, hB);
            canvas.repaint();
        }
    }
    @Override
    public void mouseReleased(MouseEvent e) {

        // ----- Finish wire creation (unified) -----
// ----- Finish wire creation (unified) -----
        if (wireStartCenter != null) {
            var to = pickInput(e.getPoint());
            if (startPick != null && to != null) {
                var r = actions.tryAddLine(
                        startPick.systemId(), startPick.portIndex(),
                        to.systemId(),       to.portIndex()
                );
                // 🔎 Add explicit client-side tracing so we know what happened
                System.out.println("[CLIENT] AddLine -> from("
                        + startPick.systemId() + "," + startPick.portIndex() + ") to("
                        + to.systemId() + "," + to.portIndex() + ") result=" + r);

                if (!online && r != BuildActions.OpResult.OK) {
                    showWarn("Cannot create wire (" + r + ").\nAvailable: "
                            + (int) (model.getWireBudgetPx() - model.getWireUsedPx()) + " px");
                }
            } else {
                // 🔎 If the pick failed, say it loudly so we can adjust hit-testing
                System.out.println("[CLIENT] AddLine aborted: "
                        + (startPick == null ? "no OUTPUT selected" : "no INPUT under cursor on release")
                        + "  mouse=(" + e.getX() + "," + e.getY() + ")");
            }

            canvas.hidePreview();
            startPick = null;
            wireStartCenter = null;
            canvas.repaint();
            return;
        }

        // ----- Finish system move -----
        if (mode == Mode.MOVING_SYSTEM) {
            Point curTopLeft = movingSystem.getLocation();

            if (online) {
                // Revert local preview; server authoritative
                movingSystem.setLocation(moveStartTopLeft);
            }

            var r = actions.tryMoveSystem(movingSystem.getId(), curTopLeft.x, curTopLeft.y);

            if (!online && r != BuildActions.OpResult.OK) {
                movingSystem.setLocation(moveStartTopLeft);
                showWarn("Move blocked (" + r + ").");
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
                // ONLINE: send using DTO ids + selectedBendIdx
                if (selectedOnlineLine != null && hMid != null && selectedBendIdx >= 0) {
                    actions.tryMoveBend(
                            selectedOnlineLine.fromSystemId(),
                            selectedOnlineLine.fromOutputIndex(),
                            selectedOnlineLine.toSystemId(),
                            selectedOnlineLine.toInputIndex(),
                            selectedBendIdx,
                            new PointDTO(hMid.x, hMid.y)
                    );
                }
                // cleanup for online
                selectedBendIdx = -1;
                mode = Mode.IDLE;
                clearHandles();
                canvas.repaint();
                return;
            }

            // OFFLINE: update local geometry and validate
            if (editLine != null && dragBend != null && hMid != null) {
                int sA = editLine.getStart().getParentSystem().getId();
                int sB = editLine.getEnd().getParentSystem().getId();
                int outIdx = editLine.getStart().getParentSystem().getOutputPorts().indexOf(editLine.getStart());
                int inIdx  = editLine.getEnd().getParentSystem().getInputPorts().indexOf(editLine.getEnd());
                int bendIdx = editLine.getBendPoints().indexOf(dragBend);

                var r = actions.tryMoveBend(sA, outIdx, sB, inIdx, bendIdx, new PointDTO(hMid.x, hMid.y));
                if (r != BuildActions.OpResult.OK) {
                    // revert preview
                    dragBend.setMiddle(midStartOnDrag);
                    editLine.invalidateLengthCache();
                    showWarn("Bend move rejected (" + r + ").");
                }
            }

            mode = Mode.IDLE;
            clearHandles();
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
                LineIds ids = currentLineIds();
                if (ids == null) { clearHandles(); mode = Mode.IDLE; return; }

                if (!online && editLine != null) {
                    java.awt.Point aSnap = snapToLine(editLine, hA);
                    java.awt.Point bSnap = snapToLine(editLine, hB);

                    var r = actions.tryAddBend(
                            ids.fromSys, ids.fromOut, ids.toSys, ids.toIn,
                            new PointDTO(aSnap.x, aSnap.y),
                            new PointDTO(hMid.x,  hMid.y),
                            new PointDTO(bSnap.x, bSnap.y)
                    );
                    if (r != BuildActions.OpResult.OK) showWarn("Cannot add bend (" + r + ").");
                } else {
                    var r = actions.tryAddBend(
                            ids.fromSys, ids.fromOut, ids.toSys, ids.toIn,
                            new PointDTO(hA.x, hA.y),
                            new PointDTO(hMid.x, hMid.y),
                            new PointDTO(hB.x, hB.y)
                    );
                    // Online: server authoritative
                }

                clearHandles();
                mode = Mode.IDLE;
                canvas.repaint();
            }

            default -> { /* no-op */ }
        }
    }

    /* ===================== Helpers ===================== */

    private GamePanel.PortPick pickOutput(Point p) {
        var hud = canvas.pickOutputAt(p);
        return (hud != null) ? hud : pickOutputFallback(p);
    }
    private GamePanel.PortPick pickInput(Point p) {
        var hud = canvas.pickInputAt(p);
        return (hud != null) ? hud : pickInputFallback(p);
    }

    private void clearHandles() {
        hMid = hA = hB = null;
        dragBend = null;
        canvas.clearHandles();
        // also clear any online line selection when closing the bend FSM
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

    private void showWarn(String msg) {
        JOptionPane.showMessageDialog(canvas, msg, "Action blocked", JOptionPane.WARNING_MESSAGE);
    }

    private LineIds currentLineIds() {
        if (selectedOnlineLine != null) {
            return new LineIds(selectedOnlineLine.fromSystemId(),
                    selectedOnlineLine.fromOutputIndex(),
                    selectedOnlineLine.toSystemId(),
                    selectedOnlineLine.toInputIndex());
        }
        return currentLineIdsFromEdit();
    }

    private LineIds currentLineIdsFromEdit() {
        if (editLine == null) return null;
        int sA = editLine.getStart().getParentSystem().getId();
        int sB = editLine.getEnd().getParentSystem().getId();
        int outIdx = editLine.getStart().getParentSystem().getOutputPorts().indexOf(editLine.getStart());
        int inIdx  = editLine.getEnd().getParentSystem().getInputPorts().indexOf(editLine.getEnd());
        return new LineIds(sA, outIdx, sB, inIdx);
    }

    private record LineIds(int fromSys, int fromOut, int toSys, int toIn) {}
    private static java.awt.Point snapToLine(model.Line l, java.awt.Point p) {
        java.util.List<java.awt.Point> pts = l.getPath(6);
        if (pts == null || pts.size() < 2) return p;

        double bestD = Double.POSITIVE_INFINITY;
        java.awt.Point best = p;

        for (int i = 0; i < pts.size() - 1; i++) {
            java.awt.Point a = pts.get(i), b = pts.get(i+1);
            java.awt.Point proj = projectPointOnSegment(p, a, b);
            double d = proj.distance(p);
            if (d < bestD) { bestD = d; best = proj; }
        }
        return best;
    }
    private static java.awt.Point projectPointOnSegment(java.awt.Point p, java.awt.Point a, java.awt.Point b) {
        double vx = b.x - a.x, vy = b.y - a.y;
        double wx = p.x - a.x, wy = p.y - a.y;
        double len2 = vx*vx + vy*vy;
        double t = (len2 <= 0.0) ? 0.0 : (wx*vx + wy*vy) / len2;
        t = Math.max(0.0, Math.min(1.0, t));
        return new java.awt.Point(
                (int)Math.round(a.x + t*vx),
                (int)Math.round(a.y + t*vy)
        );
    }
    // Find the bend whose middle is nearest to p (within tolPx). Returns null if none close.
    private BendPoint findNearestBendMiddle(Line l, Point p, int tolPx) {
        if (l == null) return null;
        BendPoint best = null;
        double bestD = Double.POSITIVE_INFINITY;
        for (BendPoint bp : l.getBendPoints()) {
            Point m = bp.getMiddle();
            double d = p.distance(m);
            if (d < bestD && d <= tolPx) { bestD = d; best = bp; }
        }
        return best;
    }

    // Try to resolve a HUD-picked line back into the local Line (offline only)
    private Line resolveLocalLineFromIds(int fs, int fo, int ts, int ti) {
        var sa = model.getSystemById(fs);
        var sb = model.getSystemById(ts);
        if (sa == null || sb == null) return null;
        if (fo < 0 || fo >= sa.getOutputPorts().size()) return null;
        var out = sa.getOutputPorts().get(fo);
        var l = (out != null) ? out.getLine() : null;
        if (l == null) return null;
        // sanity check that it ends where we expect
        if (l.getEnd() == null || l.getEnd().getParentSystem() == null) return null;
        var endSys = l.getEnd().getParentSystem();
        if (endSys.getId() != ts) return null;
        return l;
    }
    // === add to ConnectionController ===

    /** Build a throwaway Line with BendPoints from a LineDTO (online hit-tests only). */
    private Line shadowFromDTO(LineDTO ld) {
        if (ld == null) return null;
        // Create a fake line between current port centers (we only need the polyline)
        var sa = model.getSystemById(ld.fromSystemId());
        var sb = model.getSystemById(ld.toSystemId());
        if (sa == null || sb == null) return null;
        var out = sa.getOutputPorts().get(ld.fromOutputIndex());
        var in  = sb.getInputPorts().get(ld.toInputIndex());
        if (out == null || in == null) return null;

        Line L = new Line(out, in); // plain line; we won't add it to the model

        // Re-create bends using DTO points if present
        var bends = ld.bends(); // ← assumes LineDTO exposes bend triplets or path points
        if (bends != null) {
            for (var b : bends) { // b must carry start/middle/end (or adapt if your DTO differs)
                L.getBendPoints().add(new BendPoint(
                        new java.awt.Point(b.start().x(),  b.start().y()),
                        new java.awt.Point(b.middle().x(), b.middle().y()),
                        new java.awt.Point(b.end().x(),    b.end().y())
                ));
            }
        }
        return L;
    }

    /** Find nearest bend index on a Line built from DTO under point p. */
    private int nearestBendIdxFromDTO(LineDTO ld, Point p, int tolPx) {
        Line shadow = shadowFromDTO(ld);
        if (shadow == null) return -1;
        int best = -1; double bestD = Double.POSITIVE_INFINITY;
        var list = shadow.getBendPoints();
        for (int i = 0; i < list.size(); i++) {
            var m = list.get(i).getMiddle();
            double d = p.distance(m);
            if (d <= tolPx && d < bestD) { bestD = d; best = i; }
        }
        // cache handles for a nice UI preview
        if (best >= 0) {
            var bp = list.get(best);
            hMid = new Point(bp.getMiddle());
            hA   = new Point(bp.getStart());
            hB   = new Point(bp.getEnd());
            canvas.setHandles(hMid, hA, hB);
        }
        return best;
    }


}
