package io.github.vlaaad.ghosttyfx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class ShellTest {
    @Test
    void rejectsEmptyCommand() {
        assertThrows(IllegalArgumentException.class, () -> Shell.integrate(List.of(), Map.of()));
    }

    @Test
    void preparesTerminalEnvironmentForUnknownShell() {
        var command = List.of("nu");
        var environment = Map.of(
                "A", "B",
                "TERM", "dumb",
                "COLORTERM", "false",
                "VTE_VERSION", "12345");

        var launcher = Shell.integrate(command, environment);

        assertSame(command, launcher.command());
        assertEquals("B", launcher.environment().get("A"));
        assertEquals("xterm-256color", launcher.environment().get("TERM"));
        assertEquals("truecolor", launcher.environment().get("COLORTERM"));
        assertFalse(launcher.environment().containsKey("VTE_VERSION"));
        assertEquals("dumb", environment.get("TERM"));
        assertEquals("false", environment.get("COLORTERM"));
        assertEquals("12345", environment.get("VTE_VERSION"));
    }

    @Test
    void integratesBash() {
        var environment = Map.of("A", "B");

        var launcher = Shell.integrate(List.of("bash"), environment);

        assertEquals(List.of("bash", "--posix"), launcher.command());
        assertEquals("B", launcher.environment().get("A"));
        assertTrue(launcher.environment().containsKey("GHOSTTY_BASH_INJECT"));
        assertTrue(launcher.environment().containsKey("ENV"));
        assertTrue(Files.isRegularFile(Path.of(launcher.environment().get("ENV"))));
    }

    @Test
    void bashPreservesExistingEnvAndRcfile() {
        var launcher = Shell.integrate(
                List.of("bash", "--norc", "--rcfile", "custom.bash"),
                Map.of("ENV", "existing.env"));

        assertEquals(List.of("bash", "--posix"), launcher.command());
        assertEquals("existing.env", launcher.environment().get("GHOSTTY_BASH_ENV"));
        assertEquals("1 --norc", launcher.environment().get("GHOSTTY_BASH_INJECT"));
        assertEquals("custom.bash", launcher.environment().get("GHOSTTY_BASH_RCFILE"));
    }

    @Test
    void bashUnsupportedCommandModeSkipsShellIntegration() {
        var command = List.of("bash", "-ic", "echo hi");
        var environment = Map.of("A", "B");

        var launcher = Shell.integrate(command, environment);

        assertSame(command, launcher.command());
        assertEquals("B", launcher.environment().get("A"));
        assertEquals("xterm-256color", launcher.environment().get("TERM"));
        assertFalse(launcher.environment().containsKey("GHOSTTY_BASH_INJECT"));
    }

    @Test
    void integratesCmdPrompt() {
        var launcher = Shell.integrate(List.of("cmd.exe"), Map.of("PROMPT", "$P$G"));

        assertEquals(List.of("cmd.exe"), launcher.command());
        assertEquals("$e]133;D$e\\$e]133;A;redraw=last;cl=line$e\\$e]9;9;$P$e\\$P$G$e]133;B$e\\",
                launcher.environment().get("PROMPT"));
    }

    @Test
    void integratesFish() {
        var launcher = Shell.integrate(List.of("fish"), Map.of("XDG_DATA_DIRS", "/opt/share"));

        assertEquals(List.of("fish"), launcher.command());
        assertTrue(launcher.environment().containsKey("GHOSTTY_SHELL_INTEGRATION_XDG_DIR"));
        assertTrue(launcher.environment().get("XDG_DATA_DIRS").endsWith("/opt/share"));
        var integration = Path.of(launcher.environment().get("GHOSTTY_SHELL_INTEGRATION_XDG_DIR"));
        assertTrue(Files.isRegularFile(integration.resolve("fish/vendor_conf.d/ghostty-shell-integration.fish")));
    }

    @Test
    void integratesPwsh() {
        var environment = Map.of("A", "B");

        var launcher = Shell.integrate(List.of("pwsh"), environment);

        assertEquals(List.of("pwsh", "-NoExit", "-Command", ". $env:GHOSTTYFX_PWSH_INTEGRATION"), launcher.command());
        assertEquals("B", launcher.environment().get("A"));
        assertTrue(launcher.environment().containsKey("GHOSTTYFX_PWSH_INTEGRATION"));
        var script = Path.of(launcher.environment().get("GHOSTTYFX_PWSH_INTEGRATION"));
        assertTrue(Files.isRegularFile(script));
    }

    @Test
    void integratesPwshExeByBasename() {
        var launcher = Shell.integrate(
                List.of("C:\\Program Files\\PowerShell\\7\\pwsh.exe", "-NoLogo"),
                Map.of());

        assertEquals(List.of(
                "C:\\Program Files\\PowerShell\\7\\pwsh.exe",
                "-NoLogo",
                "-NoExit",
                "-Command",
                ". $env:GHOSTTYFX_PWSH_INTEGRATION"), launcher.command());
    }

    @Test
    void integratesPowershellExeByBasename() {
        var launcher = Shell.integrate(
                List.of("C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe", "-NoLogo"),
                Map.of());

        assertEquals(List.of(
                "C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe",
                "-NoLogo",
                "-NoExit",
                "-Command",
                ". $env:GHOSTTYFX_PWSH_INTEGRATION"), launcher.command());
        assertTrue(launcher.environment().containsKey("GHOSTTYFX_PWSH_INTEGRATION"));
    }

    @Test
    void integratesZsh() {
        var launcher = Shell.integrate(List.of("zsh"), Map.of("ZDOTDIR", "/home/me/.zsh"));

        assertEquals(List.of("zsh"), launcher.command());
        assertEquals("/home/me/.zsh", launcher.environment().get("GHOSTTY_ZSH_ZDOTDIR"));
        var zdotdir = Path.of(launcher.environment().get("ZDOTDIR"));
        assertTrue(Files.isRegularFile(zdotdir.resolve(".zshenv")));
        assertTrue(Files.isRegularFile(zdotdir.resolve("ghostty-integration")));
    }

    @Test
    void shipsShellZipsOnly() throws Exception {
        assertTrue(Shell.class.getResource("/shell/bash.zip") != null);
        assertTrue(Shell.class.getResource("/shell/fish.zip") != null);
        assertTrue(Shell.class.getResource("/shell/pwsh.zip") != null);
        assertTrue(Shell.class.getResource("/shell/zsh.zip") != null);
        assertTrue(Shell.class.getResource("/shell/bash/ghostty.bash") == null);
        assertTrue(Shell.class.getResource("/shell/fish/fish/vendor_conf.d/ghostty-shell-integration.fish") == null);
        assertTrue(Shell.class.getResource("/shell/pwsh/ghosttyfx.ps1") == null);
        assertTrue(Shell.class.getResource("/shell/zsh/ghostty-integration") == null);

        var extracted = ResourceCache.extractZip(Shell.class, "/shell/pwsh.zip");
        var script = extracted.resolve("ghosttyfx.ps1");
        assertTrue(Files.isRegularFile(script));
        var text = Files.readString(script);
        assertTrue(text.contains("[char]27"));
        assertFalse(text.contains("`e]133"));
        assertTrue(text.contains("133;A"));
        assertTrue(text.contains("redraw=0"));
        assertTrue(text.contains("133;B"));
        assertTrue(text.contains("133;C"));
        assertTrue(text.contains("133;D"));
        assertFalse(Files.isDirectory(extracted.resolve("pwsh")));
    }
}
