package com.rsmt.gui;

import com.rsmt.core.*;
import com.rsmt.sim.*;
import javafx.animation.*;
import javafx.beans.value.ChangeListener;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.canvas.*;
import javafx.scene.chart.*;
import javafx.scene.control.*;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.*;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.util.Duration;

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

    /**
     * Called by {@link MainApp} when the window close button is pressed.
     * Cancels any running simulation task and unblocks the engine thread
     * so the daemon thread exits and the JVM can shut down cleanly.
     */
    void shutdown() {
        guiControl.shutdown();
        if (simTask != null && simTask.isRunning()) {
            simTask.cancel(true);
        }
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
        showDialog("About rSMTv2", 620, 500, buildAboutPane());
    }

    @FXML
    private void onHelp() {
        showDialog("rSMTv2 — Help & Reference", 760, 680, buildHelpPane());
    }

    private void showDialog(String title, double w, double h, javafx.scene.Parent root) {
        Stage dialog = new Stage();
        dialog.setTitle(title);
        dialog.initModality(Modality.APPLICATION_MODAL);
        dialog.setScene(new javafx.scene.Scene(root, w, h));
        dialog.setResizable(true);
        dialog.show();
    }

    // ─── About pane (pure JavaFX, no WebView) ────────────────────────────────

    private javafx.scene.Parent buildAboutPane() {
        // ── Root: dark background ──────────────────────────────────────────────
        StackPane root = new StackPane();
        root.setStyle("-fx-background-color: #0a0a1a;");

        // ── Animated canvas (circuit traces) behind everything ─────────────────
        Canvas canvas = new Canvas(620, 500);
        GraphicsContext gc = canvas.getGraphicsContext2D();
        double[][] nodes = new double[60][4]; // x, y, vx, vy
        Random rnd = new Random();
        for (double[] n : nodes) {
            n[0] = rnd.nextDouble() * 620; n[1] = rnd.nextDouble() * 500;
            n[2] = (rnd.nextDouble() - 0.5) * 0.5;
            n[3] = (rnd.nextDouble() - 0.5) * 0.5;
        }
        AnimationTimer chipAnim = new AnimationTimer() {
            @Override public void handle(long now) {
                gc.clearRect(0, 0, 620, 500);
                for (double[] n : nodes) {
                    n[0] += n[2]; n[1] += n[3];
                    if (n[0] < 0 || n[0] > 620) n[2] *= -1;
                    if (n[1] < 0 || n[1] > 500) n[3] *= -1;
                    for (double[] m : nodes) {
                        double dx = n[0] - m[0], dy = n[1] - m[1];
                        double dist = Math.sqrt(dx * dx + dy * dy);
                        if (dist < 110 && dist > 1) {
                            gc.setStroke(Color.web("#00d4aa", (1 - dist / 110) * 0.25));
                            gc.setLineWidth(0.6);
                            gc.beginPath();
                            gc.moveTo(n[0], n[1]);
                            gc.lineTo(n[0], m[1]);
                            gc.lineTo(m[0], m[1]);
                            gc.stroke();
                        }
                    }
                    gc.setFill(Color.web("#00d4aa", 0.35));
                    gc.fillOval(n[0] - 2, n[1] - 2, 4, 4);
                }
            }
        };
        chipAnim.start();

        // ── Foreground content ─────────────────────────────────────────────────
        VBox content = new VBox(10);
        content.setAlignment(Pos.CENTER);
        content.setPadding(new Insets(30));

        // Chip emoji with pulse scale animation
        Label chipLbl = new Label("⬛");
        chipLbl.setFont(Font.font("System", 56));
        ScaleTransition pulse = new ScaleTransition(Duration.seconds(2), chipLbl);
        pulse.setFromX(1); pulse.setFromY(1);
        pulse.setToX(1.12); pulse.setToY(1.12);
        pulse.setAutoReverse(true); pulse.setCycleCount(Animation.INDEFINITE);
        pulse.play();

        // Title
        Label title1 = styledLabel("rSMTv2", "#00d4aa", 22, true);
        Label title2 = styledLabel("IBM PowerPC Reverse Simultaneous Multithreading", "#ccd6f6", 13, false);

        // Glow fade on title1
        FadeTransition glow = new FadeTransition(Duration.seconds(2.5), title1);
        glow.setFromValue(0.7); glow.setToValue(1.0);
        glow.setAutoReverse(true); glow.setCycleCount(Animation.INDEFINITE);
        glow.play();

        // Patent
        Label patent = styledLabel("IBM Patent US8,595,468 B2  ·  Filed 2009", "#4a90d9", 11, false);

        // Signal bars
        HBox signals = new HBox(6);
        signals.setAlignment(Pos.CENTER);
        String[] sigColors = {"#00d4aa","#4488ff","#ffdd44","#ff6b6b","#44ddff"};
        for (int i = 0; i < 5; i++) {
            Region bar = new Region();
            bar.setPrefSize(40, 4);
            bar.setStyle("-fx-background-color: " + sigColors[i] + "; -fx-background-radius: 2;");
            final double delay = i * 0.35;
            ScaleTransition st = new ScaleTransition(Duration.seconds(1.8), bar);
            st.setDelay(Duration.seconds(delay));
            st.setFromX(1); st.setToX(1.7);
            st.setAutoReverse(true); st.setCycleCount(Animation.INDEFINITE);
            st.play();
            FadeTransition ft = new FadeTransition(Duration.seconds(1.8), bar);
            ft.setDelay(Duration.seconds(delay));
            ft.setFromValue(0.2); ft.setToValue(1.0);
            ft.setAutoReverse(true); ft.setCycleCount(Animation.INDEFINITE);
            ft.play();
            signals.getChildren().add(bar);
        }

        // Pipeline stage labels that light up in sequence
        String[] stages = {"FETCH","DISPATCH","EXECUTE","COMPLETE","RETIRE"};
        String[] arrows  = {"→","→","→","→"};
        HBox pipeline = new HBox(4);
        pipeline.setAlignment(Pos.CENTER);
        List<Label> stageLabels = new ArrayList<>();
        for (int i = 0; i < stages.length; i++) {
            Label s = styledLabel(stages[i], "#44aaff", 10, true);
            s.setStyle(s.getStyle()
                + "-fx-background-color:#1a3a5a; -fx-background-radius:4;"
                + "-fx-padding:4 8 4 8;");
            stageLabels.add(s);
            pipeline.getChildren().add(s);
            if (i < arrows.length) {
                pipeline.getChildren().add(styledLabel(arrows[i], "#2a4a6a", 12, false));
            }
        }
        // Sequential stage highlight animation
        int[] stageIdx = {0};
        Timeline stageTl = new Timeline(new KeyFrame(Duration.seconds(0.9), e -> {
            for (int i = 0; i < stageLabels.size(); i++) {
                boolean active = (i == stageIdx[0]);
                stageLabels.get(i).setStyle(
                    "-fx-text-fill:" + (active ? "#0a0a1a" : "#44aaff") + ";"
                    + "-fx-font-weight:bold; -fx-font-size:10px;"
                    + "-fx-background-color:" + (active ? "#00d4aa" : "#1a3a5a") + ";"
                    + "-fx-background-radius:4; -fx-padding:4 8 4 8;"
                    + (active ? "-fx-effect:dropshadow(gaussian,#00d4aa,12,0.6,0,0);" : "")
                );
            }
            stageIdx[0] = (stageIdx[0] + 1) % stageLabels.size();
        }));
        stageTl.setCycleCount(Animation.INDEFINITE);
        stageTl.play();

        // Tagline
        Label tagline = styledLabel("From the minds of  IBM Bob  &  Daneyand", "#ccd6f6", 14, false);
        tagline.setStyle(tagline.getStyle() + "-fx-font-style:italic;");
        Label tagHighlight = styledLabel("IBM Bob  &  Daneyand", "#00d4aa", 14, true);

        // Version
        Label version = styledLabel("Version 2.0  ·  Java 21  ·  JavaFX 21", "#2a4a6a", 10, false);

        content.getChildren().addAll(
            chipLbl, title1, title2, patent,
            new Region() {{ setPrefHeight(8); }},
            signals,
            new Region() {{ setPrefHeight(4); }},
            pipeline,
            new Region() {{ setPrefHeight(12); }},
            tagline, tagHighlight,
            new Region() {{ setPrefHeight(8); }},
            version
        );

        root.getChildren().addAll(canvas, content);
        return root;
    }
    // ─── Help pane (pure JavaFX, scrollable) ─────────────────────────────────

    private javafx.scene.Parent buildHelpPane() {
        VBox body = new VBox(0);
        body.setStyle("-fx-background-color:#0d1117;");
        body.setPadding(new Insets(24, 28, 36, 28));

        // H1
        body.getChildren().add(h1("rSMTv2 — Help & Reference"));
        body.getChildren().add(separator());

        // WHAT IS THIS
        body.getChildren().add(h2("What is rSMTv2?"));
        body.getChildren().add(para(
            "rSMTv2 is an interactive demonstration of Reverse Simultaneous Multithreading (rSMT) " +
            "— a technique invented at IBM and patented in 2009. Traditional SMT lets a single core " +
            "run multiple threads. rSMT flips this: a single thread can dispatch instructions to " +
            "execution units on a different physical core, borrowing idle capacity when latency and " +
            "data-hazard conditions allow it."));
        body.getChildren().add(para(
            "This simulator models a PowerPC 600-style in-order pipeline with a Local Core (always " +
            "active) and a Remote Core (the rSMT target, active when rSMT is ON). Watch instructions " +
            "flow in real time and compare IPC and cycle counts with rSMT on vs. off."));

        // THE PATENT
        body.getChildren().add(h2("The Patent"));
        body.getChildren().add(patentBox(
            "IBM Patent US8,595,468 B2",
            "\"Reverse Simultaneous Multi-Threading\" — IBM Corporation, filed 2009.\n" +
            "A processor thread dispatches FXU/FPU ops to an idle execution unit on a sibling core, " +
            "subject to inter-core latency and data-dependency constraints, increasing throughput " +
            "without adding hardware threads or OS scheduling changes."));

        // PIPELINE
        body.getChildren().add(h2("Pipeline Model (PowerPC 600 Style)"));
        body.getChildren().add(tableView(
            new String[]{"Stage", "What happens"},
            new String[][]{
                {"FETCH",    "Instruction is pulled from the stream into the pipeline."},
                {"DISPATCH", "Instruction is decoded and routed to the correct execution unit."},
                {"EXECUTE",  "Unit processes the instruction for its full latency (FXU=5, FPU=6, Branch=4, LSU=3 cycles default)."},
                {"COMPLETE", "Result written back; the unit slot is freed."},
                {"RETIRE",   "Instruction architecturally committed and removed from pipeline."}
            }));

        // EXECUTION UNITS
        body.getChildren().add(h2("Execution Units & Colours"));
        body.getChildren().add(tableView(
            new String[]{"Unit", "Instructions", "Latency", "Core"},
            new String[][]{
                {"FXU0",          "Integer: ADD, SUB, MUL, DIV", "5 cycles (default)", "Local"},
                {"FXU1 (REMOTE)", "Integer rSMT offload",         "5 + rSMT Delay",     "Remote"},
                {"FPU0",          "Float: fADD, fSUB, fMUL, fDIV","6 cycles (default)", "Local"},
                {"FPU1 (REMOTE)", "Float rSMT offload",           "6 + rSMT Delay",     "Remote"},
                {"Branch",        "Conditional branches",         "4 cycles (default)", "Local"},
                {"LSU",           "Load / Store",                 "3 cycles (default)", "Local"},
                {"NOP",           "No-operation (fills remainder %)", "0 cycles",       "—"}
            }));

        // STALLS
        body.getChildren().add(h2("Stall Types"));
        body.getChildren().add(tableView(
            new String[]{"Stall", "Cell shows", "Cause"},
            new String[][]{
                {"Structural", "STALL (red)",   "Target unit busy — new instruction must wait."},
                {"Data",       "DEP (yellow)",  "Read-after-write hazard blocks issue to remote FXU slot."},
                {"Control",    "BR (blue)",     "Branch in-flight — Fetch stage frozen until branch retires."}
            }));

        // GUI REFERENCE
        body.getChildren().add(h2("GUI Element Reference"));

        body.getChildren().add(h3("Left Panel — Simulation Config"));
        body.getChildren().add(tableView(
            new String[]{"Control", "What it does"},
            new String[][]{
                {"Instructions",       "Total instructions to generate. More = longer run, smoother IPC curve."},
                {"rSMT Delay (cycles)","Extra latency on FXU1/FPU1. Models the inter-core bus penalty. 0 = no overhead."},
                {"% rSMT Availability","Probability the remote slot is free each cycle. 100% = always available."},
                {"% Data Dependency",  "Probability a hazard blocks the rSMT issue. 100% = rSMT never fires."},
                {"Animation Speed",    "Delay between clock ticks. Ludicrous ⚡ = full speed, no delay."},
                {"rSMT ON toggle",     "Enable/disable rSMT mid-simulation. REMOTE CORE rows show/hide live."},
                {"▶ Run",              "Generate instructions and start both SMT-ON and SMT-OFF passes."},
                {"⏸ Pause / ▶ Resume","Freeze and unfreeze the simulation at any cycle."},
                {"⟳ Reset",           "Stop the simulation, clear the pipeline grid and all charts."},
                {"GAIN",              "Performance gain = (SMT-OFF cycles / SMT-ON cycles) × 100%. Above 100% = rSMT helped."}
            }));

        body.getChildren().add(h3("Right Panel — Workload Mix"));
        body.getChildren().add(para(
            "Four sliders control the exact percentage of each instruction type. They are mutually " +
            "constrained — dragging one up trims the others (largest first) so the total never exceeds 100%. " +
            "NOP fills whatever percentage is left over."));
        body.getChildren().add(tableView(
            new String[]{"Slider", "Effect"},
            new String[][]{
                {"% FXU (integer)", "Share of ADD/SUB/MUL/DIV. High values = more FXU congestion, more rSMT opportunity."},
                {"% FPU (float)",   "Share of fADD/fSUB/fMUL/fDIV. FPU latency=6 so high FPU % = lots of structural stalls."},
                {"% Branch",        "Share of branches. Each freezes Fetch for 4 cycles — high branch % hammers IPC."},
                {"% Load/Store",    "Share of memory ops. LSU latency=3; back-to-back LSU causes stalls."}
            }));

        body.getChildren().add(h3("Right Panel — Latency (cycles)"));
        body.getChildren().add(tableView(
            new String[]{"Spinner", "Default", "Tip"},
            new String[][]{
                {"FXU cycles",    "5", "Lower = faster integer retire = less structural stall."},
                {"FPU cycles",    "6", "Biggest impact when FPU % is high."},
                {"Branch cycles", "4", "Reduce to simulate an early-resolving branch predictor."},
                {"LSU cycles",    "3", "Increase to model cache misses."}
            }));

        body.getChildren().add(h3("Pipeline Grid (Centre)"));
        body.getChildren().add(para(
            "Each column = one clock cycle. Each row = one execution unit slot. The grid auto-scrolls " +
            "right. LOCAL CORE rows are always visible. REMOTE CORE rows (FXU1, FPU1) appear only when " +
            "rSMT is ON — cells have an orange border to highlight offloaded instructions."));

        body.getChildren().add(h3("Bottom Charts"));
        body.getChildren().add(tableView(
            new String[]{"Chart", "What it shows"},
            new String[][]{
                {"IPC Over Time",       "Live IPC sampled every 5 ticks. Watch it stabilise as the pipeline fills."},
                {"Cycles: SMT-ON vs OFF","Final cycle counts for both passes. Shorter SMT-ON bar = rSMT helped."},
                {"Unit Utilization",    "% of cycles each unit was busy. Low FXU1 = rSMT conditions rarely met."},
                {"Stall Breakdown",     "Total structural / data / control stall cycles across the run."}
            }));

        body.getChildren().add(tipBox(
            "Pro tip: Set FXU=80%, rSMT Availability=100%, Data Dependency=0%, rSMT Delay=0 " +
            "and watch GAIN soar. Then crank Data Dependency to 100% and watch it collapse to 100% " +
            "(no benefit). That's the patent in action."));

        ScrollPane scroll = new ScrollPane(body);
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background-color:#0d1117; -fx-background:#0d1117;");
        return scroll;
    }

    // ─── Help/About styling helpers ───────────────────────────────────────────

    private static Label styledLabel(String text, String color, double size, boolean bold) {
        Label l = new Label(text);
        l.setStyle("-fx-text-fill:" + color + "; -fx-font-size:" + size + "px;"
                + (bold ? "-fx-font-weight:bold;" : ""));
        return l;
    }

    private static Label h1(String text) {
        Label l = styledLabel(text, "#00d4aa", 18, true);
        l.setPadding(new Insets(0, 0, 6, 0));
        return l;
    }

    private static Label h2(String text) {
        Label l = styledLabel(text.toUpperCase(), "#4488ff", 12, true);
        l.setPadding(new Insets(16, 0, 6, 0));
        return l;
    }

    private static Label h3(String text) {
        Label l = styledLabel(text.toUpperCase(), "#00d4aa", 11, true);
        l.setPadding(new Insets(10, 0, 4, 0));
        return l;
    }

    private static Label para(String text) {
        Label l = new Label(text);
        l.setWrapText(true);
        l.setStyle("-fx-text-fill:#b0bec5; -fx-font-size:12px;");
        l.setPadding(new Insets(0, 0, 8, 0));
        return l;
    }

    private static Region separator() {
        Region r = new Region();
        r.setPrefHeight(1);
        r.setStyle("-fx-background-color:#00d4aa44;");
        VBox.setMargin(r, new Insets(4, 0, 12, 0));
        return r;
    }

    private static VBox patentBox(String title, String body) {
        VBox box = new VBox(4);
        box.setStyle("-fx-background-color:#0f3460; -fx-border-color:#4488ff44;"
                + "-fx-border-width:0 0 0 3; -fx-border-insets:0 0 0 0;"
                + "-fx-background-radius:4; -fx-border-radius:4;");
        box.setPadding(new Insets(10, 14, 10, 14));
        VBox.setMargin(box, new Insets(4, 0, 10, 0));
        Label t = styledLabel(title, "#4488ff", 13, true);
        Label b = new Label(body);
        b.setWrapText(true);
        b.setStyle("-fx-text-fill:#8892b0; -fx-font-size:11px;");
        box.getChildren().addAll(t, b);
        return box;
    }

    private static VBox tipBox(String text) {
        VBox box = new VBox();
        box.setStyle("-fx-background-color:#1a2a1a; -fx-border-color:#00d4aa;"
                + "-fx-border-width:0 0 0 3; -fx-background-radius:4;");
        box.setPadding(new Insets(8, 12, 8, 12));
        VBox.setMargin(box, new Insets(12, 0, 0, 0));
        Label l = new Label(text);
        l.setWrapText(true);
        l.setStyle("-fx-text-fill:#8892b0; -fx-font-size:11px;");
        box.getChildren().add(l);
        return box;
    }

    private static javafx.scene.Node tableView(String[] headers, String[][] rows) {
        GridPane grid = new GridPane();
        grid.setStyle("-fx-background-color:transparent;");
        VBox.setMargin(grid, new Insets(0, 0, 10, 0));

        // Header row
        for (int c = 0; c < headers.length; c++) {
            Label h = styledLabel(headers[c].toUpperCase(), "#00d4aa", 10, true);
            h.setPadding(new Insets(4, 10, 4, 6));
            h.setMaxWidth(Double.MAX_VALUE);
            h.setStyle(h.getStyle() + "-fx-background-color:#161b22;"
                    + "-fx-border-color:#00d4aa44; -fx-border-width:0 0 1 0;");
            GridPane.setHgrow(h, Priority.ALWAYS);
            grid.add(h, c, 0);
        }

        // Data rows
        for (int r = 0; r < rows.length; r++) {
            String rowBg = (r % 2 == 0) ? "#0d1117" : "#111820";
            for (int c = 0; c < rows[r].length; c++) {
                Label cell = new Label(rows[r][c]);
                cell.setWrapText(true);
                cell.setPadding(new Insets(4, 10, 4, 6));
                cell.setMaxWidth(Double.MAX_VALUE);
                cell.setStyle("-fx-text-fill:" + (c == 0 ? "#e6f1ff" : "#b0bec5") + ";"
                        + "-fx-font-size:11px;"
                        + (c == 0 ? "-fx-font-weight:bold;" : "")
                        + "-fx-background-color:" + rowBg + ";"
                        + "-fx-border-color:#21262d; -fx-border-width:0 0 1 0;");
                GridPane.setHgrow(cell, Priority.ALWAYS);
                grid.add(cell, c, r + 1);
            }
        }

        // Equal column widths
        for (int c = 0; c < headers.length; c++) {
            ColumnConstraints cc = new ColumnConstraints();
            cc.setPercentWidth(100.0 / headers.length);
            grid.getColumnConstraints().add(cc);
        }
        return grid;
    }
}
