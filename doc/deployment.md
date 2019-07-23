The ClinGen Data Service is intended to be deployed on Kubernetes, in the Geisinger/Broad Google Cloud environment. The following steps describe the process for setting up a running instance of this from scratch, assuming no Kubernetes related services are running. It may be possible to reuse existing resources, for example, a running Kubernetes cluster. This process will change when we transition to a GitOps based deployment strategy.


# Enable k8s API

The API necessary for configuring Kubernetes is not available by default, enable it if necessary by visiting the following page in the GCP Console:

https://console.cloud.google.com/kubernetes/


# Configure cluster resources.

ClinGen Search requires Kubernetes-configured nodes with Local SSD available. Only one disk is required, more may be needed if the size of the database expands in future. More detail is available here: https://cloud.google.com/kubernetes-engine/docs/how-to/persistent-volumes/local-ssd . At minimum, a machine with 8GB RAM and 2 cores is recommended (n1-standard-2 is appropriate). If scaling is required, it is preferable to increase the number of cores and amount of RAM on a single node, rather than spin up additional nodes (although two nodes would be more appropriate for a production configuraiton)

The following command configures an appropriate one-node cluster in the staging environment:

    gcloud container clusters create clingen-staging --num-nodes 1 --local-ssd-count 1 --machine-type=n1-standard-2

# Configure secret
        
The password for decrypting the client certificate needs to be configured on the cluster, but only once. Password omitted.
        
    kubectl create secret generic serveur-key-pass --from-literal password=<password>
    
# Create static IP

The data service should have a static IP. If one does not exist, create it.

    gcloud compute addresses create clingen-ds-stage-ip --region us-east1
    
The IP can be retrieved:

    gcloud compute addresses list
    
Be sure the service.yaml manifest for the file includes this as the loadBalancerIP value.

# Create container image and deploy to container registry

Docker and the GCP command line tools are required on the local machine in order to build the image. There are no other dependencies on the local machine (though Java 11 and Leiningen are useful for development). If you haven't already configure docker to use your GCP credentials:

    gcloud auth configure-docker

To build and upload image in the staging environment (change project name  and version tag as appropriate):

    docker build -t gcr.io/clingen-stage/clingen-ds:v1
    docker push gcr.io/clingen-stage/clingen-ds:v1
    
Make sure the image name is updated in deploy/<environment>/deployment.yaml for the target environment.

# Deploy manifest files

The deployment files are located in deploy/<environment> To deploy stage:

    kubectl apply -f deploy/stage/
    









