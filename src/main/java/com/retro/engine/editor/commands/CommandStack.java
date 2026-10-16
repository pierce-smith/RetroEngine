package com.retro.engine.editor.commands;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Manages Undo/Redo stacks for editor state mutation with stack capacity limits.
 */
public class CommandStack {
    private final Deque<ICommand> undoStack = new ArrayDeque<>();
    private final Deque<ICommand> redoStack = new ArrayDeque<>();
    private static final int MAX_CAPACITY = 50;

    public void executeCommand(ICommand cmd) {
        if (cmd == null) return;
        cmd.execute();
        undoStack.push(cmd);
        redoStack.clear();
        if (undoStack.size() > MAX_CAPACITY) {
            undoStack.removeLast();
        }
        assert (undoStack.size() <= 50) : "[FATAL] CommandStack undo stack exceeded maximum capacity limit of 50";
        System.out.println("[CommandStack] Executed: " + cmd.getName());
    }

    public boolean undo() {
        if (undoStack.isEmpty()) {
            System.out.println("[CommandStack] Nothing to undo.");
            return false;
        }
        ICommand cmd = undoStack.pop();
        cmd.undo();
        redoStack.push(cmd);
        System.out.println("[CommandStack] Undid: " + cmd.getName());
        return true;
    }

    public boolean redo() {
        if (redoStack.isEmpty()) {
            System.out.println("[CommandStack] Nothing to redo.");
            return false;
        }
        ICommand cmd = redoStack.pop();
        cmd.execute();
        undoStack.push(cmd);
        System.out.println("[CommandStack] Redid: " + cmd.getName());
        return true;
    }

    public boolean canUndo() {
        return !undoStack.isEmpty();
    }

    public boolean canRedo() {
        return !redoStack.isEmpty();
    }

    public void clear() {
        undoStack.clear();
        redoStack.clear();
    }
}
