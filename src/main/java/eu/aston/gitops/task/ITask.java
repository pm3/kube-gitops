package eu.aston.gitops.task;

import java.util.Map;

@FunctionalInterface
public interface ITask {
    void exec(Map<String, Object> data);
}
