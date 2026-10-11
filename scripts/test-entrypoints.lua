-- Exercise the public tasks with controlled tool downloads and child processes.
-- This runs in its own dotcmd process so mocks cannot affect the real Maven build.
local original_host = host
local calls = {}
local display = ":1"
local tool_root = "/tools with spaces"
fetch = function(options)
    assert(options.sha256:match("^[0-9a-f]+$") and #options.sha256 == 64)
    return tool_root .. (options.url:match("apache%-maven") and "/maven" or "/jdk")
end
fs = {}
-- fs.list returns names, not array indices.
fs.list = function()
    local done = false
    return function()
        if not done then done = true; return "plexus-classworlds-2.9.0.jar" end
    end
end
os.getenv = function(name) if name == "DISPLAY" then return display end end
exec = function(command) calls[#calls + 1] = command; return { exit_code = 0 } end

local function launch(task_name, expected, ...)
    calls = {}
    task(task_name, ...)
    assert(#calls == 1, "Task should launch one Maven process: " .. task_name)
    local command = calls[1]
    local launcher = #command - #expected - 1
    assert(command[launcher] == "org.codehaus.plexus.classworlds.launcher.Launcher",
        "Maven must use the pinned Java launcher")
    assert(command[launcher + 1] == "-Dghosttyfx.dotcmd.executable=" .. original_host.executable)
    assert(#command == launcher + 1 + #expected, "Maven argument count changed")
    for i, value in ipairs(expected) do
        assert(command[launcher + 1 + i] == value, "Maven argument changed: " .. value)
    end
    assert(command.env.JAVA_HOME:match("^" .. tool_root))
    assert(type(command.env.PATH) == "function", "Pinned tools must precede system tools")
    return command
end

for _, os_name in ipairs { "linux", "macos", "windows" } do
    host = {
        os = os_name, arch = "x64", exe_suffix = os_name == "windows" and ".exe" or "",
        executable = original_host.executable, project_dir = original_host.project_dir
    }
    local command = launch("mvn", { "validate", "-Dexample=a b;$literal", "" },
        "validate", "-Dexample=a b;$literal", "")
    assert(command[1] == tool_root .. "/jdk/bin/java" .. host.exe_suffix,
        "Maven must launch Java directly, including on Windows")
    launch("test", { "clean", "test", "-B" }, "-B")
    launch("manual-app", { "-pl", "ghosttyfx-manual-app", "-am", "-Pmanual-app", "compile", "-o" }, "-o")
    launch("nord-app", { "-pl", "ghosttyfx-nord-app", "-am", "-Pnord-app", "compile" })
end

host.os, host.exe_suffix = "linux", ""
display = nil
assert(launch("test", { "clean", "test" })[1] == "xvfb-run", "Headless tests need a virtual display")
local command = launch("manual-app", { "-pl", "ghosttyfx-manual-app", "-am", "-Pmanual-app", "compile" })
assert(command[1] == "xvfb-run" and command[2] == "-a" and command[3]:match("/bin/java$"))
assert(launch("mvn", { "-P", "manual-app", "compile" }, "-P", "manual-app", "compile")[1] == "xvfb-run")
assert(launch("mvn", { "--activate-profiles=nord-app", "compile" },
    "--activate-profiles=nord-app", "compile")[1] == "xvfb-run")
assert(launch("mvn", { "validate" }, "validate")[1] ~= "xvfb-run", "Validation needs no display")
assert(launch("mvn", { "package", "-DskipTests" }, "package", "-DskipTests")[1] ~= "xvfb-run")
assert(launch("mvn", { "test", "-DskipTests=false" }, "test", "-DskipTests=false")[1] == "xvfb-run")
display = ":1"
assert(launch("test", { "clean", "test" })[1] ~= "xvfb-run", "Reuse an existing display")
host.os, host.arch = "windows", "arm64"
local ok = pcall(task, "mvn", "--version")
assert(not ok, "Fail before downloading tools for an unsupported native platform")

-- Refresh pinned sources before looking at files or cached native bundles.
host = original_host
calls = {}
local refreshed, commit_queried = false, false
exec = function(command)
    calls[#calls + 1] = command
    if command[2] == "submodule" and command[3] == "update" then refreshed = true end
    if command[4] == "rev-parse" then
        assert(refreshed, "Read the source commit only after refreshing submodules")
        commit_queried = true
        return { stdout = string.rep("a", 40) .. "\n" }
    end
    return { stdout = "" }
end
fs.stat = function()
    assert(refreshed, "Initialize missing submodules before inspecting their files")
    return { type = "file" }
end
fs.read = function(path)
    assert(refreshed and commit_queried, "Refresh sources before consulting the native cache")
    assert(path:sub(1, #host.cache_dir) == host.cache_dir, "Native cache must stay outside the checkout")
    error("fixture cache reached", 0)
end
local id = host.os .. "-" .. ({ x64 = "x86_64", arm64 = "aarch64" })[host.arch]
local generated, failure = pcall(task, "generate-platform", id, "ghosttyfx-" .. id, "/unused target")
assert(not generated and failure == "fixture cache reached", tostring(failure))
assert(table.concat(calls[1], " ") == "git submodule sync --recursive")
assert(table.concat(calls[2], " ") == "git submodule update --init --recursive")

-- A failed refresh must stop generation without consulting the old source/cache.
fs.stat = function() error("Inspected stale source after refresh failed", 0) end
fs.read = function() error("Inspected stale cache after refresh failed", 0) end
for _, operation in ipairs { "sync", "update" } do
    exec = function(command)
        if command[3] == operation then error("fixture refresh failed", 0) end
        return { stdout = "" }
    end
    generated, failure = pcall(task, "generate-platform", id, "ghosttyfx-" .. id, "/unused target")
    assert(not generated and failure == "fixture refresh failed", tostring(failure))
end
print("dotcmd entrypoint checks passed")
