package eu.aston.gitops.event;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import eu.aston.gitops.utils.CronPattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class EventCtx {

    private static final Logger LOGGER = LoggerFactory.getLogger(EventCtx.class);

    private final Map<String, Consumer<Map<String, Object>>> handlers = new ConcurrentHashMap<>();
    private final Executor executor = Executors.newSingleThreadExecutor();
    private final ConcurrentHashMap<String, AtomicBoolean> insideQueueMap = new ConcurrentHashMap<>();
    private final List<CronEvent> cronEvents = new ArrayList<>();

    public List<String> eventNames(){
        return new ArrayList<>(handlers.keySet());
    }

    public boolean exec(String name) {
        return exec(name, Map.of());
    }

    public boolean exec(String name, Map<String, Object> data) {
        Consumer<Map<String, Object>> handler = handlers.get(name);
        if(handler!=null){
            handler.accept(data);
            return true;
        }
        return false;
    }

    public void addAsync(String name, Consumer<Map<String, Object>> handler) {
        insideQueueMap.put(name, new AtomicBoolean(false));
        handlers.put(name, (d)->{
            AtomicBoolean lock = insideQueueMap.get(name);
            if(lock!=null && !lock.compareAndSet(false, true)){
                LOGGER.info("ignore locked event {}", name);
                return;
            }
            executor.execute(()->localExec(name, handler, d));
        });
    }

    public void addAsync(String name, Runnable handler) {
        addAsync(name, (d)->handler.run());
    }

    public void addBlocked(String name, Consumer<Map<String, Object>> handler) {
        insideQueueMap.remove(name);
        handlers.put(name, (d)->localExec(name, handler, d));
    }

    public void remove(String name) {
        handlers.remove(name);
        insideQueueMap.remove(name);
        cronEvents.removeIf((e->e.event().equals(name)));
    }

    private void localExec(String name, Consumer<Map<String, Object>> handler, Map<String, Object> data) {
        LOGGER.info("start event {}", name);
        try{
            AtomicBoolean lock = insideQueueMap.get(name);
            if(lock!=null){
                lock.set(false);
            }
            handler.accept(data);
        }catch (Exception e){
            LOGGER.warn("event error {} - {}", name, e.getMessage());
            LOGGER.debug("event trace {}", name, e);
        }
    }

    public void addCronExpression(String pattern, String eventName){
        cronEvents.add(new CronEvent(new CronPattern(pattern), eventName));
    }

    public void replaceCronExpression(String pattern, String eventName) {
        cronEvents.removeIf((e->e.event().equals(eventName)));
        if(pattern!=null){
            addCronExpression(pattern, eventName);
        }
    }

    public void checkCronNow(){
        CronPattern.matchAll(cronEvents, CronEvent::pattern).map(CronEvent::event).forEach(this::exec);
    }

    private record CronEvent(CronPattern pattern, String event){}
}
