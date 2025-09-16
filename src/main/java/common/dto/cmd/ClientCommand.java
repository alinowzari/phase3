// common/dto/cmd/ClientCommand.java
package common.dto.cmd;

import com.fasterxml.jackson.annotation.*;

//@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "t")
//@JsonSubTypes({
//        @JsonSubTypes.Type(value = AddLineCmd.class,    name = "addLine"),
//        @JsonSubTypes.Type(value = RemoveLineCmd.class, name = "removeLine"),
//        @JsonSubTypes.Type(value = MoveSystemCmd.class, name = "moveSystem"),
//        @JsonSubTypes.Type(value = AddBendCmd.class,    name = "addBend"),
//        @JsonSubTypes.Type(value = MoveBendCmd.class,   name = "moveBend"),
//        @JsonSubTypes.Type(value = UseAbilityCmd.class, name = "useAbility"),
//        @JsonSubTypes.Type(value = ReadyCmd.class,      name = "ready"),
//        @JsonSubTypes.Type(value = LaunchCmd.class,     name = "launch")
//})
@com.fasterxml.jackson.annotation.JsonTypeInfo(
        use = com.fasterxml.jackson.annotation.JsonTypeInfo.Id.NAME,
        include = com.fasterxml.jackson.annotation.JsonTypeInfo.As.PROPERTY,
        property = "type",
        visible = true
)
@com.fasterxml.jackson.annotation.JsonSubTypes({
        @com.fasterxml.jackson.annotation.JsonSubTypes.Type(value=AddLineCmd.class,    name="AddLineCmd"),
        @com.fasterxml.jackson.annotation.JsonSubTypes.Type(value=RemoveLineCmd.class, name="RemoveLineCmd"),
        @com.fasterxml.jackson.annotation.JsonSubTypes.Type(value=MoveSystemCmd.class, name="MoveSystemCmd"),
        @com.fasterxml.jackson.annotation.JsonSubTypes.Type(value=AddBendCmd.class,    name="AddBendCmd"),
        @com.fasterxml.jackson.annotation.JsonSubTypes.Type(value=MoveBendCmd.class,   name="MoveBendCmd"),
        @com.fasterxml.jackson.annotation.JsonSubTypes.Type(value=UseAbilityCmd.class, name="UseAbilityCmd"),
        @com.fasterxml.jackson.annotation.JsonSubTypes.Type(value=ReadyCmd.class,      name="ReadyCmd"),
        @com.fasterxml.jackson.annotation.JsonSubTypes.Type(value=LaunchCmd.class,     name="LaunchCmd"),
})
public interface ClientCommand {
    long seq();
}