# kube-gitops
manage kubernetes resource from git and kustomize 

## Flux Configuration

The `flux.yaml` file defines a complete GitOps deployment for Kubernetes using the Flux operator.

### Components

#### Service Account and RBAC
- Creates `flux` ServiceAccount in the `flux` namespace with image pull secret `astonvse`
- Sets up a ClusterRole with full permissions (`*`) on all resources and non-resource URLs
- Establishes ClusterRoleBinding to connect ServiceAccount and ClusterRole

#### Secrets and Configuration
- Secret `my-infra` containing:
  - `git-auth`: Git authentication credentials (encoded)
  - `encrypt-key`: Encryption key for sensitive data (encoded)
- ConfigMap `my-infra` containing repository configuration:
  - URL: https://github.com/pm3/kube-gitops-infra.git
  - Branch: master
  - Authentication using referenced secrets

#### Networking and Deployment
- Service exposing port 8080
- Deployment running `molnar33/kube-gitops` image with:
  - ServiceAccount-based permissions
  - Always pull policy
- Ingress configuration with:
  - Host: ww.example.com
  - Paths for Git operations and container registry access

### Deployment

To deploy Flux to your Kubernetes cluster:

```bash
kubectl apply -f docs/flux.yaml
```

After deployment, Flux will continuously synchronize your cluster with the state defined in the Git repository.


