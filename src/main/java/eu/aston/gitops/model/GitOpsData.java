package eu.aston.gitops.model;

import java.util.List;
import java.util.Map;

public record GitOpsData(GitData git,
                         OciData oci,
                         String encryptKey,
                         Map<String, String> namespacesMap,
                         List<String> namespacesInclude,
                         List<String> namespacesExclude,
                         String cronExpression,
                         WebhookData webhook) {
}
