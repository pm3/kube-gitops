package eu.aston.gitops.event;

import eu.aston.gitops.task.ITask;
import eu.aston.gitops.utils.CronPattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public class EventCtx {

    private static final Logger LOGGER = LoggerFactory.getLogger(EventCtx.class);

    private final Executor executor = Executors.newSingleThreadExecutor();
    private final Map<String, TaskDef> tasks = new ConcurrentHashMap<>();
    private final Set<String> waitingTasks = new HashSet<>();
    private final List<CronEvent> cronEvents = new ArrayList<>();

    record TaskDef(ITask task, boolean async){}

    public List<String> eventNames() {
        return new ArrayList<>(tasks.keySet());
    }

    public ITask task(String path) {
        TaskDef taskDef = tasks.get(path);
        return taskDef!=null ? taskDef.task() : null;
    }

    public boolean exec(String name) {
        return exec(name, Map.of());
    }

    public boolean exec(String name, Map<String, Object> data) {
        TaskDef taskDef = tasks.get(name);
        if (taskDef != null) {
            if(taskDef.async()){
                if(waitingTasks.contains(name)){
                    LOGGER.info("ignore locked event {}", name);
                    return false;
                }
                waitingTasks.add(name);
                executor.execute(()->localExec(name, taskDef.task(), data));
            } else {
                localExec(name, taskDef.task(), data);
            }
            return true;
        }
        return false;
    }

    public void addAsync(String name, ITask task) {
        tasks.put(name, new TaskDef(task, true));
    }

    public void addBlocked(String name, ITask task) {
        tasks.put(name, new TaskDef(task, false));
    }

    public void remove(String name) {
        tasks.remove(name);
        cronEvents.removeIf((e -> e.event().equals(name)));
    }

    private void localExec(String name, ITask task, Map<String, Object> data) {
        LOGGER.info("start event {}", name);
        try {
            waitingTasks.remove(name);
            task.exec(data);
        } catch (Exception e) {
            LOGGER.warn("event error {} - {}", name, e.getMessage());
            LOGGER.debug("event trace {}", name, e);
        }
    }

    public void addCronExpression(String pattern, String eventName) {
        cronEvents.add(new CronEvent(new CronPattern(pattern), eventName));
    }

    public void replaceCronExpression(String pattern, String eventName) {
        cronEvents.removeIf((e -> e.event().equals(eventName)));
        if (pattern != null) {
            addCronExpression(pattern, eventName);
        }
    }

    public void checkCronNow() {
        CronPattern.matchAll(cronEvents, CronEvent::pattern).map(CronEvent::event).forEach(this::exec);
    }

    private record CronEvent(CronPattern pattern, String event) {
    }
}
