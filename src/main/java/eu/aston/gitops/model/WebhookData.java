package eu.aston.gitops.model;

import java.util.Map;

public record WebhookData(String url,
                          Map<String, String> headers) {
}
