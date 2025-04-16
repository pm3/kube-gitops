package eu.aston.gitops.service;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import eu.aston.gitops.model.GitData;
import eu.aston.gitops.utils.ChecksumDir;
import eu.aston.gitops.utils.ExecProcess;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GitService {

    private final static Logger LOGGER = LoggerFactory.getLogger(GitService.class);

    private final Map<String, Long> checkSumCache = new ConcurrentHashMap<>();

    public boolean pullOrClone(File dir, GitData git) {
        File gitDir = new File(dir, ".git");
        if(dir.exists() && gitDir.exists()){
            return gitPull(dir);
        }
        return gitClone(dir, git);
    }

    private boolean gitPull(File dir) {
        try{
            StringBuilder out = new StringBuilder();
            ExecProcess.execBuilder(dir, List.of("git", "pull"), out);
            return out.indexOf("Already up to date.")<0;
        }catch (Exception e){
            LOGGER.warn("git pull {} - {}", dir.getAbsolutePath(), e.getMessage());
        }
        return false;
    }

    private boolean gitClone(File dir, GitData gitData) {
        try{
            if(gitData.url().startsWith("https://")){
                gitCloneHttp(dir, gitData);
            } else if(gitData.url().startsWith("git@")){
                gitCloneSsh(dir, gitData);
            } else {
                LOGGER.warn("invalid git url {}", gitData.url());
                return false;
            }
        }catch (Exception e){
            LOGGER.warn("git clone error {} - {}", gitData.url(), e.getMessage());
            LOGGER.warn("git clone trace", e);
            return false;
        }
        return true;
    }

    private void gitCloneHttp(File dir, GitData gitData) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        cmd.add("clone");
        if(gitData.branch()!=null){
            cmd.add("-b");
            cmd.add(gitData.branch());
        }
        String uri = gitData.url();
        if(gitData.user()!=null && gitData.password()!=null){
            String passwd = new String(Base64.getDecoder().decode(gitData.password()), StandardCharsets.UTF_8);
            uri = "https://"+gitData.user()+":"+passwd+"@"+gitData.url().substring(8);
        }
        cmd.add(uri);
        cmd.add(dir.getName());
        StringBuilder out = new StringBuilder();
        ExecProcess.execBuilder(dir.getParentFile(), cmd, out);
    }

    private void gitCloneSsh(File dir, GitData gitData) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        cmd.add("clone");
        if(gitData.sshKey()!=null){
            File keyFile = new File(dir.getParent(), dir.getName()+".key");
            if(!ExecProcess.isWindows){
                Files.createFile(keyFile.toPath(), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            }
            byte[] data = Base64.getDecoder().decode(gitData.sshKey());
            Files.write(keyFile.toPath(), data);
            cmd.add("-c");
            cmd.add("core.sshCommand=/usr/bin/ssh -i "+keyFile.getAbsolutePath()+" -o StrictHostKeyChecking=accept-new");
        }
        if(gitData.branch()!=null){
            cmd.add("-b");
            cmd.add(gitData.branch());
        }
        cmd.add(gitData.url());
        cmd.add(dir.getName());

        ExecProcess.execBuilder(dir.getParentFile(), cmd, null);
    }

    public boolean checkDirChanged(File dir) throws IOException {
        String key = dir.getCanonicalPath();
        long aktCheckSum = ChecksumDir.checksumDir(dir);
        Long lastCheckSum = checkSumCache.get(key);
        if(lastCheckSum!=null && lastCheckSum==aktCheckSum){
            return false;
        }
        checkSumCache.put(key, aktCheckSum);
        return true;
    }
}
