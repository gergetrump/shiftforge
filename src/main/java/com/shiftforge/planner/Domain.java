package com.shiftforge.planner;

import java.util.*;

/** Immutable wire/domain records. JSON uses snake_case to share the dashboard contract. */
public final class Domain {
    private Domain() {}
    public record Product(String id, int initialInventoryUnits) {}
    public record Cell(String id, int crewOperators) {}
    public record ProcessingTime(String productId, String cellId, int minutesPerUnit) {}
    public record Shift(String id, int index, int durationMinutes, int availableOperators) {}
    public record Capacity(String cellId, String shiftId, int availableMachineMinutes) {}
    public record Demand(String productId, String shiftId, int dueUnits) {}
    public record Scenario(int schemaVersion, String name, String dataOrigin, List<Product> products,
                           List<Cell> cells, List<ProcessingTime> processingTimes, List<Shift> shifts,
                           List<Capacity> capacity, List<Demand> demand) {
        public Scenario {
            products = List.copyOf(products); cells = List.copyOf(cells);
            processingTimes = List.copyOf(processingTimes); shifts = List.copyOf(shifts);
            capacity = List.copyOf(capacity); demand = List.copyOf(demand);
        }
        public List<Shift> orderedShifts() { return shifts.stream().sorted(Comparator.comparingInt(Shift::index)).toList(); }
    }
    public record Production(String productId, String cellId, String shiftId, int units) {}
    public record Activity(String cellId, String shiftId, int active) {}
    public record Stock(String productId, String shiftId, int inventoryUnits, int backlogUnits) {}
    public record CellUsage(String cellId, String shiftId, long usedMinutes, long availableMinutes, Double utilization) {}
    public record Staffing(String shiftId, int reservedOperators, int availableOperators, Double reservationShare) {}
    public record Metrics(long backlogUnitShifts, long finalBacklogUnits, long activeCellShifts,
                          long inventoryUnitShifts, long producedUnits, long machineMinutesUsed,
                          long machineMinutesAvailable, Double capacityUtilization,
                          List<CellUsage> cellUsage, List<Staffing> staffing) {
        public Metrics { cellUsage = List.copyOf(cellUsage); staffing = List.copyOf(staffing); }
    }
    public record Solution(long objective, List<Production> production, List<Activity> activity,
                           List<Stock> stock, Metrics metrics) {
        public Solution { production = List.copyOf(production); activity = List.copyOf(activity); stock = List.copyOf(stock); }
    }
    public record Validation(boolean valid, List<String> errors) {
        public Validation { errors = List.copyOf(errors); }
    }
    public record SolverInfo(String version, String termination, int terminationCode, Double rawObjective,
                             Double objectiveBound, Double relativeGap, double solveRuntimeSeconds) {}
    public record Result(int formatVersion, Scenario input, String algorithm, String status,
                         Map<String, Number> settings, SolverInfo solver, Solution solution,
                         Validation validation, String error) {
        public Result { settings = Collections.unmodifiableMap(new LinkedHashMap<>(settings)); }
    }
    public record SolveRequest(Scenario scenario, String algorithm, int solverTimeLimitSeconds) {}
    public record Pair(String first, String second) {}
    public record Triple(String product, String cell, String shift) {}
}
