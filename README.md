# InstaInfer-SoCC24

This repo contains a demo implementation of our SoCC 2024 paper, [Pre-Warming is Not Enough: Accelerating Serverless Inference With Opportunistic Pre-Loading](https://intellisys.haow.us/assets/pdf/yifan-socc24.pdf)

This work was performed when Yifan Sui was a remote intern student advised by Dr. Hao Wang at the IntelliSys Lab of Stevens Institute of Technology.

Please refer to this work as

```
@inproceedings{sui2024pre,
  title={Pre-Warming is Not Enough: Accelerating Serverless Inference With Opportunistic Pre-Loading},
  author={Sui, Yifan and Yu, Hanfei and Hu, Yitao and Li, Jianxun and Wang, Hao},
  booktitle={Proceedings of the 2024 ACM Symposium on Cloud Computing},
  pages={178--195},
  year={2024}
}
```

---

> Serverless computing has rapidly prospered as a new cloud computing diagram with agile scalability, pay-as-you-go pricing, and ease-to-use features for Machine Learning (ML) inference tasks. Users package their ML code into lightweight serverless functions and execute them using containers. Unfortunately, a notorious problem, called cold-starts, hinders serverless computing from providing low-latency function executions. To mitigate cold-starts, pre-warming, which keeps containers warm predictively, has been widely accepted by academia and industry. However, pre-warming fails to eliminate the unique latency incurred by loading ML artifacts. We observed that for ML inference functions, the loading of libraries and models takes significantly more time than container warming. Consequently, pre-warming alone is not enough to mitigate the ML inference function's cold-starts.
>

> This paper introduces InstaInfer, an opportunistic pre-loading technique to achieve instant inference by eliminating the latency associated with loading ML artifacts, thereby achieving minimal time cost in function execution. InstaInfer fully utilizes the memory of warmed containers to pre-load the function's libraries and model, striking a balance between maximum acceleration and resource wastage. We design InstaInfer to be transparent to providers and compatible with existing pre-warming solutions. Experiments on OpenWhisk with real-world workloads show that InstaInfer reduces up to 93% loading latency and achieves up to 8 × speedup compared to state-of-the-art pre-warming solutions.
>

---

InstaInfer is built atop [Apache OpenWhisk](https://github.com/apache/openwhisk). We describe how to build and deploy InstaInfer from scratch for this demo.

As InstaInfer is compatible with different pre-warming solutions, this repo shows InstaInfer + Pagurus, the pre-warming method of [Help Rather Than Recycle](https://www.usenix.org/conference/atc22/presentation/li-zijun-help).

## Build From Scratch

### Hardware Prerequisite

- Operating systems and versions: Ubuntu 22.04
- Resource requirement
  - CPU: >= 8 cores
  - Memory: >= 15 GB
  - Disk: >= 40 GB
  - Network: no requirement since it's a single-node deployment

### Deployment and Run Demo

This demo hosts all InstaInfer’s components on a single node.

**Instruction**

1. Download the github repo.

```
git clone https://github.com/IntelliSys-Lab/InstaInfer.git
```

2. Set up OpenWhisk Environment.

```
cd ~/InstaInfer/tools/ubuntu-setup
sudo ./all.sh
```

3. Deploy InstaInfer. This could take quite a while due to building Docker images from scratch. The recommended shell is Bash.

```
cd ~/InstaInfer
sudo chmod +x setup.sh
sudo ./setup.sh
```

4. Run InstaInfer’s demo. The demo experiment runs a 5-minute workload from Azure Trace.

```bash
cd ~/InstaInfer/demo
python3 Excute_from_trace_5min.py
```

## Experimental Results and OpenWhisk Logs

After executing `Excute_from_trace_5min.py`, you may use the `wsk-cli` to check the results of function executions:

```
wsk -i activation list
```

Detailed experimental results are collected as `output.log` file in  `InstaInfer/demo`. The result includes function end-to-end and startup latency, invocation startup types, timelines, and whether pre-loaded. Note that `~/InstaInfer/demo/output.log` is not present in the initial repo. It will only be generated after running an experiment. OpenWhisk system logs can be found under `/var/tmp/wsklogs`.

## Workloads

We use the industrial trace of [Azure Function](https://github.com/Azure/AzurePublicDataset?tab=readme-ov-file#azure-functions-traces) for evaluation. In detail, after downloading the Azure trace files, we use [Select_Trace_5min.py](demo%2FSelect_Trace_5min.py) to extract the trace that can be used to invoke InstaInfer. Then, run the [Excute_from_trace_5min.py](demo%2FExcute_from_trace_5min.py) to start invoking.


## Distributed InstaInfer

The steps of deploying a distributed InstaInfer are basically the same as deploying a distributed OpenWhisk cluster. For deploying a distributed InstaInfer, please refer to the README of [Apache OpenWhisk](https://github.com/apache/openwhisk) and [Ansible](https://github.com/apache/openwhisk/tree/master/ansible).


## How to write your own inference functions and achieve pre-loading?


In OpenWhisk, each Docker Container has a Runtime Proxy component that can interact with the OpenWhisk Invoker. To achieve pre-loading, we need to modify the container’s runtime proxy, which is written in GoLang.

InstaInfer’s Pre-loading logic within the proxy in stored in [OpenWhisk-Runtime-Go](openwhisk-runtime-go-master). You can refer to this repo to build your own Proxy and add inference functions.

## How to modify InstaInfer’s code?

1. To modify the Proactive Pre-loader component, please modify:

```bash
InstaInfer/core/controller/src/main/scala/org/apache/openwhisk/core/loadBalancer/ShardingContainerPoolBalancer.scala
```

2. To modify the Pre-loading Scheduler, please modify:

```bash
InstaInfer/core/invoker/src/main/scala/org/apache/openwhisk/core/containerpool/ContainerPool.scala

InstaInfer/core/invoker/src/main/scala/org/apache/openwhisk/core/containerpool/ContainerProxy.scala
```

3. To modify the Intra-Container Manager, Please modify the runtime proxy and build your own runtime image to create custom actions in OpenWhisk. For building custom runtime image, please refer to [How to build Runtime Image](openwhisk-runtime-go-master%2Fbuild_image).
