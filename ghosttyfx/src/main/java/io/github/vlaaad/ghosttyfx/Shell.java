package io.github.vlaaad.ghosttyfx;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public final class Shell {
    private static final String PWSH_INTEGRATION = "GHOSTTYFX_PWSH_INTEGRATION";

    private Shell() {}

    public static Launcher integrate(List<String> command, Map<String, String> environment) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(environment, "environment");
        if (command.isEmpty()) {
            throw new IllegalArgumentException("command must not be empty");
        }

        var executable = command.getFirst();
        var separator = Math.max(executable.lastIndexOf('/'), executable.lastIndexOf('\\'));
        var name = executable.substring(separator + 1).toLowerCase(Locale.ROOT);
        if (name.equals("pwsh") || name.equals("pwsh.exe") || name.equals("powershell") || name.equals("powershell.exe")) {
            var script = ResourceCache.extractZip(Shell.class, "/shell/pwsh.zip").resolve("ghosttyfx.ps1");
            var integratedCommand = new ArrayList<>(command);
            integratedCommand.add("-NoExit");
            integratedCommand.add("-Command");
            integratedCommand.add(". $env:" + PWSH_INTEGRATION);

            var integratedEnvironment = new LinkedHashMap<>(environment);
            integratedEnvironment.put(PWSH_INTEGRATION, script.toString());
            return new Launcher(integratedCommand, integratedEnvironment);
        }

        return new Launcher(command, environment);
    }

    public record Launcher(List<String> command, Map<String, String> environment) {}
}
