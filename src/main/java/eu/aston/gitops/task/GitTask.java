package eu.aston.gitops.task;

import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import eu.aston.gitops.kube.ConfigMap;
import eu.aston.gitops.kube.Pod;
import eu.aston.gitops.model.GitOpsData;
import eu.aston.gitops.service.EncryptService;
import eu.aston.gitops.service.GitService;
import eu.aston.gitops.service.KubeService;
import eu.aston.gitops.service.YamlGsonParser;
import eu.aston.gitops.utils.ChecksumDir;
import eu.aston.gitops.utils.GsonPath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GitTask implements Consumer<Map<String, Object>> {
    private final static Logger LOGGER = LoggerFactory.getLogger(GitTask.class);

    private final KubeService kubeService;
    private final GitService gitService;
    private final EncryptService encryptService;
    private final Gson gson;
    private final String namespace;
    private final String name;
    private final Set<String> opsNamespaces;
    private long lastConfigHash = 0;

    public GitTask(KubeService kubeService, GitService gitService, EncryptService encryptService, Gson gson,
                   String namespace, String name, Set<String> opsNamespaces) {
        this.kubeService = kubeService;
        this.gitService = gitService;
        this.encryptService = encryptService;
        this.gson = gson;
        this.namespace = namespace;
        this.name = name;
        this.opsNamespaces = opsNamespaces;
    }

    @Override
    public void accept(Map<String, Object> data) {
        try{
            boolean force = data!=null && "1".equals(data.get("params.force"));
            if(force){
                gitService.clearCheckSumCache();
            }
            GitOpsData gitOpsData = createGitOpsData();
            run(gitOpsData, force);
            this.lastConfigHash = ChecksumDir.checksumString(gitOpsData.toString());
        }catch (Exception e){
            LOGGER.warn("error git task {}", name, e);
        }
    }

    public GitOpsData reload() throws Exception {
        GitOpsData gitOpsData = createGitOpsData();
        long hash = ChecksumDir.checksumString(gitOpsData.toString());
        if(hash!=lastConfigHash){
            run(gitOpsData, false);
            this.lastConfigHash = hash;
        }
        return gitOpsData;
    }

    private GitOpsData createGitOpsData() {
        ConfigMap cm = kubeService.getConfigMap(namespace, name);
        String yaml = GsonPath.str(cm.raw(), "data", "git-ops.yaml");
        YamlGsonParser yamlGsonParser = new YamlGsonParser(gson, kubeService);
        return yamlGsonParser.parseYaml(new StringReader(yaml), GitOpsData.class, namespace);
    }

    private void run(GitOpsData gitOpsData, boolean force) throws Exception {
        File gitDir = new File("/app/data/", name);
        LOGGER.debug("clone or pull {}", gitOpsData.git().url());
        boolean changed = gitService.pullOrClone(gitDir, gitOpsData.git());
        if(!changed && !force){
            LOGGER.info("not changed {}", name);
            return;
        }
        File dataDir = decrypt(gitDir, gitOpsData.encryptKey());
        List<String> namespaces = kubeService.listNamespaces();

        for(String namespace: namespaces){
            applyNamespace(gitOpsData, namespace, dataDir);
        }

        for(String namespace: opsNamespaces){
            reloadPodByConfigmap(namespace);
        }
    }

    private void applyNamespace(GitOpsData gitOpsData, String namespace, File dataDir) throws IOException {
        if(gitOpsData.namespacesExclude()!=null && gitOpsData.namespacesExclude().contains(namespace)){
            return;
        }
        if(gitOpsData.namespacesInclude()!=null && !gitOpsData.namespacesInclude().contains(namespace)){
            return;
        }
        File nsDir = namespaceDir(dataDir, namespace, gitOpsData.namespacesMap());
        if(!nsDir.exists()){
            return;
        }
        if(!opsNamespaces.contains(namespace)){
            LOGGER.debug("add namespace {}", namespace);
            opsNamespaces.add(namespace);
        }
        if(!gitService.checkDirChanged(nsDir)){
            LOGGER.info("namespace not modified {}", namespace);
            return;
        }

        LOGGER.info("apply {}", namespace);
        kubeService.kustomizeApply(nsDir);
    }

    private File decrypt(File gitDir, String key) throws Exception {
        if(key==null){
            return gitDir;
        }
        File dataDir = new File(gitDir.getParent(), gitDir.getName()+"_encrypted");
        encryptService.decryptDir(gitDir, dataDir, key);
        return dataDir;
    }

    private File namespaceDir(File dataDir, String namespace, Map<String, String> namespacesMap) {
        String name = namespacesMap!=null ? namespacesMap.getOrDefault(namespace, namespace) : namespace;
        return new File(dataDir, name);
    }

    private void reloadPodByConfigmap(String namespace) {
        List<Pod> reloadPods = listReloadPodByConfigmap(namespace);
        if(!reloadPods.isEmpty()){
            kubeService.reloadPods(reloadPods);
        }
    }

    private List<Pod> listReloadPodByConfigmap(String namespace) {
        LOGGER.debug("listReloadPodByConfigmap {}", namespace);
        List<Pod> reloadPods = new ArrayList<>();
        Map<String, Long> configMapVersionMap = new HashMap<>();
        List<Pod> pods = kubeService.listPods(namespace);
        for (Pod pod : pods) {
            long podVersion = resourceVersion(pod.raw());
            List<String> podConfigMaps = podConfigMaps(pod);
            for(String cmName : podConfigMaps) {
                Long configMapVersion = configMapVersionMap.computeIfAbsent(
                        cmName,
                        (k)-> {
                            ConfigMap cm = kubeService.getConfigMap(pod.namespace(), cmName);
                            return cm!=null ? resourceVersion(cm.raw()) : null;
                        });
                LOGGER.debug("ReloadPodByConfigmap pod {} - {} vs configmap {} - {}",pod.name(), podVersion, cmName, configMapVersion);
                if (configMapVersion != null && configMapVersion>podVersion) {
                    reloadPods.add(pod);
                    break;
                }
            }
        }
        return reloadPods;
    }

    private List<String> podConfigMaps(Pod pod) {
        List<String> resp = new ArrayList<>();
        String names = GsonPath.str(pod.raw(), "metadata", "annotations", "configmap.aston.sk/reload");
        if(names!=null) {
            List<String> fullNames = GsonPath.arrayMap(pod.raw(), GsonPath::asString, "spec", "volumes", "*", "configMap", "name");
            for (String name : names.split(",")) {
                for (String fullName : fullNames) {
                    if (fullName.startsWith(name) && !resp.contains(fullName)) {
                        resp.add(fullName);
                    }
                }
            }
        }
        return resp;
    }

    private Long resourceVersion(JsonElement root) {
        return GsonPath.asLong(GsonPath.el(root, "metadata", "resourceVersion"));
    }
}
