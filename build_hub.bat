
docker build -t kube-gitops .

docker tag kube-gitops molnar33/kube-gitops:latest
docker tag kube-gitops molnar33/kube-gitops:0.10.0

docker push molnar33/kube-gitops:latest
docker push molnar33/kube-gitops:0.10.0
