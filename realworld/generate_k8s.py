#!/usr/bin/env python3
"""Generate the Kubernetes objects for the 2-DC, 6-host, 20-VM topology."""

import argparse
from pathlib import Path


# (VM id, type, CPU limit, memory limit, namespace, datacenter, host)
PLACEMENT = [
    (0, "V3", "1000m", "512Mi", "dc1-performance", "DC-1", "dc1-hostA0"),
    (1, "V3", "1000m", "512Mi", "dc1-performance", "DC-1", "dc1-hostA1"),
    (2, "V3", "1000m", "512Mi", "dc1-performance", "DC-1", "dc1-hostA2"),
    (3, "V3", "1000m", "512Mi", "dc1-performance", "DC-1", "dc1-hostA0"),
    (4, "V2", "300m", "256Mi", "dc1-performance", "DC-1", "dc1-hostA1"),
    (5, "V2", "300m", "256Mi", "dc1-performance", "DC-1", "dc1-hostA2"),
    (6, "V2", "300m", "256Mi", "dc1-performance", "DC-1", "dc1-hostA1"),
    (7, "V2", "300m", "256Mi", "dc1-performance", "DC-1", "dc1-hostA2"),
    (8, "V2", "300m", "256Mi", "dc2-efficiency", "DC-2", "dc2-hostB0"),
    (9, "V2", "300m", "256Mi", "dc2-efficiency", "DC-2", "dc2-hostB1"),
    (10, "V2", "300m", "256Mi", "dc2-efficiency", "DC-2", "dc2-hostB2"),
    (11, "V2", "300m", "256Mi", "dc2-efficiency", "DC-2", "dc2-hostB0"),
    (12, "V1", "100m", "128Mi", "dc2-efficiency", "DC-2", "dc2-hostB1"),
    (13, "V1", "100m", "128Mi", "dc2-efficiency", "DC-2", "dc2-hostB2"),
    (14, "V1", "100m", "128Mi", "dc2-efficiency", "DC-2", "dc2-hostB1"),
    (15, "V1", "100m", "128Mi", "dc2-efficiency", "DC-2", "dc2-hostB2"),
    (16, "V1", "100m", "128Mi", "dc2-efficiency", "DC-2", "dc2-hostB0"),
    (17, "V1", "100m", "128Mi", "dc2-efficiency", "DC-2", "dc2-hostB1"),
    (18, "V1", "100m", "128Mi", "dc2-efficiency", "DC-2", "dc2-hostB2"),
    (19, "V1", "100m", "128Mi", "dc2-efficiency", "DC-2", "dc2-hostB0"),
]

NAMESPACES = ("dc1-performance", "dc2-efficiency")


def namespace_manifest(name):
    datacenter = "DC-1" if name == "dc1-performance" else "DC-2"
    return f"""apiVersion: v1
kind: Namespace
metadata:
  name: {name}
  labels:
    soka.dc: "{datacenter}"
"""


def vm_manifests(vm_id, vm_type, cpu, memory, namespace, datacenter, host):
    name = f"vm{vm_id:02d}"
    node_port = 30100 + vm_id
    return f"""apiVersion: v1
kind: Pod
metadata:
  name: kpb-{name}
  namespace: {namespace}
  labels:
    app: soka-vm
    vm: {name}
    soka.dc: "{datacenter}"
    soka.host: "{host}"
    soka.vm_type: "{vm_type}"
spec:
  nodeSelector:
    soka.host: "{host}"
  containers:
    - name: worker
      image: soka-worker:v1
      imagePullPolicy: Never
      ports:
        - name: http
          containerPort: 8000
          protocol: TCP
      readinessProbe:
        httpGet:
          path: /health
          port: http
        periodSeconds: 2
        timeoutSeconds: 1
        failureThreshold: 30
      livenessProbe:
        httpGet:
          path: /health
          port: http
        periodSeconds: 10
        timeoutSeconds: 1
      env:
        - name: VM_NAME
          value: "{name}"
        - name: VM_TYPE
          value: "{vm_type}"
        - name: PORT
          value: "8000"
      resources:
        requests:
          cpu: "50m"
          memory: "64Mi"
        limits:
          cpu: "{cpu}"
          memory: "{memory}"
---
apiVersion: v1
kind: Service
metadata:
  name: {name}-svc
  namespace: {namespace}
spec:
  type: NodePort
  selector:
    app: soka-vm
    vm: {name}
  ports:
    - name: http
      port: 8000
      targetPort: 8000
      nodePort: {node_port}
      protocol: TCP
"""


def generate():
    sections = [namespace_manifest(namespace).rstrip() for namespace in NAMESPACES]
    sections.extend(
        vm_manifests(*vm).rstrip()
        for vm in PLACEMENT
    )
    return "\n---\n".join(sections) + "\n"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--output",
        type=Path,
        default=Path(__file__).with_name("k8s-topology.yaml"),
        help="destination manifest (default: realworld/k8s-topology.yaml)",
    )
    args = parser.parse_args()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(generate(), encoding="utf-8")
    print(f"Generated {args.output}: 2 namespaces, 20 VM Pods, 20 NodePort Services")


if __name__ == "__main__":
    main()
