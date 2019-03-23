package net.snowflake.spark.snowflake.io

import java.sql.Connection

import net.snowflake.client.jdbc.internal.fasterxml.jackson.databind.ObjectMapper
import net.snowflake.client.jdbc.internal.fasterxml.jackson.databind.node.ObjectNode
import net.snowflake.spark.snowflake.Parameters.MergedParameters
import net.snowflake.spark.snowflake._
import net.snowflake.spark.snowflake.io.SupportedFormat.SupportedFormat
import net.snowflake.spark.snowflake.DefaultJDBCWrapper.DataBaseOperations
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.{Row, SQLContext}
import org.apache.spark.sql.execution.datasources.DataSource
import org.apache.spark.sql.execution.datasources.parquet.ParquetFileFormat
import org.slf4j.{Logger, LoggerFactory}

import scala.util.Random

private[io] object StageReader {

  private val mapper: ObjectMapper = new ObjectMapper()
  private val logger: Logger = LoggerFactory.getLogger(this.getClass)
  private val OUTPUT_BYTES: String = "output_bytes"

  def readFromStage(
                     sqlContext: SQLContext,
                     params: MergedParameters,
                     statement: SnowflakeSQLStatement,
                     format: SupportedFormat
                   ): RDD[Row] = {
    val conn = DefaultJDBCWrapper.getConnector(params)
    val (storage, stage, url) = //TODO: separate stage creation from storage client creation and only use stage creation here
      CloudStorageOperations.createStorageClient(params, conn)
    val compress = params.sfCompress
    val compressFormat = if (params.sfCompress) "gzip" else "none"

    Utils.genPrologueSql(params).execute(params.bindVariableEnabled)(conn)

    Utils.executePreActions(DefaultJDBCWrapper, conn, params, params.table)

    val prefix = Random.alphanumeric take 10 mkString ""

    val res = buildUnloadStatement(
      params,
      statement,
      s"@$stage/$prefix/").execute(params.bindVariableEnabled)(conn)

    // Verify it's the expected format
    val sch = res.getMetaData
    assert(sch.getColumnCount == 3)
    assert(sch.getColumnName(1) == "rows_unloaded")
    assert(sch.getColumnTypeName(1) == "NUMBER") // First record must be in
    assert(sch.getColumnName(3) == "output_bytes")
    val first = res.next()
    assert(first)

    //report egress usage
    sendEgressUsage(res.getLong(3), conn)

    val second = res.next()
    assert(!second)

    Utils.executePostActions(DefaultJDBCWrapper, conn, params, params.table)

    SnowflakeTelemetry.send(conn.getTelemetry)

//    storage.download(
//      sqlContext.sparkContext,
//      format,
//      compress,
//      prefix
//    )

    println("*"*20)
    println(s"@$stage/$prefix/")
    println(url)
    println(params.parameters)
    println("*"*20)

    val sparkSession = sqlContext.sparkSession
    val baseRelation = DataSource.apply(
      sparkSession,
      className = classOf[ParquetFileFormat].getName,
      paths = List(url)
    ).resolveRelation(checkFilesExist = true)

    sparkSession.baseRelationToDataFrame(baseRelation).rdd
  }

  private def buildUnloadStatement(
                                    params: MergedParameters,
                                    statement: SnowflakeSQLStatement,
                                    location: String
                                  ): SnowflakeSQLStatement = {
    // Save the last SELECT so it can be inspected
    Utils.setLastCopyUnload(statement.toString)

    ConstantString(s"COPY INTO '$location' FROM ($statement)") +
      s"FILE_FORMAT=(TYPE=PARQUET) MAX_FILE_SIZE=${params.s3maxfilesize}"
  }

  private def sendEgressUsage(bytes: Long, conn: Connection): Unit = {
    val metric: ObjectNode = mapper.createObjectNode()
    metric.put(OUTPUT_BYTES, bytes)

    SnowflakeTelemetry.addLog((TelemetryTypes.SPARK_EGRESS, metric), System.currentTimeMillis())
    SnowflakeTelemetry.send(conn.getTelemetry)

    logger.debug(s"Data Egress Usage: $bytes bytes")
  }

}
