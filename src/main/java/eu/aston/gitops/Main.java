package eu.aston.gitops;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.sun.net.httpserver.HttpServer;
import eu.aston.gitops.event.EventCtx;
import eu.aston.gitops.event.EventHttpHandler;
import eu.aston.gitops.kube.ConfigMap;
import eu.aston.gitops.model.GitOpsData;
import eu.aston.gitops.service.*;
import eu.aston.gitops.task.EncryptTask;
import eu.aston.gitops.task.GitTask;
import eu.aston.gitops.task.OciTask;
import eu.aston.gitops.task.RepoTask;
import eu.aston.gitops.utils.GsonPath;
import eu.aston.gitops.utils.YamlGsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.io.StringReader;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.*;

public class Main {

    private final Logger LOGGER = LoggerFactory.getLogger(Main.class);

    public static final String RELOAD_PATH = "/reload";
    public static final String GIT_PREFIX = "/git/";
    public static final String OCI_PREFIX = "/oci/";
    public static final String ENCRYPT_PREFIX = "/encrypt/";
    public static final String REPO_PREFIX = "/repo/";

    public static void main(String[] args) {
        try {
            if ("debug".equalsIgnoreCase(System.getenv("LOG_LEVEL"))) {
                System.setProperty(org.slf4j.simple.SimpleLogger.DEFAULT_LOG_LEVEL_KEY, "DEBUG");
            }

            logBuildInfo();

            Main main = new Main();

            if ("1".equals(System.getenv("WATCH"))) {
                WatchFacade watchFacade = new WatchFacade();
                String configMapPath = "/api/v1/configmaps?labelSelector=git-ops%3Dwatch&watch=true=";
                watchFacade.watch(configMapPath, () -> main.eventCtx.exec(RELOAD_PATH));
                String secretPath = "/api/v1/secrets?labelSelector=git-ops%3Dwatch&watch=true";
                watchFacade.watch(secretPath, () -> main.eventCtx.exec(RELOAD_PATH));
            }

            long period = Duration.ofMinutes(15).toMillis();
            Timer timer = new Timer(true);
            timer.schedule(main.timerTask(() -> main.eventCtx.exec(RELOAD_PATH)), period, period);

            main.eventCtx.addAsync(RELOAD_PATH, (data) -> main.reload());
            main.eventCtx.addAsync(REPO_PREFIX, main::createRepoReload);

            long start = (((System.currentTimeMillis() / 60_000L)) * 60_000L) + 61_000;
            timer.schedule(main.timerTask(main.eventCtx::checkCronNow), new Date(start), 60_000);

            int port = 8080;
            HttpServer httpServer = HttpServer.create(new InetSocketAddress(port), 0);
            String contextPath = System.getenv("CONTEXT_PATH");
            if (contextPath == null) contextPath = "/git-ops/";
            if (!contextPath.endsWith("/")) contextPath += "/";
            httpServer.createContext("/", new EventHttpHandler(main.eventCtx, contextPath));
            httpServer.start();
            System.out.println("star web server port " + port);

            main.reload();
        } catch (Exception e) {
            System.out.println("start error " + e.getMessage());
        }
    }

    private final Gson gson;
    private final KubeService kubeService;
    private final RepoService repoService;
    private final EncryptService encryptService;
    private final GitService gitService;
    private final Set<String> opsNamespaces = new HashSet<>();
    private final EventCtx eventCtx = new EventCtx();

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
        try {
            List<String> oldPaths = new ArrayList<>(eventCtx.eventNames());
            List<ConfigMap> l = kubeService.listConfigMapsByLabel("git-ops=watch");
            for (ConfigMap cm : l) {
                String yaml = GsonPath.str(cm.raw(), "data", "git-ops.yaml");
                if (yaml == null) continue;
                YamlGsonParser yamlGsonParser = new YamlGsonParser(gson, kubeService);
                GitOpsData data = yamlGsonParser.parseYaml(new StringReader(yaml), GitOpsData.class, cm.namespace());
                if (data.git() != null) {
                    try {
                        String path = GIT_PREFIX + cm.name();
                        oldPaths.remove(path);
                        GitTask gitTask = (GitTask) eventCtx.task(path);
                        if (gitTask == null) {
                            gitTask = new GitTask(kubeService, gitService, encryptService,
                                    cm.name(), opsNamespaces);
                            LOGGER.info("create gitTask {}", path);
                            eventCtx.addAsync(path, gitTask);
                        }
                        gitTask.reload(data, yaml);
                    } catch (Exception e) {
                        LOGGER.warn("init git task error {}/{} - {}", cm.namespace(), cm.name(), e.getMessage());
                        LOGGER.debug("init git task strace", e);
                    }
                }
                if (data.oci() != null) {
                    try {
                        String path = OCI_PREFIX + cm.name();
                        oldPaths.remove(path);
                        OciTask ociTask = (OciTask) eventCtx.task(path);
                        if (ociTask == null) {
                            ociTask = new OciTask(kubeService, repoService, encryptService,
                                    cm.name(), cm.namespace(), opsNamespaces);
                            LOGGER.info("create ociTask {}", path);
                            eventCtx.addAsync(path, ociTask);
                        }
                        ociTask.reload(data, yaml);
                    } catch (Exception e) {
                        LOGGER.warn("init oci task error {}/{} - {}", cm.namespace(), cm.name(), e.getMessage());
                        LOGGER.debug("init oci task strace", e);
                    }
                }
                if (data.encryptKey() != null) {
                    EncryptTask encryptTask = new EncryptTask(encryptService, data.encryptKey());
                    String path = ENCRYPT_PREFIX + cm.name();
                    LOGGER.info("create encryptTask {}", path);
                    eventCtx.addBlocked(path, encryptTask);
                    oldPaths.remove(path);
                }
                try {
                    eventCtx.replaceCronExpression(data.cronExpression(), GIT_PREFIX + cm.name());
                } catch (Exception e) {
                    LOGGER.info("ignore invalid cron expression {}/{} - {}", cm.namespace(), cm.name(), e.getMessage());
                }
            }
            for (String path : oldPaths) {
                if (path.startsWith(GIT_PREFIX) || path.startsWith(OCI_PREFIX) || path.startsWith(ENCRYPT_PREFIX)) {
                    eventCtx.remove(path);
                }
            }
        } catch (Exception e) {
            LOGGER.warn("reload error {}", e.getMessage());
        }
    }

    public void createRepoReload(Map<String, Object> data) {
        if (data.get("uri") instanceof URI uri) {
            String[] items = uri.getPath().split("/");
            if (items.length == 4 && Objects.equals(items[2], "repo")) {
                String path = REPO_PREFIX + items[3];
                LOGGER.info("create repoTask {}", path);
                RepoTask repoTask = new RepoTask(kubeService, repoService, items[3], opsNamespaces);
                eventCtx.addAsync(path, repoTask);
                eventCtx.exec(path);
            }
        }
    }

    private static void logBuildInfo() {
        Logger log = LoggerFactory.getLogger(Main.class);
        try (InputStream in = Main.class.getResourceAsStream("/META-INF/build-info.properties")) {
            if (in == null) {
                log.info("build info: (not available)");
                return;
            }
            Properties p = new Properties();
            p.load(in);
            String version = p.getProperty("version", "?");
            String commit = p.getProperty("commit", "?");
            String buildTime = p.getProperty("buildTime", "?");
            if (commit != null && commit.startsWith("${")) commit = "n/a";
            if (buildTime != null && buildTime.startsWith("${")) buildTime = "n/a";
            log.info("build info: version={}, commit={}, buildTime={}", version, commit, buildTime);
        } catch (Exception e) {
            log.warn("could not load build info: {}", e.getMessage());
        }
    }

    private TimerTask timerTask(Runnable run) {
        return new TimerTask() {
            @Override
            public void run() {
                try {
                    run.run();
                } catch (Exception e) {
                    LOGGER.warn("timer error {}", e.getMessage());
                }
            }
        };
    }

}