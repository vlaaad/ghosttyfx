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
    void unknownShellReturnsInputsUnchanged() {
        var command = List.of("cmd.exe");
        var environment = Map.of("A", "B");

        var launcher = Shell.integrate(command, environment);

        assertSame(command, launcher.command());
        assertSame(environment, launcher.environment());
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
    void shipsPwshZipOnly() throws Exception {
        assertTrue(Shell.class.getResource("/shell/pwsh.zip") != null);
        assertTrue(Shell.class.getResource("/shell/pwsh/ghosttyfx.ps1") == null);

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
