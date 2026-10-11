local architectures = { x64 = "x86_64", arm64 = "aarch64" }
local platform_id = host.os .. "-" .. architectures[host.arch]
local platforms = {
    linux = {
        library = "lib/libghostty-vt.so.0.1.0", suffix = ".so", jdk_suffix = ".tar.gz", zig_suffix = ".tar.xz",
        x64 = {
            target = "x86_64-linux-gnu",
            zig_sha = "70e49664a74374b48b51e6f3fdfbf437f6395d42509050588bd49abe52ba3d00",
            jextract = { arch = "linux-x64", sha = "d0cc481abc1adb16fb9514e1c5e0bfc08d38c29228bece667fb5054ceaffaa42" },
            jdk_sha = "8dc3f4451b0affe00a6d4da0aa2331240bf7d142a353ff529673501f8bd09c4a"
        },
        arm64 = {
            target = "aarch64-linux-gnu",
            zig_sha = "ea4b09bfb22ec6f6c6ceac57ab63efb6b46e17ab08d21f69f3a48b38e1534f17",
            jextract = { arch = "linux-aarch64", sha = "0e25e6f6efa042f8758eaec65a873887fd2247fcf2e3e22dcfd7e4179fc8b0ae" },
            jdk_sha = "9bc4b2eb7be2b7d1e481bf83c6c86db5375b5e56925b083d3d4ef210dcf6e0b8"
        },
    },
    macos = {
        library = "lib/libghostty-vt.0.1.0.dylib", suffix = ".dylib", jdk_suffix = ".tar.gz", zig_suffix = ".tar.xz",
        x64 = {
            target = "x86_64-macos",
            zig_sha = "0387557ed1877bc6a2e1802c8391953baddba76081876301c522f52977b52ba7",
            jextract = { arch = "macos-x64", sha = "6ae7a46e7e7b56f077ab72623c0a894a8d525d5b698c90785b97c241f95a99b1" },
            jdk_sha = "461e34d4caac11f73aeceb7cd82b2818dae865727580651526b24cb14a1f0d85"
        },
        arm64 = {
            target = "aarch64-macos",
            zig_sha = "b23d70deaa879b5c2d486ed3316f7eaa53e84acf6fc9cc747de152450d401489",
            jextract = { arch = "macos-aarch64", sha = "3dd1dd1bde059d271739e2cc2290c64f93f85488c86c01e566c0e374eece798f" },
            jdk_sha = "0795aa8b3631839a8ab41a94cb94ea56727fa62e2749919553d8965ab3d21b6f"
        },
    },
    windows = {
        library = "bin/ghostty-vt.dll", suffix = ".dll", jdk_suffix = ".zip", zig_suffix = ".zip",
        x64 = {
            target = "x86_64-windows-msvc",
            zig_sha = "68659eb5f1e4eb1437a722f1dd889c5a322c9954607f5edcf337bc3684a75a7e",
            jextract = { arch = "windows-x64", sha = "b03533eb6b249a154752b7c7bf93cdb8c147cf2f9699422e615e84b7fb652872" },
            jdk_sha = "704e5d6ff0b6de67461d12403a9864d211fa9c64187efa185dfa70dfbb130f33"
        },
    },
}

local function tool(url, hash)
    return fetch {
        url = url, sha256 = hash,
        prepare = function(input, output)
            extract { path = input, to = output, strip_components = 1 }
        end
    }
end

local function jdk()
    local platform = assert(platforms[host.os] and platforms[host.os][host.arch], "Unsupported platform: " .. platform_id)
    local arch = host.arch == "x64" and "amd64" or "aarch64"
    return tool("https://github.com/bell-sw/Liberica/releases/download/25.0.2%2B12/"
        .. "bellsoft-jdk25.0.2%2B12-" .. host.os .. "-" .. arch .. platforms[host.os].jdk_suffix, platform.jdk_sha)
end

local function copy_tree(source, destination)
    fs.mkdir(destination)
    for name in fs.list(source) do
        local input, output = source .. "/" .. name, destination .. "/" .. name
        if fs.stat(input).type == "directory" then
            copy_tree(input, output)
        else
            fs.write(output, assert(fs.read(input)))
        end
    end
end

local function windows_environment()
    local vswhere = "C:/Program Files (x86)/Microsoft Visual Studio/Installer/vswhere.exe"
    assert(fs.stat(vswhere), "Native Windows builds require Visual Studio Build Tools and the Windows SDK.")
    local installation = exec { vswhere, "-latest", "-products", "*", "-requires",
        "Microsoft.VisualStudio.Component.VC.Tools.x86.x64", "-property", "installationPath",
        stdout = "capture" }.stdout:gsub("%s+$", "")
    assert(installation ~= "", "Native Windows builds require Visual Studio Build Tools and the Windows SDK.")
    local script = installation .. "\\VC\\Auxiliary\\Build\\vcvars64.bat"
    -- Send shell syntax through stdin so Windows argv escaping cannot alter its quotes.
    local process <close> = spawn { "cmd.exe", "/d", "/q", stdin = "pipe", stdout = "capture" }
    process.stdin:write('@echo off\r\ncall "' .. script .. '" >nul\r\nif errorlevel 1 exit 1\r\nset\r\nexit\r\n')
    process.stdin:close()
    local output = process:wait().stdout
    local environment = {}
    for line in output:gmatch("[^\r\n]+") do
        local key, value = line:match("^([^=]+)=(.*)$")
        if key then environment[key:upper() == "PATH" and "PATH" or key] = value end
    end
    return environment
end

local function generate_platform(id, artifact_id, build_dir)
    local os_name, architecture = id:match("^(%a+)%-(.+)$")
    local arch = ({ x86_64 = "x64", aarch64 = "arm64" })[architecture]
    local os_platform = assert(platforms[os_name], "Unsupported platform: " .. id)
    local platform = assert(os_platform[arch], "Unsupported platform: " .. id)
    assert(artifact_id == "ghosttyfx-" .. id, "Artifact ID does not match platform: " .. artifact_id)
    exec { "git", "submodule", "sync", "--recursive" }
    exec { "git", "submodule", "update", "--init", "--recursive" }
    assert(fs.stat("ghostty/build.zig"), "Missing ghostty/build.zig after submodule initialization.")
    local commit = exec { "git", "-C", "ghostty", "rev-parse", "HEAD", stdout = "capture" }.stdout:gsub("%s+$", "")
    -- Include local edits and the complete recipe, rather than trusting the submodule commit alone.
    local source = exec { "git", "-C", "ghostty", "diff", "--binary", "HEAD", stdout = "capture" }.stdout
    for name in exec { "git", "-C", "ghostty", "ls-files", "--others", "--exclude-standard", "-z",
        stdout = "capture" }.stdout:gmatch("([^%z]+)%z") do
        source = source .. name .. "\0" .. sha256 { path = "ghostty/" .. name }
    end
    local metadata = "artifactId=" .. artifact_id .. "\nplatform=" .. id .. "\nghostty.commit=" .. commit
        .. "\nghostty.source=" .. sha256 { bytes = source } .. "\nghosttyfx.recipe=" .. sha256 { path = ".cmd.lua" }
        .. "\nghostty.optimize=ReleaseFast\nghostty.vtFeatures=-snapshot,-color,-glyph-protocol"
        .. "\njextract.package=io.github.vlaaad.ghostty.bindings\njextract.headerClass=ghostty_vt_h"
        .. "\njextract.version=jextract-25\nzig.version=zig-" .. architecture .. "-" .. os_name .. "-0.16.0\nzig.target=" .. platform.target .. "\n"
    local function valid_bundle(directory)
        return fs.read(directory .. "/resources/metadata.properties") == metadata
            and fs.stat(directory .. "/src/io/github/vlaaad/ghostty/bindings/ghostty_vt_h.java")
            and fs.stat(directory .. "/resources/native/" .. id .. ".zip")
    end
    local build_root = host.cache_dir .. "/ghosttyfx/" .. sha256 { bytes = host.project_dir }
    local bundle = build_root .. "/bundles/" .. id
    -- Release jobs exchange bundles produced by this same recipe on their native hosts.
    local staged_bundle = "dist/" .. commit .. "/" .. artifact_id
    if not valid_bundle(bundle) and valid_bundle(staged_bundle) then bundle = staged_bundle end
    if not valid_bundle(bundle) then
        assert(id == platform_id, "Missing compatible release bundle for " .. id .. "; generate it on its native host.")
        local environment = host.os == "windows" and windows_environment() or {}
        local zig = tool("https://ziglang.org/download/0.16.0/zig-" .. architectures[host.arch] .. "-" .. host.os .. "-0.16.0" .. os_platform.zig_suffix, platform.zig_sha)
        local jextract = tool("https://download.java.net/java/early_access/jextract/25/2/"
            .. "openjdk-25-jextract+2-4_" .. platform.jextract.arch .. "_bin.tar.gz", platform.jextract.sha)
        environment.ZIG_LOCAL_CACHE_DIR = build_root .. "/zig-cache"
        environment.ZIG_GLOBAL_CACHE_DIR = host.cache_dir .. "/ghosttyfx/zig-0.16.0"
        environment.PATH = zig .. host.path_sep .. (environment.PATH or os.getenv("PATH") or "")
        exec { zig .. "/zig" .. host.exe_suffix, "build", "-Dtarget=" .. platform.target,
            "-Doptimize=ReleaseFast", "-Dapp-runtime=none", "-Demit-lib-vt=true",
            "-Dvt-features=-snapshot,-color,-glyph-protocol", "--prefix", build_root .. "/zig-out", cwd = "ghostty", env = environment }
        local staging = bundle .. ".tmp"
        fs.remove(staging, { recursive = true })
        fs.mkdir(staging .. "/src")
        local include = build_root .. "/zig-out/include"
        exec { jextract .. "/runtime/bin/java" .. host.exe_suffix,
            "-m", "org.openjdk.jextract/org.openjdk.jextract.JextractTool", "-I", include,
            "-t", "io.github.vlaaad.ghostty.bindings", "--header-class-name", "ghostty_vt_h",
            "--output", staging .. "/src", include .. "/ghostty/vt.h" }
        local library = build_root .. "/zig-out/" .. os_platform.library
        if host.os == "linux" then exec { "strip", "--strip-unneeded", library } end
        local name = "libghostty-vt-" .. id .. os_platform.suffix
        fs.mkdir(staging .. "/resources/native")
        fs.mkdir(staging .. "/library")
        fs.write(staging .. "/library/" .. name, assert(fs.read(library)))
        exec { jdk() .. "/bin/jar" .. host.exe_suffix, "--create", "--file",
            staging .. "/resources/native/" .. id .. ".zip", "--no-compress", "--no-manifest",
            "--date=1980-01-01T00:00:02Z", "-C", staging .. "/library", name }
        fs.remove(staging .. "/library", { recursive = true })
        fs.write(staging .. "/resources/metadata.properties", metadata)
        fs.remove(bundle, { recursive = true })
        fs.rename(staging, bundle)
    end
    if fs.read(build_dir .. "/generated-resources/ghosttyfx/metadata.properties") == metadata
        and fs.stat(build_dir .. "/generated-sources/jextract/io/github/vlaaad/ghostty/bindings/ghostty_vt_h.java")
        and fs.stat(build_dir .. "/generated-resources/ghosttyfx/native/" .. id .. ".zip")
        and fs.stat(build_dir .. "/ghosttyfx-artifact/resources/native/" .. id .. ".zip") then
        return
    end
    for destination, source_dir in pairs {
        [build_dir .. "/generated-sources/jextract"] = bundle .. "/src",
        [build_dir .. "/generated-resources/ghosttyfx"] = bundle .. "/resources",
        [build_dir .. "/ghosttyfx-artifact"] = bundle
    } do
        fs.remove(destination, { recursive = true })
        copy_tree(source_dir, destination)
    end
end

local function needs_display(arguments)
    local test_phase, skip_tests, profiles_next = false, false, false
    for _, argument in ipairs(arguments) do
        if (profiles_next or argument:match("^-P") or argument:match("^%-%-activate%-profiles="))
            and argument:match("app") then return true end
        profiles_next = argument == "-P" or argument == "--activate-profiles"
        if argument == "test" or argument == "package" or argument == "verify" or argument == "install"
            or argument == "deploy" or argument == "integration-test" or argument:match(":test$") then
            test_phase = true
        end
        if argument == "-DskipTests" or argument == "-DskipTests=true"
            or argument == "-Dmaven.test.skip" or argument == "-Dmaven.test.skip=true" then
            skip_tests = true
        end
    end
    return test_phase and not skip_tests
end

local function maven(...)
    local arguments = { ... }
    local java_home = jdk()
    -- Keep the version used for Central publishing: Maven 3.10 includes unwanted local metadata.
    local maven_home = tool("https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.16/"
        .. "apache-maven-3.9.16-bin.zip", "5af3b743dd8b876b5c45da33b676251e5f1687712644abb4ee519ca56e1d89ce")
    local classworlds = {}
    for name in fs.list(maven_home .. "/boot") do
        if name:match("^plexus%-classworlds%-.*%.jar$") then classworlds[#classworlds + 1] = name end
    end
    assert(#classworlds == 1, "Expected one Maven Classworlds launcher")
    local command = { java_home .. "/bin/java" .. host.exe_suffix, "--enable-native-access=ALL-UNNAMED",
        "-classpath", maven_home .. "/boot/" .. classworlds[1],
        "-Dclassworlds.conf=" .. maven_home .. "/bin/m2.conf", "-Dmaven.home=" .. maven_home,
        "-Dmaven.multiModuleProjectDirectory=" .. host.project_dir,
        "org.codehaus.plexus.classworlds.launcher.Launcher",
        "-Dghosttyfx.dotcmd.executable=" .. host.executable, table.unpack(arguments) }
    command.env = { JAVA_HOME = java_home, PATH = prepend_path(java_home .. "/bin", maven_home .. "/bin") }
    if host.os == "linux" and (os.getenv("DISPLAY") or "") == "" and needs_display(arguments) then
        -- xvfb-run owns display startup and cleanup; it launches Java, never this shell bootstrap.
        exec { env = command.env, "xvfb-run", "-a", table.unpack(command) }
    else
        exec(command)
    end
end

return {
    mvn = { description = "Run Maven with the pinned toolchain; forwards all Maven arguments", run = maven },
    test = { description = "Clean and run the test suite", run = function(...) maven("clean", "test", ...) end },
    manual_app = { description = "Compile and launch the manual terminal app", run = function(...)
        maven("-pl", "ghosttyfx-manual-app", "-am", "-Pmanual-app", "compile", ...)
    end },
    nord_app = { description = "Compile and launch the Nord terminal app", run = function(...)
        maven("-pl", "ghosttyfx-nord-app", "-am", "-Pnord-app", "compile", ...)
    end },
    generate_platform = {
        description = "Generate native bindings and resources for a Maven module",
        hidden = true, args = { { "platform" }, { "artifact-id" }, { "build-dir" } }, run = generate_platform
    },
    java = { description = "Run the pinned Java executable", run = function(...)
        exec { jdk() .. "/bin/java" .. host.exe_suffix, ... }
    end },
    jar = { description = "Run the pinned JDK jar tool", run = function(...)
        exec { jdk() .. "/bin/jar" .. host.exe_suffix, ... }
    end },
    javap = { description = "Run the pinned JDK bytecode inspector", run = function(...)
        exec { jdk() .. "/bin/javap" .. host.exe_suffix, ... }
    end }
}
