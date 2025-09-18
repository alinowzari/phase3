// src/main/java/controller/actions/OnlineBuildActions.java
package controller.actions;

import common.AbilityType;
import common.PointDTO;
import common.cmd.*;
import controller.commands.CommandSender;

public final class OnlineBuildActions implements BuildActions {
    private final CommandSender sender;

    public OnlineBuildActions(CommandSender sender) { this.sender = sender; }

    @Override public OpResult tryAddLine(int fs, int fo, int ts, int ti) {
        sender.send(new AddLineCmd(sender.nextSeq(), fs, fo, ts, ti));
        return OpResult.OK;
    }
    @Override public OpResult tryRemoveLine(int fs, int fo, int ts, int ti) {
        sender.send(new RemoveLineCmd(sender.nextSeq(), fs, fo, ts, ti));
        return OpResult.OK;
    }
    @Override public OpResult tryAddBend(int fs, int fo, int ts, int ti, PointDTO a, PointDTO m, PointDTO b) {
        sender.send(new AddBendCmd(sender.nextSeq(), fs, fo, ts, ti, a, m, b));
        return OpResult.OK;
    }
    @Override public OpResult tryMoveBend(int fs, int fo, int ts, int ti, int idx, PointDTO newM) {
        sender.send(new MoveBendCmd(sender.nextSeq(), fs, fo, ts, ti, idx, newM));
        return OpResult.OK;
    }
    @Override public OpResult tryMoveSystem(int id, int x, int y) {
        sender.send(new MoveSystemCmd(sender.nextSeq(), id, x, y));
        return OpResult.OK;
    }
    @Override public OpResult useAbility(AbilityType a, int fs, int fo, int ts, int ti, PointDTO at) {
        sender.send(new UseAbilityCmd(sender.nextSeq(), a, fs, fo, ts, ti, at));
        return OpResult.OK;
    }
}
