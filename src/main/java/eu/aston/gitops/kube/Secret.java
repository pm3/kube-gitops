package eu.aston.gitops.kube;

import com.google.gson.JsonElement;

public record Secret(String name,
                     String namespace,
                     JsonElement raw) {
}
