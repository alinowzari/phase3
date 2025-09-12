// src/main/java/common/dto/cmd/ResumeCmd.java
package common.dto.cmd;

/** For catch-up after a hiccup or reconnect. */
public record ResumeCmd(
        long seq,
        long lastTick
){}
