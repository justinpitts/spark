/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.spark.deploy.k8s.integrationtest

import java.io.File
import java.nio.file.{Path, Paths}
import java.util.UUID
import java.util.regex.Pattern

import com.google.common.io.PatternFilenameFilter
import io.fabric8.kubernetes.api.model.Pod
import org.scalatest.{BeforeAndAfter, BeforeAndAfterAll, Tag}
import org.scalatest.concurrent.{Eventually, PatienceConfiguration}
import org.scalatest.time.{Minutes, Seconds, Span}
import scala.collection.JavaConverters._

import org.apache.spark.SparkFunSuite
import org.apache.spark.deploy.k8s.integrationtest.backend.{IntegrationTestBackend, IntegrationTestBackendFactory}
import org.apache.spark.deploy.k8s.integrationtest.config._
import org.apache.spark.launcher.SparkLauncher

private[spark] class KubernetesSuite extends SparkFunSuite
  with BeforeAndAfterAll with BeforeAndAfter {

  import KubernetesSuite._

  private var testBackend: IntegrationTestBackend = _
  private var sparkHomeDir: Path = _
  private var kubernetesTestComponents: KubernetesTestComponents = _
  private var sparkAppConf: SparkAppConf = _
  private var image: String = _
  private var pyImage: String = _
  private var containerLocalSparkDistroExamplesJar: String = _
  private var appLocator: String = _
  private var driverPodName: String = _
  private val k8sTestTag = Tag("k8s")

  override def beforeAll(): Unit = {
    // The scalatest-maven-plugin gives system properties that are referenced but not set null
    // values. We need to remove the null-value properties before initializing the test backend.
    val nullValueProperties = System.getProperties.asScala
      .filter(entry => entry._2.equals("null"))
      .map(entry => entry._1.toString)
    nullValueProperties.foreach { key =>
      System.clearProperty(key)
    }

    val sparkDirProp = System.getProperty("spark.kubernetes.test.unpackSparkDir")
    require(sparkDirProp != null, "Spark home directory must be provided in system properties.")
    sparkHomeDir = Paths.get(sparkDirProp)
    require(sparkHomeDir.toFile.isDirectory,
      s"No directory found for spark home specified at $sparkHomeDir.")
    val imageTag = getTestImageTag
    val imageRepo = getTestImageRepo
    image = s"$imageRepo/spark:$imageTag"
    pyImage = s"$imageRepo/spark-py:$imageTag"

    val sparkDistroExamplesJarFile: File = sparkHomeDir.resolve(Paths.get("examples", "jars"))
      .toFile
      .listFiles(new PatternFilenameFilter(Pattern.compile("^spark-examples_.*\\.jar$")))(0)
    containerLocalSparkDistroExamplesJar = s"local:///opt/spark/examples/jars/" +
      s"${sparkDistroExamplesJarFile.getName}"
    testBackend = IntegrationTestBackendFactory.getTestBackend
    testBackend.initialize()
    kubernetesTestComponents = new KubernetesTestComponents(testBackend.getKubernetesClient)
  }

  override def afterAll(): Unit = {
    testBackend.cleanUp()
  }

  before {
    appLocator = UUID.randomUUID().toString.replaceAll("-", "")
    driverPodName = "spark-test-app-" + UUID.randomUUID().toString.replaceAll("-", "")
    sparkAppConf = kubernetesTestComponents.newSparkAppConf()
      .set("spark.kubernetes.container.image", image)
      .set("spark.kubernetes.driver.pod.name", driverPodName)
      .set("spark.kubernetes.driver.label.spark-app-locator", appLocator)
      .set("spark.kubernetes.executor.label.spark-app-locator", appLocator)
    if (!kubernetesTestComponents.hasUserSpecifiedNamespace) {
      kubernetesTestComponents.createNamespace()
    }
  }

  after {
    if (!kubernetesTestComponents.hasUserSpecifiedNamespace) {
      kubernetesTestComponents.deleteNamespace()
    }
    deleteDriverPod()
  }

  test("Run SparkPi with no resources", k8sTestTag) {
    runSparkPiAndVerifyCompletion()
  }

  test("Run SparkPi with a very long application name.", k8sTestTag) {
    sparkAppConf.set("spark.app.name", "long" * 40)
    runSparkPiAndVerifyCompletion()
  }

  test("Use SparkLauncher.NO_RESOURCE", k8sTestTag) {
    sparkAppConf.setJars(Seq(containerLocalSparkDistroExamplesJar))
    runSparkPiAndVerifyCompletion(
      appResource = SparkLauncher.NO_RESOURCE)
  }

  test("Run SparkPi with a master URL without a scheme.", k8sTestTag) {
    val url = kubernetesTestComponents.kubernetesClient.getMasterUrl
    val k8sMasterUrl = if (url.getPort < 0) {
      s"k8s://${url.getHost}"
    } else {
      s"k8s://${url.getHost}:${url.getPort}"
    }
    sparkAppConf.set("spark.master", k8sMasterUrl)
    runSparkPiAndVerifyCompletion()
  }

  test("Run SparkPi with an argument.", k8sTestTag) {
    runSparkPiAndVerifyCompletion(appArgs = Array("5"))
  }

  test("Run SparkPi with custom labels, annotations, and environment variables.", k8sTestTag) {
    sparkAppConf
      .set("spark.kubernetes.driver.label.label1", "label1-value")
      .set("spark.kubernetes.driver.label.label2", "label2-value")
      .set("spark.kubernetes.driver.annotation.annotation1", "annotation1-value")
      .set("spark.kubernetes.driver.annotation.annotation2", "annotation2-value")
      .set("spark.kubernetes.driverEnv.ENV1", "VALUE1")
      .set("spark.kubernetes.driverEnv.ENV2", "VALUE2")
      .set("spark.kubernetes.executor.label.label1", "label1-value")
      .set("spark.kubernetes.executor.label.label2", "label2-value")
      .set("spark.kubernetes.executor.annotation.annotation1", "annotation1-value")
      .set("spark.kubernetes.executor.annotation.annotation2", "annotation2-value")
      .set("spark.executorEnv.ENV1", "VALUE1")
      .set("spark.executorEnv.ENV2", "VALUE2")

    runSparkPiAndVerifyCompletion(
      driverPodChecker = (driverPod: Pod) => {
        doBasicDriverPodCheck(driverPod)
        checkCustomSettings(driverPod)
      },
      executorPodChecker = (executorPod: Pod) => {
        doBasicExecutorPodCheck(executorPod)
        checkCustomSettings(executorPod)
      })
  }

  test("Run extraJVMOptions check on driver", k8sTestTag) {
    sparkAppConf
      .set("spark.driver.extraJavaOptions", "-Dspark.test.foo=spark.test.bar")
    runSparkJVMCheckAndVerifyCompletion(
      expectedJVMValue = Seq("(spark.test.foo,spark.test.bar)"))
  }

  test("Run SparkRemoteFileTest using a remote data file", k8sTestTag) {
    sparkAppConf
      .set("spark.files", REMOTE_PAGE_RANK_DATA_FILE)
    runSparkRemoteCheckAndVerifyCompletion(
      appArgs = Array(REMOTE_PAGE_RANK_FILE_NAME))
  }

  test("Run PySpark on simple pi.py example", k8sTestTag) {
    sparkAppConf
      .set("spark.kubernetes.container.image", s"${getTestImageRepo}/spark-py:${getTestImageTag}")
    runSparkApplicationAndVerifyCompletion(
      appResource = PYSPARK_PI,
      mainClass = "",
      expectedLogOnCompletion = Seq("Pi is roughly 3"),
      appArgs = Array("5"),
      driverPodChecker = doBasicDriverPyPodCheck,
      executorPodChecker = doBasicExecutorPyPodCheck,
      appLocator = appLocator,
      isJVM = false)
  }

  test("Run PySpark with Python2 to test a pyfiles example", k8sTestTag) {
    sparkAppConf
      .set("spark.kubernetes.container.image", s"${getTestImageRepo}/spark-py:${getTestImageTag}")
      .set("spark.kubernetes.pyspark.pythonversion", "2")
    runSparkApplicationAndVerifyCompletion(
      appResource = PYSPARK_FILES,
      mainClass = "",
      expectedLogOnCompletion = Seq(
        "Python runtime version check is: True",
        "Python environment version check is: True"),
      appArgs = Array("python"),
      driverPodChecker = doBasicDriverPyPodCheck,
      executorPodChecker = doBasicExecutorPyPodCheck,
      appLocator = appLocator,
      isJVM = false,
      pyFiles = Some(PYSPARK_CONTAINER_TESTS))
  }

  test("Run PySpark with Python3 to test a pyfiles example", k8sTestTag) {
    sparkAppConf
      .set("spark.kubernetes.container.image", s"${getTestImageRepo}/spark-py:${getTestImageTag}")
      .set("spark.kubernetes.pyspark.pythonversion", "3")
    runSparkApplicationAndVerifyCompletion(
      appResource = PYSPARK_FILES,
      mainClass = "",
      expectedLogOnCompletion = Seq(
        "Python runtime version check is: True",
        "Python environment version check is: True"),
      appArgs = Array("python3"),
      driverPodChecker = doBasicDriverPyPodCheck,
      executorPodChecker = doBasicExecutorPyPodCheck,
      appLocator = appLocator,
      isJVM = false,
      pyFiles = Some(PYSPARK_CONTAINER_TESTS))
  }

  private def runSparkPiAndVerifyCompletion(
      appResource: String = containerLocalSparkDistroExamplesJar,
      driverPodChecker: Pod => Unit = doBasicDriverPodCheck,
      executorPodChecker: Pod => Unit = doBasicExecutorPodCheck,
      appArgs: Array[String] = Array.empty[String],
      appLocator: String = appLocator,
      isJVM: Boolean = true ): Unit = {
    runSparkApplicationAndVerifyCompletion(
      appResource,
      SPARK_PI_MAIN_CLASS,
      Seq("Pi is roughly 3"),
      appArgs,
      driverPodChecker,
      executorPodChecker,
      appLocator,
      isJVM)
  }

  private def runSparkRemoteCheckAndVerifyCompletion(
      appResource: String = containerLocalSparkDistroExamplesJar,
      driverPodChecker: Pod => Unit = doBasicDriverPodCheck,
      executorPodChecker: Pod => Unit = doBasicExecutorPodCheck,
      appArgs: Array[String],
      appLocator: String = appLocator): Unit = {
    runSparkApplicationAndVerifyCompletion(
      appResource,
      SPARK_REMOTE_MAIN_CLASS,
      Seq(s"Mounting of ${appArgs.head} was true"),
      appArgs,
      driverPodChecker,
      executorPodChecker,
      appLocator,
      true)
  }

  private def runSparkJVMCheckAndVerifyCompletion(
      appResource: String = containerLocalSparkDistroExamplesJar,
      mainClass: String = SPARK_DRIVER_MAIN_CLASS,
      driverPodChecker: Pod => Unit = doBasicDriverPodCheck,
      appArgs: Array[String] = Array("5"),
      expectedJVMValue: Seq[String]): Unit = {
    val appArguments = SparkAppArguments(
      mainAppResource = appResource,
      mainClass = mainClass,
      appArgs = appArgs)
    SparkAppLauncher.launch(
      appArguments,
      sparkAppConf,
      TIMEOUT.value.toSeconds.toInt,
      sparkHomeDir,
      true)

    val driverPod = kubernetesTestComponents.kubernetesClient
      .pods()
      .withLabel("spark-app-locator", appLocator)
      .withLabel("spark-role", "driver")
      .list()
      .getItems
      .get(0)
    doBasicDriverPodCheck(driverPod)

    Eventually.eventually(TIMEOUT, INTERVAL) {
      expectedJVMValue.foreach { e =>
        assert(kubernetesTestComponents.kubernetesClient
          .pods()
          .withName(driverPod.getMetadata.getName)
          .getLog
          .contains(e), "The application did not complete.")
      }
    }
  }

  private def runSparkApplicationAndVerifyCompletion(
      appResource: String,
      mainClass: String,
      expectedLogOnCompletion: Seq[String],
      appArgs: Array[String],
      driverPodChecker: Pod => Unit,
      executorPodChecker: Pod => Unit,
      appLocator: String,
      isJVM: Boolean,
      pyFiles: Option[String] = None): Unit = {
    val appArguments = SparkAppArguments(
      mainAppResource = appResource,
      mainClass = mainClass,
      appArgs = appArgs)
    SparkAppLauncher.launch(
      appArguments,
      sparkAppConf,
      TIMEOUT.value.toSeconds.toInt,
      sparkHomeDir,
      isJVM,
      pyFiles)

    val driverPod = kubernetesTestComponents.kubernetesClient
      .pods()
      .withLabel("spark-app-locator", appLocator)
      .withLabel("spark-role", "driver")
      .list()
      .getItems
      .get(0)
    driverPodChecker(driverPod)

    val executorPods = kubernetesTestComponents.kubernetesClient
      .pods()
      .withLabel("spark-app-locator", appLocator)
      .withLabel("spark-role", "executor")
      .list()
      .getItems
    executorPods.asScala.foreach { pod =>
      executorPodChecker(pod)
    }

    Eventually.eventually(TIMEOUT, INTERVAL) {
      expectedLogOnCompletion.foreach { e =>
        assert(kubernetesTestComponents.kubernetesClient
          .pods()
          .withName(driverPod.getMetadata.getName)
          .getLog
          .contains(e), "The application did not complete.")
      }
    }
  }

  private def doBasicDriverPodCheck(driverPod: Pod): Unit = {
    assert(driverPod.getMetadata.getName === driverPodName)
    assert(driverPod.getSpec.getContainers.get(0).getImage === image)
    assert(driverPod.getSpec.getContainers.get(0).getName === "spark-kubernetes-driver")
  }

  private def doBasicDriverPyPodCheck(driverPod: Pod): Unit = {
    assert(driverPod.getMetadata.getName === driverPodName)
    assert(driverPod.getSpec.getContainers.get(0).getImage === pyImage)
    assert(driverPod.getSpec.getContainers.get(0).getName === "spark-kubernetes-driver")
  }

  private def doBasicExecutorPodCheck(executorPod: Pod): Unit = {
    assert(executorPod.getSpec.getContainers.get(0).getImage === image)
    assert(executorPod.getSpec.getContainers.get(0).getName === "executor")
  }

  private def doBasicExecutorPyPodCheck(executorPod: Pod): Unit = {
    assert(executorPod.getSpec.getContainers.get(0).getImage === pyImage)
    assert(executorPod.getSpec.getContainers.get(0).getName === "executor")
  }

  private def checkCustomSettings(pod: Pod): Unit = {
    assert(pod.getMetadata.getLabels.get("label1") === "label1-value")
    assert(pod.getMetadata.getLabels.get("label2") === "label2-value")
    assert(pod.getMetadata.getAnnotations.get("annotation1") === "annotation1-value")
    assert(pod.getMetadata.getAnnotations.get("annotation2") === "annotation2-value")

    val container = pod.getSpec.getContainers.get(0)
    val envVars = container
      .getEnv
      .asScala
      .map { env =>
        (env.getName, env.getValue)
      }
      .toMap
    assert(envVars("ENV1") === "VALUE1")
    assert(envVars("ENV2") === "VALUE2")
  }

  private def deleteDriverPod(): Unit = {
    kubernetesTestComponents.kubernetesClient.pods().withName(driverPodName).delete()
    Eventually.eventually(TIMEOUT, INTERVAL) {
      assert(kubernetesTestComponents.kubernetesClient
        .pods()
        .withName(driverPodName)
        .get() == null)
    }
  }
}

private[spark] object KubernetesSuite {

  val TIMEOUT = PatienceConfiguration.Timeout(Span(2, Minutes))
  val INTERVAL = PatienceConfiguration.Interval(Span(2, Seconds))
  val SPARK_PI_MAIN_CLASS: String = "org.apache.spark.examples.SparkPi"
  val SPARK_REMOTE_MAIN_CLASS: String = "org.apache.spark.examples.SparkRemoteFileTest"
  val SPARK_DRIVER_MAIN_CLASS: String = "org.apache.spark.examples.DriverSubmissionTest"
  val SPARK_PAGE_RANK_MAIN_CLASS: String = "org.apache.spark.examples.SparkPageRank"
  val CONTAINER_LOCAL_PYSPARK: String = "local:///opt/spark/examples/src/main/python/"
  val PYSPARK_PI: String = CONTAINER_LOCAL_PYSPARK + "pi.py"
  val PYSPARK_FILES: String = CONTAINER_LOCAL_PYSPARK + "pyfiles.py"
  val PYSPARK_CONTAINER_TESTS: String = CONTAINER_LOCAL_PYSPARK + "py_container_checks.py"

  val TEST_SECRET_NAME_PREFIX = "test-secret-"
  val TEST_SECRET_KEY = "test-key"
  val TEST_SECRET_VALUE = "test-data"
  val TEST_SECRET_MOUNT_PATH = "/etc/secrets"

  val REMOTE_PAGE_RANK_DATA_FILE =
    "https://storage.googleapis.com/spark-k8s-integration-tests/files/pagerank_data.txt"
  val REMOTE_PAGE_RANK_FILE_NAME = "pagerank_data.txt"

  case object ShuffleNotReadyException extends Exception
}
