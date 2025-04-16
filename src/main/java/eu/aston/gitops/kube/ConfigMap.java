package eu.aston.gitops.kube;

import com.google.gson.JsonElement;

public record ConfigMap(String name,
                        String namespace,
                        JsonElement raw) {
}
