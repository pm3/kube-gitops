
docker build -t kube-gitops .

docker tag kube-gitops repo.astondev.sk/kube-gitops:latest

docker push repo.astondev.sk/kube-gitops:latest
