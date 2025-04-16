package eu.aston.gitops.service;

import java.io.Reader;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import eu.aston.gitops.kube.Secret;
import eu.aston.gitops.utils.GsonPath;
import eu.aston.gitops.utils.Yaml2GsonConverter;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.nodes.Node;

public class YamlGsonParser {

    private final Gson gson;
    private final KubeService kubeService;

    public YamlGsonParser(Gson gson, KubeService kubeService) {
        this.gson = gson;
        this.kubeService = kubeService;
    }

    public <T> T parseYaml(Reader reader, Class<T> type, String namespace) {
        Yaml yaml = new Yaml();
        Node node = yaml.compose(reader);
        Yaml2GsonConverter yaml2GsonConverter = new Yaml2GsonConverter();
        JsonElement element = yaml2GsonConverter.convertAny(node);
        if(!element.isJsonObject()) throw new RuntimeException("parse yaml, root is not Object");
        Map<String, String> cache = new ConcurrentHashMap<>();
        parseObject(element.getAsJsonObject(), namespace, cache);
        return gson.fromJson(element, type);
    }

    private void parseObject(JsonObject obj, String namespace, Map<String, String> cache){
        for(var e : obj.entrySet()){
            if(e.getValue().isJsonObject()){
                parseObject(e.getValue().getAsJsonObject(), namespace, cache);
            } else if(e.getValue().isJsonArray()) {
                parseArray(e.getValue().getAsJsonArray(), namespace, cache);
            } else if(e.getValue().isJsonPrimitive()) {
                String val2 = parseStr(e.getValue().getAsString(), namespace, cache);
                if(val2!=null){
                    obj.addProperty(e.getKey(), val2);
                }
            }
        }
    }

    private void parseArray(JsonArray arr, String namespace, Map<String, String> cache){
        for(int i=0; i<arr.size(); i++) {
            JsonElement el = arr.get(i);
            if(el.isJsonObject()){
                parseObject(el.getAsJsonObject(), namespace, cache);
            } else if(el.isJsonArray()) {
                parseArray(el.getAsJsonArray(), namespace, cache);
            } else if(el.isJsonPrimitive()) {
                String val2 = parseStr(el.getAsString(), namespace, cache);
                if(val2!=null){
                    arr.set(i, new JsonPrimitive(val2));
                }
            }
        }
    }

    private String parseStr(String expr, String namespace, Map<String, String> cache) {
        if(expr.startsWith("${") && expr.endsWith("}")){
            String[] items = expr.substring(2, expr.length()-1).split("/");
            if(items.length==2){
                return readSecret(namespace, items[0], items[1], cache);
            } else if(items.length==3){
                return readSecret(items[0], items[1], items[2], cache);
            }
        }
        return null;
    }

    private String readSecret(String ns, String name, String prop, Map<String, String> cache){
        if(!cache.containsKey(ns+"/"+name)){
            cache.put(ns+"/"+name, "1");
            Secret secret = kubeService.getSecret(ns, name);
            if(secret!=null){
                JsonObject data = GsonPath.obj(secret.raw(), "data");
                for (var e : data.entrySet()) {
                    cache.put(ns+"/"+name+"/"+e.getKey(), e.getValue().getAsString());
                }
            }
        }
        return cache.get(ns+"/"+name+"/"+prop);
    }

}
