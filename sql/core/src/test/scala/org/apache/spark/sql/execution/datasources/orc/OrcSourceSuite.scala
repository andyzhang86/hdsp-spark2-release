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

package org.apache.spark.sql.execution.datasources.orc

import java.io.File

import org.scalatest.BeforeAndAfterAll

import org.apache.spark.sql.{QueryTest, Row}
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.sources._
import org.apache.spark.sql.test.SharedSQLContext
import org.apache.spark.sql.types._
import org.apache.spark.util.Utils

case class OrcData(intField: Int, stringField: String)

/**
 * This test suite is a port of org.apache.spark.sql.hive.orc.OrcSuite.
 */
abstract class OrcSuite extends QueryTest with SharedSQLContext with BeforeAndAfterAll {
  import testImplicits._

  var orcTableDir: File = null
  var orcTableAsDir: File = null

  override def beforeAll(): Unit = {
    super.beforeAll()

    orcTableAsDir = Utils.createTempDir("orctests", "sparksql")

    // Hack: to prepare orc data files using hive external tables
    orcTableDir = Utils.createTempDir("orctests", "sparksql")

    spark.sparkContext
      .makeRDD(1 to 10)
      .map(i => OrcData(i, s"part-$i"))
      .toDF()
      .createOrReplaceTempView(s"orc_temp_table")

    spark.sessionState.conf.setConf(SQLConf.ORC_ENABLED, true)

    sql(
      s"""CREATE TABLE normal_orc(
         |  intField INT,
         |  stringField STRING
         |)
         |USING ORC
         |LOCATION '${orcTableAsDir.toURI}'
     """.stripMargin)

    sql(
      s"""INSERT INTO TABLE normal_orc
         |SELECT intField, stringField FROM orc_temp_table
     """.stripMargin)
  }

  override def afterAll(): Unit = {
    spark.sessionState.conf.unsetConf(SQLConf.ORC_ENABLED)
    super.afterAll()
  }

  test("create temporary orc table") {
    checkAnswer(sql("SELECT COUNT(*) FROM normal_orc_source"), Row(10))

    checkAnswer(
      sql("SELECT * FROM normal_orc_source"),
      (1 to 10).map(i => Row(i, s"part-$i")))

    checkAnswer(
      sql("SELECT * FROM normal_orc_source where intField > 5"),
      (6 to 10).map(i => Row(i, s"part-$i")))

    checkAnswer(
      sql("SELECT COUNT(intField), stringField FROM normal_orc_source GROUP BY stringField"),
      (1 to 10).map(i => Row(1, s"part-$i")))
  }

  test("create temporary orc table as") {
    checkAnswer(sql("SELECT COUNT(*) FROM normal_orc_as_source"), Row(10))

    checkAnswer(
      sql("SELECT * FROM normal_orc_source"),
      (1 to 10).map(i => Row(i, s"part-$i")))

    checkAnswer(
      sql("SELECT * FROM normal_orc_source WHERE intField > 5"),
      (6 to 10).map(i => Row(i, s"part-$i")))

    checkAnswer(
      sql("SELECT COUNT(intField), stringField FROM normal_orc_source GROUP BY stringField"),
      (1 to 10).map(i => Row(1, s"part-$i")))
  }

  test("appending insert") {
    sql("INSERT INTO TABLE normal_orc_source SELECT * FROM orc_temp_table WHERE intField > 5")

    checkAnswer(
      sql("SELECT * FROM normal_orc_source"),
      (1 to 5).map(i => Row(i, s"part-$i")) ++ (6 to 10).flatMap { i =>
        Seq.fill(2)(Row(i, s"part-$i"))
      })
  }

  test("overwrite insert") {
    sql(
      """INSERT OVERWRITE TABLE normal_orc_as_source
        |SELECT * FROM orc_temp_table WHERE intField > 5
      """.stripMargin)

    checkAnswer(
      sql("SELECT * FROM normal_orc_as_source"),
      (6 to 10).map(i => Row(i, s"part-$i")))
  }

  test("write null values") {

    sql("DROP TABLE IF EXISTS orcNullValues")

    val df = sql(
      """
        |SELECT
        |  CAST(null as TINYINT) as c0,
        |  CAST(null as SMALLINT) as c1,
        |  CAST(null as INT) as c2,
        |  CAST(null as BIGINT) as c3,
        |  CAST(null as FLOAT) as c4,
        |  CAST(null as DOUBLE) as c5,
        |  CAST(null as DECIMAL(7,2)) as c6,
        |  CAST(null as TIMESTAMP) as c7,
        |  CAST(null as DATE) as c8,
        |  CAST(null as STRING) as c9,
        |  CAST(null as VARCHAR(10)) as c10
        |FROM orc_temp_table limit 1
      """.stripMargin)

    df.write.format("orc").saveAsTable("orcNullValues")

    checkAnswer(
      sql("SELECT * FROM orcNullValues"),
      Row.fromSeq(Seq.fill(11)(null)))

    sql("DROP TABLE IF EXISTS orcNullValues")
  }

  test("SPARK-18433: Improve DataSource option keys to be more case-insensitive") {
    val options = new OrcOptions(Map("Orc.Compress" -> "NONE"), spark.sessionState.conf)
    assert(options.compressionCodecClassName == "NONE")
  }
}

class OrcSourceSuite extends OrcSuite {
  override def beforeAll(): Unit = {
    super.beforeAll()

    spark.sql(
      s"""CREATE TEMPORARY VIEW normal_orc_source
         |USING ORC
         |OPTIONS (
         |  PATH '${new File(orcTableAsDir.getAbsolutePath).toURI}'
         |)
     """.stripMargin)

    spark.sql(
      s"""CREATE TEMPORARY VIEW normal_orc_as_source
         |USING ORC
         |OPTIONS (
         |  PATH '${new File(orcTableAsDir.getAbsolutePath).toURI}'
         |)
     """.stripMargin)
  }

  test("SPARK-12218 Converting conjunctions into ORC SearchArguments") {
    Seq("false", "true").foreach { value =>
      withSQLConf(SQLConf.ORC_VECTORIZED_READER_ENABLED.key -> value) {
        // The `LessThan` should be converted while the `StringContains` shouldn't
        val schema = new StructType(
          Array(
            StructField("a", IntegerType, nullable = true),
            StructField("b", StringType, nullable = true)))
        assertResult("leaf-0 = (LESS_THAN a 10), expr = leaf-0") {
          OrcFilters.createFilter(schema, Array(
            LessThan("a", 10),
            StringContains("b", "prefix")
          )).get.toString
        }

        // The `LessThan` should be converted while the whole inner `And` shouldn't
        assertResult("leaf-0 = (LESS_THAN a 10), expr = leaf-0") {
          OrcFilters.createFilter(schema, Array(
            LessThan("a", 10),
            Not(And(
              GreaterThan("a", 1),
              StringContains("b", "prefix")
            ))
          )).get.toString
        }
      }
    }
  }

  test("SPARK-21791 ORC should support column names with dot") {
    import testImplicits._
    withTempDir { dir =>
      val path = new File(dir, "orc").getCanonicalPath
      Seq(Some(1), None).toDF("col.dots").write.orc(path)
      assert(spark.read.orc(path).collect().length == 2)
    }
  }
}