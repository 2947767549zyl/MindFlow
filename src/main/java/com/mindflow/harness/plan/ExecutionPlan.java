package com.mindflow.harness.plan;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 执行计划 - 包含一组有依赖关系的任务（移植自 MindFlow plan 包）
 */
public class ExecutionPlan {
    private final String id;
    private final String goal;
    private final Map<String, Task> tasks;
    private final List<String> executionOrder;
    private PlanStatus status;
    private String summary;
    private String context;
    private long startTime;
    private long endTime;

    public enum PlanStatus {
        CREATED,
        RUNNING,
        COMPLETED,
        FAILED,
        CANCELLED
    }

    public ExecutionPlan(String id, String goal) {
        this.id = id;
        this.goal = goal;
        this.tasks = new LinkedHashMap<>();
        this.executionOrder = new ArrayList<>();
        this.status = PlanStatus.CREATED;
    }

    public String getId() { return id; }
    public String getGoal() { return goal; }
    public PlanStatus getStatus() { return status; }
    public String getSummary() { return summary; }
    public long getStartTime() { return startTime; }
    public long getEndTime() { return endTime; }

    public void setSummary(String summary) { this.summary = summary; }

    public String getContext() { return context; }

    public void setContext(String context) { this.context = context; }
    public void setStatus(PlanStatus status) { this.status = status; }

    public void addTask(Task task) {
        tasks.put(task.getId(), task);
        for (String depId : task.getDependencies()) {
            Task dep = tasks.get(depId);
            if (dep != null) {
                dep.addDependent(task.getId());
            }
        }
    }

    public Task getTask(String id) {
        return tasks.get(id);
    }

    public Collection<Task> getAllTasks() {
        return tasks.values();
    }

    public List<Task> getRootTasks() {
        return tasks.values().stream()
                .filter(t -> t.getDependencies().isEmpty())
                .toList();
    }

    public List<Task> getExecutableTasks() {
        return tasks.values().stream()
                .filter(t -> t.isExecutable(tasks))
                .toList();
    }

    public boolean computeExecutionOrder() {
        executionOrder.clear();
        Set<String> visited = new HashSet<>();
        Set<String> visiting = new HashSet<>();

        for (Task task : tasks.values()) {
            if (!visited.contains(task.getId())) {
                if (!topologicalSort(task, visited, visiting)) {
                    return false;
                }
            }
        }

        return true;
    }

    private boolean topologicalSort(Task task, Set<String> visited, Set<String> visiting) {
        String id = task.getId();

        if (visiting.contains(id)) {
            return false;
        }
        if (visited.contains(id)) {
            return true;
        }

        visiting.add(id);

        for (String depId : task.getDependencies()) {
            Task dep = tasks.get(depId);
            if (dep != null) {
                if (!topologicalSort(dep, visited, visiting)) {
                    return false;
                }
            }
        }

        visiting.remove(id);
        visited.add(id);
        executionOrder.add(id);
        return true;
    }

    public List<String> getExecutionOrder() {
        if (executionOrder.isEmpty()) {
            computeExecutionOrder();
        }
        return new ArrayList<>(executionOrder);
    }

    public double getProgress() {
        if (tasks.isEmpty()) return 1.0;
        long completed = tasks.values().stream()
                .filter(t -> t.getStatus() == Task.TaskStatus.COMPLETED)
                .count();
        return (double) completed / tasks.size();
    }

    public boolean isAllCompleted() {
        return tasks.values().stream()
                .allMatch(t -> t.getStatus() == Task.TaskStatus.COMPLETED);
    }

    public boolean hasFailed() {
        return tasks.values().stream()
                .anyMatch(t -> t.getStatus() == Task.TaskStatus.FAILED);
    }

    public void markStarted() {
        this.status = PlanStatus.RUNNING;
        this.startTime = System.currentTimeMillis();
    }

    public void markCompleted() {
        this.status = PlanStatus.COMPLETED;
        this.endTime = System.currentTimeMillis();
    }

    public void markFailed() {
        this.status = PlanStatus.FAILED;
        this.endTime = System.currentTimeMillis();
    }

    public long getDuration() {
        if (startTime == 0) return 0;
        if (endTime == 0) return System.currentTimeMillis() - startTime;
        return endTime - startTime;
    }

    public List<List<Task>> getExecutionBatches() {
        if (tasks.isEmpty()) {
            return List.of();
        }

        Map<String, Task> remaining = new LinkedHashMap<>(tasks);
        Set<String> completed = new HashSet<>();
        List<List<Task>> batches = new ArrayList<>();

        while (!remaining.isEmpty()) {
            List<Task> batch = remaining.values().stream()
                    .filter(task -> completed.containsAll(task.getDependencies()))
                    .toList();

            if (batch.isEmpty()) {
                break;
            }

            batches.add(batch);
            for (Task task : batch) {
                remaining.remove(task.getId());
                completed.add(task.getId());
            }
        }

        return batches;
    }

    public String summarize() {
        List<List<Task>> batches = getExecutionBatches();
        List<Task> readyTasks = getExecutableTasks();
        StringBuilder sb = new StringBuilder();
        sb.append("📋 计划摘要\n");
        sb.append("   - 目标: ").append(compactGoal(goal, 48)).append('\n');
        sb.append("   - 任务数: ").append(tasks.size())
                .append(" | 并行批次: ").append(batches.size())
                .append(" | 当前可执行: ").append(readyTasks.size())
                .append(" | 状态: ").append(status).append('\n');

        if (!batches.isEmpty()) {
            sb.append("   - 首批执行: ").append(formatTaskList(batches.get(0), 5)).append('\n');
            if (batches.size() > 1) {
                sb.append("   - 最终收敛: ")
                        .append(formatTaskList(batches.get(batches.size() - 1), 5))
                        .append('\n');
            }
        }

        return sb.toString();
    }

    private String compactGoal(String rawGoal, int maxLength) {
        String singleLineGoal = rawGoal
                .replace("\r\n", " ")
                .replace('\r', ' ')
                .replace('\n', ' ')
                .trim()
                .replaceAll(" {2,}", " ");
        if (singleLineGoal.length() <= maxLength) {
            return singleLineGoal;
        }
        return singleLineGoal.substring(0, maxLength - 3) + "...";
    }

    private String formatTaskList(List<Task> batch, int limit) {
        if (batch.isEmpty()) {
            return "无";
        }

        List<String> taskIds = batch.stream()
                .map(Task::getId)
                .toList();

        if (taskIds.size() <= limit) {
            return String.join(", ", taskIds);
        }

        return String.join(", ", taskIds.subList(0, limit)) + " 等 " + taskIds.size() + " 个任务";
    }
    public List<Map<String, Object>> toStepDtos() {
        List<Map<String, Object>> steps = new ArrayList<>();
        for (Task task : tasks.values()) {
            Map<String, Object> step = new LinkedHashMap<>();
            step.put("id", task.getId());
            step.put("description", task.getDescription());
            step.put("type", task.getType().name());
            step.put("dependencies", task.getDependencies());
            step.put("status", task.getStatus().name());
            step.put("result", task.getResult());
            List<Map<String, Object>> optionDtos = new ArrayList<>();
            for (Task.TaskOption option : task.getOptions()) {
                Map<String, Object> optionDto = new LinkedHashMap<>();
                optionDto.put("label", option.label());
                optionDto.put("reason", option.reason());
                optionDtos.add(optionDto);
            }
            step.put("options", optionDtos);
            step.put("chosenOption", task.getChosenOption());
            steps.add(step);
        }
        return steps;
    }

    @Override
    public String toString() {
        return String.format("ExecutionPlan[%s: %s] (%d tasks, %s)",
                id, goal, tasks.size(), status);
    }
}
