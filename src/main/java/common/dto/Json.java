package common.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.module.paramnames.ParameterNamesModule;
// if you use JavaTime/etc, findAndRegisterModules() will pick them up.

public final class Json {
    private Json() {}
    public static ObjectMapper mapper() {
        var M = new ObjectMapper();
        M.findAndRegisterModules();
        M.registerModule(new ParameterNamesModule());

        // If you DO NOT use @JsonTypeName/@JsonSubTypes on commands,
        // uncomment the explicit registrations below so polymorphism works.
    /*
    var nt = com.fasterxml.jackson.databind.jsontype.NamedType.class;
    M.registerSubtypes(
      new nt(common.dto.cmd.AddLineCmd.class,    "addLine"),
      new nt(common.dto.cmd.RemoveLineCmd.class, "removeLine"),
      new nt(common.dto.cmd.MoveSystemCmd.class, "moveSystem"),
      new nt(common.dto.cmd.AddBendCmd.class,    "addBend"),
      new nt(common.dto.cmd.MoveBendCmd.class,   "moveBend"),
      new nt(common.dto.cmd.UseAbilityCmd.class, "useAbility"),
      new nt(common.dto.cmd.ReadyCmd.class,      "ready"),
      new nt(common.dto.cmd.LaunchCmd.class,     "launch")
    );
    */
        return M;
    }
}
