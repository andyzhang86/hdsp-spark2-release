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

package org.apache.spark.sql.internal

import java.io.File

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.Path

import org.apache.spark.SparkContext
import org.apache.spark.annotation.{Experimental, InterfaceStability}
import org.apache.spark.sql._
import org.apache.spark.sql.catalyst.analysis.{Analyzer, FunctionRegistry}
import org.apache.spark.sql.catalyst.catalog._
import org.apache.spark.sql.catalyst.optimizer.Optimizer
import org.apache.spark.sql.catalyst.parser.ParserInterface
import org.apache.spark.sql.catalyst.plans.logical.LogicalPlan
import org.apache.spark.sql.execution._
import org.apache.spark.sql.streaming.StreamingQueryManager
import org.apache.spark.sql.util.{ExecutionListenerManager, QueryExecutionListener}

/**
 * A class that holds all session-specific state in a given [[SparkSession]].
 *
 * @param sharedState The state shared across sessions, e.g. global view manager, external catalog.
 * @param conf SQL-specific key-value configurations.
 * @param experimentalMethods Interface to add custom planning strategies and optimizers.
 * @param functionRegistry Internal catalog for managing functions registered by the user.
 * @param udfRegistration Interface exposed to the user for registering user-defined functions.
 * @param catalogBuilder a function to create an internal catalog for managing table and database
 *                       states.
 * @param sqlParser Parser that extracts expressions, plans, table identifiers etc. from SQL texts.
 * @param analyzerBuilder A function to create the logical query plan analyzer for resolving
 *                        unresolved attributes and relations.
 * @param optimizerBuilder a function to create the logical query plan optimizer.
 * @param planner Planner that converts optimized logical plans to physical plans.
 * @param streamingQueryManager Interface to start and stop streaming queries.
 * @param listenerManager Interface to register custom [[QueryExecutionListener]]s.
 * @param resourceLoaderBuilder a function to create a session shared resource loader to load JARs,
 *                              files, etc.
 * @param createQueryExecution Function used to create QueryExecution objects.
 * @param createClone Function used to create clones of the session state.
 */
private[sql] class SessionState(
    sharedState: SharedState,
    val conf: SQLConf,
    val experimentalMethods: ExperimentalMethods,
    val functionRegistry: FunctionRegistry,
    val udfRegistration: UDFRegistration,
    catalogBuilder: () => SessionCatalog,
    val sqlParser: ParserInterface,
    analyzerBuilder: () => Analyzer,
    optimizerBuilder: () => Optimizer,
    val planner: SparkPlanner,
    val streamingQueryManager: StreamingQueryManager,
    val listenerManager: ExecutionListenerManager,
    resourceLoaderBuilder: () => SessionResourceLoader,
    createQueryExecution: LogicalPlan => QueryExecution,
    createClone: (SparkSession, SessionState) => SessionState) {

  // The following fields are lazy to avoid creating the Hive client when creating SessionState.
  lazy val catalog: SessionCatalog = catalogBuilder()

  lazy val analyzer: Analyzer = analyzerBuilder()

  lazy val optimizer: Optimizer = optimizerBuilder()

  lazy val resourceLoader: SessionResourceLoader = resourceLoaderBuilder()

  def newHadoopConf(): Configuration = SessionState.newHadoopConf(
    sharedState.sparkContext.hadoopConfiguration,
    conf)

  def newHadoopConfWithOptions(options: Map[String, String]): Configuration = {
    val hadoopConf = newHadoopConf()
    options.foreach { case (k, v) =>
      if ((v ne null) && k != "path" && k != "paths") {
        hadoopConf.set(k, v)
      }
    }
    hadoopConf
  }

  /**
   * Get an identical copy of the `SessionState` and associate it with the given `SparkSession`
   */
  def clone(newSparkSession: SparkSession): SessionState = createClone(newSparkSession, this)

  // ------------------------------------------------------
  //  Helper methods, partially leftover from pre-2.0 days
  // ------------------------------------------------------

  def executePlan(plan: LogicalPlan): QueryExecution = createQueryExecution(plan)

  def refreshTable(tableName: String): Unit = {
    catalog.refreshTable(sqlParser.parseTableIdentifier(tableName))
  }

  // ------------------------------------------------------
  //  Helper methods for HDP Ranger with LLAP
  // ------------------------------------------------------

  private var userName = System.getProperty("user.name")

  def setUser(user: String): Unit = {
    userName = user
  }

  def getUser(): String = {
    userName
  }

  /**
   * Return connection URL (with replaced proxy user name if exists).
   */
  def getConnectionUrl(sparkSession: SparkSession): String = {
    var userString = getUser()
    if (userString == null) {
      userString = ""
    }
    val urlString = getConnectionUrlFromConf(sparkSession)
    urlString.replace("${user}", userString)
  }

  import org.apache.spark.sql.internal.SQLConf._
  /**
   * For the given HiveServer2 JDBC URLs, attach the postfix strings if needed.
   *
   * For kerberized clusters,
   *
   * 1. YARN cluster mode: ";auth=delegationToken"
   * 2. YARN client mode: ";principal=hive/_HOST@EXAMPLE.COM"
   *
   * Non-kerberied clusters,
   * 3. Use the given URLs.
   */
  private def getConnectionUrlFromConf(sparkSession: SparkSession): String = {
    if (!sparkSession.conf.contains(HIVESERVER2_JDBC_URL.key)) {
      throw new Exception("Spark conf does not contain config " + HIVESERVER2_JDBC_URL.key)
    }

    if (sparkSession.conf.get(HIVESERVER2_CREDENTIAL_ENABLED, false) &&
      org.apache.spark.util.Utils.isRunningInYarnContainer(sparkSession.sparkContext.conf)) {
      // 1. YARN Cluster mode for kerberized clusters
      s"${sparkSession.conf.get(HIVESERVER2_JDBC_URL.key)};auth=delegationToken"
    } else if (sparkSession.sparkContext.conf.contains(HIVESERVER2_JDBC_URL_PRINCIPAL.key) &&
      !org.apache.spark.util.Utils.isRunningInYarnContainer(sparkSession.sparkContext.conf)) {
      // 2. YARN Client mode for kerberized clusters
      s"${sparkSession.conf.get(HIVESERVER2_JDBC_URL.key)};" +
        s"principal=${sparkSession.conf.get(HIVESERVER2_JDBC_URL_PRINCIPAL.key)}"
    } else {
      // 3. For non-kerberized cluster
      sparkSession.conf.get(HIVESERVER2_JDBC_URL.key)
    }
  }
}

private[sql] object SessionState {
  def newHadoopConf(hadoopConf: Configuration, sqlConf: SQLConf): Configuration = {
    val newHadoopConf = new Configuration(hadoopConf)
    sqlConf.getAllConfs.foreach { case (k, v) => if (v ne null) newHadoopConf.set(k, v) }
    newHadoopConf
  }
}

/**
 * Concrete implementation of a [[BaseSessionStateBuilder]].
 */
@Experimental
@InterfaceStability.Unstable
class SessionStateBuilder(
    session: SparkSession,
    parentState: Option[SessionState] = None)
  extends BaseSessionStateBuilder(session, parentState) {
  override protected def newBuilder: NewBuilder = new SessionStateBuilder(_, _)
}

/**
 * Session shared [[FunctionResourceLoader]].
 */
@InterfaceStability.Unstable
class SessionResourceLoader(session: SparkSession) extends FunctionResourceLoader {
  override def loadResource(resource: FunctionResource): Unit = {
    resource.resourceType match {
      case JarResource => addJar(resource.uri)
      case FileResource => session.sparkContext.addFile(resource.uri)
      case ArchiveResource =>
        throw new AnalysisException(
          "Archive is not allowed to be loaded. If YARN mode is used, " +
            "please use --archives options while calling spark-submit.")
    }
  }

  /**
   * Add a jar path to [[SparkContext]] and the classloader.
   *
   * Note: this method seems not access any session state, but a Hive based `SessionState` needs
   * to add the jar to its hive client for the current session. Hence, it still needs to be in
   * [[SessionState]].
   */
  def addJar(path: String): Unit = {
    session.sparkContext.addJar(path)
    val uri = new Path(path).toUri
    val jarURL = if (uri.getScheme == null) {
      // `path` is a local file path without a URL scheme
      new File(path).toURI.toURL
    } else {
      // `path` is a URL with a scheme
      uri.toURL
    }
    session.sharedState.jarClassLoader.addURL(jarURL)
    Thread.currentThread().setContextClassLoader(session.sharedState.jarClassLoader)
  }
}
