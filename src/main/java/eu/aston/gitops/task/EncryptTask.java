package eu.aston.gitops.task;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.function.Consumer;

import eu.aston.gitops.service.EncryptService;

public class EncryptTask implements Consumer<Map<String, Object>> {

    private final EncryptService encryptService;
    private final String key;

    public EncryptTask(EncryptService encryptService, String key) {
        this.encryptService = encryptService;
        this.key = key;
    }

    @Override
    public void accept(Map<String, Object> data) {
        if(data.get("method") instanceof String method && method.equalsIgnoreCase("POST") && data.get("body") instanceof  byte[] body){
            String sBody = new String(body, StandardCharsets.UTF_8);
            try {
                String resp = encryptService.encrypt(sBody, key);
                data.put("response.body", resp);
                data.put("response.header.Content-Type","text/plain");
            }catch (Exception e){
                throw new RuntimeException(e);
            }
        } else {
            data.put("response.body", encryptService.encryptHtml());
            data.put("response.header.Content-Type","text/html");
        }
    }
}
