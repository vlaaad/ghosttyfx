package io.github.vlaaad.ghosttyfx;

import java.util.regex.Pattern;
import javafx.animation.AnimationTimer;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyDoubleProperty;
import javafx.beans.property.StringProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.util.Duration;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.skin.TextFieldSkin;
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
    private static final Pattern SHORT_QUERY = Pattern.compile("\\X{1,2}");
    private static final long SEARCH_BUDGET_NS = 1_000_000L;
    private static final long REFRESH_INTERVAL_NS = 24_000_000L;

    private final TerminalSession terminalSession;
    private final Runnable matchSelected;
    private final Runnable render;
    private final HBox view;
    private final TextField field;
    private final Label count;
    private final PauseTransition debounce = new PauseTransition(Duration.millis(300));
    private final AnimationTimer searchTimer = new AnimationTimer() {
        @Override
        public void handle(long now) {
            if (now - lastProgress < REFRESH_INTERVAL_NS) {
                return;
            }
            lastProgress = now;
            terminalSession.advanceSearch(SEARCH_BUDGET_NS);
            if (refreshResults()) {
                render.run();
            }
        }
    };
    private TerminalSession.SearchSnapshot snapshot = TerminalSession.SearchSnapshot.empty();
    private long lastProgress;

    SearchUi(
            TerminalSession terminalSession,
            ObjectProperty<Font> font,
            StringProperty searchPromptText,
            ReadOnlyDoubleProperty terminalWidth,
            Runnable matchSelected,
            Runnable render) {
        this.terminalSession = terminalSession;
        this.matchSelected = matchSelected;
        this.render = render;
        field = new TextField();
        field.setId("ghosttyfx-search-field");
        field.setPrefColumnCount(20);
        field.setMinWidth(0);
        field.setMaxWidth(Double.MAX_VALUE);
        field.setFocusTraversable(true);
        field.setPadding(new Insets(3));
        field.setSkin(new SearchTextFieldSkin(field));
        field.fontProperty().bind(font);
        field.promptTextProperty().bind(searchPromptText);
        field.textProperty().addListener((_, _, value) -> {
            debounce.stop();
            if (visible()) {
                if (SHORT_QUERY.matcher(value).matches()) {
                    debounce.playFromStart();
                } else {
                    startSearch();
                }
            }
        });
        field.sceneProperty().addListener((_, _, _) -> updateSearchTimer());
        debounce.setOnFinished(_ -> startSearch());
        count = new Label("0/0");
        count.setId("ghosttyfx-search-count");
        count.fontProperty().bind(font);
        count.setMinWidth(Label.USE_PREF_SIZE);
        view = new HBox(6, field, count);
        view.setId("ghosttyfx-search");
        view.setAlignment(Pos.CENTER_LEFT);
        view.setMinWidth(0);
        view.prefWidthProperty().bind(Bindings.createDoubleBinding(
                () -> Math.max(0, Math.min(WIDTH, terminalWidth.get() - 16)), terminalWidth));
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

    boolean fieldFocused() {
        return visible() && field.getScene() != null && field.getScene().getFocusOwner() == field;
    }

    TerminalSession.SearchResult result() {
        return snapshot.result();
    }

    int selectedMatch() {
        return snapshot.highlightedMatch();
    }

    int selectedIndex() {
        return snapshot.selectedIndex();
    }

    int matchCount() {
        return snapshot.totalMatches();
    }

    String text() {
        return field.getText();
    }

    void open(String query) {
        var wasVisible = visible();
        view.setVisible(true);
        if (query != null || !wasVisible) {
            field.setText(query == null ? "" : query);
        }
        field.requestFocus();
        field.selectAll();
        if (!wasVisible && debounce.getStatus() != javafx.animation.Animation.Status.RUNNING) {
            startSearch();
        }
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
        debounce.stop();
        searchTimer.stop();
        terminalSession.setSearchNeedle("");
        snapshot = TerminalSession.SearchSnapshot.empty();
        count.setText("0/0");
    }

    private void startSearch() {
        if (!visible()) {
            return;
        }
        terminalSession.setSearchNeedle(field.getText());
        refresh();
    }

    void refresh() {
        if (visible()) {
            refreshResults();
            render.run();
        }
    }

    boolean refreshResults() {
        if (!visible()) {
            return false;
        }
        var next = terminalSession.searchSnapshot();
        var changed = !next.equals(snapshot);
        snapshot = next;
        count.setText(field.getText().isEmpty() ? "0/0"
                : snapshot.totalMatches() == 0 && !snapshot.complete() ? "..."
                : (snapshot.selectedIndex() < 0 ? "-" : Integer.toString(snapshot.selectedIndex() + 1)) + "/" + snapshot.totalMatches());
        updateSearchTimer();
        return changed;
    }

    boolean selectNext() {
        return selectMatch(true);
    }

    boolean selectPrevious() {
        return selectMatch(false);
    }

    private boolean selectMatch(boolean next) {
        if (!visible()) {
            return false;
        }
        if (debounce.getStatus() == javafx.animation.Animation.Status.RUNNING) {
            debounce.stop();
            startSearch();
        }
        terminalSession.advanceSearch(SEARCH_BUDGET_NS);
        refreshResults();
        if (snapshot.selectedIndex() >= 0
                && snapshot.selectedIndex() == (next ? snapshot.totalMatches() - 1 : 0)) {
            render.run();
            return true;
        }
        var selected = terminalSession.selectSearchMatch(next);
        if (selected) {
            matchSelected.run();
        }
        refresh();
        return true;
    }

    private void updateSearchTimer() {
        if (field.getScene() != null && visible() && !snapshot.complete()) {
            searchTimer.start();
        } else {
            searchTimer.stop();
        }
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
