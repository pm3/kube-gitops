package eu.aston.gitops.service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import eu.aston.gitops.kube.Secret;
import eu.aston.gitops.utils.GsonPath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RepoService {

    private final static Logger LOGGER = LoggerFactory.getLogger(RepoService.class);

    private final KubeService kubeService;
    private final HttpClient httpClient;
    private final Map<String, String> cacheAuthorize = new ConcurrentHashMap<>();

    public RepoService(KubeService kubeService, HttpClient httpClient) {
        this.kubeService = kubeService;
        this.httpClient = httpClient;
    }

    public void addStorageSecrets(String namespace, List<String> storageSecrets){
        if(storageSecrets!=null){
            for(String n : storageSecrets){
                String key = "secret:"+n;
                if(!cacheAuthorize.containsKey(key)){
                    cacheAuthorize.put(key, key);
                    Secret secret = kubeService.getSecret(namespace, n);
                    if(secret!=null){
                        createAuth(GsonPath.str(secret.raw(), "data", ".dockerconfigjson"));
                    }
                }
            }
        }
    }

    private void createAuth(String dockerJsonB64) {
        if(dockerJsonB64!=null){
            String json = new String(Base64.getDecoder().decode(dockerJsonB64), StandardCharsets.UTF_8);
            JsonElement root = JsonParser.parseString(json);
            JsonObject auths = GsonPath.obj(root, "auths");
            for(String host : auths.keySet()){
                String auth = GsonPath.str(root, "auths", host, "auth");
                if(auth==null){
                    String user = GsonPath.str(root, "auths", host, "username");
                    String password = GsonPath.str(root, "auths", host, "password");
                    if(user!=null && password!=null){
                        auth = user+":"+password;
                    }
                }
                if(auth!=null){
                    cacheAuthorize.put(host, auth);
                }
            }
        }
    }

    public String imageHash(String image) {
        String[] name = parseImageName(image);
        if(name!=null){
            try{
                return containerDigest(name[0], name[1], name[2]);
            }catch (Exception e){
                LOGGER.warn("containerDigest error ({},{},{}) {}", name[0], name[1], name[2], e.getMessage());
            }
        }
        LOGGER.debug("no parse image {}", image);
        return null;
    }

    private String[] parseImageName(String image) {
        int pos1 = image.indexOf('/');
        if(pos1>0){
            String remote = image.substring(0, pos1);
            String name = image.substring(pos1+1);
            int pos2 = name.indexOf(':');
            String version = "latest";
            if(pos2>0){
                version = name.substring(pos2+1);
                name = name.substring(0,pos2);
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
        if (auth != null){
            b.header("Authorization", "Basic "+auth);
        }
        HttpResponse<String> resp = httpClient.send(b.build(), HttpResponse.BodyHandlers.ofString());
        if(LOGGER.isDebugEnabled()){
            LOGGER.debug("containerDigest {} {} {} - response code {}", remote, name, version, resp.statusCode());
            if(resp.statusCode()!=200){
                LOGGER.debug("response: {}", resp.body());
            }
        }
        return resp.headers().firstValue("Docker-Content-Digest").orElse(null);
    }
}
