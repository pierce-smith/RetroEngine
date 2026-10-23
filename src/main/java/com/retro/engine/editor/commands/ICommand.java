package com.retro.engine.editor.commands;

/**
 * Transactional command interface for double-buffered editor state mutations.
 */
public interface ICommand {
    void execute();
    void undo();
    String getName();
}
