package eu.aston.gitops.task;

import java.net.http.HttpClient;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.google.gson.JsonElement;
import eu.aston.gitops.kube.Pod;
import eu.aston.gitops.service.KubeService;
import eu.aston.gitops.service.RepoService;
import eu.aston.gitops.utils.GsonPath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RepoTask implements Runnable {
    private final  Logger LOGGER = LoggerFactory.getLogger(RepoTask.class);

    private final KubeService kubeService;
    private final RepoService repoService;
    private final String host;
    private final Set<String> opsNamespaces;

    public RepoTask(KubeService kubeService, RepoService repoService, String host, Set<String> opsNamespaces) {
        this.kubeService = kubeService;
        this.repoService = repoService;
        this.host = host;
        this.opsNamespaces = opsNamespaces;
    }

    public String getHost() {
        return host;
    }

    @Override
    public void run() {
        Map<String, String> imageHashCache = new HashMap<>();
        for(String namespace : opsNamespaces){
            LOGGER.info("scan namespace {}", namespace);
            List<Pod> pods = kubeService.listPods(namespace);
            for(Pod pod : pods) {
                if(checkPodImages(pod, imageHashCache)){
                    LOGGER.info("reload pod {}/{}", pod.namespace(), pod.name());
                    kubeService.deletePod(pod.namespace(), pod.name());
                }
            }
        }
    }

    private boolean checkPodImages(Pod pod, Map<String, String> imageHashCache) {
        repoService.addStorageSecrets(pod.namespace(), podImagePullSecret(pod));
        List<ImageData> arr = getImageData(pod);
        for(ImageData image : arr){
            if(image.image()!=null && image.imageID()!=null && image.image().startsWith(host)){
                String aktHash = image.hash();
                if(aktHash!=null){
                    String repoHash = imageHashCache.computeIfAbsent(image.image(), repoService::imageHash);
                    if(repoHash!=null && !Objects.equals(aktHash, repoHash)){
                        LOGGER.debug("pod {}/{} hash {} != {}", pod.namespace(), pod.name(), aktHash, repoHash);
                        return true;
                    }
                } else {
                    LOGGER.debug("pod {}/{} null hash", pod.namespace(), pod.name());
                }
            }
        }
        return false;
    }

    private List<ImageData> getImageData(Pod pod) {
        return GsonPath.arrayMap(pod.raw(), this::podImage, "status", "containerStatuses", "*");
    }

    private List<String> podImagePullSecret(Pod pod) {
        return GsonPath.arrayMap(pod.raw(), GsonPath::asString, "spec", "imagePullSecrets", "*", "name");
    }

    private ImageData podImage(JsonElement e){
        String image = GsonPath.str(e, "image");
        String imageID = GsonPath.str(e, "imageID");
        if(image!=null && imageID!=null){
            String[] items = imageID.split("@");
            if(items.length>1){
                return new ImageData(image, imageID, items[items.length-1]);
            }
        }
        return null;
    }

    public record ImageData(String image,
                     String imageID,
                     String hash){}
}
