package eu.aston.gitops.event;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class EventHttpHandler implements HttpHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(EventHttpHandler.class);

    private final EventCtx eventCtx;
    private final String contextPath;

    public EventHttpHandler(EventCtx eventCtx, String contextPath) {
        this.eventCtx = eventCtx;
        this.contextPath = contextPath;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String uri = exchange.getRequestURI().getPath();
        int status = 404;
        byte[] body = new byte[0];
        try{
            if(uri.startsWith(contextPath)){
                String eventName = uri.substring(contextPath.length()-1);
                Map<String, Object> evenData = new HashMap<>();
                evenData.put("method", exchange.getRequestMethod());
                evenData.put("uri", exchange.getRequestURI());
                evenData.put("body", exchange.getRequestBody().readAllBytes());
                evenData.put("headers", exchange.getRequestHeaders());
                parseQueryParams(evenData, exchange.getRequestURI().getQuery());
                boolean ok = eventCtx.exec(eventName, evenData);
                if(!ok && eventName.lastIndexOf('/')>1){
                    eventName = eventName.substring(0, eventName.lastIndexOf('/')+1);
                    ok = eventCtx.exec(eventName, evenData);
                }
                if(ok) {
                    status = 200;
                    if(evenData.get("response.body") instanceof byte[] bresp) {
                        body = bresp;
                    } else if(evenData.get("response.body") instanceof String sreps){
                        body = sreps.getBytes(StandardCharsets.UTF_8);
                    }
                    for(var e : evenData.entrySet()){
                        if(e.getKey().startsWith("response.header.")){
                            exchange.getResponseHeaders().add(e.getKey().substring("response.header.".length()), e.getValue().toString());
                        }
                    }
                }
            }
        }catch (Exception e){
            status = 500;
            body = e.getMessage().getBytes(StandardCharsets.UTF_8);
            LOGGER.warn("call error {} - {}", uri, e.getMessage());
            LOGGER.debug("call error trace", e);
        }

        LOGGER.info("http {} {}", uri, status);
        exchange.sendResponseHeaders(status, body.length);
        OutputStream os = exchange.getResponseBody();
        os.write(body);
        os.close();
    }

    private void parseQueryParams(Map<String, Object> evenData, String query) {
        if(query!=null) {
            String[] params = query.split("&");
            for (String param : params) {
                String[] keyValue = param.split("=", 2);
                if (keyValue.length == 2) {
                    evenData.put("params." + keyValue[0], URLDecoder.decode(keyValue[1], StandardCharsets.UTF_8));
                }
            }
        }
    }
}
