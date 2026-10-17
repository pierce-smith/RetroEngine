package com.retro.engine.editor.commands;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Manages Undo/Redo stacks for editor state mutation with stack capacity limits,
 * compound macro commands, history inspection, and script execution event hooks.
 */
public class CommandStack {

    public interface CommandListener {
        void onCommandExecuted(ICommand command);
        void onCommandUndone(ICommand command);
        void onCommandRedone(ICommand command);
        void onStackCleared();
    }

    private final Deque<ICommand> undoStack = new ArrayDeque<>();
    private final Deque<ICommand> redoStack = new ArrayDeque<>();
    private final List<CommandListener> listeners = new CopyOnWriteArrayList<>();
    private static final int MAX_CAPACITY = 50;

    public void addListener(CommandListener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public void removeListener(CommandListener listener) {
        if (listener != null) {
            listeners.remove(listener);
        }
    }

    public void executeCommand(ICommand cmd) {
        if (cmd == null) return;
        cmd.execute();
        undoStack.push(cmd);
        redoStack.clear();
        if (undoStack.size() > MAX_CAPACITY) {
            undoStack.removeLast();
        }
        System.out.println("[CommandStack] Executed: " + cmd.getName());

        for (CommandListener listener : listeners) {
            try {
                listener.onCommandExecuted(cmd);
            } catch (Exception e) {
                System.err.println("[CommandStack] Error in command listener: " + e.getMessage());
            }
        }
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

        for (CommandListener listener : listeners) {
            try {
                listener.onCommandUndone(cmd);
            } catch (Exception e) {
                System.err.println("[CommandStack] Error in undo listener: " + e.getMessage());
            }
        }
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

        for (CommandListener listener : listeners) {
            try {
                listener.onCommandRedone(cmd);
            } catch (Exception e) {
                System.err.println("[CommandStack] Error in redo listener: " + e.getMessage());
            }
        }
        return true;
    }

    public boolean canUndo() {
        return !undoStack.isEmpty();
    }

    public boolean canRedo() {
        return !redoStack.isEmpty();
    }

    public int getUndoCount() {
        return undoStack.size();
    }

    public int getRedoCount() {
        return redoStack.size();
    }

    public List<String> getUndoHistoryNames() {
        List<String> names = new ArrayList<>();
        for (ICommand cmd : undoStack) {
            names.add(cmd.getName());
        }
        return Collections.unmodifiableList(names);
    }

    public List<String> getRedoHistoryNames() {
        List<String> names = new ArrayList<>();
        for (ICommand cmd : redoStack) {
            names.add(cmd.getName());
        }
        return Collections.unmodifiableList(names);
    }

    public void clear() {
        undoStack.clear();
        redoStack.clear();
        for (CommandListener listener : listeners) {
            try {
                listener.onStackCleared();
            } catch (Exception e) {
                System.err.println("[CommandStack] Error in clear listener: " + e.getMessage());
            }
        }
    }
}
