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
package org.apache.spark.sql.execution.datasources.v2

import java.util
import scala.collection.JavaConverters._
import org.apache.hadoop.fs.{FileStatus, Path}
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.hive.common.StatsSetupConst
import org.apache.hadoop.hive.metastore.{TableType => HiveTableType}
import org.apache.hadoop.hive.metastore.api.FieldSchema
import org.apache.hadoop.hive.ql.metadata.{Hive, Partition => HivePartition, Table => HiveTable}
import org.apache.spark.SparkException
import org.apache.spark.sql.catalyst.catalog.{BucketSpec, CatalogStatistics, CatalogStorageFormat, CatalogTable, CatalogTablePartition, CatalogTableType, CatalogUtils}
import org.apache.spark.sql.catalyst.parser.{CatalystSqlParser, ParseException}
import org.apache.spark.sql.{AnalysisException, SparkSession}
import org.apache.spark.sql.connector.catalog.{SupportsRead, SupportsWrite, Table, TableCapability}
import org.apache.spark.sql.connector.catalog.TableCapability._
import org.apache.spark.sql.connector.expressions.Transform
import org.apache.spark.sql.execution.datasources._
import org.apache.spark.sql.execution.streaming.{FileStreamSink, MetadataLogFileIndex}
import org.apache.spark.sql.types.{DataType, HIVE_TYPE_STRING, Metadata, MetadataBuilder, StructField, StructType}
import org.apache.spark.sql.util.CaseInsensitiveStringMap
import org.apache.spark.sql.util.SchemaUtils
import org.apache.hadoop.hive.ql.parse.BaseSemanticAnalyzer.HIVE_COLUMN_ORDER_ASC
import org.apache.spark.sql.catalyst.TableIdentifier

import java.util.Locale
import scala.collection.mutable.ArrayBuffer

abstract class FileTable(
    sparkSession: SparkSession,
    options: CaseInsensitiveStringMap,
    paths: Seq[String],
    userSpecifiedSchema: Option[StructType],
    client: Hive = null,
    hiveTable: HiveTable = null)
  extends Table with SupportsRead with SupportsWrite {

  import org.apache.spark.sql.connector.catalog.CatalogV2Implicits._

  /* new add code */
  // Below is the key of table properties for storing Hive-generated statistics
  val HiveStatisticsProperties = Set(
    StatsSetupConst.COLUMN_STATS_ACCURATE,
    StatsSetupConst.NUM_FILES,
    StatsSetupConst.NUM_PARTITIONS,
    StatsSetupConst.ROW_COUNT,
    StatsSetupConst.RAW_DATA_SIZE,
    StatsSetupConst.TOTAL_SIZE
  )

  protected val hadoopConf: Configuration = sparkSession.sessionState.newHadoopConf()
  protected val table: CatalogTable = convertHiveTableToCatalogTable(hiveTable)

  lazy val fileIndex: PartitioningAwareFileIndex = {
    val caseSensitiveMap = options.asCaseSensitiveMap.asScala.toMap
    // Hadoop Configurations are case sensitive.
    val hadoopConf = sparkSession.sessionState.newHadoopConfWithOptions(caseSensitiveMap)
    if (FileStreamSink.hasMetadata(paths, hadoopConf, sparkSession.sessionState.conf)) {
      // We are reading from the results of a streaming query. We will load files from
      // the metadata log instead of listing them using HDFS APIs.
      new MetadataLogFileIndex(sparkSession, new Path(paths.head),
        options.asScala.toMap, userSpecifiedSchema)
    } else {
      println("===== Processing FileIndex =====")

      if (paths.size > 0) {
        // This is a non-streaming file based datasource.
        val rootPathsSpecified = DataSource.checkAndGlobPathIfNecessary(paths, hadoopConf,
          checkEmptyGlobPath = true, checkFilesExist = true)
        val fileStatusCache = FileStatusCache.getOrCreate(sparkSession)
        new InMemoryFileIndex(sparkSession, rootPathsSpecified, caseSensitiveMap, userSpecifiedSchema, fileStatusCache)
      } else {
        val startTime = System.nanoTime()
//        val parts = client.getPartitions(hiveTable).asScala.map(fromHivePartition)
        val parts = client.getAllPartitionsOf(hiveTable).asScala.map(fromHivePartition).toSeq
        val partitionSchema = table.partitionSchema
        val partitions = parts.map { p =>
          val path = new Path(p.location)
          val fs = path.getFileSystem(hadoopConf)
          PartitionPath(
            p.toRow(partitionSchema, sparkSession.sessionState.conf.sessionLocalTimeZone),
            path.makeQualified(fs.getUri, fs.getWorkingDirectory))
        }
        val partitionSpec = PartitionSpec(partitionSchema, partitions)
        val timeNs = System.nanoTime() - startTime
        val fileStatusCache = FileStatusCache.getOrCreate(sparkSession)
        new InMemoryFileIndex(
          sparkSession,
          rootPathsSpecified = partitionSpec.partitions.map(_.path),
          parameters = Map.empty,
          userSpecifiedSchema = Some(partitionSpec.partitionColumns),
          fileStatusCache = fileStatusCache,
          userSpecifiedPartitionSpec = Some(partitionSpec),
          metadataOpsTimeNs = Some(timeNs)
        )
      }
    }
  }

  lazy val dataSchema: StructType = {
    val schema = userSpecifiedSchema.map { schema =>
      val partitionSchema = fileIndex.partitionSchema
      val resolver = sparkSession.sessionState.conf.resolver
      StructType(schema.filterNot(f => partitionSchema.exists(p => resolver(p.name, f.name))))
    }.orElse {
      inferSchema(fileIndex.allFiles())
    }.getOrElse {
      throw new AnalysisException(
        s"Unable to infer schema for $formatName. It must be specified manually.")
    }
    fileIndex match {
      case _: MetadataLogFileIndex => schema
      case _ => schema.asNullable
    }
    
    schema
  }

  override lazy val schema: StructType = {
    val caseSensitive = sparkSession.sessionState.conf.caseSensitiveAnalysis
    SchemaUtils.checkColumnNameDuplication(dataSchema.fieldNames,
      "in the data schema", caseSensitive)
    dataSchema.foreach { field =>
      if (!supportsDataType(field.dataType)) {
        throw new AnalysisException(
          s"$formatName data source does not support ${field.dataType.catalogString} data type.")
      }
    }
    val partitionSchema = fileIndex.partitionSchema
    SchemaUtils.checkColumnNameDuplication(partitionSchema.fieldNames,
      "in the partition schema", caseSensitive)
    val partitionNameSet: Set[String] =
      partitionSchema.fields.map(PartitioningUtils.getColName(_, caseSensitive)).toSet

    // When data and partition schemas have overlapping columns,
    // tableSchema = dataSchema - overlapSchema + partitionSchema
    val fields = dataSchema.fields.filterNot { field =>
      val colName = PartitioningUtils.getColName(field, caseSensitive)
      partitionNameSet.contains(colName)
    } ++ partitionSchema.fields

    val allSchema = StructType(fields)
    allSchema
  }

  /* new add code */
  def convertHiveTableToCatalogTable(h: HiveTable): CatalogTable = {
    // Note: Hive separates partition columns and the schema, but for us the
    // partition columns are part of the schema
    val (cols, partCols) = try {
      (h.getCols.asScala.map(fromHiveColumn), h.getPartCols.asScala.map(fromHiveColumn))
    } catch {
      case ex: SparkException =>
        throw new SparkException(s"${ex.getMessage}, db: ${h.getDbName}, table: ${h.getTableName}", ex)
    }

    val schema = StructType(cols ++ partCols)

    val bucketSpec = if (h.getNumBuckets > 0) {
      val sortColumnOrders = h.getSortCols.asScala
      // Currently Spark only supports columns to be sorted in ascending order
      // but Hive can support both ascending and descending order. If all the columns
      // are sorted in ascending order, only then propagate the sortedness information
      // to downstream processing / optimizations in Spark
      // TODO: In future we can have Spark support columns sorted in descending order
      val allAscendingSorted = sortColumnOrders.forall(_.getOrder == HIVE_COLUMN_ORDER_ASC)

      val sortColumnNames = if (allAscendingSorted) {
        sortColumnOrders.map(_.getCol)
      } else {
        Seq.empty
      }
      Option(BucketSpec(h.getNumBuckets, h.getBucketCols.asScala, sortColumnNames))
    } else {
      None
    }

    // Skew spec and storage handler can't be mapped to CatalogTable (yet)
    val unsupportedFeatures = ArrayBuffer.empty[String]

    if (!h.getSkewedColNames.isEmpty) {
      unsupportedFeatures += "skewed columns"
    }
    if (h.getStorageHandler != null) {
      unsupportedFeatures += "storage handler"
    }
    if (h.getTableType == HiveTableType.VIRTUAL_VIEW && partCols.nonEmpty) {
      unsupportedFeatures += "partitioned view"
    }

    val properties = Option(h.getParameters).map(_.asScala.toMap).orNull

    // Hive-generated Statistics are also recorded in ignoredProperties
    val ignoredProperties = scala.collection.mutable.Map.empty[String, String]
    for (key <- HiveStatisticsProperties; value <- properties.get(key)) {
      ignoredProperties += key -> value
    }

    val excludedTableProperties = HiveStatisticsProperties ++ Set(
      // The property value of "comment" is moved to the dedicated field "comment"
      "comment",
      // For EXTERNAL_TABLE, the table properties has a particular field "EXTERNAL". This is added
      // in the function toHiveTable.
      "EXTERNAL"
    )

    val filteredProperties = properties.filterNot {
      case (key, _) => excludedTableProperties.contains(key)
    }
    val comment = properties.get("comment")

    CatalogTable(
      identifier = TableIdentifier(h.getTableName, Option(h.getDbName)),
      tableType = h.getTableType match {
        case HiveTableType.EXTERNAL_TABLE => CatalogTableType.EXTERNAL
        case HiveTableType.MANAGED_TABLE => CatalogTableType.MANAGED
        case HiveTableType.VIRTUAL_VIEW => CatalogTableType.VIEW
        case unsupportedType =>
          val tableTypeStr = unsupportedType.toString.toLowerCase(Locale.ROOT).replace("_", " ")
          throw new AnalysisException(s"Hive $tableTypeStr is not supported.")
      },
      schema = schema,
      partitionColumnNames = partCols.map(_.name),
      // If the table is written by Spark, we will put bucketing information in table properties,
      // and will always overwrite the bucket spec in hive metastore by the bucketing information
      // in table properties. This means, if we have bucket spec in both hive metastore and
      // table properties, we will trust the one in table properties.
      bucketSpec = bucketSpec,
      owner = Option(h.getOwner).getOrElse(""),
      createTime = h.getTTable.getCreateTime.toLong * 1000,
      lastAccessTime = h.getLastAccessTime.toLong * 1000,
      storage = CatalogStorageFormat(
        locationUri = Option(h.getDataLocation.toString).map(CatalogUtils.stringToURI),
        // To avoid ClassNotFound exception, we try our best to not get the format class, but get
        // the class name directly. However, for non-native tables, there is no interface to get
        // the format class name, so we may still throw ClassNotFound in this case.
        inputFormat = Option(h.getTTable.getSd.getInputFormat).orElse {
          Option(h.getStorageHandler).map(_.getInputFormatClass.getName)
        },
        outputFormat = Option(h.getTTable.getSd.getOutputFormat).orElse {
          Option(h.getStorageHandler).map(_.getOutputFormatClass.getName)
        },
        serde = Option(h.getSerializationLib),
        compressed = h.getTTable.getSd.isCompressed,
        properties = Option(h.getTTable.getSd.getSerdeInfo.getParameters)
          .map(_.asScala.toMap).orNull
      ),
      // For EXTERNAL_TABLE, the table properties has a particular field "EXTERNAL". This is added
      // in the function toHiveTable.
      properties = filteredProperties,
      stats = readHiveStats(properties),
      comment = comment,
      // In older versions of Spark(before 2.2.0), we expand the view original text and
      // store that into `viewExpandedText`, that should be used in view resolution.
      // We get `viewExpandedText` as viewText, and also get `viewOriginalText` in order to
      // display the original view text in `DESC [EXTENDED|FORMATTED] table` command for views
      // that created by older versions of Spark.
      viewOriginalText = Option(h.getViewOriginalText),
      viewText = Option(h.getViewExpandedText),
      unsupportedFeatures = unsupportedFeatures,
      ignoredProperties = ignoredProperties.toMap)
  }

  /* new add code */
  /** Builds the native StructField from Hive's FieldSchema. */
  def fromHiveColumn(hc: FieldSchema): StructField = {
    val columnType = getSparkSQLDataType(hc)
    val metadata = if (hc.getType != columnType.catalogString) {
      new MetadataBuilder().putString(HIVE_TYPE_STRING, hc.getType).build()
    } else {
      Metadata.empty
    }

    val field = StructField(
      name = hc.getName,
      dataType = columnType,
      nullable = true,
      metadata = metadata
    )
    Option(hc.getComment).map(field.withComment).getOrElse(field)
  }

  /* new add code */
  /** Get the Spark SQL native DataType from Hive's FieldSchema. */
  def getSparkSQLDataType(hc: FieldSchema): DataType = {
    try {
      CatalystSqlParser.parseDataType(hc.getType)
    } catch {
      case e: ParseException =>
        throw new SparkException(s"Cannot recognize hive type string: ${hc.getType}, column: ${hc.getName}", e)
    }
  }

  /* new add code */
  def fromHivePartition(hp: HivePartition): CatalogTablePartition = {
    val apiPartition = hp.getTPartition
    val properties: Map[String, String] = if (hp.getParameters != null) {
      hp.getParameters.asScala.toMap
    } else {
      Map.empty
    }

    CatalogTablePartition(
      spec = Option(hp.getSpec).map(_.asScala.toMap).getOrElse(Map.empty),
      storage = CatalogStorageFormat(
        locationUri = Option(CatalogUtils.stringToURI(apiPartition.getSd.getLocation)),
        inputFormat = Option(apiPartition.getSd.getInputFormat),
        outputFormat = Option(apiPartition.getSd.getOutputFormat),
        serde = Option(apiPartition.getSd.getSerdeInfo.getSerializationLib),
        compressed = apiPartition.getSd.isCompressed,
        properties = Option(apiPartition.getSd.getSerdeInfo.getParameters).map(_.asScala.toMap).orNull),
      createTime = apiPartition.getCreateTime.toLong * 1000,
      lastAccessTime = apiPartition.getLastAccessTime * 1000,
      parameters = properties,
      stats = readHiveStats(properties)
    )
  }

  /* new add code */
  def readHiveStats(properties: Map[String, String]): Option[CatalogStatistics] = {
    val totalSize = properties.get(StatsSetupConst.TOTAL_SIZE).filter(_.nonEmpty).map(BigInt(_))
    val rawDataSize = properties.get(StatsSetupConst.RAW_DATA_SIZE).filter(_.nonEmpty)
      .map(BigInt(_))
    val rowCount = properties.get(StatsSetupConst.ROW_COUNT).filter(_.nonEmpty).map(BigInt(_))
    // NOTE: getting `totalSize` directly from params is kind of hacky, but this should be
    // relatively cheap if parameters for the table are populated into the metastore.
    // Currently, only totalSize, rawDataSize, and rowCount are used to build the field `stats`
    // TODO: stats should include all the other two fields (`numFiles` and `numPartitions`).
    // (see StatsSetupConst in Hive)

    // When table is external, `totalSize` is always zero, which will influence join strategy.
    // So when `totalSize` is zero, use `rawDataSize` instead. When `rawDataSize` is also zero,
    // return None.
    // In Hive, when statistics gathering is disabled, `rawDataSize` and `numRows` is always
    // zero after INSERT command. So they are used here only if they are larger than zero.
    if (totalSize.isDefined && totalSize.get > 0L) {
      Some(CatalogStatistics(sizeInBytes = totalSize.get, rowCount = rowCount.filter(_ > 0)))
    } else if (rawDataSize.isDefined && rawDataSize.get > 0) {
      Some(CatalogStatistics(sizeInBytes = rawDataSize.get, rowCount = rowCount.filter(_ > 0)))
    } else {
      // TODO: still fill the rowCount even if sizeInBytes is empty. Might break anything?
      None
    }
  }

  override def partitioning: Array[Transform] = fileIndex.partitionSchema.names.toSeq.asTransforms

  override def properties: util.Map[String, String] = options.asCaseSensitiveMap

  override def capabilities: java.util.Set[TableCapability] = FileTable.CAPABILITIES

  /**
   * When possible, this method should return the schema of the given `files`.  When the format
   * does not support inference, or no valid files are given should return None.  In these cases
   * Spark will require that user specify the schema manually.
   */
  def inferSchema(files: Seq[FileStatus]): Option[StructType]

  /**
   * Returns whether this format supports the given [[DataType]] in read/write path.
   * By default all data types are supported.
   */
  def supportsDataType(dataType: DataType): Boolean = true

  /**
   * The string that represents the format that this data source provider uses. This is
   * overridden by children to provide a nice alias for the data source. For example:
   *
   * {{{
   *   override def formatName(): String = "ORC"
   * }}}
   */
  def formatName: String

  /**
   * Returns a V1 [[FileFormat]] class of the same file data source.
   * This is a solution for the following cases:
   * 1. File datasource V2 implementations cause regression. Users can disable the problematic data
   *    source via SQL configuration and fall back to FileFormat.
   * 2. Catalog support is required, which is still under development for data source V2.
   */
  def fallbackFileFormat: Class[_ <: FileFormat]
}

object FileTable {
  private val CAPABILITIES = Set(BATCH_READ, BATCH_WRITE, TRUNCATE).asJava
}

