const std = @import("std");

pub fn build(b: *std.Build) void {
    const target = b.standardTargetOptions(.{});
    const optimize = b.standardOptimizeOption(.{});
    const ghostty_root = b.option([]const u8, "ghostty-root", "Path to the Ghostty checkout") orelse
        @panic("missing -Dghostty-root");

    const include_dir = b.pathJoin(&.{ ghostty_root, "include" });
    const module = b.createModule(.{
        .root_source_file = b.path("src/main.zig"),
        .target = target,
        .optimize = optimize,
    });

    const lib = b.addLibrary(.{
        .name = "ghosttyfx-frame",
        .linkage = .dynamic,
        .root_module = module,
    });
    lib.linkLibC();
    lib.addIncludePath(.{ .cwd_relative = include_dir });
    b.installArtifact(lib);
}
