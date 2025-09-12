package controller.commands;

import common.dto.cmd.ClientCommand;

public interface CommandSender {
    void send(ClientCommand cmd);
}