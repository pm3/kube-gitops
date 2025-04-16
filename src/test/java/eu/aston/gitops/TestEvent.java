package eu.aston.gitops;

import java.util.concurrent.atomic.AtomicInteger;

import eu.aston.gitops.event.EventCtx;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class TestEvent {

    @Test
    public void testSingleAsync(){
        AtomicInteger counter = new AtomicInteger();
        EventCtx eventCtx = new EventCtx();
        eventCtx.addAsync("/n1", (d)->{
            counter.incrementAndGet();
            try{
                Thread.sleep(10);
            }catch (Exception ignore){}
        });
        for(int i=0; i<20; i++){
            eventCtx.exec("/n1");
        }
        try{
            Thread.sleep(500);
        }catch (Exception ignore){}
        Assertions.assertEquals(counter.get(), 2, "counter");
    }
}
