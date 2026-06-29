package com.elerandir.k8stotfvars;

import com.elerandir.k8stotfvars.cli.ConvertCommand;

import lombok.experimental.UtilityClass;

import picocli.CommandLine;

/** Application entry point. */
@UtilityClass
public class Main {

    public void main(String[] args) {
        int exitCode = new CommandLine(new ConvertCommand()).execute(args);
        System.exit(exitCode);
    }
}
