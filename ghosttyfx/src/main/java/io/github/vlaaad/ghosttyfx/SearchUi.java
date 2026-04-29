package io.github.vlaaad.ghosttyfx;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;
import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyDoubleProperty;
import javafx.beans.property.StringProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.skin.TextFieldSkin;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.Background;
import javafx.scene.layout.BackgroundFill;
import javafx.scene.layout.Border;
import javafx.scene.layout.BorderStroke;
import javafx.scene.layout.BorderStrokeStyle;
import javafx.scene.layout.BorderWidths;
import javafx.scene.layout.CornerRadii;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.text.Font;

final class SearchUi {

    private static final double WIDTH = 320;
    private static final long INDEX_BUDGET_NS = 1_000_000L;

    private final TerminalSession terminalSession;
    private final Runnable closeSearch;
    private final Runnable redraw;
    private final Predicate<List<Selection>> matchesAffectViewport;
    private final Consumer<Selection> scrollMatchIntoView;
    private final HBox view;
    private final TextField field;
    private final Label count;
    private final AnimationTimer indexTimer = new AnimationTimer() {
        @Override
        public void handle(long now) {
            continueSearch();
        }
    };

    private TerminalSession.SearchResult result = TerminalSession.SearchResult.empty();
    private int selectedMatch = -1;
    private SearchJob job;

    SearchUi(
            TerminalSession terminalSession,
            ObjectProperty<Font> font,
            StringProperty searchPromptText,
            ReadOnlyDoubleProperty terminalWidth,
            Runnable closeSearch,
            Runnable redraw,
            Predicate<List<Selection>> matchesAffectViewport,
            Consumer<Selection> scrollMatchIntoView) {
        this.terminalSession = terminalSession;
        this.closeSearch = closeSearch;
        this.redraw = redraw;
        this.matchesAffectViewport = matchesAffectViewport;
        this.scrollMatchIntoView = scrollMatchIntoView;

        field = new TextField();
        field.setId("ghosttyfx-search-field");
        field.setPrefColumnCount(20);
        field.setMinWidth(0);
        field.setMaxWidth(Double.MAX_VALUE);
        field.setFocusTraversable(true);
        field.setPadding(new Insets(3, 3, 3, 3));
        field.setSkin(new SearchTextFieldSkin(field));
        field.fontProperty().bind(font);
        field.promptTextProperty().bind(searchPromptText);
        field.textProperty().addListener((_, _, _) -> refresh());
        field.addEventFilter(KeyEvent.KEY_PRESSED, this::handleKeyPressed);
        field.focusedProperty().addListener((_, _, focused) -> {
            if (!focused) {
                closeSearch.run();
            }
        });

        count = new Label();
        count.setId("ghosttyfx-search-count");
        count.setText("0/0");
        count.fontProperty().bind(font);
        count.setMinWidth(Label.USE_PREF_SIZE);
        view = new HBox(6, field, count);
        view.setId("ghosttyfx-search");
        view.setAlignment(Pos.CENTER_LEFT);
        view.setMinWidth(0);
        view.prefWidthProperty().bind(Bindings.createDoubleBinding(
                () -> Math.max(0, Math.min(WIDTH, terminalWidth.get() - 16)),
                terminalWidth));
        view.maxWidthProperty().bind(view.prefWidthProperty());
        view.setVisible(false);
        HBox.setHgrow(field, Priority.ALWAYS);
    }

    HBox view() {
        return view;
    }

    boolean visible() {
        return view.isVisible();
    }

    TerminalSession.SearchResult result() {
        return result;
    }

    int selectedMatch() {
        return selectedMatch;
    }

    int matchCount() {
        return result.matches().size();
    }

    String text() {
        return field.getText();
    }

    void open(String selected) {
        field.setText(selected);
        field.selectAll();
        view.setVisible(true);
        field.requestFocus();
        refresh();
    }

    void close() {
        view.setVisible(false);
        cancel();
    }

    void cancel() {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(this::cancel);
            return;
        }

        indexTimer.stop();
        job = null;
        result = TerminalSession.SearchResult.empty();
        selectedMatch = -1;
        count.setText("0/0");
    }

    void refresh() {
        refresh(true);
    }

    void refresh(boolean clearCurrent) {
        if (!visible()) {
            return;
        }

        var query = field.getText();
        indexTimer.stop();
        if (query.isEmpty()) {
            job = null;
            result = TerminalSession.SearchResult.empty();
            selectedMatch = -1;
            count.setText("0/0");
            redraw.run();
            return;
        }

        job = new SearchJob(query, terminalSession.newSearchDocument());
        if (clearCurrent) {
            result = TerminalSession.SearchResult.empty();
            selectedMatch = -1;
            count.setText("...");
        } else {
            updateCount();
        }
        redraw.run();
        indexTimer.start();
    }

    boolean selectNext(boolean wrap) {
        if (!visible() || result.matches().isEmpty()) {
            return false;
        }

        if (selectedMatch >= result.matches().size() - 1) {
            if (!wrap) {
                return true;
            }
            selectMatch(0);
        } else {
            selectMatch(selectedMatch + 1);
        }
        return true;
    }

    boolean selectPrevious(boolean wrap) {
        if (!visible() || result.matches().isEmpty()) {
            return false;
        }

        if (selectedMatch <= 0) {
            if (!wrap) {
                return true;
            }
            selectMatch(result.matches().size() - 1);
        } else {
            selectMatch(selectedMatch - 1);
        }
        return true;
    }

    void applyTheme(TerminalTheme theme) {
        view.setBackground(new Background(new BackgroundFill(
                theme.background(),
                new CornerRadii(2),
                Insets.EMPTY)));
        view.setBorder(new Border(new BorderStroke(
                theme.scrollbarColor(),
                BorderStrokeStyle.SOLID,
                new CornerRadii(2),
                BorderWidths.DEFAULT)));
        view.setPadding(new Insets(4));
        field.setBackground(new Background(new BackgroundFill(
                theme.background(),
                new CornerRadii(1),
                Insets.EMPTY)));
        field.setBorder(Border.EMPTY);
        ((SearchTextFieldSkin) field.getSkin()).applyTheme(theme);
        count.setTextFill(theme.foreground().deriveColor(0, 1, 1, theme.faintOpacity()));
    }

    private void continueSearch() {
        var current = job;
        if (current == null || !visible() || !current.query().equals(field.getText())) {
            indexTimer.stop();
            return;
        }

        terminalSession.appendSearchRows(current.document(), INDEX_BUDGET_NS);
        var batch = TerminalSession.search(current.document(), current.query(), current.searchedUntil());
        current.searchedUntil = batch.searchedUntil();
        result = TerminalSession.SearchResult.append(current.result(), batch.matches(), current.document().columns());
        current.result = result;
        selectedMatch = result.matches().isEmpty() ? -1 : Math.max(0, selectedMatch);
        updateCount();

        var needsRedraw = matchesAffectViewport.test(batch.matches());
        if (!result.matches().isEmpty() && !current.scrolledToFirstMatch()) {
            current.scrolledToFirstMatch = true;
            scrollMatchIntoView.accept(result.matches().getFirst());
            needsRedraw = true;
        }
        if (needsRedraw) {
            redraw.run();
        }
        if (current.document().complete()) {
            job = null;
            indexTimer.stop();
        }
    }

    private void selectMatch(int index) {
        selectedMatch = index;
        updateCount();
        scrollMatchIntoView.accept(result.matches().get(index));
        redraw.run();
    }

    private void updateCount() {
        var matches = result.matches();
        if (matches.isEmpty()) {
            count.setText("0/0");
            return;
        }
        var selected = selectedMatch >= 0 ? selectedMatch + 1 : 1;
        count.setText(selected + "/" + matches.size());
    }

    private void handleKeyPressed(KeyEvent event) {
        switch (event.getCode()) {
            case ENTER -> {
                if (event.isShiftDown()) {
                    selectPrevious(false);
                } else {
                    selectNext(false);
                }
                event.consume();
            }
            case DOWN -> {
                selectNext(false);
                event.consume();
            }
            case UP -> {
                selectPrevious(false);
                event.consume();
            }
            case ESCAPE -> {
                closeSearch.run();
                event.consume();
            }
            default -> {}
        }
    }

    private static final class SearchJob {
        private final String query;
        private final TerminalSession.SearchDocumentBuilder document;
        private TerminalSession.SearchResult result = TerminalSession.SearchResult.empty();
        private int searchedUntil;
        private boolean scrolledToFirstMatch;

        private SearchJob(String query, TerminalSession.SearchDocumentBuilder document) {
            this.query = query;
            this.document = document;
        }

        private String query() {
            return query;
        }

        private TerminalSession.SearchDocumentBuilder document() {
            return document;
        }

        private int searchedUntil() {
            return searchedUntil;
        }

        private TerminalSession.SearchResult result() {
            return result;
        }

        private boolean scrolledToFirstMatch() {
            return scrolledToFirstMatch;
        }
    }

    private static final class SearchTextFieldSkin extends TextFieldSkin {

        private SearchTextFieldSkin(TextField control) {
            super(control);
        }

        private void applyTheme(TerminalTheme theme) {
            setTextFill(theme.foreground());
            setPromptTextFill(theme.foreground().deriveColor(0, 1, 1, theme.faintOpacity()));
            setHighlightFill(theme.selectionColor());
            setHighlightTextFill(theme.selectionText());
        }
    }
}
