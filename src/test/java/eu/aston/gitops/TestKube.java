package eu.aston.gitops;


import java.io.File;
import java.lang.reflect.Method;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import eu.aston.gitops.kube.ConfigMap;
import eu.aston.gitops.kube.Pod;
import eu.aston.gitops.kube.Secret;
import eu.aston.gitops.service.KubeService;
import eu.aston.gitops.service.RepoService;
import eu.aston.gitops.task.GitTask;
import eu.aston.gitops.task.RepoTask;
import eu.aston.gitops.utils.GsonPath;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class TestKube {

    public void tesAllGet(){

        KubeService kubeService = new KubeService();
        List<String> l1 = kubeService.listNamespaces();
        System.out.println(l1);

        List<Pod> l2 = kubeService.listPods("flux");
        System.out.println(l2);

        ConfigMap cm1 = kubeService.getConfigMap("default", "aaa");
        System.out.println(cm1);

        ConfigMap cm2 = kubeService.getConfigMap("default", "aaabbb");
        System.out.println(cm2);

        Secret s1 = kubeService.getSecret("flux", "astonwus");
        System.out.println(s1);
    }

    @Test
    public void testGitTaskKube() throws Exception {
        KubeService kubeService = new KubeService();
        GitTask gitTask = new GitTask(kubeService, null, null, null, "default", "aaa", new HashSet<>());
        Pod pod = loadPod("pod.nats.json");

        String names = GsonPath.str(pod.raw(), "metadata", "annotations", "configmap.aston.sk/reload");
        System.out.println(names);
        Assertions.assertEquals(names, "nats-config");
        List<String> fullNames = GsonPath.arrayMap(pod.raw(), GsonPath::asString, "spec", "volumes", "*", "configMap",
                                                   "name");
        System.out.println(fullNames);
        Assertions.assertEquals(1, fullNames.size(), "fullNames.size");

        List<String> resp = callPrivate(gitTask, "podConfigMaps", pod);
        System.out.println(resp);
        Assertions.assertIterableEquals(fullNames, resp, "fullNames");
    }

    @Test
    public void testRepoTaskKube() throws Exception {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .version(HttpClient.Version.HTTP_1_1)
                                          .build();
        KubeService kubeService = new KubeService();
        RepoService repoService = new RepoService(kubeService, httpClient);
        RepoTask repoTask = new RepoTask(kubeService, repoService, "aaa", new HashSet<>());
        Pod pod = loadPod("pod.nats.json");

        List<String> l1 = callPrivate(repoTask, "podImagePullSecret", pod);
        System.out.println(l1);
        Assertions.assertIterableEquals(List.of(), l1, "podImagePullSecret");

        List<RepoTask.ImageData> l2 = callPrivate(repoTask, "getImageData", pod);
        System.out.println(l2);
        Assertions.assertEquals(2, l2.size(), "getImageData.size");

        String[] image1 = callPrivate(repoService, "parseImageName", l2.getFirst().image());
        System.out.println(Arrays.toString(image1));
        Assertions.assertArrayEquals(new String[]{"docker.io", "library/nats", "2.10.10-alpine"}, image1, "parseImageName");
    }

    @SuppressWarnings("unchecked")
    public <T> T callPrivate(Object instance, String name, Object... args) throws Exception {
        Method m0 = null;
        for(Method m : instance.getClass().getDeclaredMethods()){
            if(m.getName().equals(name) && m.getParameterCount()==args.length){
                m0 = m;
                break;
            }
        }
        if(m0==null){
            throw new NoSuchMethodException(name);
        }
        m0.setAccessible(true);
        return (T)m0.invoke(instance, args);
    }

    public static Pod loadPod(String path) throws Exception{
        JsonObject el = loadJson(path).getAsJsonObject();
        String name = GsonPath.str(el, "metadata", "name");
        String namespace = GsonPath.str(el, "metadata", "namespace");
        String creation = GsonPath.str(el, "metadata", "creationTimestamp");
        return new Pod(name, namespace, el);
    }

    public static ConfigMap loadConfigMap(String path) throws Exception{
        JsonObject el = loadJson(path).getAsJsonObject();
        String name = GsonPath.str(el, "metadata", "name");
        String namespace = GsonPath.str(el, "metadata", "namespace");
        String creation = GsonPath.str(el, "metadata", "creationTimestamp");
        return new ConfigMap(name, namespace, el);
    }

    public static JsonElement loadJson(String path) throws Exception {
        File f = new File("src/test", path);
        String json = Files.readString(f.toPath());
        return JsonParser.parseString(json);
    }

}
