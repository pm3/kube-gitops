package eu.aston.gitops.service;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.time.Duration;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class WatchFacade {

    private final static Logger LOGGER = LoggerFactory.getLogger(WatchFacade.class);

    private final HttpClient httpClient;
    private final Executor executor;

    public WatchFacade() throws Exception {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .sslContext(getUntrustedSslContext()).build();
        this.executor = Executors.newThreadPerTaskExecutor(Executors.defaultThreadFactory());
    }

    @SuppressWarnings("BusyWait")
    public void watch(String path, Runnable run) throws Exception {
        String kubePort = System.getenv("KUBERNETES_PORT");
        if(kubePort==null) throw new Exception("env:KUBERNETES_PORT is empty");
        URI kube = new URI(kubePort);
        URI watcher = new URI("https://"+kube.getHost()+":"+kube.getPort()+path);

        File tokenFile = new File("/var/run/secrets/kubernetes.io/serviceaccount/token");
        if(!tokenFile.exists()) throw new Exception("token file not found");
        String token = Files.readString(tokenFile.toPath());

        executor.execute(() -> {
            while (!Thread.interrupted()) {
                try {

                    HttpRequest r = HttpRequest.newBuilder().GET().uri(watcher)
                                               .header("Accept", "application/json")
                                               .header("Authorization", "Bearer "+token)
                                                .timeout(Duration.ofMinutes(5))
                                               .build();

                    HttpResponse<InputStream> resp = httpClient.send(r, HttpResponse.BodyHandlers.ofInputStream());
                    if(resp.statusCode()!=200) {
                        throw new Exception("response code "+resp.statusCode());
                    }
                    BufferedReader br = new BufferedReader(new InputStreamReader(resp.body()));
                    while (true) {
                        String l = br.readLine();
                        if (l == null)
                            break;
                        //LOGGER.debug("watch line: {}", l);
                    }
                    run.run();
                } catch (Exception e) {
                    if(!e.getMessage().equals("closed")){
                        LOGGER.warn("watch error {} - {} {}", path, e.getMessage(), e.getClass());
                    }
                    try {
                        Thread.sleep(1000);
                    }catch (Exception ignore){}
                }
            }
        });
    }

    public static SSLContext getUntrustedSslContext() throws Exception {

        // Create a trust manager that does not validate certificate chains
        TrustManager trustAllCerts = new X509TrustManager() {
            public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                return null;
            }

            public void checkClientTrusted(java.security.cert.X509Certificate[] certs, String authType) {
            }

            public void checkServerTrusted(java.security.cert.X509Certificate[] certs, String authType) {
            }
        };

        SSLContext sc = SSLContext.getInstance("SSL");
        sc.init(null, new TrustManager[]{trustAllCerts}, new java.security.SecureRandom());
        return sc;
    }

}