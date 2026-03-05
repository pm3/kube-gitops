package eu.aston.gitops.task;

import eu.aston.gitops.model.GitOpsData;
import eu.aston.gitops.service.EncryptService;
import eu.aston.gitops.service.KubeService;
import eu.aston.gitops.service.RepoService;

import java.io.File;
import java.util.Set;

public class OciTask extends BaseKustomizeTask {

    private final RepoService repoService;
    private final String namespace;

    public OciTask(KubeService kubeService, RepoService repoService, EncryptService encryptService,
                   String name, String namespace, Set<String> opsNamespaces) {
        super(kubeService, encryptService, name, opsNamespaces);
        this.repoService = repoService;
        this.namespace = namespace;
    }

    @Override
    protected boolean pull(File dir, GitOpsData data) {
        return repoService.pull(dir, data.oci(), namespace);
    }
}
