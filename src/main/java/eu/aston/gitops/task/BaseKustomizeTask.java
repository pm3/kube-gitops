package eu.aston.gitops.task;

import com.google.gson.JsonElement;
import eu.aston.gitops.kube.ConfigMap;
import eu.aston.gitops.kube.Pod;
import eu.aston.gitops.model.GitOpsData;
import eu.aston.gitops.service.EncryptService;
import eu.aston.gitops.service.KubeService;
import eu.aston.gitops.utils.ChecksumDir;
import eu.aston.gitops.utils.GsonPath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.function.Consumer;

public abstract class BaseKustomizeTask implements ITask {
    private final static Logger LOGGER = LoggerFactory.getLogger(GitTask.class);

    private final KubeService kubeService;
    private final EncryptService encryptService;
    private final String name;
    private final Set<String> opsNamespaces;
    private long lastConfigHash = 0;
    private GitOpsData gitOpsData = null;

    public BaseKustomizeTask(KubeService kubeService, EncryptService encryptService, String name, Set<String> opsNamespaces) {
        this.kubeService = kubeService;
        this.encryptService = encryptService;
        this.name = name;
        this.opsNamespaces = opsNamespaces;
    }

    @Override
    public void exec(Map<String, Object> data) {
        if (gitOpsData == null) {
            return;
        }
        try {
            boolean force = data != null && "1".equals(data.get("params.force"));
            if (force) {
                lastCheckSumMap.clear();
            }
            run(force);
            this.lastConfigHash = ChecksumDir.checksumString(gitOpsData.toString());
        } catch (Exception e) {
            LOGGER.warn("error git task {}", name, e);
        }
    }

    public void reload(GitOpsData gitOpsData, String yaml) throws Exception {
        this.gitOpsData = gitOpsData;
        long hash = ChecksumDir.checksumString(yaml);
        if (hash != lastConfigHash) {
            run(false);
            this.lastConfigHash = hash;
        }
    }

    protected abstract boolean pull(File dir, GitOpsData data);

    private void run(boolean force) throws Exception {
        File baseDir = new File("/app/data/", name);
        boolean changed = pull(baseDir, gitOpsData);
        if (!changed && !force) {
            LOGGER.info("not changed {}", name);
            return;
        }
        File dataDir = decrypt(baseDir, gitOpsData.encryptKey());
        List<String> namespaces = kubeService.listNamespaces();

        for (String namespace : namespaces) {
            applyNamespace(gitOpsData, namespace, dataDir);
        }

        for (String namespace : opsNamespaces) {
            reloadPodByConfigmap(namespace);
        }
    }

    private void applyNamespace(GitOpsData gitOpsData, String namespace, File dataDir) throws IOException {
        if (gitOpsData.namespacesExclude() != null && gitOpsData.namespacesExclude().contains(namespace)) {
            return;
        }
        if (gitOpsData.namespacesInclude() != null && !gitOpsData.namespacesInclude().contains(namespace)) {
            return;
        }
        File nsDir = namespaceDir(dataDir, namespace, gitOpsData.namespacesMap());
        if (!nsDir.exists()) {
            return;
        }
        if (!opsNamespaces.contains(namespace)) {
            LOGGER.info("add namespace {}", namespace);
            opsNamespaces.add(namespace);
        }
        if (!checkDirChanged(nsDir)) {
            LOGGER.info("namespace not modified {}", namespace);
            return;
        }

        LOGGER.info("apply {}", namespace);
        kubeService.kustomizeApply(nsDir);
    }

    private File decrypt(File gitDir, String key) throws Exception {
        if (key == null) {
            return gitDir;
        }
        File dataDir = new File(gitDir.getParent(), gitDir.getName() + "_encrypted");
        encryptService.decryptDir(gitDir, dataDir, key);
        return dataDir;
    }

    private File namespaceDir(File dataDir, String namespace, Map<String, String> namespacesMap) {
        String name = namespacesMap != null ? namespacesMap.getOrDefault(namespace, namespace) : namespace;
        return new File(dataDir, name);
    }

    private void reloadPodByConfigmap(String namespace) {
        List<Pod> reloadPods = listReloadPodByConfigmap(namespace);
        if (!reloadPods.isEmpty()) {
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
            for (String cmName : podConfigMaps) {
                Long configMapVersion = configMapVersionMap.computeIfAbsent(
                        cmName,
                        (k) -> {
                            ConfigMap cm = kubeService.getConfigMap(pod.namespace(), cmName);
                            return cm != null ? resourceVersion(cm.raw()) : null;
                        });
                LOGGER.debug("ReloadPodByConfigmap pod {} - {} vs configmap {} - {}", pod.name(), podVersion, cmName, configMapVersion);
                if (configMapVersion != null && configMapVersion > podVersion) {
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
        if (names != null) {
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

    private final Map<File, Long> lastCheckSumMap = new HashMap<>();

    protected boolean checkDirChanged(File dir) throws IOException {
        Long lastCheckSum = lastCheckSumMap.get(dir);
        long aktCheckSum = ChecksumDir.checksumDir(dir);
        if (lastCheckSum != null && lastCheckSum == aktCheckSum) {
            return false;
        }
        lastCheckSumMap.put(dir, aktCheckSum);
        return true;
    }
}
