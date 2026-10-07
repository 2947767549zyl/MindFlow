package com.mindflow.harness.plan;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 任务节点 - 表示一个可执行的任务单元（移植自 MindFlow plan 包）
 */
public class Task {
    private final String id;
    private final String description;
    private final TaskType type;
    private volatile TaskStatus status;
    private volatile String result;
    private volatile String error;
    private final List<String> dependencies;
    private final List<String> dependents;
    private final List<TaskOption> options = new ArrayList<>();
    private volatile String chosenOption;
    private volatile String chosenNote;
    private volatile boolean decisionSettled;
    private volatile long startTime;
    private volatile long endTime;

    /** 需要用户拍板的候选项：动手前存在多个"等价合理"选择时由规划器给出，第一个为推荐 */
    public record TaskOption(String label, String reason) {
    }

    public enum TaskType {
        PLANNING,
        FILE_READ,
        FILE_WRITE,
        COMMAND,
        ANALYSIS,
        VERIFICATION
    }

    public enum TaskStatus {
        PENDING,
        RUNNING,
        COMPLETED,
        FAILED,
        SKIPPED
    }

    public Task(String id, String description, TaskType type) {
        this.id = id;
        this.description = description;
        this.type = type;
        this.status = TaskStatus.PENDING;
        this.dependencies = new ArrayList<>();
        this.dependents = new ArrayList<>();
    }

    public Task(String id, String description, TaskType type, List<String> dependencies) {
        this(id, description, type);
        if (dependencies != null) {
            this.dependencies.addAll(dependencies);
        }
    }

    public String getId() { return id; }
    public String getDescription() { return description; }
    public TaskType getType() { return type; }
    public TaskStatus getStatus() { return status; }
    public String getResult() { return result; }
    public String getError() { return error; }
    public List<String> getDependencies() { return new ArrayList<>(dependencies); }
    public List<String> getDependents() { return new ArrayList<>(dependents); }
    public long getStartTime() { return startTime; }
    public long getEndTime() { return endTime; }

    public void setStatus(TaskStatus status) { this.status = status; }
    public void setResult(String result) { this.result = result; }
    public void setError(String error) { this.error = error; }

    public List<TaskOption> getOptions() { return new ArrayList<>(options); }

    public void setOptions(List<TaskOption> taskOptions) {
        this.options.clear();
        if (taskOptions != null) {
            this.options.addAll(taskOptions);
        }
    }

    public String getChosenOption() { return chosenOption; }

    public String getChosenNote() { return chosenNote; }

    /** 记录用户的拍板结果（选项或自由输入），并标记该任务的决策已定 */
    public void settleDecision(String option, String note) {
        this.chosenOption = option;
        this.chosenNote = note;
        this.decisionSettled = true;
    }

    public boolean isDecisionSettled() { return decisionSettled; }

    /** 有候选项但还没人在计划审阅里选择 → 执行前需要走澄清闸门 */
    public boolean hasPendingDecision() {
        return !decisionSettled && !options.isEmpty();
    }

    public void addDependent(String taskId) {
        if (!dependents.contains(taskId)) {
            dependents.add(taskId);
        }
    }

    public void addDependency(String taskId) {
        if (!dependencies.contains(taskId)) {
            dependencies.add(taskId);
        }
    }

    public void markStarted() {
        this.status = TaskStatus.RUNNING;
        this.startTime = System.currentTimeMillis();
    }

    public void markCompleted(String result) {
        this.status = TaskStatus.COMPLETED;
        this.result = result;
        this.endTime = System.currentTimeMillis();
    }

    public void markFailed(String error) {
        this.status = TaskStatus.FAILED;
        this.error = error;
        this.endTime = System.currentTimeMillis();
    }

    public void markSkipped() {
        this.status = TaskStatus.SKIPPED;
        this.endTime = System.currentTimeMillis();
    }

    public void markPending() {
        this.status = TaskStatus.PENDING;
        this.error = null;
        this.result = null;
        this.startTime = 0;
        this.endTime = 0;
    }

    public long getDuration() {
        if (startTime == 0) return 0;
        if (endTime == 0) return System.currentTimeMillis() - startTime;
        return endTime - startTime;
    }

    public boolean isExecutable(Map<String, Task> allTasks) {
        if (status != TaskStatus.PENDING) return false;
        for (String depId : dependencies) {
            Task dep = allTasks.get(depId);
            if (dep == null || dep.getStatus() != TaskStatus.COMPLETED) {
                return false;
            }
        }
        return true;
    }

    @Override
    public String toString() {
        return String.format("Task[%s: %s] (%s)", id, description, status);
    }
}
