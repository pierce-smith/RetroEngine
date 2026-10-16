package com.retro.engine.editor.commands;

/**
 * Universal interface for executable and undoable editor commands.
 */
public interface ICommand {
    void execute();
    void undo();
    String getName();
}
