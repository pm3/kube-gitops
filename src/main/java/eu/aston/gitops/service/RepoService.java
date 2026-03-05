package eu.aston.gitops.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import eu.aston.gitops.kube.Secret;
import eu.aston.gitops.model.OciData;
import eu.aston.gitops.utils.GsonPath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class RepoService {

    private final static Logger LOGGER = LoggerFactory.getLogger(RepoService.class);

    private final KubeService kubeService;
    private final HttpClient httpClient;
    private final String registryScheme;
    private final Map<String, String> cacheAuthorize = new ConcurrentHashMap<>();
    /**
     * Cache image reference -> manifest digest; pull only when digest changes.
     */
    private final Map<String, String> cacheDigest = new ConcurrentHashMap<>();

    public RepoService(KubeService kubeService, HttpClient httpClient) {
        this(kubeService, httpClient, "https");
    }

    /**
     * For tests: use registryScheme "http" when mocking registry on localhost.
     */
    public RepoService(KubeService kubeService, HttpClient httpClient, String registryScheme) {
        this.kubeService = kubeService;
        this.httpClient = httpClient;
        this.registryScheme = registryScheme != null ? registryScheme : "https";
    }

    public void addStorageSecrets(String namespace, List<String> storageSecrets) {
        if (storageSecrets != null) {
            for (String n : storageSecrets) {
                String key = "secret:" + n;
                if (!cacheAuthorize.containsKey(key)) {
                    cacheAuthorize.put(key, key);
                    Secret secret = kubeService.getSecret(namespace, n);
                    if (secret != null) {
                        createAuth(GsonPath.str(secret.raw(), "data", ".dockerconfigjson"));
                    }
                }
            }
        }
    }

    private void createAuth(String dockerJsonB64) {
        if (dockerJsonB64 != null) {
            String json = new String(Base64.getDecoder().decode(dockerJsonB64), StandardCharsets.UTF_8);
            JsonElement root = JsonParser.parseString(json);
            JsonObject auths = GsonPath.obj(root, "auths");
            for (String host : auths.keySet()) {
                String auth = GsonPath.str(root, "auths", host, "auth");
                if (auth == null) {
                    String user = GsonPath.str(root, "auths", host, "username");
                    String password = GsonPath.str(root, "auths", host, "password");
                    if (user != null && password != null) {
                        auth = user + ":" + password;
                    }
                }
                if (auth != null) {
                    cacheAuthorize.put(host, auth);
                }
            }
        }
    }

    public String imageHash(String image) {
        String[] name = parseImageName(image);
        if (name != null) {
            try {
                return containerDigest(name[0], name[1], name[2]);
            } catch (Exception e) {
                LOGGER.warn("containerDigest error ({},{},{}) {}", name[0], name[1], name[2], e.getMessage());
            }
        }
        LOGGER.debug("no parse image {}", image);
        return null;
    }

    private String[] parseImageName(String image) {
        int pos1 = image.indexOf('/');
        if (pos1 > 0) {
            String remote = image.substring(0, pos1);
            String name = image.substring(pos1 + 1);
            int pos2 = name.indexOf(':');
            String version = "latest";
            if (pos2 > 0) {
                version = name.substring(pos2 + 1);
                name = name.substring(0, pos2);
            }
            return new String[]{remote, name, version};
        }
        return null;
    }


    public String
    containerDigest(String remote, String name, String version) throws Exception {
        String uri = "https://" + remote + "/v2/" + name + "/manifests/" + version;
        String auth = cacheAuthorize.get(remote);
        HttpRequest.Builder b = HttpRequest.newBuilder().GET().uri(new URI(uri));
        b.header("Accept", "application/vnd.docker.distribution.manifest.v2+json");
        b.header("Accept", "application/vnd.oci.image.index.v1+json");
        b.timeout(Duration.ofSeconds(10));
        if (auth != null) {
            b.header("Authorization", "Basic " + auth);
        }
        HttpResponse<String> resp = httpClient.send(b.build(), HttpResponse.BodyHandlers.ofString());
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("containerDigest {} {} {} - response code {}", remote, name, version, resp.statusCode());
            if (resp.statusCode() != 200) {
                LOGGER.debug("response: {}", resp.body());
            }
        }
        return resp.headers().firstValue("Docker-Content-Digest").orElse(null);
    }

    /**
     * Load auth from Kubernetes secret into cache. authRef is either "secret" (use defaultNamespace)
     * or "namespace/secret".
     */
    public void ensureAuthForPull(String authRef, String defaultNamespace) {
        if (authRef == null || authRef.isBlank()) return;
        String namespace;
        String secretName;
        int slash = authRef.indexOf('/');
        if (slash > 0) {
            namespace = authRef.substring(0, slash);
            secretName = authRef.substring(slash + 1);
        } else {
            namespace = defaultNamespace;
            secretName = authRef.trim();
        }
        String key = "secret:" + namespace + "/" + secretName;
        if (cacheAuthorize.containsKey(key)) return;
        cacheAuthorize.put(key, key);
        Secret secret = kubeService.getSecret(namespace, secretName);
        if (secret != null) {
            createAuth(GsonPath.str(secret.raw(), "data", ".dockerconfigjson"));
        }
    }

    private record ManifestResponse(String digest, String body) {
    }

    private ManifestResponse getManifest(String remote, String name, String version) throws Exception {
        String uri = registryScheme + "://" + remote + "/v2/" + name + "/manifests/" + version;
        String auth = cacheAuthorize.get(remote);
        HttpRequest.Builder b = HttpRequest.newBuilder().GET().uri(URI.create(uri));
        b.header("Accept", "application/vnd.docker.distribution.manifest.v2+json");
        b.timeout(Duration.ofSeconds(30));
        if (auth != null) {
            b.header("Authorization", "Basic " + auth);
        }
        HttpResponse<String> resp = httpClient.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() != 200) {
            LOGGER.warn("getManifest {} {} {} - {}", remote, name, version, resp.statusCode());
            throw new RuntimeException("manifest " + resp.statusCode() + " " + resp.body());
        }
        String digest = resp.headers().firstValue("Docker-Content-Digest").orElse(null);
        return new ManifestResponse(digest, resp.body());
    }

    private byte[] getBlob(String remote, String name, String digest) throws Exception {
        String uri = registryScheme + "://" + remote + "/v2/" + name + "/blobs/" + digest;
        String auth = cacheAuthorize.get(remote);
        HttpRequest.Builder b = HttpRequest.newBuilder().GET().uri(URI.create(uri));
        b.timeout(Duration.ofMinutes(5));
        if (auth != null) {
            b.header("Authorization", "Basic " + auth);
        }
        HttpResponse<byte[]> resp = httpClient.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
        if (resp.statusCode() != 200) {
            LOGGER.warn("getBlob {} {} - {}", remote, digest, resp.statusCode());
            throw new RuntimeException("blob " + resp.statusCode());
        }
        return resp.body();
    }

    /**
     * Pull OCI image: fetch manifest, download first layer, extract tar.gzip to dir.
     * oci.img() is e.g. aidaston.azurecr.io/edu-infra-deploy:latest
     * oci.auth() is "secret" or "namespace/secret" (Kubernetes secret with .dockerconfigjson from docker login).
     */
    public boolean pull(File dir, OciData oci, String defaultNamespace) {
        if (oci == null || oci.img() == null || oci.img().isBlank()) {
            LOGGER.warn("pull: missing oci image");
            return false;
        }
        String[] parsed = parseImageName(oci.img());
        if (parsed == null) {
            LOGGER.warn("pull: cannot parse image {}", oci.img());
            return false;
        }
        String remote = parsed[0];
        String name = parsed[1];
        String version = parsed[2];
        try {
            ensureAuthForPull(oci.auth(), defaultNamespace);
            ManifestResponse manifest = getManifest(remote, name, version);
            String currentDigest = manifest.digest();
            if (currentDigest != null && currentDigest.equals(cacheDigest.get(oci.img()))) {
                LOGGER.debug("pull {} unchanged (digest {})", oci.img(), currentDigest);
                return false;
            }
            JsonElement root = JsonParser.parseString(manifest.body());
            JsonArray layers = GsonPath.asArray(GsonPath.el(root, "layers"));
            if (layers == null || layers.isEmpty()) {
                LOGGER.warn("pull: no layers in manifest");
                return false;
            }
            String layerDigest = GsonPath.str(layers.get(0), "digest");
            if (layerDigest == null) {
                LOGGER.warn("pull: first layer has no digest");
                return false;
            }
            byte[] blob = getBlob(remote, name, layerDigest);
            if (!dir.exists()) {
                Files.createDirectories(dir.toPath());
            }
            File tmp = File.createTempFile("oci-layer-", ".tar.gz");
            try {
                Files.write(tmp.toPath(), blob);
                extractTarGz(tmp, dir);
            } finally {
                Files.deleteIfExists(tmp.toPath());
            }
            if (currentDigest != null) {
                cacheDigest.put(oci.img(), currentDigest);
            }
            LOGGER.info("pull {} into {} (digest {})", oci.img(), dir.getAbsolutePath(), currentDigest);
            return true;
        } catch (Exception e) {
            LOGGER.warn("pull {} failed: {}", oci.img(), e.getMessage());
            LOGGER.debug("pull trace", e);
            return false;
        }
    }

    private void extractTarGz(File archive, File destDir) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("tar", "-xzf", archive.getAbsolutePath(), "-C", destDir.getAbsolutePath());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String err = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int code = p.waitFor();
        if (code != 0) {
            LOGGER.warn("tar exit {}: {}", code, err);
            throw new RuntimeException("tar exit " + code + " " + err);
        }
    }
}
