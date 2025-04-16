package eu.aston.gitops.utils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

public class GsonPath {

    public static JsonElement el(JsonElement root, String... path){
        JsonElement e = root;
        for(String p : path){
            if(e==null) return null;
            if(e.isJsonObject()){
                e = e.getAsJsonObject().get(p);
            } else if (e.isJsonArray()){
                int pos = Integer.parseInt(p);
                e = e.getAsJsonArray().get(pos);
            } else {
                return null;
            }
        }
        return e;
    }

    public static String str(JsonElement root, String... path){
        return asString(el(root, path));
    }

    public static JsonObject obj(JsonElement root, String... path) {
        return asObject(el(root, path));
    }


    public static <T> List<T> arrayMap(JsonElement root, Function<JsonElement, T> lastConvert, String... path) {
        List<T> resp = new ArrayList<>();
        arr2(resp, root, lastConvert, Arrays.asList(path));
        return resp;
    }

    private static <T> void arr2(List<T> resp, JsonElement root, Function<JsonElement, T> lastConvert, List<String> path) {
        if(root==null || path==null) return;
        if(path.isEmpty()){
            T val = lastConvert.apply(root);
            if(val!=null) resp.add(val);
            return;
        }
        List<String> path2 = path.subList(1,path.size());
        if(root.isJsonArray() && "*".equals(path.getFirst())){
            for(JsonElement el : root.getAsJsonArray()){
                arr2(resp, el, lastConvert, path2);
            }
        } else if(root.isJsonObject()){
            JsonElement el = root.getAsJsonObject().get(path.getFirst());
            if(el!=null) arr2(resp, el, lastConvert, path2);
        } else if (root.isJsonArray()){
            int pos = Integer.parseInt(path.getFirst());
            JsonElement el = root.getAsJsonArray().get(pos);
            if(el!=null) arr2(resp, el, lastConvert, path2);
        }
    }

    public static String asString(JsonElement e){
        return e!=null && e.isJsonPrimitive() ? e.getAsString() : null;
    }

    public static JsonObject asObject(JsonElement e){
        return e!=null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }

    public static JsonArray asArray(JsonElement e){
        return e!=null && e.isJsonArray() ? e.getAsJsonArray() : null;
    }

    public static Long asLong(JsonElement e){
        return e!=null && e.isJsonPrimitive() ? e.getAsLong() : null;
    }

}
