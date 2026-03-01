import java.nio.file.{Files, Path}

import com.typesafe.scalalogging.Logger
import config.{AppConfigLoader, AlgoConfig, DeletionMode, AppConfig}
import engine.IncrementalOdDiscovery
import io.CsvLoader
import structures.{AttributeIndexed, RecordOperation}
import utils.{DataLoader, OdComparison}

val logger = Logger("Main")

@main def main(configPathOrJson: String): Unit =
  val config = AppConfigLoader.load(configPathOrJson)
  given AlgoConfig = config.algo // Used implicitly by algorithm components

  logger.info(s"Running algorithm with config: $config")

  val deletionMode = config.algo.deletionMode.getOrElse(DeletionMode.None)
  require(
    !config.csv.canHaveDeletions || deletionMode == DeletionMode.None,
    "Auto-deleting rows only supported when CSV config allows insertions only"
  )

  // Load data based on mode
  logger.info(
    if deletionMode == DeletionMode.DeleteOnly then
      "Loading all data for deletion-only mode..."
    else "Loading initial data from config..."
  )
  val loaded =
    if deletionMode == DeletionMode.DeleteOnly then
      DataLoader.loadForDeletionOnly(config)
    else DataLoader.loadFromConfig(config)

  logger.info(
    s"Loaded ${loaded.initialData.size} rows, ${loaded.initialOds.size} ODs"
  )
  given AttributeIndexed[String] = loaded.attributeNames

  // Initialize discovery
  val start = System.currentTimeMillis()
  val discovery = IncrementalOdDiscovery(
    initialDataset = loaded.initialData,
    odIndex = loaded.createOdIndex,
    canHaveDeletions = config.csv.canHaveDeletions
  )
  logger.info(s"Initialization took ${System.currentTimeMillis() - start} ms")

  // Load increments for processing
  val random = new scala.util.Random(42)

  val loadedIncrements = config.data.increments.map { path =>
    (path, CsvLoader.loadIntegers(path, config.csv)._1)
  }

  // Insertion phase
  if deletionMode != DeletionMode.DeleteOnly then
    for ((csvPath, inc), idx) <- loadedIncrements.zipWithIndex do
      val startTime = System.currentTimeMillis()
      val (statsSeq, timingsSeq) = discovery.applyIncrement(
        inc,
        config.intermediateStatsPath.isDefined,
        config.perTupleTimingPath.isDefined
      )
      val maxMemory = statsSeq.lastOption
        .map(_.maxMemoryUsedBytes)
        .getOrElse(0L) / (1024 * 1024)
      logger.info(
        s"Increment $idx: Time = ${System.currentTimeMillis() - startTime} ms, MaxMem = $maxMemory MB"
      )

      // Write intermediate stats to CSV if configured
      config.intermediateStatsPath.foreach { path =>
        writeStatsToCSV(statsSeq, path, idx)
      }

      // Write per-tuple timing to CSV if configured
      config.perTupleTimingPath.foreach { path =>
        writePerTupleTimingToCSV(timingsSeq, path, idx, "insert")
      }

      writeResults(
        discovery,
        csvPath,
        config.results.increments.lift(idx),
        "insert",
        loaded.attrMap,
        config
      )

  // Deletion phase
  if deletionMode != DeletionMode.None then
    logger.info("Starting deletion phase...")
    for ((csvPath, inc), reverseIdx) <- loadedIncrements.reverse.zipWithIndex do
      val idx = loadedIncrements.size - 1 - reverseIdx
      val deletionInc = random
        .shuffle(
          inc.map { case (_, _, tuple) =>
            (RecordOperation.Delete, None, tuple)
          }
        )
        .toIndexedSeq

      val startTime = System.currentTimeMillis()
      val (statsSeq, timingsSeq) = discovery.applyIncrement(
        deletionInc,
        config.intermediateStatsPath.isDefined,
        config.perTupleTimingPath.isDefined
      )
      val maxMemory = statsSeq.lastOption
        .map(_.maxMemoryUsedBytes)
        .getOrElse(0L) / (1024 * 1024)
      println(
        s"Deletion $idx: Time = ${System.currentTimeMillis() - startTime} ms, MaxMem = $maxMemory MB"
      )

      // Write intermediate stats to CSV if configured
      config.intermediateStatsPath.foreach { path =>
        writeStatsToCSV(statsSeq, s"${path}_delete.csv", idx)
      }

      // Write per-tuple timing to CSV if configured
      config.perTupleTimingPath.foreach { path =>
        writePerTupleTimingToCSV(timingsSeq, path, idx, "delete")
      }

      val expectedPath =
        if idx == 0 then config.results.initial
        else config.results.increments.lift(idx - 1)
      writeResults(
        discovery,
        csvPath,
        expectedPath,
        "delete",
        loaded.attrMap,
        config
      )

  logger.info(s"Total time: ${System.currentTimeMillis() - start} ms")
  logger.info(s"Statistics: ${discovery.fullValidator.stats}")
  writeValidatorStats(discovery)
  writeFinalData(discovery, loaded.attributeNames)

private def writeResults(
    discovery: IncrementalOdDiscovery,
    csvPath: String,
    expectedPath: Option[String],
    phase: String,
    attrMap: Map[String, Int],
    config: AppConfig
)(using names: AttributeIndexed[String]): Unit =
  val found = discovery.odIndex.iterator.toSet
  val diff = expectedPath match
    case Some(path) => OdComparison.compareWithFile(found, path, attrMap)
    case None       => OdComparison.OdDiff(found, Set.empty, Set.empty)

  val content = OdComparison.formatReport(diff, phase, csvPath, expectedPath)
  val outputFile =
    if config.output != "" then config.output
    else s"results/${csvPath.split("/").last}_${phase}_results.txt"
  print(s"Writing results to: $outputFile")
  Files.writeString(Path.of(outputFile), content)

private def writeValidatorStats(discovery: IncrementalOdDiscovery): Unit =
  import io.circe.syntax.*
  import structures.OdValidatorStats.given
  Files.writeString(
    Path.of("final_stats.json"),
    discovery.fullValidator.stats.asJson.spaces2
  )
  logger.info("Wrote validator stats to final_stats.json")

private def writeFinalData(
    discovery: IncrementalOdDiscovery,
    names: AttributeIndexed[String]
): Unit =
  import scala.collection.immutable.BitSet
  import scala.jdk.CollectionConverters.*
  import utils.ones

  val allAttrs = BitSet.ones(names.size)
  val lines = scala.collection.mutable.ArrayBuffer[String]()
  lines += names.mkString(",")
  lines ++= discovery.currentlyContainedTuples.iterator.map { tupleId =>
    allAttrs.toSeq
      .map(a => discovery.plis(a).tupleToValues(tupleId))
      .mkString(",")
  }
  Files.write(Path.of("results/final_data.csv"), lines.asJava)
  logger.info("Wrote final data to results/final_data.csv")

private def writeStatsToCSV(
    stats: Seq[structures.IntermediateStats],
    path: String,
    incrementIdx: Int
): Unit =
  import java.nio.file.StandardOpenOption
  import scala.jdk.CollectionConverters.*
  import scala.collection.mutable

  val csvPath = Path.of(path)
  val fileExists = Files.exists(csvPath) && Files.size(csvPath) > 0

  val csvLines = mutable.ArrayBuffer[String]()

  // Write header only if file doesn't exist or is empty
  if !fileExists then
    csvLines += "incrementIdx,currentHighestTupleId,lastOperationId,currentConstantOds,currentConstantNonOds,currentComptibleOds,currentComptibleNonOds,timeElapsedMs,validationCount,contextIteratorsCreated,contextIteratorsReused,contextIteratorSteps,numCacheEvictions,maxMemoryUsedBytes,currentMemoryUsedBytes,contextIndexContexts,numViolationsTracked"

  // Data rows (with incrementIdx prepended)
  stats.foreach { s =>
    csvLines += s"$incrementIdx,${s.currentHighestTupleId},${s.lastOperationId},${s.currentConstantOds},${s.currentConstantNonOds},${s.currentComptibleOds},${s.currentComptibleNonOds},${s.timeElapsedMs},${s.odValidatorStats.validationCount},${s.odValidatorStats.contextIteratorsCreated},${s.odValidatorStats.contextIteratorsReused},${s.odValidatorStats.contextIteratorStepsNew},${s.numCacheEvictions},${s.maxMemoryUsedBytes},${s.currentMemoryUsedBytes},${s.contextIndexContexts},${s.numViolationsTracked}"
  }

  // Append if file exists, otherwise create new
  val openOptions =
    if fileExists then Array(StandardOpenOption.APPEND)
    else Array(StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)

  Files.write(csvPath, csvLines.asJava, openOptions*)
  logger.info(s"Wrote ${stats.size} intermediate stats to $path")

private def writePerTupleTimingToCSV(
    timings: Seq[structures.PerTupleTiming],
    path: String,
    incrementIdx: Int,
    phase: String
): Unit =
  import java.nio.file.StandardOpenOption
  import scala.jdk.CollectionConverters.*
  import scala.collection.mutable

  val csvPath = Path.of(path)
  val fileExists = Files.exists(csvPath) && Files.size(csvPath) > 0

  val csvLines = mutable.ArrayBuffer[String]()

  // Write header only if file doesn't exist or is empty
  if !fileExists then
    csvLines += "incrementIdx,phase,operationId,operationType,tupleId,recordId,timeNanos,timeMicros,timeMillis"

  // Data rows
  timings.foreach { t =>
    val recordIdStr = t.recordId.getOrElse("")
    val timeMicros = t.timeNanos / 1000.0
    val timeMillis = t.timeNanos / 1000000.0
    csvLines += s"$incrementIdx,$phase,${t.operationId},${t.operationType},${t.tupleId},$recordIdStr,${t.timeNanos},$timeMicros,$timeMillis"
  }

  // Append if file exists, otherwise create new
  val openOptions =
    if fileExists then Array(StandardOpenOption.APPEND)
    else Array(StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)

  Files.write(csvPath, csvLines.asJava, openOptions*)
  logger.info(s"Wrote ${timings.size} per-tuple timings to $path")
