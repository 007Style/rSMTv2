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

    // ─── Pipeline grid ────────────────────────────────────────────────────────
    @FXML private GridPane  pipelineGrid;
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
    private int currentCycleColumn = 0;

    // Row order must match ALL_SLOTS in SimulationEngine
    private static final List<String> SLOT_ROWS =
            List.of(SimulationEngine.SLOT_FXU0, SimulationEngine.SLOT_FXU1,
                    SimulationEngine.SLOT_FPU0,  SimulationEngine.SLOT_FPU1,
                    SimulationEngine.SLOT_BRANCH, SimulationEngine.SLOT_LSU);

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

        bindSliderLabels();
        initSpeedCombo();
        initCharts();
        initPipelineGridHeaders();
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

    // ─── Pipeline grid headers (row labels, built once) ──────────────────────

    private void initPipelineGridHeaders() {
        // Column 0 = row-label column; subsequent columns = clock cycles
        for (int row = 0; row < SLOT_ROWS.size(); row++) {
            Label lbl = new Label(SLOT_ROWS.get(row));
            lbl.getStyleClass().add("row-header");
            pipelineGrid.add(lbl, 0, row + 1); // +1 because row 0 = cycle numbers
        }
        currentCycleColumn = 1; // next column to populate
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
        clearPipelineGrid();
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
     * Row 0 = cycle number header; rows 1..6 = one slot each.
     */
    private void addPipelineColumn(ClockEvent event) {
        int col = currentCycleColumn;

        // Cycle number header (row 0)
        Label cycleLbl = new Label(String.valueOf(event.cycleNumber()));
        cycleLbl.getStyleClass().add("cycle-header");
        pipelineGrid.add(cycleLbl, col, 0);

        // One cell per slot row
        for (int row = 0; row < SLOT_ROWS.size(); row++) {
            String slot = SLOT_ROWS.get(row);
            Instruction inst = event.slotSnapshot().get(slot);
            Label cell = buildCell(slot, inst, event);
            pipelineGrid.add(cell, col, row + 1);
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
            case FxuInstruction    i -> slot.equals(SimulationEngine.SLOT_FXU1) ? "cell-fxu1" : "cell-fxu";
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

    private void clearPipelineGrid() {
        pipelineGrid.getChildren().clear();
        currentCycleColumn = 1;
        initPipelineGridHeaders();
    }

    private void clearCharts() {
        ipcSeries.getData().clear();
        cyclesChart.getData().clear();
        utilChart.getData().clear();
        stallChart.getData().clear();
    }
}
