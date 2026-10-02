package com.retroengine;

public class Main {
    public static void main(String[] args) {
        try {
            EngineShell engine = new EngineShell();
            engine.run();
        } catch (Exception e) {
            System.err.println("CRITICAL FAULT: Engine loop unmounted due to structural error.");
            e.printStackTrace();
            System.exit(-1);
        }
    }
}
