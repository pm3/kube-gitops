package eu.aston.gitops;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.sun.net.httpserver.HttpServer;
import eu.aston.gitops.event.EventCtx;
import eu.aston.gitops.event.EventHttpHandler;
import eu.aston.gitops.kube.ConfigMap;
import eu.aston.gitops.model.GitOpsData;
import eu.aston.gitops.service.EncryptService;
import eu.aston.gitops.service.GitService;
import eu.aston.gitops.service.KubeService;
import eu.aston.gitops.service.RepoService;
import eu.aston.gitops.service.WatchFacade;
import eu.aston.gitops.task.EncryptTask;
import eu.aston.gitops.task.GitTask;
import eu.aston.gitops.task.RepoTask;
import eu.aston.gitops.utils.GsonPath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Main {

    private final Logger LOGGER = LoggerFactory.getLogger(Main.class);

    public static final String RELOAD_PATH = "/reload";
    public static final String GIT_PREFIX = "/git/";
    public static final String ENCRYPT_PREFIX = "/encrypt/";
    public static final String REPO_PREFIX = "/repo/";

    public static void main(String[] args) {
        try{
            if("debug".equalsIgnoreCase(System.getenv("LOG_LEVEL"))){
                System.setProperty(org.slf4j.simple.SimpleLogger.DEFAULT_LOG_LEVEL_KEY, "DEBUG");
            }

            Main main = new Main();

            if("1".equals(System.getenv("WATCH"))) {
                WatchFacade watchFacade = new WatchFacade();
                String configMapPath = "/api/v1/configmaps?labelSelector=git-ops%3Dwatch&watch=true=";
                watchFacade.watch(configMapPath, ()-> main.eventCtx.exec(RELOAD_PATH));
                String secretPath = "/api/v1/secrets?labelSelector=git-ops%3Dwatch&watch=true";
                watchFacade.watch(secretPath, ()-> main.eventCtx.exec(RELOAD_PATH));
            }

            long period = Duration.ofMinutes(15).toMillis();
            Timer timer = new Timer(true);
            timer.schedule(main.timerTask(()->main.eventCtx.exec(RELOAD_PATH)), period, period);

            main.eventCtx.addAsync(RELOAD_PATH, (data)->main.reload());
            main.eventCtx.addAsync(REPO_PREFIX, main::createRepoReload);

            long start = (((System.currentTimeMillis()/60_000L))*60_000L)+61_000;
            timer.schedule(main.timerTask(main.eventCtx::checkCronNow), new Date(start), 60_000);

            int port = 8080;
            HttpServer httpServer = HttpServer.create(new InetSocketAddress(port), 0);
            String contextPath = System.getenv("CONTEXT_PATH");
            if(contextPath==null) contextPath = "/git-ops/";
            if(!contextPath.endsWith("/")) contextPath+="/";
            httpServer.createContext("/", new EventHttpHandler(main.eventCtx, contextPath));
            httpServer.start();
            System.out.println("star web server port "+port);

            main.reload();
        }catch (Exception e){
            System.out.println("start error "+e.getMessage());
        }
    }

    private final Gson gson;
    private final KubeService kubeService;
    private final RepoService repoService;
    private final EncryptService encryptService;
    private final GitService gitService;
    private final Set<String> opsNamespaces = new HashSet<>();
    private final EventCtx eventCtx = new EventCtx();
    private final Map<String, GitTask> gitTaskMap = new HashMap<>();

    public Main() {
        HttpClient httpClient = HttpClient
                .newBuilder()
                .build();
        this.gson = new GsonBuilder().create();
        this.kubeService = new KubeService();
        this.repoService = new RepoService(kubeService, httpClient);
        this.encryptService = new EncryptService();
        this.gitService = new GitService();
    }

    public void reload() {
        try{
            List<String> oldNames = new ArrayList<>(gitTaskMap.keySet());
            List<ConfigMap> l = kubeService.listConfigMapsByLabel("git-ops=watch");
            for(ConfigMap cm : l) {
                if(GsonPath.str(cm.raw(), "data", "git-ops.yaml")==null) continue;
                try{
                    GitTask task = gitTaskMap.get(cm.name());
                    if(task==null) {
                        task = new GitTask(kubeService, gitService, encryptService,
                                           gson, cm.namespace(), cm.name(), opsNamespaces);
                        gitTaskMap.put(cm.name(), task);
                        String path = GIT_PREFIX +cm.name();
                        LOGGER.info("create gitTask {}", path);
                        eventCtx.addAsync(path, task);
                    }
                    GitOpsData data = task.reload();
                    if(data.encryptKey()!=null){
                        EncryptTask encryptTask = new EncryptTask(encryptService, data.encryptKey());
                        String path = ENCRYPT_PREFIX +cm.name();
                        LOGGER.info("create encryptTask {}", path);
                        eventCtx.addBlocked(path, encryptTask);
                    }
                    try{
                        eventCtx.replaceCronExpression(data.cronExpression(), GIT_PREFIX +cm.name());
                    }catch (Exception e){
                        LOGGER.info("ignore invalid cron expression {}/{} - {}", cm.namespace(), cm.name(), e.getMessage());
                    }
                    oldNames.remove(cm.name());
                }catch (Exception e){
                    LOGGER.warn("init git task error {}/{} - {}", cm.namespace(), cm.name(), e.getMessage());
                    LOGGER.debug("init git task strace", e);
                }
            }
            for(String name : oldNames){
                eventCtx.remove(GIT_PREFIX +name);
                eventCtx.remove(ENCRYPT_PREFIX +name);
            }
        }catch (Exception e){
            LOGGER.warn("reload error {}", e.getMessage());
        }
    }

    public void createRepoReload(Map<String, Object> data) {
        if(data.get("uri") instanceof URI uri) {
            String[] items = uri.getPath().split("/");
            if(items.length==4 && Objects.equals(items[2], "repo")){
                String path = REPO_PREFIX+items[3];
                LOGGER.info("create repoTask {}", path);
                RepoTask repoTask = new RepoTask(kubeService, repoService, items[3], opsNamespaces);
                eventCtx.addAsync(path, repoTask);
                eventCtx.exec(path);
            }
        }
    }

    private TimerTask timerTask(Runnable run){
        return new TimerTask() {
            @Override
            public void run() {
                try{
                    run.run();
                }catch (Exception e){
                    LOGGER.warn("timer error {}", e.getMessage());
                }
            }
        };
    }

}