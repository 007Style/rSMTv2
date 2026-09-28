package com.rsmt.gui;

import com.rsmt.core.*;
import com.rsmt.sim.*;
import javafx.beans.value.ChangeListener;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.chart.*;
import javafx.scene.control.*;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.RowConstraints;

import java.net.URL;
import java.util.*;

/**
 * JavaFX controller for {@code main.fxml}.
 *
 * <p>Wires UI controls → {@link SimulationConfig}, starts a background
 * {@link Task} that runs {@link SimulationEngine}, and updates the live
 * pipeline grid and analytics charts on every {@link ClockEvent}.</p>
 *
 * <h3>Pipeline grid layout</h3>
 * <p>Uses PowerPC 600-style stage labels: Fetch → Dispatch → Execute → Complete → Retire.
 * Row 0 is the cycle-number header. Execution unit rows follow — LOCAL CORE rows first
 * (FXU0, FPU0, Branch, LSU), then a section divider, then REMOTE CORE rows (FXU1, FPU1)
 * which are only shown when rSMT is active.</p>
 */
public class SimulationController implements Initializable {

    // ─── Config controls ─────────────────────────────────────────────────────
    @FXML private Spinner<Integer>  numInstSpinner;
    @FXML private Spinner<Integer>  rSmtDelaySpinner;
    @FXML private Spinner<Integer>  fxCyclesSpinner;
    @FXML private Spinner<Integer>  fpCyclesSpinner;
    @FXML private Spinner<Integer>  brCyclesSpinner;
    @FXML private Spinner<Integer>  lsuCyclesSpinner;

    // Mix sliders (FXU / FPU / Branch / LSU — must sum ≤ 100; NOP = remainder)
    @FXML private Slider  percentFxuSlider;
    @FXML private Slider  percentFpuSlider;
    @FXML private Slider  percentBranchSlider;
    @FXML private Slider  percentLsuSlider;
    @FXML private Label   labelPercentFxu;
    @FXML private Label   labelPercentFpu;
    @FXML private Label   labelPercentBranch;
    @FXML private Label   labelPercentLsu;
    @FXML private Label   percentFxuLabel;
    @FXML private Label   percentFpuLabel;
    @FXML private Label   percentBranchLabel;
    @FXML private Label   percentLsuLabel;
    @FXML private Label   nopRemainderLabel;

    // rSMT param sliders
    @FXML private Slider  rSmtAvailSlider;
    @FXML private Slider  rSmtDependsSlider;
    @FXML private Label   labelRsmtAvail;
    @FXML private Label   labelRsmtDepends;
    @FXML private Label   rSmtAvailLabel;
    @FXML private Label   rSmtDependsLabel;

    @FXML private ComboBox<String>  speedCombo;

    // ─── Top-bar buttons ─────────────────────────────────────────────────────
    @FXML private Button helpButton;
    @FXML private Button aboutButton;

    // ─── Control buttons ─────────────────────────────────────────────────────
    @FXML private ToggleButton  smtToggle;
    @FXML private Button        runButton;
    @FXML private Button        pauseButton;
    @FXML private Button        resetButton;

    // ─── Top-bar status ───────────────────────────────────────────────────────
    @FXML private Label cycleCountLabel;
    @FXML private Label ipcLabel;
    @FXML private Label smtStatusLabel;
    @FXML private Label gainLabel;
    @FXML private Label pipelineHeaderLabel;

    // ─── Pipeline grid ────────────────────────────────────────────────────────
    @FXML private GridPane   pipelineGrid;
    @FXML private ScrollPane pipelineScroll;

    // ─── Charts ──────────────────────────────────────────────────────────────
    @FXML private LineChart<Number, Number>  ipcChart;
    @FXML private BarChart<String, Number>   cyclesChart;
    @FXML private BarChart<String, Number>   utilChart;
    @FXML private BarChart<String, Number>   stallChart;

    // ─── Runtime state ───────────────────────────────────────────────────────
    private final GuiSimulationControl guiControl = new GuiSimulationControl();
    private Task<SimulationResult>     simTask;
    private XYChart.Series<Number, Number> ipcSeries;
    private int  currentCycleColumn = 0;
    private boolean lastSmtActive   = true;   // tracks whether remote rows are shown

    // ─── PowerPC 600-style stage label ───────────────────────────────────────
    private static final String STAGE_LABEL =
            "PIPELINE VIEW  (Fetch → Dispatch → Execute → Complete → Retire)";

    /**
     * LOCAL CORE rows — always visible.
     * The order defines their grid row index (1-based, row 0 = cycle header).
     */
    private static final List<String> LOCAL_ROWS =
            List.of(SimulationEngine.SLOT_FXU0,
                    SimulationEngine.SLOT_FPU0,
                    SimulationEngine.SLOT_BRANCH,
                    SimulationEngine.SLOT_LSU);

    /**
     * REMOTE CORE rows — only visible when rSMT is active.
     */
    private static final List<String> REMOTE_ROWS =
            List.of(SimulationEngine.SLOT_FXU1,
                    SimulationEngine.SLOT_FPU1);

    // Computed grid row indices (assigned in initPipelineGridHeaders)
    private int dividerRow     = -1;
    private int remoteCoreRow  = -1; // row index of the "REMOTE CORE" section header
    private int firstRemoteRow = -1; // row index of FXU1

    private static final Map<String, Long> SPEED_MAP = new LinkedHashMap<>();
    static {
        SPEED_MAP.put("10s / cycle",    10000L);
        SPEED_MAP.put("5s / cycle",      5000L);
        SPEED_MAP.put("2s / cycle",      2000L);
        SPEED_MAP.put("1s / cycle",      1000L);
        SPEED_MAP.put("500ms / cycle",    500L);
        SPEED_MAP.put("Ludicrous ⚡",       0L);
    }

    // ─── Initialise ──────────────────────────────────────────────────────────

    @Override
    public void initialize(URL url, ResourceBundle rb) {
        // Spinners — programmatic ValueFactory required
        numInstSpinner.setValueFactory(
                new SpinnerValueFactory.IntegerSpinnerValueFactory(10, 100_000, 500));
        rSmtDelaySpinner.setValueFactory(
                new SpinnerValueFactory.IntegerSpinnerValueFactory(0, 100, 0));

        // Latency spinners — defaults match InstructionGenerator constants
        fxCyclesSpinner.setValueFactory(
                new SpinnerValueFactory.IntegerSpinnerValueFactory(1, 30, InstructionGenerator.FX_CYCLES));
        fpCyclesSpinner.setValueFactory(
                new SpinnerValueFactory.IntegerSpinnerValueFactory(1, 30, InstructionGenerator.FP_CYCLES));
        brCyclesSpinner.setValueFactory(
                new SpinnerValueFactory.IntegerSpinnerValueFactory(1, 30, InstructionGenerator.BR_CYCLES));
        lsuCyclesSpinner.setValueFactory(
                new SpinnerValueFactory.IntegerSpinnerValueFactory(1, 30, InstructionGenerator.LSU_CYCLES));

        // All labels whose text starts with % must be set in code — FXML treats % as resource bundle key prefix
        labelPercentFxu.setText("% FXU (integer)");
        labelPercentFpu.setText("% FPU (float)");
        labelPercentBranch.setText("% Branch");
        labelPercentLsu.setText("% Load/Store");
        labelRsmtAvail.setText("% rSMT Availability");
        labelRsmtDepends.setText("% Data Dependency");

        // PowerPC 600-style stage header
        pipelineHeaderLabel.setText(STAGE_LABEL);

        initMixSliders();
        bindPct(rSmtAvailSlider,   rSmtAvailLabel);
        bindPct(rSmtDependsSlider, rSmtDependsLabel);
        initSpeedCombo();
        initCharts();
        initPipelineGridHeaders(true);
        smtToggle.selectedProperty().addListener((obs, o, v) -> {
            guiControl.setSmtEnabled(v);
            smtToggle.setText(v ? "rSMT ON" : "rSMT OFF");
        });
    }

    /**
     * Sets up the 4 mix sliders with mutual constraint: FXU + FPU + Branch + LSU ≤ 100.
     *
     * <p>When the user moves slider A up, the remaining budget (100 − A) is distributed
     * proportionally across the other three. Each slider is protected from going below 0.
     * NOP remainder = 100 − sum, displayed as a read-only label.</p>
     *
     * <p>Initial values: FXU=50, FPU=15, Branch=10, LSU=15 → NOP=10.</p>
     */
    private void initMixSliders() {
        // Set initial values
        percentFxuSlider.setValue(50);
        percentFpuSlider.setValue(15);
        percentBranchSlider.setValue(10);
        percentLsuSlider.setValue(15);

        // Collect into array for generic handling
        Slider[] mixSliders = {percentFxuSlider, percentFpuSlider, percentBranchSlider, percentLsuSlider};
        Label[]  mixLabels  = {percentFxuLabel,  percentFpuLabel,  percentBranchLabel,  percentLsuLabel};

        // Initial label text
        for (int i = 0; i < mixSliders.length; i++) {
            mixLabels[i].setText((int) mixSliders[i].getValue() + "%");
        }
        updateNopLabel();

        // Attach mutual-adjustment listeners
        for (int i = 0; i < mixSliders.length; i++) {
            final int idx = i;
            mixSliders[idx].valueProperty().addListener((obs, oldVal, newVal) -> {
                // Snap to integer
                int newInt = (int) Math.round(newVal.doubleValue());
                mixLabels[idx].setText(newInt + "%");

                // How much budget is left for the other three sliders?
                int budget = 100 - newInt;
                if (budget < 0) {
                    // Clamp the moved slider itself
                    mixSliders[idx].setValue(100);
                    return;
                }

                // Sum of the other three current values
                int otherSum = 0;
                for (int j = 0; j < mixSliders.length; j++) {
                    if (j != idx) otherSum += (int) Math.round(mixSliders[j].getValue());
                }

                if (otherSum > budget) {
                    // Need to trim. Distribute reduction proportionally, largest-first to avoid rounding drift.
                    int excess = otherSum - budget;
                    // Build sorted list of (index, value) for the other sliders, descending by value
                    List<int[]> others = new ArrayList<>();
                    for (int j = 0; j < mixSliders.length; j++) {
                        if (j != idx) others.add(new int[]{j, (int) Math.round(mixSliders[j].getValue())});
                    }
                    others.sort((a, b) -> b[1] - a[1]);

                    // Trim proportionally: subtract from largest first
                    for (int[] entry : others) {
                        if (excess <= 0) break;
                        int canTrim = Math.min(entry[1], excess);
                        entry[1] -= canTrim;
                        excess   -= canTrim;
                    }

                    // Apply the adjusted values — suppress re-entrant listeners with a flag
                    adjusting = true;
                    try {
                        for (int[] entry : others) {
                            mixSliders[entry[0]].setValue(entry[1]);
                            mixLabels[entry[0]].setText(entry[1] + "%");
                        }
                    } finally {
                        adjusting = false;
                    }
                }

                updateNopLabel();
            });
        }
    }

    /** Re-entrancy guard — prevents slider listeners from triggering each other infinitely. */
    private boolean adjusting = false;

    private void updateNopLabel() {
        int sum = (int) Math.round(percentFxuSlider.getValue())
                + (int) Math.round(percentFpuSlider.getValue())
                + (int) Math.round(percentBranchSlider.getValue())
                + (int) Math.round(percentLsuSlider.getValue());
        nopRemainderLabel.setText("NOP (remainder): " + (100 - sum) + "%");
    }

    private void bindPct(Slider s, Label l) {
        l.setText((int) s.getValue() + "%");
        s.valueProperty().addListener((obs, o, v) -> {
            if (!adjusting) l.setText(v.intValue() + "%");
        });
    }

    private void initSpeedCombo() {
        speedCombo.setItems(FXCollections.observableArrayList(SPEED_MAP.keySet()));
        speedCombo.getSelectionModel().select("500ms / cycle");
        speedCombo.valueProperty().addListener((obs, o, v) -> {
            if (v != null) guiControl.setTickDelayMs(SPEED_MAP.getOrDefault(v, 500L));
        });
    }

    private void initCharts() {
        ipcSeries = new XYChart.Series<>();
        ipcSeries.setName("IPC");
        ipcChart.getData().add(ipcSeries);
    }

    // ─── Pipeline grid headers ───────────────────────────────────────────────

    /**
     * Builds the fixed row-label column (column 0) of the pipeline grid.
     *
     * <p>Layout (1-based grid rows):
     * <pre>
     *   row 0  : cycle number headers  (added per tick)
     *   row 1  : "LOCAL CORE" section label
     *   rows 2…5 : FXU0, FPU0, Branch, LSU
     *   row 6  : ─── divider ───
     *   row 7  : "REMOTE CORE" section label   (hidden when SMT off)
     *   rows 8…9 : FXU1, FPU1                  (hidden when SMT off)
     * </pre>
     * </p>
     */
    private void initPipelineGridHeaders(boolean smtActive) {
        pipelineGrid.getChildren().clear();
        currentCycleColumn = 1;
        lastSmtActive = smtActive;

        int row = 0; // row 0 reserved for cycle numbers

        // "LOCAL CORE" section header
        row++;
        Label localHeader = new Label("LOCAL CORE");
        localHeader.getStyleClass().add("section-header");
        pipelineGrid.add(localHeader, 0, row);

        // Local execution unit rows
        for (String slot : LOCAL_ROWS) {
            row++;
            Label lbl = new Label(slot);
            lbl.getStyleClass().add("row-header");
            pipelineGrid.add(lbl, 0, row);
        }

        // Divider row
        row++;
        dividerRow = row;
        Label divLbl = new Label("─────");
        divLbl.getStyleClass().add("divider-label");
        pipelineGrid.add(divLbl, 0, row);

        // Remote core section — only add labels if SMT active
        row++;
        remoteCoreRow = row;
        Label remoteHeader = new Label("REMOTE CORE");
        remoteHeader.getStyleClass().add("section-header-remote");
        remoteHeader.setVisible(smtActive);
        remoteHeader.setManaged(smtActive);
        pipelineGrid.add(remoteHeader, 0, row);

        firstRemoteRow = row + 1;
        for (String slot : REMOTE_ROWS) {
            row++;
            Label lbl = new Label(slot);
            lbl.getStyleClass().add("row-header-remote");
            lbl.setVisible(smtActive);
            lbl.setManaged(smtActive);
            pipelineGrid.add(lbl, 0, row);
        }
    }

    // ─── Button handlers ─────────────────────────────────────────────────────

    @FXML
    private void onRun() {
        SimulationConfig config = buildConfig();
        long seed = System.currentTimeMillis();
        InstructionGenerator gen = new InstructionGenerator(seed);
        List<Instruction> instructions = gen.generate(config);

        guiControl.setPaused(false);
        guiControl.setSmtEnabled(smtToggle.isSelected());
        guiControl.setTickDelayMs(SPEED_MAP.getOrDefault(speedCombo.getValue(), 500L));

        GuiSimulationListener guiListener = new GuiSimulationListener(
                this::onTick,
                this::onComplete
        );

        SimulationEngine engine = new SimulationEngine(guiListener, guiControl);

        simTask = new Task<>() {
            @Override protected SimulationResult call() {
                return engine.run(config, instructions, gen);
            }
        };

        simTask.setOnFailed(e -> {
            gainLabel.setText("ERROR");
            setRunning(false);
        });

        runButton.setDisable(true);
        pauseButton.setDisable(false);
        gainLabel.setText("Running…");

        Thread t = new Thread(simTask);
        t.setDaemon(true);
        t.start();
    }

    @FXML
    private void onPause() {
        boolean nowPaused = !guiControl.isPaused();
        guiControl.setPaused(nowPaused);
        pauseButton.setText(nowPaused ? "▶ Resume" : "⏸ Pause");
    }

    @FXML
    private void onReset() {
        if (simTask != null && simTask.isRunning()) {
            simTask.cancel();
            guiControl.setPaused(false);
        }
        initPipelineGridHeaders(true);
        clearCharts();
        cycleCountLabel.setText("0");
        ipcLabel.setText("0.000");
        gainLabel.setText("—");
        smtStatusLabel.setText("rSMT ON");
        smtStatusLabel.getStyleClass().setAll("smt-on-label");
        setRunning(false);
    }

    // ─── Clock tick handler (called on FX thread via Platform.runLater) ──────

    private void onTick(ClockEvent event) {
        // Top bar stats
        cycleCountLabel.setText(String.valueOf(event.cycleNumber()));
        ipcLabel.setText(String.format("%.3f", event.currentIpc()));

        // SMT indicator
        boolean smt = event.smtActive();
        smtStatusLabel.setText(smt ? "rSMT ON" : "rSMT OFF");
        smtStatusLabel.getStyleClass().setAll(smt ? "smt-on-label" : "smt-off-label");

        // Show/hide REMOTE CORE rows when SMT state changes
        if (smt != lastSmtActive) {
            updateRemoteCoreVisibility(smt);
            lastSmtActive = smt;
        }

        // IPC series (sample every 5 ticks to keep chart smooth)
        if (event.cycleNumber() % 5 == 0) {
            ipcSeries.getData().add(
                    new XYChart.Data<>(event.cycleNumber(), event.currentIpc()));
            // Keep at most 200 points on screen
            if (ipcSeries.getData().size() > 200) {
                ipcSeries.getData().remove(0);
            }
        }

        // Pipeline grid: add a new column for this cycle
        addPipelineColumn(event);
    }

    /**
     * Shows or hides the REMOTE CORE section header and execution unit rows
     * based on whether rSMT is currently active.
     */
    private void updateRemoteCoreVisibility(boolean smtActive) {
        pipelineGrid.getChildren().forEach(node -> {
            Integer row = GridPane.getRowIndex(node);
            if (row == null) return;
            if (row == remoteCoreRow || (row >= firstRemoteRow && row < firstRemoteRow + REMOTE_ROWS.size())) {
                // Only toggle the row-label nodes in column 0
                Integer col = GridPane.getColumnIndex(node);
                if (col == null || col == 0) {
                    node.setVisible(smtActive);
                    node.setManaged(smtActive);
                }
            }
        });
    }

    // ─── Completion handler ───────────────────────────────────────────────────

    private void onComplete(SimulationResult result) {
        // Gain callout
        gainLabel.setText(String.format("%.1f%%", result.performanceGain()));

        // Cycles comparison bar chart
        XYChart.Series<String, Number> smtOnSeries  = new XYChart.Series<>();
        XYChart.Series<String, Number> smtOffSeries = new XYChart.Series<>();
        smtOnSeries.setName("rSMT ON");
        smtOffSeries.setName("rSMT OFF");
        smtOnSeries.getData().add(new XYChart.Data<>("Cycles", result.rCycles()));
        smtOffSeries.getData().add(new XYChart.Data<>("Cycles", result.normCycles()));
        cyclesChart.getData().setAll(smtOnSeries, smtOffSeries);

        // Unit utilization chart
        XYChart.Series<String, Number> utilSeries = new XYChart.Series<>();
        result.unitUtilization().forEach((slot, pct) ->
                utilSeries.getData().add(new XYChart.Data<>(slot, pct)));
        utilChart.getData().setAll(utilSeries);

        // Stall breakdown
        XYChart.Series<String, Number> stallSeries = new XYChart.Series<>();
        stallSeries.getData().add(new XYChart.Data<>("Structural", result.structuralStalls()));
        stallSeries.getData().add(new XYChart.Data<>("Data",       result.dataStalls()));
        stallSeries.getData().add(new XYChart.Data<>("Control",    result.controlStalls()));
        stallChart.getData().setAll(stallSeries);

        setRunning(false);
    }

    // ─── Pipeline grid rendering ──────────────────────────────────────────────

    /**
     * Adds one column (one clock cycle) to the scrollable pipeline grid.
     *
     * <p>Grid row layout:
     * <pre>
     *   row 0          : cycle number header
     *   row 1          : "LOCAL CORE" label (no cell)
     *   rows 2…5       : FXU0, FPU0, Branch, LSU
     *   row 6          : divider (no cell)
     *   row 7          : "REMOTE CORE" label (no cell)
     *   rows 8…9       : FXU1, FPU1
     * </pre>
     */
    private void addPipelineColumn(ClockEvent event) {
        int col = currentCycleColumn;

        // Cycle number header (row 0)
        Label cycleLbl = new Label(String.valueOf(event.cycleNumber()));
        cycleLbl.getStyleClass().add("cycle-header");
        pipelineGrid.add(cycleLbl, col, 0);

        // Local core rows: start at grid row 2 (row 1 = section header, row 2 = FXU0)
        int gridRow = 2;
        for (String slot : LOCAL_ROWS) {
            Instruction inst = event.slotSnapshot().get(slot);
            Label cell = buildCell(slot, inst, event);
            pipelineGrid.add(cell, col, gridRow);
            gridRow++;
        }

        // Skip divider row (gridRow is now dividerRow index)
        gridRow++; // skip divider
        // Skip remote core section header
        gridRow++; // skip "REMOTE CORE" label row

        // Remote core rows — add cells even if hidden (grid needs cells to fill)
        for (String slot : REMOTE_ROWS) {
            Instruction inst = event.slotSnapshot().get(slot);
            Label cell = buildCell(slot, inst, event);
            // Mark remote cells as remote for distinct styling
            if (!cell.getStyleClass().contains("cell-idle")) {
                cell.getStyleClass().add("cell-remote");
            }
            cell.setVisible(event.smtActive());
            cell.setManaged(event.smtActive());
            pipelineGrid.add(cell, col, gridRow);
            gridRow++;
        }

        currentCycleColumn++;

        // Auto-scroll to keep latest column visible
        pipelineScroll.setHvalue(1.0);
    }

    private Label buildCell(String slot, Instruction inst, ClockEvent event) {
        Label lbl = new Label();
        lbl.getStyleClass().add("pipeline-cell");

        if (inst == null) {
            // Show stall indicator if this slot was involved in a stall
            if (event.structuralStall() && (slot.equals(SimulationEngine.SLOT_FXU0)
                    || slot.equals(SimulationEngine.SLOT_FPU0))) {
                lbl.setText("STALL");
                lbl.getStyleClass().add("cell-stall-structural");
            } else if (event.dataStall() && slot.equals(SimulationEngine.SLOT_FXU1)) {
                lbl.setText("DEP");
                lbl.getStyleClass().add("cell-stall-data");
            } else if (event.controlStall() && slot.equals(SimulationEngine.SLOT_FXU0)) {
                lbl.setText("BR");
                lbl.getStyleClass().add("cell-stall-control");
            } else {
                lbl.getStyleClass().add("cell-idle");
            }
        } else {
            lbl.setText(instructionLabel(inst));
            lbl.getStyleClass().add(instStyle(slot, inst));
        }
        return lbl;
    }

    private static String instructionLabel(Instruction inst) {
        return switch (inst) {
            case FxuInstruction    i -> opName(i.opIndex());
            case FpuInstruction    i -> fpName(i.opIndex());
            case BranchInstruction i -> "B";
            case LoadInstruction   i -> "LD";
            case StoreInstruction  i -> "ST";
            case NopInstruction    i -> "NOP";
        };
    }

    private static String instStyle(String slot, Instruction inst) {
        return switch (inst) {
            case FxuInstruction    i -> "cell-fxu";
            case FpuInstruction    i -> "cell-fpu";
            case BranchInstruction i -> "cell-branch";
            case LoadInstruction   i -> "cell-load";
            case StoreInstruction  i -> "cell-store";
            case NopInstruction    i -> "cell-nop";
        };
    }

    private static String opName(int idx) {
        return switch (idx) { case 0 -> "ADD"; case 1 -> "SUB"; case 2 -> "MUL"; default -> "DIV"; };
    }

    private static String fpName(int idx) {
        return switch (idx) { case 4 -> "fADD"; case 5 -> "fSUB"; case 6 -> "fMUL"; default -> "fDIV"; };
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private SimulationConfig buildConfig() {
        return new SimulationConfig(
                numInstSpinner.getValue(),
                rSmtDelaySpinner.getValue(),
                (int) Math.round(percentFxuSlider.getValue()),
                (int) Math.round(percentFpuSlider.getValue()),
                (int) Math.round(percentBranchSlider.getValue()),
                (int) Math.round(percentLsuSlider.getValue()),
                (int) rSmtAvailSlider.getValue(),
                (int) rSmtDependsSlider.getValue(),
                fxCyclesSpinner.getValue(),
                fpCyclesSpinner.getValue(),
                brCyclesSpinner.getValue(),
                lsuCyclesSpinner.getValue()
        );
    }

    private void setRunning(boolean running) {
        runButton.setDisable(running);
        pauseButton.setDisable(!running);
        if (!running) pauseButton.setText("⏸ Pause");
    }

    private void clearCharts() {
        ipcSeries.getData().clear();
        cyclesChart.getData().clear();
        utilChart.getData().clear();
        stallChart.getData().clear();
    }

    // ─── About & Help dialogs ─────────────────────────────────────────────────

    @FXML
    private void onAbout() {
        showWebDialog("About rSMTv2", 620, 540, buildAboutHtml());
    }

    @FXML
    private void onHelp() {
        showWebDialog("rSMTv2 Help", 780, 700, buildHelpHtml());
    }

    private void showWebDialog(String title, double w, double h, String html) {
        javafx.scene.web.WebView wv = new javafx.scene.web.WebView();
        wv.getEngine().loadContent(html, "text/html");
        wv.setPrefSize(w, h);
        javafx.stage.Stage dialog = new javafx.stage.Stage();
        dialog.setTitle(title);
        dialog.initModality(javafx.stage.Modality.APPLICATION_MODAL);
        dialog.setScene(new javafx.scene.Scene(wv, w, h));
        dialog.setResizable(true);
        dialog.show();
    }

    // ─── About HTML ───────────────────────────────────────────────────────────

    private static String buildAboutHtml() {
        return """
<!DOCTYPE html>
<html>
<head>
<meta charset="UTF-8"/>
<style>
  * { margin:0; padding:0; box-sizing:border-box; }
  body {
    background: #0a0a1a;
    color: #e6f1ff;
    font-family: 'Menlo', 'Consolas', monospace;
    overflow: hidden;
    height: 100vh;
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    text-align: center;
  }

  /* ── Animated chip grid background ── */
  canvas#chip { position:fixed; top:0; left:0; z-index:0; opacity:0.18; }

  .content { position:relative; z-index:1; padding: 32px 40px; }

  .chip-icon {
    font-size: 64px;
    animation: pulse 2s ease-in-out infinite;
    display: block;
    margin-bottom: 12px;
  }
  @keyframes pulse {
    0%,100% { transform: scale(1);   opacity: 1;   }
    50%      { transform: scale(1.1); opacity: 0.8; }
  }

  h1 {
    font-size: 22px;
    color: #00d4aa;
    font-weight: bold;
    letter-spacing: 1px;
    margin-bottom: 4px;
    animation: glow 3s ease-in-out infinite;
  }
  @keyframes glow {
    0%,100% { text-shadow: 0 0 8px #00d4aa88; }
    50%      { text-shadow: 0 0 24px #00d4aacc, 0 0 48px #00d4aa44; }
  }

  .patent {
    font-size: 12px;
    color: #4a90d9;
    margin: 6px 0 20px;
    letter-spacing: 2px;
    text-transform: uppercase;
  }

  .tagline {
    font-size: 15px;
    color: #ccd6f6;
    margin-bottom: 28px;
    font-style: italic;
  }
  .tagline span { color: #00d4aa; font-style: normal; font-weight: bold; }

  /* ── Signal line animation ── */
  .signals {
    display: flex;
    gap: 6px;
    justify-content: center;
    margin-bottom: 24px;
  }
  .sig {
    width: 40px; height: 3px;
    border-radius: 2px;
    background: #00d4aa;
    animation: sig-flash 1.8s ease-in-out infinite;
  }
  .sig:nth-child(2) { animation-delay: 0.3s; background: #4488ff; }
  .sig:nth-child(3) { animation-delay: 0.6s; background: #ffdd44; }
  .sig:nth-child(4) { animation-delay: 0.9s; background: #ff6b6b; }
  .sig:nth-child(5) { animation-delay: 1.2s; background: #44ddff; }
  @keyframes sig-flash {
    0%,100% { opacity: 0.25; transform: scaleX(1);   }
    50%      { opacity: 1;    transform: scaleX(1.6); }
  }

  /* ── Pipeline animation ── */
  .pipeline {
    display: flex;
    gap: 4px;
    justify-content: center;
    align-items: center;
    margin-bottom: 28px;
  }
  .stage {
    padding: 4px 10px;
    border-radius: 4px;
    font-size: 10px;
    font-weight: bold;
    letter-spacing: 1px;
    animation: stage-light 4s linear infinite;
  }
  .stage:nth-child(1) { background:#1a3a5a; color:#44aaff; animation-delay:0s; }
  .stage:nth-child(2) { background:#1a3a5a; color:#44aaff; animation-delay:0.8s; }
  .stage:nth-child(3) { background:#1a3a5a; color:#44aaff; animation-delay:1.6s; }
  .stage:nth-child(4) { background:#1a3a5a; color:#44aaff; animation-delay:2.4s; }
  .stage:nth-child(5) { background:#1a3a5a; color:#44aaff; animation-delay:3.2s; }
  .arrow { color:#2a4a6a; font-size:12px; }
  @keyframes stage-light {
    0%,15%,100% { background:#1a3a5a; color:#44aaff; box-shadow:none; }
    5%,10%      { background:#00d4aa; color:#0a0a1a;
                  box-shadow: 0 0 14px #00d4aaaa; }
  }

  .version {
    font-size: 10px;
    color: #2a4a6a;
    letter-spacing: 2px;
    text-transform: uppercase;
  }
</style>
</head>
<body>
<canvas id="chip"></canvas>
<div class="content">
  <span class="chip-icon">⬛</span>
  <h1>rSMTv2</h1>
  <h1 style="font-size:14px; margin-top:2px;">IBM PowerPC Reverse Simultaneous Multithreading</h1>
  <div class="patent">IBM Patent US8595468 &nbsp;·&nbsp; Filed 2009</div>

  <div class="signals">
    <div class="sig"></div>
    <div class="sig"></div>
    <div class="sig"></div>
    <div class="sig"></div>
    <div class="sig"></div>
  </div>

  <div class="pipeline">
    <div class="stage">FETCH</div>
    <div class="arrow">→</div>
    <div class="stage">DISPATCH</div>
    <div class="arrow">→</div>
    <div class="stage">EXECUTE</div>
    <div class="arrow">→</div>
    <div class="stage">COMPLETE</div>
    <div class="arrow">→</div>
    <div class="stage">RETIRE</div>
  </div>

  <div class="tagline">
    From the minds of <span>IBM Bob</span> &amp; <span>Daneyand</span>
  </div>

  <div class="version">Version 2.0 &nbsp;·&nbsp; Java 21 &nbsp;·&nbsp; JavaFX 21</div>
</div>

<script>
  // Animated chip trace grid on canvas
  var c = document.getElementById('chip');
  var ctx = c.getContext('2d');
  function resize() { c.width = window.innerWidth; c.height = window.innerHeight; }
  resize();

  var nodes = [];
  for (var i = 0; i < 60; i++) {
    nodes.push({
      x: Math.random() * c.width,
      y: Math.random() * c.height,
      vx: (Math.random() - 0.5) * 0.4,
      vy: (Math.random() - 0.5) * 0.4,
      r: Math.random() * 2 + 1
    });
  }

  function draw() {
    ctx.clearRect(0, 0, c.width, c.height);
    // Trace lines between nearby nodes
    for (var i = 0; i < nodes.length; i++) {
      var n = nodes[i];
      n.x += n.vx; n.y += n.vy;
      if (n.x < 0 || n.x > c.width)  n.vx *= -1;
      if (n.y < 0 || n.y > c.height) n.vy *= -1;
      for (var j = i+1; j < nodes.length; j++) {
        var m = nodes[j];
        var dx = n.x - m.x, dy = n.y - m.y;
        var dist = Math.sqrt(dx*dx + dy*dy);
        if (dist < 110) {
          ctx.beginPath();
          ctx.moveTo(n.x, n.y);
          // Right-angle trace style
          ctx.lineTo(n.x, m.y);
          ctx.lineTo(m.x, m.y);
          ctx.strokeStyle = '#00d4aa';
          ctx.lineWidth = 0.5;
          ctx.globalAlpha = 1 - dist/110;
          ctx.stroke();
          ctx.globalAlpha = 1;
        }
      }
      // Node dot
      ctx.beginPath();
      ctx.arc(n.x, n.y, n.r, 0, Math.PI*2);
      ctx.fillStyle = '#00d4aa';
      ctx.fill();
    }
    requestAnimationFrame(draw);
  }
  draw();
</script>
</body>
</html>
""";
    }

    // ─── Help HTML ────────────────────────────────────────────────────────────

    private static String buildHelpHtml() {
        return """
<!DOCTYPE html>
<html>
<head>
<meta charset="UTF-8"/>
<style>
  * { box-sizing: border-box; margin:0; padding:0; }
  body {
    background: #0d1117;
    color: #c9d1d9;
    font-family: -apple-system, 'Segoe UI', sans-serif;
    font-size: 13px;
    line-height: 1.7;
    padding: 28px 32px 40px;
  }
  h1 {
    color: #00d4aa;
    font-size: 20px;
    border-bottom: 2px solid #00d4aa44;
    padding-bottom: 8px;
    margin-bottom: 18px;
    letter-spacing: 0.5px;
  }
  h2 {
    color: #4488ff;
    font-size: 14px;
    font-weight: bold;
    margin: 24px 0 8px;
    text-transform: uppercase;
    letter-spacing: 1px;
  }
  h3 {
    color: #00d4aa;
    font-size: 12px;
    font-weight: bold;
    margin: 14px 0 4px;
    text-transform: uppercase;
    letter-spacing: 0.5px;
  }
  p { margin-bottom: 10px; color: #b0bec5; }
  strong { color: #e6f1ff; }
  code {
    background: #161b22;
    color: #00d4aa;
    padding: 1px 5px;
    border-radius: 3px;
    font-family: 'Menlo', 'Consolas', monospace;
    font-size: 11px;
  }
  .patent-box {
    background: #0f3460;
    border: 1px solid #4488ff44;
    border-left: 3px solid #4488ff;
    border-radius: 4px;
    padding: 12px 16px;
    margin: 12px 0;
  }
  .patent-box .num { color: #4488ff; font-weight: bold; font-size: 15px; }
  .patent-box .desc { color: #8892b0; font-size: 12px; margin-top: 4px; }

  table {
    width: 100%;
    border-collapse: collapse;
    margin: 10px 0 16px;
    font-size: 12px;
  }
  th {
    background: #161b22;
    color: #00d4aa;
    text-align: left;
    padding: 6px 10px;
    border-bottom: 1px solid #00d4aa44;
    font-size: 11px;
    text-transform: uppercase;
    letter-spacing: 0.5px;
  }
  td {
    padding: 5px 10px;
    border-bottom: 1px solid #21262d;
    color: #b0bec5;
    vertical-align: top;
  }
  td:first-child { color: #e6f1ff; font-weight: bold; white-space: nowrap; }
  tr:hover td { background: #161b22; }

  .tag {
    display: inline-block;
    padding: 1px 7px;
    border-radius: 10px;
    font-size: 10px;
    font-weight: bold;
    margin-right: 4px;
  }
  .tag-fxu    { background:#1a4a2a; color:#1aff7a; }
  .tag-fpu    { background:#1a2a4a; color:#4488ff; }
  .tag-branch { background:#4a3a1a; color:#ffdd44; }
  .tag-lsu    { background:#1a3a4a; color:#44ddff; }
  .tag-nop    { background:#2a2a3a; color:#888899; }
  .tag-stall  { background:#3a1a1a; color:#ff9999; }
  .tag-remote { background:#3a2a1a; color:#ff9944; }

  .tip {
    background: #1a2a1a;
    border-left: 3px solid #00d4aa;
    padding: 8px 12px;
    border-radius: 0 4px 4px 0;
    margin: 10px 0;
    font-size: 12px;
    color: #8892b0;
  }
  .tip strong { color: #00d4aa; }

  .section { margin-bottom: 4px; }
</style>
</head>
<body>

<h1>rSMTv2 — Help &amp; Reference</h1>

<!-- ── WHAT IS THIS ── -->
<h2>What is rSMTv2?</h2>
<p>
  <strong>rSMTv2</strong> is an interactive demonstration of <strong>Reverse Simultaneous
  Multithreading (rSMT)</strong> — a technique invented at IBM and patented in 2009.
  Traditional SMT lets a single physical core run multiple threads by sharing its
  execution units. <strong>rSMT flips this</strong>: a single thread can dispatch
  instructions to execution units on a <em>different</em> physical core, borrowing
  idle capacity across cores when latency and data-hazard conditions allow it.
</p>
<p>
  This simulator models a <strong>PowerPC 600-style in-order pipeline</strong> with two
  execution paths — a <em>Local Core</em> (always active) and a <em>Remote Core</em>
  (the rSMT target, active only when rSMT is ON and conditions are met). Watch
  instructions flow through the pipeline in real time and compare IPC, stall counts,
  and cycle counts with rSMT on vs. off.
</p>

<!-- ── THE PATENT ── -->
<h2>The Patent</h2>
<div class="patent-box">
  <div class="num">IBM Patent US8,595,468 B2</div>
  <div class="desc">
    "Reverse Simultaneous Multi-Threading" — IBM Corporation, filed 2009.<br/>
    Inventors describe a method by which a processor thread may dispatch fixed-point
    and floating-point operations to an otherwise-idle execution unit on a sibling
    core, subject to inter-core latency and data-dependency constraints, thereby
    increasing effective instruction throughput without adding hardware threads.
  </div>
</div>
<p>
  The key insight: modern out-of-order cores often have FXU and FPU slots sitting
  idle while the primary thread is stalled on a long-latency load or branch. rSMT
  harvests that slack for the benefit of the <em>same</em> thread — no OS scheduling
  changes required.
</p>

<!-- ── PIPELINE ── -->
<h2>Pipeline Model (PowerPC 600 Style)</h2>
<p>Every instruction travels through five named stages:</p>
<table>
  <tr><th>Stage</th><th>What happens</th></tr>
  <tr><td>FETCH</td><td>Instruction is pulled from the stream into the pipeline.</td></tr>
  <tr><td>DISPATCH</td><td>Instruction is decoded and routed to the correct execution unit queue.</td></tr>
  <tr><td>EXECUTE</td><td>The execution unit processes the instruction for its full latency (FXU=5, FPU=6, Branch=4, LSU=3 cycles by default).</td></tr>
  <tr><td>COMPLETE</td><td>Result is written back; the unit slot is freed.</td></tr>
  <tr><td>RETIRE</td><td>Instruction is architecturally committed and removed from the pipeline.</td></tr>
</table>
<p>
  The simulator uses a <strong>single-issue in-order pipeline</strong>. rSMT adds a
  second issue opportunity per cycle to the remote FXU and FPU slots.
</p>

<!-- ── EXECUTION UNITS ── -->
<h2>Execution Units &amp; Colours</h2>
<table>
  <tr><th>Unit</th><th>Instruction type</th><th>Latency</th><th>Core</th></tr>
  <tr>
    <td><span class="tag tag-fxu">FXU0</span></td>
    <td>Integer: ADD, SUB, MUL, DIV</td>
    <td>5 cycles (default)</td>
    <td>Local</td>
  </tr>
  <tr>
    <td><span class="tag tag-fxu">FXU1</span> <span class="tag tag-remote">REMOTE</span></td>
    <td>Integer (rSMT offload)</td>
    <td>5 + rSMT Delay</td>
    <td>Remote</td>
  </tr>
  <tr>
    <td><span class="tag tag-fpu">FPU0</span></td>
    <td>Float: fADD, fSUB, fMUL, fDIV</td>
    <td>6 cycles (default)</td>
    <td>Local</td>
  </tr>
  <tr>
    <td><span class="tag tag-fpu">FPU1</span> <span class="tag tag-remote">REMOTE</span></td>
    <td>Float (rSMT offload)</td>
    <td>6 + rSMT Delay</td>
    <td>Remote</td>
  </tr>
  <tr>
    <td><span class="tag tag-branch">Branch</span></td>
    <td>Conditional branches</td>
    <td>4 cycles (default)</td>
    <td>Local</td>
  </tr>
  <tr>
    <td><span class="tag tag-lsu">LSU</span></td>
    <td>Load / Store</td>
    <td>3 cycles (default)</td>
    <td>Local</td>
  </tr>
  <tr>
    <td><span class="tag tag-nop">NOP</span></td>
    <td>No-operation (fills remainder %)</td>
    <td>0 cycles</td>
    <td>—</td>
  </tr>
</table>

<!-- ── STALLS ── -->
<h2>Stall Types</h2>
<table>
  <tr><th>Stall</th><th>Colour</th><th>Cause</th></tr>
  <tr>
    <td>Structural</td>
    <td><span class="tag tag-stall">STALL</span></td>
    <td>The target execution unit is still busy with a previous instruction. The new instruction must wait.</td>
  </tr>
  <tr>
    <td>Data</td>
    <td><span class="tag" style="background:#3a2a00;color:#ffe699;">DEP</span></td>
    <td>A data hazard (read-after-write dependency) prevents issuing to the remote FXU slot.</td>
  </tr>
  <tr>
    <td>Control</td>
    <td><span class="tag" style="background:#1a1a3a;color:#99b8ff;">BR</span></td>
    <td>A branch is in-flight. The Fetch stage is frozen until the branch retires.</td>
  </tr>
</table>

<!-- ── GUI REFERENCE ── -->
<h2>GUI Element Reference</h2>

<h3>Left Panel — Simulation Config</h3>
<table>
  <tr><th>Control</th><th>What it does</th></tr>
  <tr><td>Instructions</td><td>Total number of instructions to generate and simulate. More = longer run, smoother IPC curve.</td></tr>
  <tr><td>rSMT Delay (cycles)</td><td>Extra latency added to remote-core execution (FXU1, FPU1). Models the real inter-core bus penalty. 0 = no overhead.</td></tr>
  <tr><td><code>% rSMT Availability</code></td><td>Probability (0–100%) that the remote core slot is actually free each cycle. 100% = always available.</td></tr>
  <tr><td><code>% Data Dependency</code></td><td>Probability that a data hazard blocks the rSMT issue attempt. 100% = always blocked (rSMT never fires).</td></tr>
  <tr><td>Animation Speed</td><td>Delay between clock ticks. "Ludicrous ⚡" runs at full speed with no delay.</td></tr>
  <tr><td>rSMT ON toggle</td><td>Enable or disable rSMT mid-simulation. The pipeline grid shows/hides the REMOTE CORE section dynamically.</td></tr>
  <tr><td>▶ Run</td><td>Generate instructions and start the simulation. Runs SMT-ON pass, then SMT-OFF pass.</td></tr>
  <tr><td>⏸ Pause / ▶ Resume</td><td>Freeze and unfreeze the simulation at any cycle.</td></tr>
  <tr><td>⟳ Reset</td><td>Stop the simulation and clear the pipeline grid and all charts.</td></tr>
  <tr><td>GAIN</td><td>Performance gain = (SMT-OFF cycles / SMT-ON cycles) × 100%. Values above 100% mean rSMT helped.</td></tr>
</table>

<h3>Right Panel — Workload Mix</h3>
<p>Four sliders control the exact percentage of each instruction type generated.
  They are <strong>mutually constrained</strong> — dragging one up automatically
  trims the others (largest first) so the total never exceeds 100%. The
  <strong>NOP remainder</strong> fills whatever percentage is left over.</p>
<table>
  <tr><th>Slider</th><th>Effect</th></tr>
  <tr><td><code>% FXU (integer)</code></td><td>Share of integer ops (ADD/SUB/MUL/DIV). High values = more FXU congestion, more rSMT opportunities.</td></tr>
  <tr><td><code>% FPU (float)</code></td><td>Share of floating-point ops. FPU has the longest latency (6 cycles), so high FPU = lots of structural stalls on FPU0.</td></tr>
  <tr><td><code>% Branch</code></td><td>Share of branch ops. Each branch freezes Fetch for 4 cycles — high branch % hammers IPC.</td></tr>
  <tr><td><code>% Load/Store</code></td><td>Share of memory ops. LSU has 3-cycle latency; shares one slot, so back-to-back LSU causes stalls.</td></tr>
</table>

<h3>Right Panel — Latency (cycles)</h3>
<p>Override the default execution latency for each unit type. Changes take effect
  on the next <strong>Run</strong>. Useful for modelling faster/slower hardware:</p>
<table>
  <tr><th>Spinner</th><th>Default</th><th>Tip</th></tr>
  <tr><td>FXU cycles</td><td>5</td><td>Lower = integer ops retire faster = less structural stall on FXU0.</td></tr>
  <tr><td>FPU cycles</td><td>6</td><td>The longest default. Reducing this has a big IPC impact when FPU % is high.</td></tr>
  <tr><td>Branch cycles</td><td>4</td><td>Reduce to simulate a branch predictor that resolves early.</td></tr>
  <tr><td>LSU cycles</td><td>3</td><td>Increase to model cache misses.</td></tr>
</table>

<h3>Pipeline Grid (Centre)</h3>
<p>Each column is one clock cycle. Each row is one execution unit slot.
  The grid auto-scrolls right as the simulation runs.</p>
<div class="tip"><strong>LOCAL CORE</strong> rows are always visible.
  <strong>REMOTE CORE</strong> rows (FXU1, FPU1) appear only when rSMT is ON —
  cells have an orange border to make offloaded instructions immediately obvious.</div>

<h3>Bottom Charts</h3>
<table>
  <tr><th>Chart</th><th>What it shows</th></tr>
  <tr><td>IPC Over Time</td><td>Live instructions-per-cycle ratio, sampled every 5 ticks. Watch it stabilise as the pipeline fills.</td></tr>
  <tr><td>Cycles: SMT-ON vs OFF</td><td>Final cycle counts for both passes. A shorter SMT-ON bar = rSMT helped.</td></tr>
  <tr><td>Unit Utilization</td><td>Percentage of cycles each execution unit was busy. Low FXU1 utilization = rSMT conditions were rarely met.</td></tr>
  <tr><td>Stall Breakdown</td><td>Total structural, data, and control stall cycles. Dominated by control stalls when branch % is high.</td></tr>
</table>

<div class="tip">
  <strong>Pro tip:</strong> Set FXU=80%, rSMT Availability=100%, Data Dependency=0%,
  rSMT Delay=0 and watch the GAIN soar. Then crank Data Dependency to 100% and
  watch it collapse to 100% (no benefit). That's the patent in action.
</div>

</body>
</html>
""";
    }
}
