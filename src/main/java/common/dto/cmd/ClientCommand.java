// src/main/java/common/dto/cmd/ClientCommand.java
package common.dto.cmd;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(AddLineCmd.class),
        @JsonSubTypes.Type(RemoveLineCmd.class),
        @JsonSubTypes.Type(MoveSystemCmd.class),
        @JsonSubTypes.Type(AddBendCmd.class),
        @JsonSubTypes.Type(MoveBendCmd.class),
        @JsonSubTypes.Type(ReadyCmd.class),
        @JsonSubTypes.Type(LaunchCmd.class),
        @JsonSubTypes.Type(UseAbilityCmd.class),
        @JsonSubTypes.Type(ResumeCmd.class)
})
public interface ClientCommand {
    long seq(); // client sequence for de-dup/ack
}
