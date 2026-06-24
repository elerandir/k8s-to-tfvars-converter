package com.elerandir.k8stotfvars;

import com.elerandir.k8stotfvars.cli.ConvertCommand;

import picocli.CommandLine;

/** Application entry point. */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new ConvertCommand()).execute(args);
        System.exit(exitCode);
    }
}
