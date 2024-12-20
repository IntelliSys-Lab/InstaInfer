/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.openwhisk.core.containerpool

import akka.actor.{Actor, ActorRef, ActorRefFactory, Props}
import org.apache.openwhisk.common.{Logging, LoggingMarkers, MetricEmitter, TransactionId}
import org.apache.openwhisk.core.connector.MessageFeed
import org.apache.openwhisk.core.containerpool.docker.ProcessRunner
import org.apache.openwhisk.core.entity.ExecManifest.ReactivePrewarmingConfig
import org.apache.openwhisk.core.entity._
import org.apache.openwhisk.core.entity.size._

import java.util.concurrent.{Executors, TimeUnit}
import scala.annotation.tailrec
import scala.collection.immutable
import scala.collection.mutable
import scala.collection.mutable.PriorityQueue
import scala.concurrent.duration._
import scala.util.{ Random, Try}

//新添加的
import scala.concurrent.Future
import akka.actor.ActorSystem
import scala.math.exp







case class ColdStartKey(kind: String, memory: ByteSize)

case object EmitMetrics

case object AdjustPrewarmedContainer

/*Use WindowMap to store each function's pre-warm and keep-alive time (from pre-warming method like
Histogram. And store each function's pre-load and off-load time.
These data are passed from SharingPoolLoadBalancer.
 */
case object WindowMap {
  var MAP = scala.collection.mutable.Map("ContainerName" -> (0,0,0,0))

  def getWindow(job:Run) = {
    val prewarm = job.msg.preWarmParameter
    val keepalive = job.msg.keepAliveParameter
    val preload = job.msg.preLoadParameter
    val offload = job.msg.offLoadParameter
    MAP(job.action.fullyQualifiedName(false).toString)=(prewarm,keepalive,preload,offload)
    (prewarm,keepalive,preload,offload)
  }
}


//For each type of inference function, build a class to store its model size, loading latency, request arrival probability.
case class ModelData(
                      actionName: String,
                      modelName: String,
                      modelNameNumber:Int,
                      modelLoadingLatency: Double,
                      lamda: Double,
                      modelArrivalProbability: Double,
                      dataKeyPath: String,
                      expectedSavedLatency: Double,
                      modelSize: Int
                    )

class ModelTable {
  private var models: List[ModelData] = List()


  def getModels: List[ModelData] = models

  def addModel(modelData: ModelData): Unit = {
    models = modelData :: models
  }

  def removeModel(modelName: String): Unit = {
    models = models.filterNot(_.modelName == modelName)
  }

  def displayTable(): Unit = {
    println("Action Name | Model Name | Model Loading Latency | Model Arrival Probability | Data Key & Path | Expected Saved Latency")
    models.foreach { model =>
      println(s"${model.actionName} | ${model.modelName} | ${model.modelLoadingLatency} | ${model.modelArrivalProbability} | ${model.dataKeyPath} | ${model.expectedSavedLatency}")
    }
  }

  def findModelByActionName(actionName: String): Option[ModelData] = {
    models.find(_.actionName == actionName)
  }

  def updateModelLamda(actionName: String, newLamda: Double): Unit = {
    // The map operation creates a new list, where for each element...
    models = models.map { model =>
      // ...if the actionName matches the given actionName...
      if (model.actionName == actionName)
      // ...a new ModelData object is created with the updated lamda value...
        model.copy(lamda = newLamda)
      else
      // ...otherwise, the original ModelData object is kept.
        model
    }
  }


  //update the inference function's arrival probability to the newest.
  def updateAllModelParameters(window: Double): Unit = {
    models = models.map { model =>
      val newModelArrivalProbability = 1 - exp(-model.lamda * window)
      val newExpectedSavedLatency = newModelArrivalProbability * model.modelLoadingLatency
      model.copy(
        modelArrivalProbability = newModelArrivalProbability,
        expectedSavedLatency = newExpectedSavedLatency
      )
    }
  }

}




/**
 * A pool managing containers to run actions on.
 *
 * This pool fulfills the other half of the ContainerProxy contract. Only
 * one job (either Start or Run) is sent to a child-actor at any given
 * time. The pool then waits for a response of that container, indicating
 * the container is done with the job. Only then will the pool send another
 * request to that container.
 *
 * Upon actor creation, the pool will start to prewarm containers according
 * to the provided prewarmConfig, iff set. Those containers will **not** be
 * part of the poolsize calculation, which is capped by the poolSize parameter.
 * Prewarm containers are only used, if they have matching arguments
 * (kind, memory) and there is space in the pool.
 *
 * @param childFactory method to create new container proxy actor
 * @param feed actor to request more work from
 * @param prewarmConfig optional settings for container prewarming
 * @param poolConfig config for the ContainerPool
 */
class ContainerPool(childFactory: ActorRefFactory => ActorRef,
                    feed: ActorRef,
                    prewarmConfig: List[PrewarmingConfig] = List.empty,
                    poolConfig: ContainerPoolConfig)(implicit val logging: Logging, as: ActorSystem)
  extends Actor
    with ProcessRunner {
  import ContainerPool.memoryConsumptionOf

  implicit val ec = context.dispatcher

  var freePool = immutable.Map.empty[ActorRef, ContainerData]
  var busyPool = immutable.Map.empty[ActorRef, ContainerData]
  var prewarmedPool = immutable.Map.empty[ActorRef, PreWarmedData]
  var prewarmStartingPool = immutable.Map.empty[ActorRef, (String, ByteSize)]

  // If all memory slots are occupied and if there is currently no container to be removed, than the actions will be
  // buffered here to keep order of computation.
  // Otherwise actions with small memory-limits could block actions with large memory limits.
  var runBuffer = immutable.Queue.empty[Run]
  // Track the resent buffer head - so that we don't resend buffer head multiple times
  var resent: Option[Run] = None
  val logMessageInterval = 10.seconds
  //periodically emit metrics (don't need to do this for each message!)
  context.system.scheduler.scheduleAtFixedRate(30.seconds, 10.seconds, self, EmitMetrics)

  // Key is ColdStartKey, value is the number of cold Start in minute
  var coldStartCount = immutable.Map.empty[ColdStartKey, Int]

  val execCmd: Seq[String] = Seq("/usr/bin/docker")

  /*Used to provide "Run" information for the pre-warm container,
    making it a warmed container instead of a stem-cell container
   */
  var userPrewarmRun = immutable.Map.empty[ExecutableWhiskAction, Run]

  // Create a pool to store all containers available for bin-packing
  var sharedPool = immutable.Map.empty[ActorRef, ContainerData]

  // Create the pool required by pagurus (The paper <Help rather than recycle> to store the "zygote" container
  var zygotePool = immutable.Map.empty[ActorRef, ContainerData]

  //The Pre-load table: show the pre-loaded function on each container.
  val PreloadTable_New: mutable.Map[ContainerId, List[ModelData]] = mutable.Map.empty


  //create a modelTable to store all inference functions's ModelData
  val modelTable = new ModelTable()

  var model1 = ModelData(
    actionName = "ptest04",
    modelName = "ResNet18",
    modelNameNumber = 1,
    modelLoadingLatency = 210,
    lamda = 0.1,
    modelArrivalProbability = 0.5,
    dataKeyPath = "path/to/data1",
    expectedSavedLatency = 100,
    modelSize = 649
  )

  var model2 = ModelData(
    actionName = "ptest05",
    modelName = "ResNet50",
    modelNameNumber = 50,
    modelLoadingLatency = 486,
    lamda = 0.1,
    modelArrivalProbability = 0.3,
    dataKeyPath = "path/to/data2",
    expectedSavedLatency = 120,
    modelSize = 907
  )

  var model3 = ModelData(
    actionName = "ptest06",
    modelName = "ResNet152",
    modelNameNumber = 152,
    modelLoadingLatency = 1180,
    lamda = 0.1,
    modelArrivalProbability = 0.3,
    dataKeyPath = "path/to/data2",
    expectedSavedLatency = 120,
    modelSize = 1348
  )

  var model4 = ModelData(
    actionName = "ptest01",
    modelName = "AlexNet",
    modelNameNumber = 1,
    modelLoadingLatency = 671,
    lamda = 0.1,
    modelArrivalProbability = 0.5,
    dataKeyPath = "path/to/data1",
    expectedSavedLatency = 100,
    modelSize = 923
  )



  var model5 = ModelData(
    actionName = "ptest02",
    modelName = "VGG",
    modelNameNumber = 1,
    modelLoadingLatency = 2410,
    lamda = 0.1,
    modelArrivalProbability = 0.5,
    dataKeyPath = "path/to/data1",
    expectedSavedLatency = 100,
    modelSize = 1648
  )

  var model6 = ModelData(
    actionName = "ptest03",
    modelName = "Inception",
    modelNameNumber = 1,
    modelLoadingLatency = 2281,
    lamda = 0.1,
    modelArrivalProbability = 0.5,
    dataKeyPath = "path/to/data1",
    expectedSavedLatency = 100,
    modelSize = 957
  )

  var model7 = ModelData(
    actionName = "ptest07",
    modelName = "GoogleNet",
    modelNameNumber = 1,
    modelLoadingLatency = 1351,
    lamda = 0.1,
    modelArrivalProbability = 0.5,
    dataKeyPath = "path/to/data1",
    expectedSavedLatency = 100,
    modelSize = 1027
  )

  var model8 = ModelData(
    actionName = "ptest08",
    modelName = "Bert",
    modelNameNumber = 1,
    modelLoadingLatency = 2078,
    lamda = 0.1,
    modelArrivalProbability = 0.5,
    dataKeyPath = "path/to/data1",
    expectedSavedLatency = 100,
    modelSize = 1333
  )


  modelTable.addModel(model1)
  modelTable.addModel(model2)
  modelTable.addModel(model3)
  modelTable.addModel(model4)
  modelTable.addModel(model5)
  modelTable.addModel(model6)
  modelTable.addModel(model7)
  modelTable.addModel(model8)

  // Setup redis connection
  private val redisClient = new RedisClient(logging = logging)
  redisClient.init




  adjustPrewarmedContainer(true, false)

  // check periodically, adjust prewarmed container(delete if unused for some time and create some increment containers)
  // add some random amount to this schedule to avoid a herd of container removal + creation
  val interval = poolConfig.prewarmExpirationCheckInterval + poolConfig.prewarmExpirationCheckIntervalVariance
    .map(v =>
      Random
        .nextInt(v.toSeconds.toInt))
    .getOrElse(0)
    .seconds
  if (prewarmConfig.exists(!_.reactive.isEmpty)) {
    context.system.scheduler.scheduleAtFixedRate(
      poolConfig.prewarmExpirationCheckInitDelay,
      interval,
      self,
      AdjustPrewarmedContainer)
  }

  def logContainerStart(r: Run, containerState: String, activeActivations: Int, container: Option[Container]): Unit = {
    val namespaceName = r.msg.user.namespace.name.asString
    val actionName = r.action.name.name
    val actionNamespace = r.action.namespace.namespace
    val maxConcurrent = r.action.limits.concurrency.maxConcurrent
    val activationId = r.msg.activationId.toString

    r.msg.transid.mark(
      this,
      LoggingMarkers.INVOKER_CONTAINER_START(containerState, namespaceName, actionNamespace, actionName),
      s"containerStart containerState: $containerState container: $container activations: $activeActivations of max $maxConcurrent action: $actionName namespace: $namespaceName activationId: $activationId",
      akka.event.Logging.InfoLevel)
  }


  def receive: Receive = {
    // A job to run on a container
    //
    // Run messages are received either via the feed or from child containers which cannot process
    // their requests and send them back to the pool for rescheduling (this may happen if "docker" operations
    // fail for example, or a container has aged and was destroying itself when a new request was assigned)
    case r: Run =>
      // Check if the message is resent from the buffer. Only the first message on the buffer can be resent.
      val isResentFromBuffer = runBuffer.nonEmpty && runBuffer.dequeueOption.exists(_._1.msg == r.msg)

      // Only process request, if there are no other requests waiting for free slots, or if the current request is the
      // next request to process
      // It is guaranteed, that only the first message on the buffer is resent.
      if (runBuffer.isEmpty || isResentFromBuffer) {
        if (isResentFromBuffer) {
          //remove from resent tracking - it may get resent again, or get processed
          resent = None
        }
        val kind = r.action.exec.kind
        val memory = r.action.limits.memory.megabytes.MB

        var preWarmWindow = r.msg.preWarmParameter
        var keepAliveWindow = r.msg.keepAliveParameter
        var actionName = r.action.fullyQualifiedName(false)
        WindowMap.getWindow(r)

        // Update activation message for prewarming only once，provide "RUN" info for pre-warm container to make it be warmed one instaed of stem-cell one
        val action = r.action
        userPrewarmRun.get(action) match {
          case Some(j) =>
          case None => {
            userPrewarmRun = userPrewarmRun + (action -> r)
            logging.info(this, s"Update action ${action.toString} with prewarm job ${r.toString}")
          }
        }

        val createdContainer =
        // Schedule a job to a warm container
          ContainerPool
            .schedule(r.action, r.msg.user.namespace.name, freePool,modelTable, PreloadTable_New)
            .map(container => (container, container._2.initingState)) //warmed, warming, and warmingCold always know their state   initingState在ContainerProxy中可以找到，warming表示prewarm的容器
            .orElse(
              // There was no warm/warming/warmingCold container. Try to take a prewarm container or a cold container.
              // When take prewarm container, has no need to judge whether user memory is enough
              takePrewarmContainer(r.action)
                .map(container => (container, "prewarmed"))
                .orElse {
                  // Is there enough space to create a new container or do other containers have to be removed?
                  if (hasPoolSpaceFor(busyPool ++ freePool ++ prewarmedPool, prewarmStartingPool, memory)) {
                    val container = Some(createContainer(memory), "cold")
                    incrementColdStartCount(kind, memory)
                    container
                  } else None
                })
            .orElse(
              // Remove a container and create a new one for the given job
              ContainerPool
                // Only free up the amount, that is really needed to free up
                .remove(freePool, Math.min(r.action.limits.memory.megabytes, memoryConsumptionOf(freePool)).MB)
                .map(removeContainer)
                // If the list had at least one entry, enough containers were removed to start the new container. After
                // removing the containers, we are not interested anymore in the containers that have been removed.
                .headOption
                .map(_ =>
                  takePrewarmContainer(r.action)
                    .map(container => (container, "recreatedPrewarm"))
                    .getOrElse {
                      val container = (createContainer(memory), "recreated")
                      incrementColdStartCount(kind, memory)
                      container
                    }))

        createdContainer match {
          case Some(((actor, data), containerState)) =>
            //increment active count before storing in pool map
            val newData = data.nextRun(r)    // update Container state
            val container = newData.getContainer

            if (newData.activeActivationCount < 1) {
              logging.error(this, s"invalid activation count < 1 ${newData}")
            }

            //only move to busyPool if max reached
            if (!newData.hasCapacity()) {
              if (r.action.limits.concurrency.maxConcurrent > 1) {
                logging.info(
                  this,
                  s"container ${container} is now busy with ${newData.activeActivationCount} activations")
              }
              busyPool = busyPool + (actor -> newData)
              freePool = freePool - actor
            } else {
              //update freePool to track counts
              freePool = freePool + (actor -> newData)
            }

            //update busyPool to redis
            redisClient.storeBusyPoolSize(Instance.InvokerID,busyPool)
            logging.info(
              this,
              s"redis has stored busy pool size. InvokerID: ${Instance.InvokerID}.")


            // Remove the action that was just executed from the buffer and execute the next one in the queue.
            if (isResentFromBuffer) {
              // It is guaranteed that the currently executed messages is the head of the queue, if the message comes
              // from the buffer
              val (_, newBuffer) = runBuffer.dequeue
              runBuffer = newBuffer
              // Try to process the next item in buffer (or get another message from feed, if buffer is now empty)
              processBufferOrFeed()
            }
            actor ! r // forwards the run request to the container
            logContainerStart(r, containerState, newData.activeActivationCount, container)
          case None =>
            // this can also happen if createContainer fails to start a new container, or
            // if a job is rescheduled but the container it was allocated to has not yet destroyed itself
            // (and a new container would over commit the pool)
            val isErrorLogged = r.retryLogDeadline.map(_.isOverdue).getOrElse(true)
            val retryLogDeadline = if (isErrorLogged) {
              logging.warn(
                this,
                s"Rescheduling Run message, too many message in the pool, " +
                  s"freePoolSize: ${freePool.size} containers and ${memoryConsumptionOf(freePool)} MB, " +
                  s"busyPoolSize: ${busyPool.size} containers and ${memoryConsumptionOf(busyPool)} MB, " +
                  s"maxContainersMemory ${poolConfig.userMemory.toMB} MB, " +
                  s"userNamespace: ${r.msg.user.namespace.name}, action: ${r.action}, " +
                  s"needed memory: ${r.action.limits.memory.megabytes} MB, " +
                  s"waiting messages: ${runBuffer.size}")(r.msg.transid)
              MetricEmitter.emitCounterMetric(LoggingMarkers.CONTAINER_POOL_RESCHEDULED_ACTIVATION)
              Some(logMessageInterval.fromNow)
              Some(logMessageInterval.fromNow)
            } else {
              r.retryLogDeadline
            }
            if (!isResentFromBuffer) {
              // Add this request to the buffer, as it is not there yet.
              runBuffer = runBuffer.enqueue(Run(r.action, r.msg, retryLogDeadline))
            }
          //buffered items will be processed via processBufferOrFeed()
        }
      } else {
        // There are currently actions waiting to be executed before this action gets executed.
        // These waiting actions were not able to free up enough memory.
        runBuffer = runBuffer.enqueue(r)
      }


    // Container is free to take more work  (Just start a new idle container)
    case NeedWork(warmData: WarmedData) =>
      val oldData = freePool.get(sender()).getOrElse(busyPool(sender()))
      val newData =
        warmData.copy(lastUsed = oldData.lastUsed, activeActivationCount = oldData.activeActivationCount - 1)
      if (newData.activeActivationCount < 0) {
        logging.error(this, s"invalid activation count after warming < 1 ${newData}")
      }
      if (newData.hasCapacity()) {
        //remove from busy pool (may already not be there), put back into free pool (to update activation counts)
        freePool = freePool + (sender() -> newData)

        //If we receive the Run Job from Zygote, it will also send the NeedWork signal after RunComplete and
        // turn it into a Private Idle container. Therefore, we need to remove it from the sharedPool
        if (sharedPool.contains(sender())) {
          sharedPool = sharedPool - sender()
        }

        //Remove the container from PreLoadTable
        val containerA = warmData.getContainer.get.containerId
        if (PreloadTable_New.contains(containerA)) {
          PreloadTable_New -= containerA
        }

        //update redis
        logging.info(this, s"In Need_Work, the preload table is ${PreloadTable_New}")
        redisClient.storeActionNames(Instance.InvokerID,PreloadTable_New)



        if (busyPool.contains(sender())) {
          busyPool = busyPool - sender()
          if (newData.action.limits.concurrency.maxConcurrent > 1) {
            logging.info(
              this,
              s"concurrent container ${newData.container} is no longer busy with ${newData.activeActivationCount} activations")
          }
        }
      } else {
        busyPool = busyPool + (sender() -> newData)
        freePool = freePool - sender()
        logging.info(
          this,
          s"container ${newData.container} has no capacity!")
      }
      processBufferOrFeed()

      //update busyPool to redis
      redisClient.storeBusyPoolSize(Instance.InvokerID,busyPool)
      logging.info(
        this,
        s"redis has stored busy pool size. InvokerID: ${Instance.InvokerID}.")

      logging.info(
        this,
        s"Pool received the NeedWork Message. Container:${newData.container}")


      //I name all inference functions "ptestXX". So this logic is to deal with inference function.
      if (newData.action.name.toString.contains("ptest")) {
        //仅仅pre-load newData.action.name对应的model。
        val containerId = newData.getContainer.get.containerId
        val model = modelTable.findModelByActionName(newData.action.name.toString).get
        //Sent the pre-load signal to the container Proxy.
        SendPreLoadMessage(model, containerId)
        logging.info(
          this,
          s"Pool Load action: ${newData.action.name}. New Idle Container:${containerId}")
      }


    // Zygote Container is free to take more work
    case ContainerIdle(warmData: WarmedData) =>
      val oldData = freePool.get(sender()).getOrElse(busyPool(sender()))
      val newData =
        warmData.copy(lastUsed = oldData.lastUsed, activeActivationCount = oldData.activeActivationCount )
      if (newData.activeActivationCount < 0) {
        logging.error(this, s"invalid activation count after warming < 1 ${newData}")
      }
      if (newData.hasCapacity()) {
        //remove from busy pool (may already not be there), put back into free pool (to update activation counts)
        freePool = freePool + (sender() -> newData)
        if (newData.action.name.toString.contains("ptest")) {
          sharedPool = sharedPool + (sender() -> newData)
          //PreloadTable_New也添加一个新containerId
          //PreloadTable_New += (newData.getContainer.get.containerId -> List.empty[ModelData])
          newData.getContainer.foreach { container =>
            PreloadTable_New += (container.containerId -> List.empty[ModelData])
          }
        }

        if (busyPool.contains(sender())) {
          busyPool = busyPool - sender()
          if (newData.action.limits.concurrency.maxConcurrent > 1) {
            logging.info(
              this,
              s"concurrent container ${newData.container} is no longer busy with ${newData.activeActivationCount} activations")
          }
        }
      } else {
        busyPool = busyPool + (sender() -> newData)
        freePool = freePool - sender()
        logging.info(
          this,
          s"container ${newData.container} has no capacity!")
      }
      processBufferOrFeed()

      //update busyPool to redis
      redisClient.storeBusyPoolSize(Instance.InvokerID,busyPool)

      logging.info(
        this,
        s"Pool received the NeedWork Message. Container:${newData.container}")


      //仅针对custom image
      if (newData.action.name.toString.contains("ptest")) {
        val model = modelTable.findModelByActionName(newData.action.name.toString).get

        //As there's an idle zygote container, we have room to pre-load more functions
        val modelTableModels = modelTable.getModels
        val preloadedModels = PreloadTable_New.values.flatten.toSet
        val notInPreloadTable: List[ModelData] = modelTableModels.filterNot(preloadedModels.contains)

        notInPreloadTable.foreach { modelData =>
          Future {
            val delay = (0.1 + scala.util.Random.nextDouble() * 2.0).seconds
            Thread.sleep(delay.toMillis)

            logging.info(this, s"Assigining model: ${modelData.modelName}! (from NeedWork)")

            if (sharedPool.isEmpty) {
              logging.info(this, s"SharedPool is empty!! (from NeedWork)")
            }
            else {
              val containerA = BinPacking(sharedPool, modelData, PreloadTable_New)
              if (containerA != null) {
                logging.info(
                  this,
                  s"SharedPool is not empty!! (from NeedWork). Chosen Container: ${containerA}")
                val oldModelDataList: List[ModelData] = PreloadTable_New.getOrElse(containerA, List.empty[ModelData])
                val newModelDataList: List[ModelData] = oldModelDataList :+ modelData

                PreloadTable_New(containerA) = newModelDataList
                logging.info(this, s"updated Table: ${PreloadTable_New}. (from NeedWork)")
                SendPreLoadMessage(modelData, containerA)
                //update redis
                redisClient.storeActionNames(Instance.InvokerID,PreloadTable_New)
              }
              else {
                logging.info(
                  this,
                  s"SharedPool is not empty!! (from NeedWork). But no space left in containers.")
              }
            }
          }
        }

        logging.info(
          this,
          s" Started the pre-load process. ContainerID: ${newData.container.containerId}, data: ${newData}. ")

        sharedPool = sharedPool.filterKeys(key => freePool.contains(key))

        val window = 1
        modelTable.updateAllModelParameters(window)
      }


    case StartRunMessage(data: WarmedData, whiskAction: ExecutableWhiskAction, lamda: Double) =>
      //The case that the Container receives a Run Request (invocation)

      if (whiskAction.name.toString.contains("ptest")) {
        modelTable.updateModelLamda(whiskAction.name.toString, lamda)

        if (sharedPool.contains(sender())) {
          sharedPool = sharedPool - sender()
          sharedPool = sharedPool.filterKeys(key => freePool.contains(key))
        }

        val window = 1
        modelTable.updateAllModelParameters(window)

        //Re-load other functions in the container to other containers (as the container is going to execute the invoked function)
        val containerA = data.getContainer.get.containerId
        logging.info(this, s"Sender's containerId is: ${containerA}. (from StartRunMessage)")

        val modelOption = modelTable.findModelByActionName(whiskAction.name.toString)
        val modelList: List[ModelData] = PreloadTable_New.getOrElse(containerA, List.empty[ModelData])
        val filteredModelList: List[ModelData] = modelOption match {
          case Some(model) => modelList.filterNot(_ == model)
          case None => modelList
        }


        if (PreloadTable_New.contains(containerA)) {
          PreloadTable_New -= containerA
        }
        logging.info(this, s"PreLoadTable_New is: ${PreloadTable_New}. (from StartRunMessage)")

        //update redis
        redisClient.storeActionNames(Instance.InvokerID,PreloadTable_New)



        filteredModelList.foreach { modelData =>
          Future {
            val delay = (0.1 + scala.util.Random.nextDouble() * 2.0).seconds
            // 使用Future的sleep函数实现延迟
            Thread.sleep(delay.toMillis)

            if (sharedPool.isEmpty) {
              logging.info(this, s"SharedPool is empty!! (from StartRunMessage)")
            } else {
              val containerA = BinPacking(sharedPool, modelData, PreloadTable_New)
              logging.info(
                this,
                s"SharedPool is not empty!! (from StartRunMessage). Chosen Container: ${containerA}")

              val oldModelDataList: List[ModelData] = PreloadTable_New.getOrElse(containerA, List.empty[ModelData])
              val newModelDataList: List[ModelData] = oldModelDataList :+ modelData

              PreloadTable_New(containerA) = newModelDataList
              SendPreLoadMessage(modelData, containerA)
              //update redis
              redisClient.storeActionNames(Instance.InvokerID,PreloadTable_New)
            }
          }
        }

        logging.info(
          this,
          s" Receive a StartRunMessage. action: ${whiskAction.name}. ")
      }


    case PreLoadMessage(data: WarmedData) =>
      val preloadWindow = WindowMap.MAP.get(data.action.fullyQualifiedName(false).toString).get._3
      val offloadWindow = WindowMap.MAP.get(data.action.fullyQualifiedName(false).toString).get._4
      logging.info(
        this,
        s" Pool has received a PreLoad message. actionName: ${data.action.name}. modelwindow:'${(preloadWindow, offloadWindow)} ")

      if (data.action.name.toString.contains("ptest")) {
        val model = modelTable.findModelByActionName(data.action.name.toString).get


        //load the function after "pre-load window" minutes
        val delayRun1 = Executors.newSingleThreadScheduledExecutor()

        val process = new Runnable {
          override def run() = {
            logging.info(
              this,
              s"ActionName: '${data.action.name}, This model will be loaded after '${preloadWindow} minutes. ")

            if (sharedPool.isEmpty) {
              logging.info(
                this,
                s"SharedPool is empty")
            } else {
              logging.info(
                this, s"SharedPool is not empty, pre-load window is: ${preloadWindow}. offload window is: ${offloadWindow}.")

              //assignedModels_v1 = syncModelsAndPreload(assignedModels_v1,PreloadTable_New)
              val containerID = BinPacking(sharedPool, model, PreloadTable_New)
              SendPreLoadMessage(model, containerID)


              val oldModelDataList: List[ModelData] = PreloadTable_New.getOrElse(containerID, List.empty[ModelData])
              val newModelDataList: List[ModelData] = if (!oldModelDataList.exists(_.modelName == model.modelName)) {
                oldModelDataList :+ model
              } else {
                oldModelDataList
              }
              PreloadTable_New(containerID) = newModelDataList
              //update redis
              redisClient.storeActionNames(Instance.InvokerID,PreloadTable_New)

              logging.info(
                this,
                s" Started the pre-load process. ModelData: ${model} , ContainerID: ${containerID}, data: ${data}. ")
            }
          }
        }
        delayRun1.schedule(process, preloadWindow, TimeUnit.MINUTES) // 第二个参数为延时时间
      }


    case OffLoadSignal(data: WarmedData) =>
      logging.info(
        this,
        s" Pool has received a Offload message. actionName: ${data.action.name}. ")
      val offloadWindow = WindowMap.MAP.get(data.action.fullyQualifiedName(false).toString).get._4
      val keepAliveWindow = WindowMap.MAP.get(data.action.fullyQualifiedName(false).toString).get._2
      val offloadTime = offloadWindow - keepAliveWindow
      if (data.action.name.toString.contains("ptest") & offloadTime > 0) {

        var modelList: Option[List[ModelData]] = None

        if (freePool.contains(sender())) {
          val container_remove = freePool(sender()).getContainer.get.containerId
          if (PreloadTable_New.contains(container_remove)) {
            // If it does, remove containerA and its models
            modelList = Some(PreloadTable_New(container_remove)) //提取该container对应的modellist
            PreloadTable_New -= container_remove

            //update redis
            redisClient.storeActionNames(Instance.InvokerID,PreloadTable_New)
          }
        }
        // if container was in free pool, it may have been processing (but under capacity), so there is capacity to accept another job request
        freePool.get(sender()).foreach { f =>
          freePool = freePool - sender()
        }

        if (sharedPool.contains(sender())) {
          sharedPool = sharedPool - sender()
          sharedPool = sharedPool.filterKeys(key => freePool.contains(key))
        }

        modelList.foreach(_.foreach { modelData =>
          if (sharedPool.isEmpty) {
            logging.info(
              this,
              s"SharedPool is empty!! (from offloadSignal)")
          }
          else {
            val containerA = BinPacking(sharedPool, modelData, PreloadTable_New)
            logging.info(
              this,
              s"SharedPool is not empty!! (from offloadSignal). Chosen Container: ${containerA}")

            val oldModelDataList: List[ModelData] = PreloadTable_New.getOrElse(containerA, List.empty[ModelData])
            val newModelDataList: List[ModelData] = oldModelDataList :+ modelData

            PreloadTable_New(containerA) = newModelDataList
            SendPreLoadMessage(modelData, containerA)
            //update redis
            redisClient.storeActionNames(Instance.InvokerID,PreloadTable_New)

            //Offload the function after "off-load window" minutes
            val executor1 = Executors.newSingleThreadScheduledExecutor()
            executor1.schedule(new Runnable {
              override def run() = {
                if (PreloadTable_New.contains(containerA)) {
                  // If it exists, check if the associated list contains the given ModelData
                  if (PreloadTable_New(containerA).exists(_.modelName == modelData.modelName)) {
                    //Offload
                    SendOffLoadMessage(modelData, containerA)
                    logging.info(
                      this,
                      s" The ContainerData's value contains the given ModelData. Container: '${containerA}, model: '${modelData.modelName}")
                    //更新PreLoadTable_New:
                    val modelList: List[ModelData] = PreloadTable_New.getOrElse(containerA, List.empty[ModelData])
                    val updatedModelList: List[ModelData] = modelList.filterNot(_ == modelData)
                    PreloadTable_New.update(containerA, updatedModelList)

                    //update redis
                    redisClient.storeActionNames(Instance.InvokerID,PreloadTable_New)
                  } else {
                    logging.info(
                      this,
                      s"The ContainerData's value does not contain the given ModelData.")
                  }
                } else {
                  logging.info(
                    this,
                    s"The ContainerData does not exist in the PreloadTable.")
                }
              }
            }, offloadTime, TimeUnit.MINUTES)
            //delayRun2.schedule(process, offloadTime, TimeUnit.MINUTES) // 第二个参数为延时时间
          }
        })
      }

    // Container got removed
    case ContainerRemoved(replacePrewarm) =>
      if (freePool.contains(sender())) {
        val container_remove = freePool(sender()).getContainer.get.containerId
        if (PreloadTable_New.contains(container_remove)) {
          // If it does, remove containerA and its models
          PreloadTable_New -= container_remove
          //update redis
          redisClient.storeActionNames(Instance.InvokerID,PreloadTable_New)
        }
      }

      // if container was in free pool, it may have been processing (but under capacity),
      // so there is capacity to accept another job request
      freePool.get(sender()).foreach { f =>
        freePool = freePool - sender()
      }


      // if container was in free pool, it may have been processing (but under capacity),
      // so there is capacity to accept another job request
      freePool.get(sender()).foreach { f =>
        freePool = freePool - sender()
      }

      if(sharedPool.contains(sender())){
        sharedPool = sharedPool - sender()
        sharedPool = sharedPool.filterKeys(key => freePool.contains(key))

        val window = 1
        modelTable.updateAllModelParameters(window)
      }




      // container was busy (busy indicates at full capacity), so there is capacity to accept another job request
      busyPool.get(sender()).foreach { _ =>
        busyPool = busyPool - sender()
      }
      processBufferOrFeed()

      //update busyPool to redis
      redisClient.storeBusyPoolSize(Instance.InvokerID,busyPool)
      logging.info(
        this,
        s"redis has stored busy pool size. InvokerID: ${Instance.InvokerID}.")

      // in case this was a prewarm
      prewarmedPool.get(sender()).foreach { data =>
        prewarmedPool = prewarmedPool - sender()
      }

      // in case this was a starting prewarm
      prewarmStartingPool.get(sender()).foreach { _ =>
        logging.info(this, "failed starting prewarm, removed")
        prewarmStartingPool = prewarmStartingPool - sender()
      }

      //backfill prewarms on every ContainerRemoved(replacePrewarm = true), just in case
      if (replacePrewarm) {
        adjustPrewarmedContainer(false, false) //in case a prewarm is removed due to health failure or crash
      }

    // This message is received for one of these reasons:
    // 1. Container errored while resuming a warm container, could not process the job, and sent the job back
    // 2. The container aged, is destroying itself, and was assigned a job which it had to send back
    // 3. The container aged and is destroying itself
    // Update the free/busy lists but no message is sent to the feed since there is no change in capacity yet
    case RescheduleJob =>
      freePool = freePool - sender()
      busyPool = busyPool - sender()

      //update busyPool to redis
      redisClient.storeBusyPoolSize(Instance.InvokerID,busyPool)
      logging.info(
        this,
        s"redis has stored busy pool size. InvokerID: ${Instance.InvokerID}.")

    case EmitMetrics =>
      emitMetrics()

    case AdjustPrewarmedContainer =>
      adjustPrewarmedContainer(false, true)
  }

  /** Resend next item in the buffer, or trigger next item in the feed, if no items in the buffer. */
  def processBufferOrFeed() = {
    // If buffer has more items, and head has not already been resent, send next one, otherwise get next from feed.
    runBuffer.dequeueOption match {
      case Some((run, _)) => //run the first from buffer
        implicit val tid = run.msg.transid
        //avoid sending dupes
        if (resent.isEmpty) {
          logging.info(this, s"re-processing from buffer (${runBuffer.length} items in buffer)")
          resent = Some(run)
          self ! run
        } else {
          //do not resend the buffer head multiple times (may reach this point from multiple messages, before the buffer head is re-processed)
        }
      case None => //feed me!
        feed ! MessageFeed.Processed
    }
  }

  /** adjust prewarm containers up to the configured requirements for each kind/memory combination. */
  def adjustPrewarmedContainer(init: Boolean, scheduled: Boolean): Unit = {
    if (scheduled) {
      //on scheduled time, remove expired prewarms
      ContainerPool.removeExpired(poolConfig, prewarmConfig, prewarmedPool).foreach { p =>
        prewarmedPool = prewarmedPool - p
        p ! Remove
      }
      //on scheduled time, emit cold start counter metric with memory + kind
      coldStartCount foreach { coldStart =>
        val coldStartKey = coldStart._1
        MetricEmitter.emitCounterMetric(
          LoggingMarkers.CONTAINER_POOL_PREWARM_COLDSTART(coldStartKey.memory.toString, coldStartKey.kind))
      }
    }
    //fill in missing prewarms (replaces any deletes)
    ContainerPool
      .increasePrewarms(init, scheduled, coldStartCount, prewarmConfig, prewarmedPool, prewarmStartingPool)
      .foreach { c =>
        val config = c._1
        val currentCount = c._2._1
        val desiredCount = c._2._2
        if (currentCount < desiredCount) {
          (currentCount until desiredCount).foreach { _ =>
            prewarmContainer(config.exec, config.memoryLimit, config.reactive.map(_.ttl))
          }
        }
      }
    if (scheduled) {
      //   lastly, clear coldStartCounts each time scheduled event is processed to reset counts
      coldStartCount = immutable.Map.empty[ColdStartKey, Int]
    }
  }




  /** Creates a new container and updates state accordingly. */
  def createContainer(memoryLimit: ByteSize): (ActorRef, ContainerData) = {
    val ref = childFactory(context)
    val data = MemoryData(memoryLimit)
    freePool = freePool + (ref -> data)
    ref -> data
  }

  /** Creates a new prewarmed container */
  def prewarmContainer(exec: CodeExec[_], memoryLimit: ByteSize, ttl: Option[FiniteDuration]): Unit = {
    if (hasPoolSpaceFor(busyPool ++ freePool ++ prewarmedPool, prewarmStartingPool, memoryLimit)) {
      val newContainer = childFactory(context)
      prewarmStartingPool = prewarmStartingPool + (newContainer -> (exec.kind, memoryLimit))
      newContainer ! Start(exec, memoryLimit, ttl)
    } else {
      logging.warn(
        this,
        s"Cannot create prewarm container due to reach the invoker memory limit: ${poolConfig.userMemory.toMB}")
    }
  }

  /** Creates a new prewarmed container */
  def prewarmSharedContainer(action: ExecutableWhiskAction, r: Run): Unit = {
    val memoryLimit = action.limits.memory.megabytes.MB
    if (hasPoolSpaceFor(busyPool ++ freePool ++ prewarmedPool, prewarmStartingPool, memoryLimit)) {
      val actor = childFactory(context)
      val data = MemoryData(memoryLimit)
      val newData = data.nextRun(r)
      val container = newData.getContainer
      freePool = freePool + (actor -> newData)
      actor ! CreateWarmedContainer(action, r.msg) // forwards the prewarm request to the container
      logging.info(this, s"create (pre)warmed ${actor.toString} and add to free pool")
    } else {
      logging.warn(
        this,
        s"Cannot create prewarm container due to reach the invoker memory limit: ${poolConfig.userMemory.toMB}")
    }
  }





  /** Create a Map with Container as the key and a List of assigned ModelData objects as the value. */
  def extractAssignedModels(models: List[ModelData], containers: Array[ContainerData], assignment: Array[Array[Int]]): Map[ContainerData, List[ModelData]] = {
    var assignedModels = Map.empty[ContainerData, List[ModelData]]

    for (i <- assignment.indices) {
      for (j <- assignment(i).indices) {
        if (assignment(i)(j) == 1) {
          val model = models(i)
          val container = containers(j)
          if (assignedModels.contains(container)) {
            assignedModels = assignedModels.updated(container, model :: assignedModels(container))
          } else {
            assignedModels = assignedModels + (container -> List(model))
          }
        }
      }
    }
    logging.info(
      this,
      s" Has extract Assigned Models, result: ${assignedModels}. ")
    assignedModels
  }




  case class ModelWithContainer(model: ModelData, container: ContainerId)

  def BinPacking(
                        freePool: immutable.Map[ActorRef, ContainerData],
                        model: ModelData,
                        assigned: mutable.Map[ContainerId, List[ModelData]]
                      ): ContainerId = {

    val remainingCapacity = mutable.Map[ContainerId, Int](freePool.values.toList.map(container => container.getContainer.get.containerId -> 2047): _*)

    // Use a priority queue to keep track of the models with the highest expectedSavedLatency.
    val modelQueue = PriorityQueue[ModelWithContainer]()(Ordering.by(-_.model.expectedSavedLatency))

    for ((container, models) <- assigned) {
      val usedCapacity = models.map(_.modelSize).sum
      if (remainingCapacity.contains(container)) {
        remainingCapacity(container) -= usedCapacity
        models.foreach(model => modelQueue.enqueue(ModelWithContainer(model, container)))
      } else {
      }
    }

    var targetcontainer: ContainerId = null
    var found: Boolean = false


    for ((container, _) <- remainingCapacity.toSeq.sortBy(-_._2) if !found && !(assigned.get(container).exists(_.exists(_.modelName == model.modelName)))) {
      if (remainingCapacity(container) >= model.modelSize) {
        targetcontainer = container
        found = true
      }
    }


    if (!found) {
      var found1: Boolean = false
      while (!modelQueue.isEmpty && !found1 && modelQueue.head.model.expectedSavedLatency < model.expectedSavedLatency) {
        val removedModel = modelQueue.dequeue()
        remainingCapacity(removedModel.container) += removedModel.model.modelSize
        if (remainingCapacity(removedModel.container) >= model.modelSize) {
          targetcontainer = removedModel.container
          found = true
          // Remove the model from the assigned list
          assigned(removedModel.container) = assigned(removedModel.container).filterNot(_ == removedModel.model)
          SendOffLoadMessage(removedModel.model,removedModel.container)
          found1 = true
        }
      }
    }

    targetcontainer
  }


  /** Creates a new prewarmed container */
  def SendPreLoadMessage(model: ModelData, ContainerID: ContainerId): Unit = {
    val matchedActorRefOption: Option[(ActorRef, ContainerData)] = freePool.find {
      case (_, containerData) => containerData.getContainer.get.containerId == ContainerID
    }
    val ActionName = model.actionName


    userPrewarmRun.foreach { case (action, job) =>
      logging.info(this, s"deal with actionName: ${action.name}.")
      //刚创建的时候，还没有存放ResNet152
      if (action.name.toString.contains(ActionName)) {
        userPrewarmRun.get(action) match { //找到action对应的run job（仅用来创建pre-warm容器）
          case Some(job) => {
            matchedActorRefOption match {
              case Some((actorRef, _)) =>
                actorRef ! LoadModelSignal(action, job.msg)
                logging.info(this, s"Send Load Signal for action: ${action}.")
              case None =>
                logging.info(this, s"No matching ActorRef found.")
            }
          }
          case None =>
        }
      }
    }
  }


  def SendOffLoadMessage(model: ModelData, ContainerID: ContainerId): Unit = {
    val matchedActorRefOption: Option[(ActorRef, ContainerData)] = freePool.find {
      case (_, containerData) => containerData.getContainer.get.containerId == ContainerID
    }
    val ActionName = model.actionName

    userPrewarmRun.foreach { case (action, job) =>
      if (action.name.toString == ActionName) {
        userPrewarmRun.get(action) match { //找到action对应的run job（仅用来创建pre-warm容器）
          case Some(job) => {
            matchedActorRefOption match {
              case Some((actorRef, _)) =>
                actorRef ! OffLoadModelSignal(action, job.msg)
                logging.info(this, s"Send OffLoad Signal for action: ${action}.")
              case None =>
                logging.info(this, s"No matching ActorRef found.")
            }
          }
          case None =>
        }
      }
    }
  }



  /** this is only for cold start statistics of prewarm configs, e.g. not blackbox or other configs. */
  def incrementColdStartCount(kind: String, memoryLimit: ByteSize): Unit = {
    prewarmConfig
      .filter { config =>
        kind == config.exec.kind && memoryLimit == config.memoryLimit
      }
      .foreach { _ =>
        val coldStartKey = ColdStartKey(kind, memoryLimit)
        coldStartCount.get(coldStartKey) match {
          case Some(value) => coldStartCount = coldStartCount + (coldStartKey -> (value + 1))
          case None        => coldStartCount = coldStartCount + (coldStartKey -> 1)
        }
      }
  }

  /**
   * Takes a prewarm container out of the prewarmed pool
   * iff a container with a matching kind and memory is found.  只有找到了对应kind和memory的容器时，才将其取出
   *
   * @param action the action that holds the kind and the required memory.
   * @return the container iff found
   */
  def takePrewarmContainer(action: ExecutableWhiskAction): Option[(ActorRef, ContainerData)] = {
    val kind = action.exec.kind
    val memory = action.limits.memory.megabytes.MB
    val now = Deadline.now
    prewarmedPool.toSeq
      .sortBy(_._2.expires.getOrElse(now))
      .find {
        case (_, PreWarmedData(_, `kind`, `memory`, _, _)) => true
        case _                                             => false
      }
      .map {
        case (ref, data) =>    //如果在find中找到了pre-warm的容器
          // Move the container to the usual pool
          freePool = freePool + (ref -> data)
          prewarmedPool = prewarmedPool - ref
          // Create a new prewarm container
          // NOTE: prewarming ignores the action code in exec, but this is dangerous as the field is accessible to the
          // factory

          //get the appropriate ttl from prewarm configs
          val ttl =
            prewarmConfig.find(pc => pc.memoryLimit == memory && pc.exec.kind == kind).flatMap(_.reactive.map(_.ttl))
          prewarmContainer(action.exec, memory, ttl)
          (ref, data)
      }
  }

  /** Removes a container and updates state accordingly. */
  def removeContainer(toDelete: ActorRef) = {
    toDelete ! Remove
    freePool = freePool - toDelete
    busyPool = busyPool - toDelete
    //sharedPool = sharedPool - toDelete
  }

  /**
   * Calculate if there is enough free memory within a given pool.
   *
   * @param pool The pool, that has to be checked, if there is enough free memory.
   * @param memory The amount of memory to check.
   * @return true, if there is enough space for the given amount of memory.
   */
  def hasPoolSpaceFor[A](pool: Map[A, ContainerData],
                         prewarmStartingPool: Map[A, (String, ByteSize)],
                         memory: ByteSize): Boolean = {
    memoryConsumptionOf(pool) + prewarmStartingPool.map(_._2._2.toMB).sum + memory.toMB <= poolConfig.userMemory.toMB
  }

  /**
   * Log metrics about pool state (buffer size, buffer memory requirements, active number, active memory, prewarm number, prewarm memory)
   */
  private def emitMetrics() = {
    MetricEmitter.emitGaugeMetric(LoggingMarkers.CONTAINER_POOL_RUNBUFFER_COUNT, runBuffer.size)
    MetricEmitter.emitGaugeMetric(
      LoggingMarkers.CONTAINER_POOL_RUNBUFFER_SIZE,
      runBuffer.map(_.action.limits.memory.megabytes).sum)
    val containersInUse = freePool.filter(_._2.activeActivationCount > 0) ++ busyPool
    MetricEmitter.emitGaugeMetric(LoggingMarkers.CONTAINER_POOL_ACTIVE_COUNT, containersInUse.size)
    MetricEmitter.emitGaugeMetric(
      LoggingMarkers.CONTAINER_POOL_ACTIVE_SIZE,
      containersInUse.map(_._2.memoryLimit.toMB).sum)
    MetricEmitter.emitGaugeMetric(
      LoggingMarkers.CONTAINER_POOL_PREWARM_COUNT,
      prewarmedPool.size + prewarmStartingPool.size)
    MetricEmitter.emitGaugeMetric(
      LoggingMarkers.CONTAINER_POOL_PREWARM_SIZE,
      prewarmedPool.map(_._2.memoryLimit.toMB).sum + prewarmStartingPool.map(_._2._2.toMB).sum)
    val unused = freePool.filter(_._2.activeActivationCount == 0)
    val unusedMB = unused.map(_._2.memoryLimit.toMB).sum
    MetricEmitter.emitGaugeMetric(LoggingMarkers.CONTAINER_POOL_IDLES_COUNT, unused.size)
    MetricEmitter.emitGaugeMetric(LoggingMarkers.CONTAINER_POOL_IDLES_SIZE, unusedMB)
  }
}

object ContainerPool {

  /**
   * Calculate the memory of a given pool.
   *
   * @param pool The pool with the containers.
   * @return The memory consumption of all containers in the pool in Megabytes.
   */
  protected[containerpool] def memoryConsumptionOf[A](pool: Map[A, ContainerData]): Long = {
    pool.map(_._2.memoryLimit.toMB).sum
  }

  /**
   * Finds the best container for a given job to run on.
   *
   * Selects an arbitrary warm container from the passed pool of idle containers
   * that matches the action and the invocation namespace. The implementation uses
   * matching such that structural equality of action and the invocation namespace
   * is required.
   * Returns None iff no matching container is in the idle pool.
   * Does not consider pre-warmed containers.
   *
   * @param action the action to run
   * @param invocationNamespace the namespace, that wants to run the action
   * @param idles a map of idle containers, awaiting work
   * @return a container if one found
   */
  protected[containerpool] def schedule[A](action: ExecutableWhiskAction,
                                           invocationNamespace: EntityName,
                                           idles: Map[A, ContainerData],
                                           modelTable: ModelTable,
                                           preLoadTable: mutable.Map[ContainerId, List[ModelData]]): Option[(A, ContainerData)] = {

    idles.find {
      case (_, c@WarmedData(_, `invocationNamespace`, `action`, _, _, _)) if c.hasCapacity() => true
      case _ => false
    }.orElse {
      idles.find {
        case (_, c@WarmingData(_, `invocationNamespace`, `action`, _, _)) if c.hasCapacity() => true
        case _ => false
      }
    }.orElse {
      modelTable.findModelByActionName(action.name.toString).flatMap { modelData =>
        val potentialContainers = preLoadTable.collect {
          case (containerId, models) if models.exists(_.modelName == modelData.modelName) => (containerId, models.map(_.modelSize).sum)
        }

        if (potentialContainers.nonEmpty) {
          val selectedContainerId = potentialContainers.minBy(_._2)._1

          idles.collectFirst {
            case (actorRef, containerData) if containerData.getContainer.get.containerId == selectedContainerId => (actorRef, containerData)
          }
        } else None
      }
    }.orElse {
      idles.find {
        case (_, c@WarmingColdData(`invocationNamespace`, `action`, _, _)) if c.hasCapacity() => true
        case _ => false
      }
    }
  }


  /**
   * Finds the oldest previously used container to remove to make space for the job passed to run.
   * Depending on the space that has to be allocated, several containers might be removed.
   *
   * NOTE: This method is never called to remove an action that is in the pool already,
   * since this would be picked up earlier in the scheduler and the container reused.
   *
   * @param pool a map of all free containers in the pool
   * @param memory the amount of memory that has to be freed up
   * @return a list of containers to be removed iff found
   */
  @tailrec
  protected[containerpool] def remove[A](pool: Map[A, ContainerData],
                                         memory: ByteSize,
                                         toRemove: List[A] = List.empty): List[A] = {
    // Try to find a Free container that does NOT have any active activations AND is initialized with any OTHER action
    val freeContainers = pool.collect {
      // Only warm containers will be removed. Prewarmed containers will stay always.
      case (ref, w: WarmedData) if w.activeActivationCount == 0 =>
        ref -> w
    }

    if (memory > 0.B && freeContainers.nonEmpty && memoryConsumptionOf(freeContainers) >= memory.toMB) {
      // Remove the oldest container if:
      // - there is more memory required
      // - there are still containers that can be removed
      // - there are enough free containers that can be removed
      val (ref, data) = freeContainers.minBy(_._2.lastUsed)
      // Catch exception if remaining memory will be negative
      val remainingMemory = Try(memory - data.memoryLimit).getOrElse(0.B)
      remove(freeContainers - ref, remainingMemory, toRemove ++ List(ref))
    } else {
      // If this is the first call: All containers are in use currently, or there is more memory needed than
      // containers can be removed.
      // Or, if this is one of the recursions: Enough containers are found to get the memory, that is
      // necessary. -> Abort recursion
      toRemove
    }
  }

  /**
   * Find the expired actor in prewarmedPool   //需要设定一个expire值，目前是NULL
   *
   * @param poolConfig
   * @param prewarmConfig
   * @param prewarmedPool
   * @param logging
   * @return a list of expired actor
   */
  def removeExpired[A](poolConfig: ContainerPoolConfig,
                       prewarmConfig: List[PrewarmingConfig],
                       prewarmedPool: Map[A, PreWarmedData])(implicit logging: Logging): List[A] = {
    val now = Deadline.now
    val expireds = prewarmConfig
      .flatMap { config =>
        val kind = config.exec.kind
        val memory = config.memoryLimit
        config.reactive
          .map { c =>
            val expiredPrewarmedContainer = prewarmedPool.toSeq
              .filter { warmInfo =>
                warmInfo match {
                  case (_, p @ PreWarmedData(_, `kind`, `memory`, _, _)) if p.isExpired() => true   //在这里判断是否expire
                  case _                                                                  => false
                }
              }
              .sortBy(_._2.expires.getOrElse(now))

            if (expiredPrewarmedContainer.nonEmpty) {
              // emit expired container counter metric with memory + kind
              MetricEmitter.emitCounterMetric(LoggingMarkers.CONTAINER_POOL_PREWARM_EXPIRED(memory.toString, kind))
              logging.info(
                this,
                s"[kind: ${kind} memory: ${memory.toString}] ${expiredPrewarmedContainer.size} expired prewarmed containers")
            }
            expiredPrewarmedContainer.map(e => (e._1, e._2.expires.getOrElse(now)))
          }
          .getOrElse(List.empty)
      }
      .sortBy(_._2) //need to sort these so that if the results are limited, we take the oldest
      .map(_._1)
    if (expireds.nonEmpty) {
      logging.info(this, s"removing up to ${poolConfig.prewarmExpirationLimit} of ${expireds.size} expired containers")
      expireds.take(poolConfig.prewarmExpirationLimit).foreach { e =>
        prewarmedPool.get(e).map { d =>
          logging.info(this, s"removing expired prewarm of kind ${d.kind} with container ${d.container} ")
        }
      }
    }
    expireds.take(poolConfig.prewarmExpirationLimit)
  }

  /**
   * Find the increased number for the prewarmed kind
   *
   * @param init
   * @param scheduled
   * @param coldStartCount
   * @param prewarmConfig
   * @param prewarmedPool
   * @param prewarmStartingPool
   * @param logging
   * @return the current number and increased number for the kind in the Map
   */
  def increasePrewarms(init: Boolean,
                       scheduled: Boolean,
                       coldStartCount: Map[ColdStartKey, Int],
                       prewarmConfig: List[PrewarmingConfig],
                       prewarmedPool: Map[ActorRef, PreWarmedData],
                       prewarmStartingPool: Map[ActorRef, (String, ByteSize)])(
                        implicit logging: Logging): Map[PrewarmingConfig, (Int, Int)] = {
    prewarmConfig.map { config =>
      val kind = config.exec.kind
      val memory = config.memoryLimit

      val runningCount = prewarmedPool.count {
        // done starting (include expired, since they may not have been removed yet)
        case (_, p @ PreWarmedData(_, `kind`, `memory`, _, _)) => true
        // started but not finished starting (or expired)
        case _ => false
      }
      val startingCount = prewarmStartingPool.count(p => p._2._1 == kind && p._2._2 == memory)
      val currentCount = runningCount + startingCount

      // determine how many are needed
      val desiredCount: Int =
        if (init) config.initialCount
        else {
          if (scheduled) {
            // scheduled/reactive config backfill
            config.reactive
              .map(c => getReactiveCold(coldStartCount, c, kind, memory).getOrElse(c.minCount)) //reactive -> desired is either cold start driven, or minCount
              .getOrElse(config.initialCount) //not reactive -> desired is always initial count
          } else {
            // normal backfill after removal - make sure at least minCount or initialCount is started
            config.reactive.map(_.minCount).getOrElse(config.initialCount)
          }
        }

      if (currentCount < desiredCount) {
        logging.info(
          this,
          s"found ${currentCount} started and ${startingCount} starting; ${if (init) "initing" else "backfilling"} ${desiredCount - currentCount} pre-warms to desired count: ${desiredCount} for kind:${config.exec.kind} mem:${config.memoryLimit.toString}")(
          TransactionId.invokerWarmup)
      }
      (config, (currentCount, desiredCount))
    }.toMap
  }

  /**
   * Get the required prewarmed container number according to the cold start happened in previous minute
   *
   * @param coldStartCount
   * @param config
   * @param kind
   * @param memory
   * @return the required prewarmed container number
   */
  def getReactiveCold(coldStartCount: Map[ColdStartKey, Int],
                      config: ReactivePrewarmingConfig,
                      kind: String,
                      memory: ByteSize): Option[Int] = {
    coldStartCount.get(ColdStartKey(kind, memory)).map { value =>
      // Let's assume that threshold is `2`, increment is `1` in runtimes.json
      // if cold start number in previous minute is `2`, requireCount is `2/2 * 1 = 1`
      // if cold start number in previous minute is `4`, requireCount is `4/2 * 1 = 2`
      math.min(math.max(config.minCount, (value / config.threshold) * config.increment), config.maxCount)
    }
  }

  def props(factory: ActorRefFactory => ActorRef,
            poolConfig: ContainerPoolConfig,
            feed: ActorRef,
            prewarmConfig: List[PrewarmingConfig] = List.empty)(implicit logging: Logging, as: ActorSystem) =
    Props(new ContainerPool(factory, feed, prewarmConfig, poolConfig))
}

/** Contains settings needed to perform container prewarming. */
case class PrewarmingConfig(initialCount: Int,
                            exec: CodeExec[_],
                            memoryLimit: ByteSize,
                            reactive: Option[ReactivePrewarmingConfig] = None)
