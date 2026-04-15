const std = @import("std");
const Allocator = std.mem.Allocator;

const c = @cImport({
    @cInclude("ghostty/vt.h");
});

const native_allocator = std.heap.page_allocator;

const header_size: usize = 176;
const row_entry_size: usize = 16;
const style_entry_size: usize = 20;
const run_entry_size: usize = 16;

const row_flag_wrapped: u32 = 1 << 0;
const row_flag_wrap_continuation: u32 = 1 << 1;
const row_flag_grapheme: u32 = 1 << 2;
const row_flag_styled: u32 = 1 << 3;
const row_flag_hyperlink: u32 = 1 << 4;
const row_flag_kitty_virtual_placeholder: u32 = 1 << 5;
const row_flag_dirty: u32 = 1 << 6;

const style_flag_bold: u32 = 1 << 0;
const style_flag_faint: u32 = 1 << 1;
const style_flag_italic: u32 = 1 << 2;
const style_flag_underline: u32 = 1 << 3;
const style_flag_blink: u32 = 1 << 4;
const style_flag_inverse: u32 = 1 << 5;
const style_flag_invisible: u32 = 1 << 6;
const style_flag_strikethrough: u32 = 1 << 7;
const style_flag_overline: u32 = 1 << 8;

const SnapshotView = extern struct {
    size: usize,
    data: ?[*]const u8,
    len: usize,
};

const RenderStateNewFn = *const fn (?*const c.GhosttyAllocator, *c.GhosttyRenderState) callconv(.c) c.GhosttyResult;
const RenderStateFreeFn = *const fn (c.GhosttyRenderState) callconv(.c) void;
const RenderStateUpdateFn = *const fn (c.GhosttyRenderState, c.GhosttyTerminal) callconv(.c) c.GhosttyResult;
const RenderStateGetFn = *const fn (c.GhosttyRenderState, c.GhosttyRenderStateData, ?*anyopaque) callconv(.c) c.GhosttyResult;
const RenderStateSetFn = *const fn (c.GhosttyRenderState, c.GhosttyRenderStateOption, ?*const anyopaque) callconv(.c) c.GhosttyResult;
const RenderStateColorsGetFn = *const fn (c.GhosttyRenderState, *c.GhosttyRenderStateColors) callconv(.c) c.GhosttyResult;
const RowIteratorNewFn = *const fn (?*const c.GhosttyAllocator, *c.GhosttyRenderStateRowIterator) callconv(.c) c.GhosttyResult;
const RowIteratorFreeFn = *const fn (c.GhosttyRenderStateRowIterator) callconv(.c) void;
const RowIteratorNextFn = *const fn (c.GhosttyRenderStateRowIterator) callconv(.c) bool;
const RowGetFn = *const fn (c.GhosttyRenderStateRowIterator, c.GhosttyRenderStateRowData, ?*anyopaque) callconv(.c) c.GhosttyResult;
const RowSetFn = *const fn (c.GhosttyRenderStateRowIterator, c.GhosttyRenderStateRowOption, ?*const anyopaque) callconv(.c) c.GhosttyResult;
const RowCellsNewFn = *const fn (?*const c.GhosttyAllocator, *c.GhosttyRenderStateRowCells) callconv(.c) c.GhosttyResult;
const RowCellsFreeFn = *const fn (c.GhosttyRenderStateRowCells) callconv(.c) void;
const RowCellsNextFn = *const fn (c.GhosttyRenderStateRowCells) callconv(.c) bool;
const RowCellsGetFn = *const fn (c.GhosttyRenderStateRowCells, c.GhosttyRenderStateRowCellsData, ?*anyopaque) callconv(.c) c.GhosttyResult;
const TerminalGetFn = *const fn (c.GhosttyTerminal, c.GhosttyTerminalData, ?*anyopaque) callconv(.c) c.GhosttyResult;
const TerminalModeGetFn = *const fn (c.GhosttyTerminal, c.GhosttyMode, *bool) callconv(.c) c.GhosttyResult;
const RawRowGetFn = *const fn (c.GhosttyRow, c.GhosttyRowData, ?*anyopaque) callconv(.c) c.GhosttyResult;
const RawCellGetFn = *const fn (c.GhosttyCell, c.GhosttyCellData, ?*anyopaque) callconv(.c) c.GhosttyResult;

const Api = struct {
    ghostty_render_state_new: RenderStateNewFn,
    ghostty_render_state_free: RenderStateFreeFn,
    ghostty_render_state_update: RenderStateUpdateFn,
    ghostty_render_state_get: RenderStateGetFn,
    ghostty_render_state_set: RenderStateSetFn,
    ghostty_render_state_colors_get: RenderStateColorsGetFn,
    ghostty_render_state_row_iterator_new: RowIteratorNewFn,
    ghostty_render_state_row_iterator_free: RowIteratorFreeFn,
    ghostty_render_state_row_iterator_next: RowIteratorNextFn,
    ghostty_render_state_row_get: RowGetFn,
    ghostty_render_state_row_set: RowSetFn,
    ghostty_render_state_row_cells_new: RowCellsNewFn,
    ghostty_render_state_row_cells_free: RowCellsFreeFn,
    ghostty_render_state_row_cells_next: RowCellsNextFn,
    ghostty_render_state_row_cells_get: RowCellsGetFn,
    ghostty_terminal_get: TerminalGetFn,
    ghostty_terminal_mode_get: TerminalModeGetFn,
    ghostty_row_get: RawRowGetFn,
    ghostty_cell_get: RawCellGetFn,

    fn load(library: *std.DynLib) !Api {
        return .{
            .ghostty_render_state_new = library.lookup(RenderStateNewFn, "ghostty_render_state_new") orelse return error.MissingSymbol,
            .ghostty_render_state_free = library.lookup(RenderStateFreeFn, "ghostty_render_state_free") orelse return error.MissingSymbol,
            .ghostty_render_state_update = library.lookup(RenderStateUpdateFn, "ghostty_render_state_update") orelse return error.MissingSymbol,
            .ghostty_render_state_get = library.lookup(RenderStateGetFn, "ghostty_render_state_get") orelse return error.MissingSymbol,
            .ghostty_render_state_set = library.lookup(RenderStateSetFn, "ghostty_render_state_set") orelse return error.MissingSymbol,
            .ghostty_render_state_colors_get = library.lookup(RenderStateColorsGetFn, "ghostty_render_state_colors_get") orelse return error.MissingSymbol,
            .ghostty_render_state_row_iterator_new = library.lookup(RowIteratorNewFn, "ghostty_render_state_row_iterator_new") orelse return error.MissingSymbol,
            .ghostty_render_state_row_iterator_free = library.lookup(RowIteratorFreeFn, "ghostty_render_state_row_iterator_free") orelse return error.MissingSymbol,
            .ghostty_render_state_row_iterator_next = library.lookup(RowIteratorNextFn, "ghostty_render_state_row_iterator_next") orelse return error.MissingSymbol,
            .ghostty_render_state_row_get = library.lookup(RowGetFn, "ghostty_render_state_row_get") orelse return error.MissingSymbol,
            .ghostty_render_state_row_set = library.lookup(RowSetFn, "ghostty_render_state_row_set") orelse return error.MissingSymbol,
            .ghostty_render_state_row_cells_new = library.lookup(RowCellsNewFn, "ghostty_render_state_row_cells_new") orelse return error.MissingSymbol,
            .ghostty_render_state_row_cells_free = library.lookup(RowCellsFreeFn, "ghostty_render_state_row_cells_free") orelse return error.MissingSymbol,
            .ghostty_render_state_row_cells_next = library.lookup(RowCellsNextFn, "ghostty_render_state_row_cells_next") orelse return error.MissingSymbol,
            .ghostty_render_state_row_cells_get = library.lookup(RowCellsGetFn, "ghostty_render_state_row_cells_get") orelse return error.MissingSymbol,
            .ghostty_terminal_get = library.lookup(TerminalGetFn, "ghostty_terminal_get") orelse return error.MissingSymbol,
            .ghostty_terminal_mode_get = library.lookup(TerminalModeGetFn, "ghostty_terminal_mode_get") orelse return error.MissingSymbol,
            .ghostty_row_get = library.lookup(RawRowGetFn, "ghostty_row_get") orelse return error.MissingSymbol,
            .ghostty_cell_get = library.lookup(RawCellGetFn, "ghostty_cell_get") orelse return error.MissingSymbol,
        };
    }
};

const RowEntry = struct {
    flags: u32,
    run_start: u32,
    run_count: u32,
};

const StyleEntry = struct {
    foreground: u32,
    background: u32,
    underline_color: u32,
    underline_style: u32,
    flags: u32,
};

const RunEntry = struct {
    style_index: u32,
    text_start: u32,
    text_len: u32,
    columns: u32,
};

const FrameSnapshot = struct {
    library: std.DynLib,
    api: Api,
    render_state: c.GhosttyRenderState = null,
    row_iterator: c.GhosttyRenderStateRowIterator = null,
    row_cells: c.GhosttyRenderStateRowCells = null,
    rows: std.ArrayListUnmanaged(RowEntry) = .{},
    styles: std.ArrayListUnmanaged(StyleEntry) = .{},
    runs: std.ArrayListUnmanaged(RunEntry) = .{},
    text_bytes: std.ArrayListUnmanaged(u8) = .{},
    graphemes: std.ArrayListUnmanaged(u32) = .{},
    style_ids: std.AutoHashMapUnmanaged(StyleEntry, u32) = .{},
    buffer: std.ArrayListUnmanaged(u8) = .{},
    has_previous: bool = false,
    previous_cols: u32 = 0,
    previous_rows: u32 = 0,
    previous_active_screen: u32 = 0,

    fn deinit(self: *FrameSnapshot) void {
        self.buffer.deinit(native_allocator);
        self.style_ids.deinit(native_allocator);
        self.graphemes.deinit(native_allocator);
        self.text_bytes.deinit(native_allocator);
        self.runs.deinit(native_allocator);
        self.styles.deinit(native_allocator);
        self.rows.deinit(native_allocator);
        if (self.row_cells != null) self.api.ghostty_render_state_row_cells_free(self.row_cells);
        if (self.row_iterator != null) self.api.ghostty_render_state_row_iterator_free(self.row_iterator);
        if (self.render_state != null) self.api.ghostty_render_state_free(self.render_state);
        self.library.close();
    }

    fn capture(self: *FrameSnapshot, terminal: c.GhosttyTerminal, out_view: *SnapshotView) c.GhosttyResult {
        if (terminal == null or out_view.size < @sizeOf(SnapshotView)) {
            return c.GHOSTTY_INVALID_VALUE;
        }

        out_view.data = null;
        out_view.len = 0;

        var result = self.api.ghostty_render_state_update(self.render_state, terminal);
        if (result != c.GHOSTTY_SUCCESS) return result;

        const cols = renderStateU16(&self.api, self.render_state, c.GHOSTTY_RENDER_STATE_DATA_COLS, &result);
        if (result != c.GHOSTTY_SUCCESS) return result;
        const rows = renderStateU16(&self.api, self.render_state, c.GHOSTTY_RENDER_STATE_DATA_ROWS, &result);
        if (result != c.GHOSTTY_SUCCESS) return result;
        const active_screen = terminalU32(&self.api, terminal, c.GHOSTTY_TERMINAL_DATA_ACTIVE_SCREEN, &result);
        if (result != c.GHOSTTY_SUCCESS) return result;

        const dirty_value = renderStateU32(&self.api, self.render_state, c.GHOSTTY_RENDER_STATE_DATA_DIRTY, &result);
        if (result != c.GHOSTTY_SUCCESS) return result;

        const effective_dirty: u32 = if (!self.has_previous or cols != self.previous_cols or rows != self.previous_rows or active_screen != self.previous_active_screen or dirty_value == c.GHOSTTY_RENDER_STATE_DIRTY_FULL)
            c.GHOSTTY_RENDER_STATE_DIRTY_FULL
        else
            dirty_value;

        const colors = colorsValue(&self.api, self.render_state, &result);
        if (result != c.GHOSTTY_SUCCESS) return result;
        const width_px = terminalU32(&self.api, terminal, c.GHOSTTY_TERMINAL_DATA_WIDTH_PX, &result);
        if (result != c.GHOSTTY_SUCCESS) return result;
        const height_px = terminalU32(&self.api, terminal, c.GHOSTTY_TERMINAL_DATA_HEIGHT_PX, &result);
        if (result != c.GHOSTTY_SUCCESS) return result;
        const kitty_flags = terminalU32(&self.api, terminal, c.GHOSTTY_TERMINAL_DATA_KITTY_KEYBOARD_FLAGS, &result);
        if (result != c.GHOSTTY_SUCCESS) return result;
        const scrollbar = scrollbarValue(&self.api, terminal, &result);
        if (result != c.GHOSTTY_SUCCESS) return result;
        const title = terminalString(&self.api, terminal, c.GHOSTTY_TERMINAL_DATA_TITLE, &result);
        if (result != c.GHOSTTY_SUCCESS) return result;
        const pwd = terminalString(&self.api, terminal, c.GHOSTTY_TERMINAL_DATA_PWD, &result);
        if (result != c.GHOSTTY_SUCCESS) return result;
        const mouse_tracking = mouseTrackingMode(&self.api, terminal, &result);
        if (result != c.GHOSTTY_SUCCESS) return result;

        const cursor_visible = renderStateBool(&self.api, self.render_state, c.GHOSTTY_RENDER_STATE_DATA_CURSOR_VISIBLE, &result);
        if (result != c.GHOSTTY_SUCCESS) return result;
        const cursor_blinking = renderStateBool(&self.api, self.render_state, c.GHOSTTY_RENDER_STATE_DATA_CURSOR_BLINKING, &result);
        if (result != c.GHOSTTY_SUCCESS) return result;
        const cursor_password = renderStateBool(&self.api, self.render_state, c.GHOSTTY_RENDER_STATE_DATA_CURSOR_PASSWORD_INPUT, &result);
        if (result != c.GHOSTTY_SUCCESS) return result;
        const cursor_in_viewport = renderStateBool(&self.api, self.render_state, c.GHOSTTY_RENDER_STATE_DATA_CURSOR_VIEWPORT_HAS_VALUE, &result);
        if (result != c.GHOSTTY_SUCCESS) return result;
        const cursor_x: i32 = if (cursor_in_viewport)
            @intCast(renderStateU16(&self.api, self.render_state, c.GHOSTTY_RENDER_STATE_DATA_CURSOR_VIEWPORT_X, &result))
        else
            -1;
        if (result != c.GHOSTTY_SUCCESS) return result;
        const cursor_y: i32 = if (cursor_in_viewport)
            @intCast(renderStateU16(&self.api, self.render_state, c.GHOSTTY_RENDER_STATE_DATA_CURSOR_VIEWPORT_Y, &result))
        else
            -1;
        if (result != c.GHOSTTY_SUCCESS) return result;
        const cursor_wide_tail = if (cursor_in_viewport)
            renderStateBool(&self.api, self.render_state, c.GHOSTTY_RENDER_STATE_DATA_CURSOR_VIEWPORT_WIDE_TAIL, &result)
        else
            false;
        if (result != c.GHOSTTY_SUCCESS) return result;
        const cursor_style = renderStateU32(&self.api, self.render_state, c.GHOSTTY_RENDER_STATE_DATA_CURSOR_VISUAL_STYLE, &result);
        if (result != c.GHOSTTY_SUCCESS) return result;

        self.rows.clearRetainingCapacity();
        self.styles.clearRetainingCapacity();
        self.runs.clearRetainingCapacity();
        self.text_bytes.clearRetainingCapacity();
        self.graphemes.clearRetainingCapacity();
        self.style_ids.clearRetainingCapacity();

        result = self.api.ghostty_render_state_get(self.render_state, c.GHOSTTY_RENDER_STATE_DATA_ROW_ITERATOR, @ptrCast(&self.row_iterator));
        if (result != c.GHOSTTY_SUCCESS) return result;

        var row_index: u32 = 0;
        while (row_index < rows) : (row_index += 1) {
            if (!self.api.ghostty_render_state_row_iterator_next(self.row_iterator)) {
                return c.GHOSTTY_INVALID_VALUE;
            }

            const raw_row = renderRowRaw(&self.api, self.row_iterator, &result);
            if (result != c.GHOSTTY_SUCCESS) return result;

            var row_flags = rowFlags(&self.api, raw_row, &result);
            if (result != c.GHOSTTY_SUCCESS) return result;

            const row_dirty = effective_dirty == c.GHOSTTY_RENDER_STATE_DIRTY_FULL or renderRowBool(&self.api, self.row_iterator, c.GHOSTTY_RENDER_STATE_ROW_DATA_DIRTY, &result);
            if (result != c.GHOSTTY_SUCCESS) return result;
            if (row_dirty) row_flags |= row_flag_dirty;

            var row_entry = RowEntry{
                .flags = row_flags,
                .run_start = 0,
                .run_count = 0,
            };

            if (row_dirty) {
                row_entry.run_start = castU32(self.runs.items.len) orelse return c.GHOSTTY_INVALID_VALUE;

                result = self.api.ghostty_render_state_row_get(self.row_iterator, c.GHOSTTY_RENDER_STATE_ROW_DATA_CELLS, @ptrCast(&self.row_cells));
                if (result != c.GHOSTTY_SUCCESS) return result;

                var active_run_style: ?u32 = null;
                var active_run_text_start: u32 = 0;
                var active_run_text_len: u32 = 0;
                var active_run_columns: u32 = 0;

                var column: u32 = 0;
                while (column < cols) : (column += 1) {
                    if (!self.api.ghostty_render_state_row_cells_next(self.row_cells)) {
                        return c.GHOSTTY_INVALID_VALUE;
                    }

                    const raw_cell = renderRowCellRaw(&self.api, self.row_cells, &result);
                    if (result != c.GHOSTTY_SUCCESS) return result;
                    const codepoint_len = renderRowCellU32(&self.api, self.row_cells, c.GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_GRAPHEMES_LEN, &result);
                    if (result != c.GHOSTTY_SUCCESS) return result;

                    const width = cellWide(&self.api, raw_cell, &result);
                    if (result != c.GHOSTTY_SUCCESS) return result;
                    const columns_for_cell = cellColumns(width) orelse return c.GHOSTTY_INVALID_VALUE;
                    const style = styleValue(&self.api, self.row_cells, &result);
                    if (result != c.GHOSTTY_SUCCESS) return result;
                    const foreground = resolvedColor(&self.api, self.row_cells, c.GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_FG_COLOR, colors.foreground, &result);
                    if (result != c.GHOSTTY_SUCCESS) return result;
                    const background = resolvedColor(&self.api, self.row_cells, c.GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_BG_COLOR, colors.background, &result);
                    if (result != c.GHOSTTY_SUCCESS) return result;

                    const style_entry = StyleEntry{
                        .foreground = foreground,
                        .background = background,
                        .underline_color = styleColor(style.underline_color, packRgb(colors.foreground), &colors),
                        .underline_style = castU32(style.underline) orelse return c.GHOSTTY_INVALID_VALUE,
                        .flags = styleFlags(style),
                    };
                    const style_index = styleIndex(self, style_entry);
                    if (style_index == null) return c.GHOSTTY_OUT_OF_MEMORY;

                    const text_start = castU32(self.text_bytes.items.len) orelse return c.GHOSTTY_INVALID_VALUE;
                    result = appendRunText(self, codepoint_len, width);
                    if (result != c.GHOSTTY_SUCCESS) return result;
                    const text_len = castU32(self.text_bytes.items.len - text_start) orelse return c.GHOSTTY_INVALID_VALUE;

                    if (columns_for_cell == 0 and text_len == 0) {
                        continue;
                    }

                    if (active_run_style == null or active_run_style.? != style_index.?) {
                        if (active_run_style) |current_style| {
                            result = appendRowRun(self, &row_entry, current_style, active_run_text_start, active_run_text_len, active_run_columns);
                            if (result != c.GHOSTTY_SUCCESS) return result;
                        }
                        active_run_style = style_index.?;
                        active_run_text_start = text_start;
                        active_run_text_len = text_len;
                        active_run_columns = columns_for_cell;
                    } else {
                        active_run_text_len += text_len;
                        active_run_columns += columns_for_cell;
                    }
                }

                if (active_run_style) |current_style| {
                    result = appendRowRun(self, &row_entry, current_style, active_run_text_start, active_run_text_len, active_run_columns);
                    if (result != c.GHOSTTY_SUCCESS) return result;
                }

                var clean = false;
                result = self.api.ghostty_render_state_row_set(self.row_iterator, c.GHOSTTY_RENDER_STATE_ROW_OPTION_DIRTY, &clean);
                if (result != c.GHOSTTY_SUCCESS) return result;
            }

            self.rows.append(native_allocator, row_entry) catch return c.GHOSTTY_OUT_OF_MEMORY;
        }

        var clean_state: c.GhosttyRenderStateDirty = c.GHOSTTY_RENDER_STATE_DIRTY_FALSE;
        result = self.api.ghostty_render_state_set(self.render_state, c.GHOSTTY_RENDER_STATE_OPTION_DIRTY, &clean_state);
        if (result != c.GHOSTTY_SUCCESS) return result;

        result = buildPayload(
            self,
            effective_dirty,
            active_screen,
            mouse_tracking,
            kitty_flags,
            cols,
            rows,
            if (cols == 0) 0 else width_px / cols,
            if (rows == 0) 0 else height_px / rows,
            cursor_visible,
            cursor_blinking,
            cursor_password,
            cursor_in_viewport,
            cursor_x,
            cursor_y,
            cursor_wide_tail,
            cursor_style,
            colors,
            scrollbar,
            title,
            pwd,
        );
        if (result != c.GHOSTTY_SUCCESS) return result;

        out_view.data = self.buffer.items.ptr;
        out_view.len = self.buffer.items.len;
        self.has_previous = true;
        self.previous_cols = cols;
        self.previous_rows = rows;
        self.previous_active_screen = active_screen;
        return c.GHOSTTY_SUCCESS;
    }
};

pub export fn ghosttyfx_frame_snapshot_new(
    _: ?*const c.GhosttyAllocator,
    vt_library_path: ?[*:0]const u8,
    out_handle: *?*FrameSnapshot,
) c.GhosttyResult {
    const path = vt_library_path orelse return c.GHOSTTY_INVALID_VALUE;
    var library = std.DynLib.open(std.mem.span(path)) catch return c.GHOSTTY_INVALID_VALUE;
    const api = Api.load(&library) catch {
        library.close();
        return c.GHOSTTY_INVALID_VALUE;
    };

    const handle = native_allocator.create(FrameSnapshot) catch {
        library.close();
        return c.GHOSTTY_OUT_OF_MEMORY;
    };
    handle.* = .{
        .library = library,
        .api = api,
    };

    var result = handle.api.ghostty_render_state_new(null, &handle.render_state);
    if (result != c.GHOSTTY_SUCCESS) {
        handle.deinit();
        native_allocator.destroy(handle);
        return result;
    }
    result = handle.api.ghostty_render_state_row_iterator_new(null, &handle.row_iterator);
    if (result != c.GHOSTTY_SUCCESS) {
        handle.deinit();
        native_allocator.destroy(handle);
        return result;
    }
    result = handle.api.ghostty_render_state_row_cells_new(null, &handle.row_cells);
    if (result != c.GHOSTTY_SUCCESS) {
        handle.deinit();
        native_allocator.destroy(handle);
        return result;
    }

    out_handle.* = handle;
    return c.GHOSTTY_SUCCESS;
}

pub export fn ghosttyfx_frame_snapshot_free(handle: ?*FrameSnapshot) void {
    if (handle) |value| {
        value.deinit();
        native_allocator.destroy(value);
    }
}

pub export fn ghosttyfx_frame_snapshot_capture(handle: ?*FrameSnapshot, terminal: c.GhosttyTerminal, out_view: *SnapshotView) c.GhosttyResult {
    const value = handle orelse return c.GHOSTTY_INVALID_VALUE;
    return value.capture(terminal, out_view);
}

fn styleIndex(self: *FrameSnapshot, entry: StyleEntry) ?u32 {
    const gop = self.style_ids.getOrPut(native_allocator, entry) catch return null;
    if (gop.found_existing) return gop.value_ptr.*;

    const index = castU32(self.styles.items.len) orelse return null;
    self.styles.append(native_allocator, entry) catch return null;
    gop.value_ptr.* = index;
    return index;
}

fn appendRowRun(self: *FrameSnapshot, row_entry: *RowEntry, style_index: u32, text_start: u32, text_len: u32, columns: u32) c.GhosttyResult {
    self.runs.append(native_allocator, .{
        .style_index = style_index,
        .text_start = text_start,
        .text_len = text_len,
        .columns = columns,
    }) catch return c.GHOSTTY_OUT_OF_MEMORY;
    row_entry.run_count += 1;
    return c.GHOSTTY_SUCCESS;
}

fn appendRunText(self: *FrameSnapshot, codepoint_len: u32, width: u32) c.GhosttyResult {
    if (codepoint_len > 0) {
        self.graphemes.clearRetainingCapacity();
        self.graphemes.appendNTimes(native_allocator, 0, codepoint_len) catch return c.GHOSTTY_OUT_OF_MEMORY;
        const result = self.api.ghostty_render_state_row_cells_get(
            self.row_cells,
            c.GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_GRAPHEMES_BUF,
            self.graphemes.items.ptr,
        );
        if (result != c.GHOSTTY_SUCCESS) return result;

        var utf8: [4]u8 = undefined;
        for (self.graphemes.items[0..codepoint_len]) |codepoint| {
            const scalar = std.math.cast(u21, codepoint) orelse return c.GHOSTTY_INVALID_VALUE;
            const len = std.unicode.utf8Encode(scalar, &utf8) catch return c.GHOSTTY_INVALID_VALUE;
            self.text_bytes.appendSlice(native_allocator, utf8[0..len]) catch return c.GHOSTTY_OUT_OF_MEMORY;
        }
        return c.GHOSTTY_SUCCESS;
    }

    const columns = cellColumns(width) orelse return c.GHOSTTY_INVALID_VALUE;
    if (columns > 0) {
        self.text_bytes.appendNTimes(native_allocator, ' ', columns) catch return c.GHOSTTY_OUT_OF_MEMORY;
    }
    return c.GHOSTTY_SUCCESS;
}

fn cellColumns(width: u32) ?u32 {
    return switch (width) {
        0 => 1,
        1 => 2,
        2, 3 => 0,
        else => null,
    };
}

fn buildPayload(
    self: *FrameSnapshot,
    dirty: u32,
    active_screen: u32,
    mouse_tracking: u32,
    kitty_flags: u32,
    cols: u32,
    rows: u32,
    cell_width_px: u32,
    cell_height_px: u32,
    cursor_visible: bool,
    cursor_blinking: bool,
    cursor_password: bool,
    cursor_in_viewport: bool,
    cursor_x: i32,
    cursor_y: i32,
    cursor_wide_tail: bool,
    cursor_style: u32,
    colors: c.GhosttyRenderStateColors,
    scrollbar: c.GhosttyTerminalScrollbar,
    title: c.GhosttyString,
    pwd: c.GhosttyString,
) c.GhosttyResult {
    const title_len = title.len;
    const pwd_len = pwd.len;
    const rows_len = self.rows.items.len;
    const styles_len = self.styles.items.len;
    const runs_len = self.runs.items.len;
    const text_bytes_len = self.text_bytes.items.len;

    const rows_offset = header_size;
    const styles_offset = rows_offset + rows_len * row_entry_size;
    const runs_offset = styles_offset + styles_len * style_entry_size;
    const text_bytes_offset = runs_offset + runs_len * run_entry_size;
    const title_offset = text_bytes_offset + text_bytes_len;
    const pwd_offset = title_offset + title_len;
    const total_size = pwd_offset + pwd_len;

    self.buffer.clearRetainingCapacity();
    self.buffer.appendNTimes(native_allocator, 0, total_size) catch return c.GHOSTTY_OUT_OF_MEMORY;
    const bytes = self.buffer.items;

    writeU32(bytes, 0, dirty);
    writeU32(bytes, 4, active_screen);
    writeU32(bytes, 8, mouse_tracking);
    writeU32(bytes, 12, kitty_flags);

    writeU32(bytes, 16, cols);
    writeU32(bytes, 20, rows);
    writeU32(bytes, 24, cell_width_px);
    writeU32(bytes, 28, cell_height_px);

    writeU32(bytes, 32, boolU32(cursor_visible));
    writeU32(bytes, 36, boolU32(cursor_blinking));
    writeU32(bytes, 40, boolU32(cursor_password));
    writeU32(bytes, 44, boolU32(cursor_in_viewport));
    writeI32(bytes, 48, cursor_x);
    writeI32(bytes, 52, cursor_y);
    writeU32(bytes, 56, boolU32(cursor_wide_tail));
    writeU32(bytes, 60, cursor_style);

    writeU32(bytes, 64, packRgb(colors.foreground));
    writeU32(bytes, 68, packRgb(colors.background));
    writeU32(bytes, 72, packRgb(colors.cursor));
    writeU32(bytes, 76, boolU32(colors.cursor_has_value));

    writeU64(bytes, 80, scrollbar.total);
    writeU64(bytes, 88, scrollbar.offset);
    writeU64(bytes, 96, scrollbar.len);

    writeU32(bytes, 104, castU32(styles_len) orelse return c.GHOSTTY_INVALID_VALUE);

    writeU64(bytes, 108, rows_offset);
    writeU64(bytes, 116, styles_offset);
    writeU64(bytes, 124, runs_offset);
    writeU64(bytes, 132, text_bytes_offset);
    writeU64(bytes, 140, title_offset);
    writeU32(bytes, 148, castU32(title_len) orelse return c.GHOSTTY_INVALID_VALUE);
    writeU64(bytes, 152, pwd_offset);
    writeU32(bytes, 160, castU32(pwd_len) orelse return c.GHOSTTY_INVALID_VALUE);

    for (self.rows.items, 0..) |row, index| {
        const offset = rows_offset + index * row_entry_size;
        writeU32(bytes, offset + 0, row.flags);
        writeU32(bytes, offset + 4, row.run_start);
        writeU32(bytes, offset + 8, row.run_count);
        writeU32(bytes, offset + 12, 0);
    }
    for (self.styles.items, 0..) |style, index| {
        const offset = styles_offset + index * style_entry_size;
        writeU32(bytes, offset + 0, style.foreground);
        writeU32(bytes, offset + 4, style.background);
        writeU32(bytes, offset + 8, style.underline_color);
        writeU32(bytes, offset + 12, style.underline_style);
        writeU32(bytes, offset + 16, style.flags);
    }
    for (self.runs.items, 0..) |run, index| {
        const offset = runs_offset + index * run_entry_size;
        writeU32(bytes, offset + 0, run.style_index);
        writeU32(bytes, offset + 4, run.text_start);
        writeU32(bytes, offset + 8, run.text_len);
        writeU32(bytes, offset + 12, run.columns);
    }
    if (text_bytes_len > 0) {
        @memcpy(bytes[text_bytes_offset .. text_bytes_offset + text_bytes_len], self.text_bytes.items);
    }
    if (title_len > 0) {
        const slice = @as([*]const u8, @ptrCast(title.ptr))[0..title_len];
        @memcpy(bytes[title_offset .. title_offset + title_len], slice);
    }
    if (pwd_len > 0) {
        const slice = @as([*]const u8, @ptrCast(pwd.ptr))[0..pwd_len];
        @memcpy(bytes[pwd_offset .. pwd_offset + pwd_len], slice);
    }

    return c.GHOSTTY_SUCCESS;
}

fn rowFlags(api: *const Api, row: c.GhosttyRow, out_result: *c.GhosttyResult) u32 {
    var flags: u32 = 0;
    if (rowBool(api, row, c.GHOSTTY_ROW_DATA_WRAP, out_result)) flags |= row_flag_wrapped;
    if (out_result.* != c.GHOSTTY_SUCCESS) return 0;
    if (rowBool(api, row, c.GHOSTTY_ROW_DATA_WRAP_CONTINUATION, out_result)) flags |= row_flag_wrap_continuation;
    if (out_result.* != c.GHOSTTY_SUCCESS) return 0;
    if (rowBool(api, row, c.GHOSTTY_ROW_DATA_GRAPHEME, out_result)) flags |= row_flag_grapheme;
    if (out_result.* != c.GHOSTTY_SUCCESS) return 0;
    if (rowBool(api, row, c.GHOSTTY_ROW_DATA_STYLED, out_result)) flags |= row_flag_styled;
    if (out_result.* != c.GHOSTTY_SUCCESS) return 0;
    if (rowBool(api, row, c.GHOSTTY_ROW_DATA_HYPERLINK, out_result)) flags |= row_flag_hyperlink;
    if (out_result.* != c.GHOSTTY_SUCCESS) return 0;
    if (rowBool(api, row, c.GHOSTTY_ROW_DATA_KITTY_VIRTUAL_PLACEHOLDER, out_result)) flags |= row_flag_kitty_virtual_placeholder;
    return flags;
}

fn styleFlags(style: c.GhosttyStyle) u32 {
    var flags: u32 = 0;
    if (style.bold) flags |= style_flag_bold;
    if (style.faint) flags |= style_flag_faint;
    if (style.italic) flags |= style_flag_italic;
    if (style.underline != 0) flags |= style_flag_underline;
    if (style.blink) flags |= style_flag_blink;
    if (style.inverse) flags |= style_flag_inverse;
    if (style.invisible) flags |= style_flag_invisible;
    if (style.strikethrough) flags |= style_flag_strikethrough;
    if (style.overline) flags |= style_flag_overline;
    return flags;
}

fn styleColor(color: c.GhosttyStyleColor, fallback: u32, colors: *const c.GhosttyRenderStateColors) u32 {
    return switch (color.tag) {
        c.GHOSTTY_STYLE_COLOR_NONE => fallback,
        c.GHOSTTY_STYLE_COLOR_PALETTE => packRgb(colors.palette[color.value.palette]),
        c.GHOSTTY_STYLE_COLOR_RGB => packRgb(color.value.rgb),
        else => fallback,
    };
}

fn resolvedColor(api: *const Api, cells: c.GhosttyRenderStateRowCells, data: c.GhosttyRenderStateRowCellsData, fallback: c.GhosttyColorRgb, out_result: *c.GhosttyResult) u32 {
    var rgb: c.GhosttyColorRgb = undefined;
    const result = api.ghostty_render_state_row_cells_get(cells, data, &rgb);
    if (result == c.GHOSTTY_SUCCESS) {
        out_result.* = result;
        return packRgb(rgb);
    }
    if (result == c.GHOSTTY_INVALID_VALUE or result == c.GHOSTTY_NO_VALUE) {
        out_result.* = c.GHOSTTY_SUCCESS;
        return packRgb(fallback);
    }
    out_result.* = result;
    return 0;
}

fn colorsValue(api: *const Api, render_state: c.GhosttyRenderState, out_result: *c.GhosttyResult) c.GhosttyRenderStateColors {
    var value: c.GhosttyRenderStateColors = .{
        .size = @sizeOf(c.GhosttyRenderStateColors),
        .background = undefined,
        .foreground = undefined,
        .cursor = undefined,
        .cursor_has_value = false,
        .palette = undefined,
    };
    out_result.* = api.ghostty_render_state_colors_get(render_state, &value);
    return value;
}

fn styleValue(api: *const Api, cells: c.GhosttyRenderStateRowCells, out_result: *c.GhosttyResult) c.GhosttyStyle {
    var value: c.GhosttyStyle = .{
        .size = @sizeOf(c.GhosttyStyle),
        .fg_color = undefined,
        .bg_color = undefined,
        .underline_color = undefined,
        .bold = false,
        .italic = false,
        .faint = false,
        .blink = false,
        .inverse = false,
        .invisible = false,
        .strikethrough = false,
        .overline = false,
        .underline = 0,
    };
    out_result.* = api.ghostty_render_state_row_cells_get(cells, c.GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_STYLE, &value);
    return value;
}

fn scrollbarValue(api: *const Api, terminal: c.GhosttyTerminal, out_result: *c.GhosttyResult) c.GhosttyTerminalScrollbar {
    var value: c.GhosttyTerminalScrollbar = undefined;
    out_result.* = api.ghostty_terminal_get(terminal, c.GHOSTTY_TERMINAL_DATA_SCROLLBAR, &value);
    return value;
}

fn terminalString(api: *const Api, terminal: c.GhosttyTerminal, data: c.GhosttyTerminalData, out_result: *c.GhosttyResult) c.GhosttyString {
    var value: c.GhosttyString = .{
        .ptr = null,
        .len = 0,
    };
    out_result.* = api.ghostty_terminal_get(terminal, data, &value);
    return value;
}

fn mouseTrackingMode(api: *const Api, terminal: c.GhosttyTerminal, out_result: *c.GhosttyResult) u32 {
    if (modeEnabled(api, terminal, c.ghostty_mode_new(1003, false), out_result)) return 4;
    if (out_result.* != c.GHOSTTY_SUCCESS) return 0;
    if (modeEnabled(api, terminal, c.ghostty_mode_new(1002, false), out_result)) return 3;
    if (out_result.* != c.GHOSTTY_SUCCESS) return 0;
    if (modeEnabled(api, terminal, c.ghostty_mode_new(1000, false), out_result)) return 2;
    if (out_result.* != c.GHOSTTY_SUCCESS) return 0;
    if (modeEnabled(api, terminal, c.ghostty_mode_new(9, false), out_result)) return 1;
    return 0;
}

fn modeEnabled(api: *const Api, terminal: c.GhosttyTerminal, mode: c.GhosttyMode, out_result: *c.GhosttyResult) bool {
    var enabled = false;
    out_result.* = api.ghostty_terminal_mode_get(terminal, mode, &enabled);
    return enabled;
}

fn renderStateBool(api: *const Api, render_state: c.GhosttyRenderState, data: c.GhosttyRenderStateData, out_result: *c.GhosttyResult) bool {
    var value = false;
    out_result.* = api.ghostty_render_state_get(render_state, data, &value);
    return value;
}

fn renderStateU16(api: *const Api, render_state: c.GhosttyRenderState, data: c.GhosttyRenderStateData, out_result: *c.GhosttyResult) u32 {
    var value: u16 = 0;
    out_result.* = api.ghostty_render_state_get(render_state, data, &value);
    return value;
}

fn renderStateU32(api: *const Api, render_state: c.GhosttyRenderState, data: c.GhosttyRenderStateData, out_result: *c.GhosttyResult) u32 {
    var value: u32 = 0;
    out_result.* = api.ghostty_render_state_get(render_state, data, &value);
    return value;
}

fn renderRowBool(api: *const Api, iterator: c.GhosttyRenderStateRowIterator, data: c.GhosttyRenderStateRowData, out_result: *c.GhosttyResult) bool {
    var value = false;
    out_result.* = api.ghostty_render_state_row_get(iterator, data, &value);
    return value;
}

fn renderRowRaw(api: *const Api, iterator: c.GhosttyRenderStateRowIterator, out_result: *c.GhosttyResult) c.GhosttyRow {
    var value: c.GhosttyRow = 0;
    out_result.* = api.ghostty_render_state_row_get(iterator, c.GHOSTTY_RENDER_STATE_ROW_DATA_RAW, &value);
    return value;
}

fn renderRowCellRaw(api: *const Api, cells: c.GhosttyRenderStateRowCells, out_result: *c.GhosttyResult) c.GhosttyCell {
    var value: c.GhosttyCell = 0;
    out_result.* = api.ghostty_render_state_row_cells_get(cells, c.GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_RAW, &value);
    return value;
}

fn renderRowCellU32(api: *const Api, cells: c.GhosttyRenderStateRowCells, data: c.GhosttyRenderStateRowCellsData, out_result: *c.GhosttyResult) u32 {
    var value: u32 = 0;
    out_result.* = api.ghostty_render_state_row_cells_get(cells, data, &value);
    return value;
}

fn terminalU32(api: *const Api, terminal: c.GhosttyTerminal, data: c.GhosttyTerminalData, out_result: *c.GhosttyResult) u32 {
    var value: u32 = 0;
    out_result.* = api.ghostty_terminal_get(terminal, data, &value);
    return value;
}

fn rowBool(api: *const Api, row: c.GhosttyRow, data: c.GhosttyRowData, out_result: *c.GhosttyResult) bool {
    var value = false;
    out_result.* = api.ghostty_row_get(row, data, &value);
    return value;
}

fn cellWide(api: *const Api, cell: c.GhosttyCell, out_result: *c.GhosttyResult) u32 {
    var value: c.GhosttyCellWide = 0;
    out_result.* = api.ghostty_cell_get(cell, c.GHOSTTY_CELL_DATA_WIDE, &value);
    return value;
}

fn packRgb(rgb: c.GhosttyColorRgb) u32 {
    return (@as(u32, rgb.r) << 16) | (@as(u32, rgb.g) << 8) | @as(u32, rgb.b);
}

fn writeU32(bytes: []u8, offset: usize, value: u32) void {
    std.mem.writeInt(u32, bytes[offset..][0..4], value, .little);
}

fn writeI32(bytes: []u8, offset: usize, value: i32) void {
    std.mem.writeInt(i32, bytes[offset..][0..4], value, .little);
}

fn writeU64(bytes: []u8, offset: usize, value: anytype) void {
    std.mem.writeInt(u64, bytes[offset..][0..8], value, .little);
}

fn boolU32(value: bool) u32 {
    return if (value) 1 else 0;
}

fn castU32(value: anytype) ?u32 {
    return std.math.cast(u32, value);
}
