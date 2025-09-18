// src/main/java/controller/actions/BuildActions.java
package controller.actions;

import common.AbilityType;
import common.PointDTO;

public interface BuildActions {
    enum OpResult { OK, OVER_BUDGET, INVALID, REJECTED }

    OpResult tryAddLine(int fromSys, int fromOut, int toSys, int toIn);
    OpResult tryRemoveLine(int fromSys, int fromOut, int toSys, int toIn);

    OpResult tryAddBend(int fromSys, int fromOut, int toSys, int toIn,
                        PointDTO footA, PointDTO middle, PointDTO footB);
    OpResult tryMoveBend(int fromSys, int fromOut, int toSys, int toIn,
                         int bendIdx, PointDTO newMiddle);

    OpResult tryMoveSystem(int systemId, int x, int y);

    OpResult useAbility(AbilityType a, int fromSys, int fromOut, int toSys, int toIn, PointDTO at);

}
