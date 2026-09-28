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
    @FXML private Slider            percentIntSlider;
    @FXML private Slider            percentLoadSlider;
    @FXML private Slider            rSmtAvailSlider;
    @FXML private Slider            rSmtDependsSlider;
    @FXML private Label             labelPercentInt;
    @FXML private Label             labelPercentLoad;
    @FXML private Label             labelRsmtAvail;
    @FXML private Label             labelRsmtDepends;
    @FXML private Label             percentIntLabel;
    @FXML private Label             percentLoadLabel;
    @FXML private Label             rSmtAvailLabel;
    @FXML private Label             rSmtDependsLabel;
    @FXML private ComboBox<String>  speedCombo;

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
        // Spinners need programmatic ValueFactory
        numInstSpinner.setValueFactory(
                new SpinnerValueFactory.IntegerSpinnerValueFactory(10, 100_000, 500));
        rSmtDelaySpinner.setValueFactory(
                new SpinnerValueFactory.IntegerSpinnerValueFactory(0, 100, 0));

        // Static labels that contain % must be set in code (FXML treats % as resource key prefix)
        labelPercentInt.setText("% Integer (FXU)");
        labelPercentLoad.setText("% Load/Store");
        labelRsmtAvail.setText("% rSMT Availability");
        labelRsmtDepends.setText("% Data Dependency");

        // PowerPC 600-style stage header
        pipelineHeaderLabel.setText(STAGE_LABEL);

        bindSliderLabels();
        initSpeedCombo();
        initCharts();
        initPipelineGridHeaders(true);
        smtToggle.selectedProperty().addListener((obs, o, v) -> {
            guiControl.setSmtEnabled(v);
            smtToggle.setText(v ? "rSMT ON" : "rSMT OFF");
        });
    }

    private void bindSliderLabels() {
        bindPct(percentIntSlider,  percentIntLabel);
        bindPct(percentLoadSlider, percentLoadLabel);
        bindPct(rSmtAvailSlider,   rSmtAvailLabel);
        bindPct(rSmtDependsSlider, rSmtDependsLabel);
    }

    private void bindPct(Slider s, Label l) {
        l.setText((int) s.getValue() + "%");
        s.valueProperty().addListener((obs, o, v) -> l.setText(v.intValue() + "%"));
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
                (int) percentIntSlider.getValue(),
                (int) percentLoadSlider.getValue(),
                (int) rSmtAvailSlider.getValue(),
                (int) rSmtDependsSlider.getValue()
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
}
