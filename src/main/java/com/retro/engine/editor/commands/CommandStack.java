package com.retro.engine.editor.commands;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Double-buffered transactional CommandStack managing Undo/Redo queues for editor state mutations.
 * Enqueues mutation intent from UI/input threads into thread-safe pending queues and flushes
 * transactions deterministically at core engine tick boundaries.
 */
public class CommandStack {

    public interface CommandListener {
        void onCommandExecuted(ICommand command);
        void onCommandUndone(ICommand command);
        void onCommandRedone(ICommand command);
        void onStackCleared();
    }

    public static class PendingTransaction {
        public enum Type { EXECUTE, UNDO, REDO }
        public final Type type;
        public final ICommand command;

        public PendingTransaction(Type type, ICommand command) {
            this.type = type;
            this.command = command;
        }
    }

    private final Queue<PendingTransaction> pendingQueue = new ConcurrentLinkedQueue<>();
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

    /**
     * Enqueues a command for double-buffered transactional execution at the next frame boundary.
     */
    public void executeCommand(ICommand cmd) {
        if (cmd == null) return;
        pendingQueue.add(new PendingTransaction(PendingTransaction.Type.EXECUTE, cmd));
    }

    /**
     * Executes a command immediately on the caller thread.
     */
    public void executeCommandNow(ICommand cmd) {
        if (cmd == null) return;
        cmd.execute();
        undoStack.push(cmd);
        redoStack.clear();
        if (undoStack.size() > MAX_CAPACITY) {
            undoStack.removeLast();
        }
        System.out.println("[CommandStack] Executed immediately: " + cmd.getName());

        for (CommandListener listener : listeners) {
            try {
                listener.onCommandExecuted(cmd);
            } catch (Exception e) {
                System.err.println("[CommandStack] Error in command listener: " + e.getMessage());
            }
        }
    }

    /**
     * Enqueues an undo request for double-buffered execution at the next frame boundary.
     */
    public boolean undo() {
        pendingQueue.add(new PendingTransaction(PendingTransaction.Type.UNDO, null));
        return true;
    }

    /**
     * Enqueues a redo request for double-buffered execution at the next frame boundary.
     */
    public boolean redo() {
        pendingQueue.add(new PendingTransaction(PendingTransaction.Type.REDO, null));
        return true;
    }

    /**
     * Flushes all pending double-buffered transactions at the frame boundary on the main engine loop thread.
     */
    public void flushPendingCommands() {
        while (!pendingQueue.isEmpty()) {
            PendingTransaction tx = pendingQueue.poll();
            if (tx == null) continue;

            switch (tx.type) {
                case EXECUTE -> executeTransactionNow(tx.command);
                case UNDO -> undoTransactionNow();
                case REDO -> redoTransactionNow();
            }
        }
    }

    private void executeTransactionNow(ICommand cmd) {
        if (cmd == null) return;
        try {
            cmd.execute();
            undoStack.push(cmd);
            redoStack.clear();
            if (undoStack.size() > MAX_CAPACITY) {
                undoStack.removeLast();
            }
            System.out.println("[CommandStack] Flushed Execution: " + cmd.getName());

            for (CommandListener listener : listeners) {
                try {
                    listener.onCommandExecuted(cmd);
                } catch (Exception e) {
                    System.err.println("[CommandStack] Error in command listener: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            System.err.println("[CommandStack] Exception executing command '" + cmd.getName() + "': " + e.getMessage());
            e.printStackTrace();
        }
    }

    private boolean undoTransactionNow() {
        if (undoStack.isEmpty()) {
            System.out.println("[CommandStack] Nothing to undo.");
            return false;
        }
        try {
            ICommand cmd = undoStack.pop();
            cmd.undo();
            redoStack.push(cmd);
            System.out.println("[CommandStack] Flushed Undo: " + cmd.getName());

            for (CommandListener listener : listeners) {
                try {
                    listener.onCommandUndone(cmd);
                } catch (Exception e) {
                    System.err.println("[CommandStack] Error in undo listener: " + e.getMessage());
                }
            }
            return true;
        } catch (Exception e) {
            System.err.println("[CommandStack] Exception during undo: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    private boolean redoTransactionNow() {
        if (redoStack.isEmpty()) {
            System.out.println("[CommandStack] Nothing to redo.");
            return false;
        }
        try {
            ICommand cmd = redoStack.pop();
            cmd.execute();
            undoStack.push(cmd);
            System.out.println("[CommandStack] Flushed Redo: " + cmd.getName());

            for (CommandListener listener : listeners) {
                try {
                    listener.onCommandRedone(cmd);
                } catch (Exception e) {
                    System.err.println("[CommandStack] Error in redo listener: " + e.getMessage());
                }
            }
            return true;
        } catch (Exception e) {
            System.err.println("[CommandStack] Exception during redo: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    public boolean canUndo() {
        return !undoStack.isEmpty() || pendingQueue.stream().anyMatch(tx -> tx.type == PendingTransaction.Type.EXECUTE || tx.type == PendingTransaction.Type.REDO);
    }

    public boolean canRedo() {
        return !redoStack.isEmpty() || pendingQueue.stream().anyMatch(tx -> tx.type == PendingTransaction.Type.UNDO);
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
        pendingQueue.clear();
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
