package eu.aston.gitops.kube;

import com.google.gson.JsonElement;

public record Pod(String name,
                  String namespace,
                  JsonElement raw) {
}
