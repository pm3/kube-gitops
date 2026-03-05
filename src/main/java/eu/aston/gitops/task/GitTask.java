package eu.aston.gitops.task;

import eu.aston.gitops.model.GitOpsData;
import eu.aston.gitops.service.EncryptService;
import eu.aston.gitops.service.GitService;
import eu.aston.gitops.service.KubeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.Set;

public class GitTask extends BaseKustomizeTask {
    private final static Logger LOGGER = LoggerFactory.getLogger(GitTask.class);

    private final GitService gitService;

    public GitTask(KubeService kubeService, GitService gitService, EncryptService encryptService, String name, Set<String> opsNamespaces) {
        super(kubeService, encryptService, name, opsNamespaces);
        this.gitService = gitService;
    }

    @Override
    protected boolean pull(File dir, GitOpsData data) {
        return gitService.pullOrClone(dir, data.git());
    }
}
