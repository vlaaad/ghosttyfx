package io.github.vlaaad.ghosttyfx;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/// Utilities for applying Ghostty shell integration to a shell launch command.
///
/// Shell integration augments supported interactive shells so the terminal can
/// observe shell lifecycle events such as prompts and command boundaries.
public final class Shell {
    private Shell() {}

    /// Applies shell integration to a launch command when the shell is supported.
    ///
    /// The returned launcher may contain a modified command, modified
    /// environment, or both. If the command is not recognized as a supported
    /// shell, the original command and environment are returned unchanged.
    ///
    /// Supported shells are Bash, Cmd, Fish, PowerShell, and Zsh.
    ///
    /// @param command the command used to start the shell
    /// @param environment the environment used to start the shell
    /// @return the command and environment to use when launching the shell
    /// @throws NullPointerException if `command` or `environment` is `null`
    /// @throws IllegalArgumentException if `command` is empty
    public static Launcher integrate(List<String> command, Map<String, String> environment) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(environment, "environment");
        if (command.isEmpty()) {
            throw new IllegalArgumentException("command must not be empty");
        }

        var executable = command.getFirst();
        var separator = Math.max(executable.lastIndexOf('/'), executable.lastIndexOf('\\'));
        var name = executable.substring(separator + 1).toLowerCase(Locale.ROOT);
        if (name.equals("bash")) {
            return integrateBash(command, environment);
        }
        if (name.equals("cmd") || name.equals("cmd.exe")) {
            return integrateCmd(command, environment);
        }
        if (name.equals("fish")) {
            return integrateFish(command, environment);
        }
        if (name.equals("pwsh") || name.equals("pwsh.exe") || name.equals("powershell") || name.equals("powershell.exe")) {
            return integratePowershell(command, environment);
        }
        if (name.equals("zsh")) {
            return integrateZsh(command, environment);
        }

        return new Launcher(command, environment);
    }

    private static Launcher integrateBash(List<String> command, Map<String, String> environment) {
        var integratedCommand = new ArrayList<String>();
        integratedCommand.add(command.getFirst());
        integratedCommand.add("--posix");

        var inject = new StringBuilder("1");
        String rcfile = null;
        for (var i = 1; i < command.size(); i++) {
            var argument = command.get(i);
            if (argument.equals("--posix") || argument.startsWith("-") && !argument.startsWith("--") && argument.contains("c")) {
                return new Launcher(command, environment);
            }
            if (argument.equals("--norc") || argument.equals("--noprofile")) {
                inject.append(' ').append(argument);
                continue;
            }
            if (argument.equals("--rcfile") || argument.equals("--init-file")) {
                if (i + 1 == command.size()) {
                    return new Launcher(command, environment);
                }
                rcfile = command.get(++i);
                continue;
            }
            integratedCommand.add(argument);
            if (argument.equals("-") || argument.equals("--")) {
                integratedCommand.addAll(command.subList(i + 1, command.size()));
                break;
            }
        }

        var script = ResourceCache.extractZip(Shell.class, "/shell/bash.zip").resolve("ghostty.bash");
        var integratedEnvironment = new LinkedHashMap<>(environment);
        var env = integratedEnvironment.get("ENV");
        if (env != null) {
            integratedEnvironment.put("GHOSTTY_BASH_ENV", env);
        }
        integratedEnvironment.put("ENV", script.toString());
        integratedEnvironment.put("GHOSTTY_BASH_INJECT", inject.toString());
        if (rcfile != null) {
            integratedEnvironment.put("GHOSTTY_BASH_RCFILE", rcfile);
        }
        if (!integratedEnvironment.containsKey("HISTFILE")) {
            integratedEnvironment.put("HISTFILE", System.getProperty("user.home") + "/.bash_history");
            integratedEnvironment.put("GHOSTTY_BASH_UNEXPORT_HISTFILE", "1");
        }
        return new Launcher(integratedCommand, integratedEnvironment);
    }

    private static Launcher integrateCmd(List<String> command, Map<String, String> environment) {
        var integratedEnvironment = new LinkedHashMap<>(environment);
        var prompt = integratedEnvironment.getOrDefault("PROMPT", "$P$G");
        integratedEnvironment.put("PROMPT", "$e]133;D$e\\$e]133;A;redraw=last;cl=line$e\\$e]9;9;$P$e\\" + prompt + "$e]133;B$e\\");
        return new Launcher(command, integratedEnvironment);
    }

    private static Launcher integrateFish(List<String> command, Map<String, String> environment) {
        var resources = ResourceCache.extractZip(Shell.class, "/shell/fish.zip");
        var integratedEnvironment = new LinkedHashMap<>(environment);
        integratedEnvironment.put("GHOSTTY_SHELL_INTEGRATION_XDG_DIR", resources.toString());
        integratedEnvironment.put("XDG_DATA_DIRS", resources + File.pathSeparator + integratedEnvironment.getOrDefault("XDG_DATA_DIRS", "/usr/local/share:/usr/share"));
        return new Launcher(command, integratedEnvironment);
    }

    private static Launcher integratePowershell(List<String> command, Map<String, String> environment) {
        var script = ResourceCache.extractZip(Shell.class, "/shell/pwsh.zip").resolve("ghosttyfx.ps1");
        var integratedCommand = new ArrayList<>(command);
        integratedCommand.add("-NoExit");
        integratedCommand.add("-Command");
        integratedCommand.add(". $env:GHOSTTYFX_PWSH_INTEGRATION");

        var integratedEnvironment = new LinkedHashMap<>(environment);
        integratedEnvironment.put("GHOSTTYFX_PWSH_INTEGRATION", script.toString());
        return new Launcher(integratedCommand, integratedEnvironment);
    }

    private static Launcher integrateZsh(List<String> command, Map<String, String> environment) {
        var zdotdir = ResourceCache.extractZip(Shell.class, "/shell/zsh.zip");
        var integratedEnvironment = new LinkedHashMap<>(environment);
        var existing = integratedEnvironment.get("ZDOTDIR");
        if (existing != null) {
            integratedEnvironment.put("GHOSTTY_ZSH_ZDOTDIR", existing);
        }
        integratedEnvironment.put("ZDOTDIR", zdotdir.toString());
        return new Launcher(command, integratedEnvironment);
    }

    /// A shell launch command and environment.
    ///
    /// @param command the command used to start the shell
    /// @param environment the environment used to start the shell
    public record Launcher(List<String> command, Map<String, String> environment) {}
}
