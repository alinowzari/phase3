package common.cmd;
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
        @com.fasterxml.jackson.annotation.JsonSubTypes.Type(value=ChatCmd.class,     name="ChatCmd"),
})
public interface ClientCommand {
    long seq();
}