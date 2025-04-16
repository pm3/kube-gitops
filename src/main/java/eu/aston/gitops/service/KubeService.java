package eu.aston.gitops.service;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import eu.aston.gitops.kube.ConfigMap;
import eu.aston.gitops.kube.Pod;
import eu.aston.gitops.kube.Secret;
import eu.aston.gitops.utils.ExecProcess;
import eu.aston.gitops.utils.GsonPath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class KubeService {

    private final static Logger LOGGER = LoggerFactory.getLogger(KubeService.class);

    public List<String> listNamespaces() {
        return kubeGetList(List.of("kubectl", "get", "ns", "-ojson"), this::parseNsName);
    }

    private String parseNsName(JsonElement el){
        return GsonPath.str(el, "metadata", "name");
    }

    public void kustomizeApply(File nsDir) {
        kubeCall(List.of("kubectl", "apply", "-k", nsDir.getAbsolutePath()));
    }

    public List<Pod> listPods(String namespace) {
        return kubeGetList(List.of("kubectl", "get", "pod", "-n", namespace, "-ojson"), this::parsePod);
    }

    private Pod parsePod(JsonElement el){
        String name = GsonPath.str(el, "metadata", "name");
        String namespace = GsonPath.str(el, "metadata", "namespace");
        return new Pod(name, namespace, el);
    }

    public ConfigMap getConfigMap(String namespace, String name) {
        return kubeGet1(List.of("kubectl", "get", "configmap", "-n", namespace, name, "-ojson"), this::parseConfigMap);
    }

    public List<ConfigMap> listConfigMapsByLabel(String query) {
        return kubeGetList(List.of("kubectl", "get", "configmap", "-A", "-l", query, "-ojson"), this::parseConfigMap);
    }

    private ConfigMap parseConfigMap(JsonElement el){
        String name = GsonPath.str(el, "metadata", "name");
        String namespace = GsonPath.str(el, "metadata", "namespace");
        return new ConfigMap(name, namespace, el);
    }

    public Secret getSecret(String namespace, String name) {
        return kubeGet1(List.of("kubectl", "get", "secret", "-n", namespace, name, "-ojson"), this::parseSecret);
    }

    private Secret parseSecret(JsonElement el){
        String name = GsonPath.str(el, "metadata", "name");
        String namespace = GsonPath.str(el, "metadata", "namespace");
        return new Secret(name, namespace, el);
    }

    private static final List<String> rolloutTypes = List.of("StatefulSet", "DaemonSet", "ReplicaSet");

    public void reloadPods(List<Pod> pods) {
        List<String> owners = new ArrayList<>();
        for(Pod pod : pods){
            String ownerKind = GsonPath.str(pod.raw(), "metadata","ownerReferences", "kind");
            String ownerName = GsonPath.str(pod.raw(), "metadata","ownerReferences", "name");
            if(ownerKind!=null && ownerName!=null){
                String key = ownerKind+":"+ownerName;
                if(owners.contains(key)) continue;
                owners.add(key);
                if(rolloutTypes.contains(ownerKind)){
                    kubeCall(List.of("kubectl", "rollout", "restart", ownerKind, "-n", pod.namespace(), ownerName));
                    continue;
                }
            }
            kubeCall(List.of("kubectl", "delete", "pod", "-n", pod.namespace(), pod.name()));
        }
    }

    public void deletePod(String namespace, String name) {
        kubeCall(List.of("kubectl", "delete", "pod", "-n", namespace, name));
    }

    public void watch(String resource, Consumer<String> changesConsumer, String query){
        ExecProcess.execStream(new File("."),
                               List.of("kubectl", "get", resource,
                                       "-A", "--watch", "--watch-only", "--no-headers",
                                       "-l", query),
                               changesConsumer);
    }

    protected void kubeCall(List<String> cmd){
        try{
            ExecProcess.execStream(new File("."), cmd, System.out::println);
        }catch (Exception ignore){
        }
    }

    private <T> List<T> kubeGetList(List<String> cmd, Function<JsonElement, T> convert) {
        try{
            StringBuilder out = new StringBuilder();
            ExecProcess.execBuilder(new File("."), cmd, out);
            JsonElement root = JsonParser.parseString(out.toString());
            JsonArray arr = root.getAsJsonObject().get("items").getAsJsonArray();
            return arr.asList().stream()
                      .map(convert)
                      .toList();
        }catch (Exception e){
            LOGGER.warn("exec kubectl {}", e.getMessage());
            LOGGER.debug("exec kubectl trace", e);
            return List.of();
        }
    }

    private <T> T kubeGet1(List<String> cmd, Function<JsonElement, T> convert) {
        try{
            StringBuilder out = new StringBuilder();
            ExecProcess.execBuilder(new File("."), cmd, out);
            JsonElement root = JsonParser.parseString(out.toString());
            return convert.apply(root);
        }catch (Exception e){
            LOGGER.warn("exec kubectl {}", e.getMessage());
            LOGGER.debug("exec kubectl trace", e);
            return null;
        }
    }
}
