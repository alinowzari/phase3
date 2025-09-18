package controller.commands;

import common.cmd.ClientCommand;

public interface CommandSender {
    long nextSeq();
    void send(ClientCommand cmd);
}