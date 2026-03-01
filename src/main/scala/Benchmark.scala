package main

import java.nio.file.{Files, Path, Paths}

import scala.collection.mutable
import scala.util.{Random, Try}

import ch.qos.logback.classic.{Level, Logger}
import com.typesafe.scalalogging.StrictLogging
import config.{AppConfig, AppConfigLoader, AlgoConfig}
import engine.IncrementalOdDiscovery
import io.{CsvLoader, OdLoader}
import io.circe.*
import io.circe.generic.semiauto.*
import io.circe.syntax.*
import org.slf4j.LoggerFactory
import structures.{
  AttributeIndexed,
  OdIndex,
  OdValidatorStats,
  RecordOperation,
  TupleValue
}
import utils.{DataLoader, OdComparison}

case class BenchmarkResult(
    configName: String,
    initTimeMs: Long = 0,
    maxMemoryUsedIncrements: List[Long] = List.empty,
    incrementTimes: List[Long] = List.empty,
    maxMemoryUsedDeletions: List[Long] = List.empty,
    deletionTimes: List[Long] = List.empty,
    validatorStatsDeletion: OdValidatorStats = OdValidatorStats(),
    maxMemoryUsedDeletionsNoInsert: List[Long] = List.empty,
    deletionTimesNoInsert: List[Long] = List.empty,
    validatorStatsDeletionNoInsert: OdValidatorStats = OdValidatorStats(),
    totalTimeMs: Long = 0,
    success: Boolean = false,
    errorMessage: Option[String] = None
)

object Benchmark extends StrictLogging:
  def main(args: Array[String]): Unit =
    setupLogging(args.contains("--verbose"))

    val configFiles = findConfigFiles()
    if configFiles.isEmpty then
      logger.error("No config files found in configs/benchmark")
      System.exit(1)

    logger.info(s"Found ${configFiles.length} config files")
    val results = configFiles.map(runBenchmarkWithGc)
    saveBenchmarkReport(results.toList)

    val (passed, failed) = results.partition(_.success)
    logger.info(
      s"Benchmark complete: ${passed.size} passed, ${failed.size} failed"
    )
    if failed.nonEmpty then System.exit(1)

  private def setupLogging(verbose: Boolean): Unit =
    val root = LoggerFactory
      .getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)
      .asInstanceOf[Logger]
    root.setLevel(if verbose then Level.INFO else Level.WARN)

  private def findConfigFiles(): Array[Path] =
    val configsDir = Paths.get("configs/benchmark")
    if !Files.exists(configsDir) then return Array.empty
    Files
      .walk(configsDir)
      .filter(p => Files.isRegularFile(p) && p.toString.endsWith(".json"))
      .toArray
      .map(_.asInstanceOf[Path])
      .sorted

  private def runBenchmarkWithGc(configFile: Path): BenchmarkResult =
    val configName = configFile.getFileName.toString.replace("_config.json", "")
    println(s"Running benchmark for $configName")
    System.gc(); Thread.sleep(1000)

    val result = runSingleBenchmark(configFile, configName)
    if result.success then
      logger.info(s"$configName: PASSED (${result.totalTimeMs}ms)")
    else
      logger.error(
        s"$configName: FAILED - ${result.errorMessage.getOrElse("Unknown")}"
      )
    result

  private def runSingleBenchmark(
      configFile: Path,
      configName: String
  ): BenchmarkResult =
    Random.setSeed(42)
    Try {
      val config = AppConfigLoader.load(configFile.toString)
      given AlgoConfig = config.algo // Used implicitly by algorithm
      val loaded = DataLoader.loadFromConfig(config)
      given AttributeIndexed[String] = loaded.attributeNames

      // Initialize discovery
      val (discovery, initTime) = timed {
        IncrementalOdDiscovery(
          initialDataset = loaded.initialData,
          odIndex = loaded.createOdIndex,
          canHaveDeletions = config.csv.canHaveDeletions
        )
      }
      OdComparison.validate(
        discovery.odIndex.iterator.toSet,
        loaded.initialOds.toSet,
        "initial"
      )

      // Process increments
      val (loadedIncrements, incTimes, incMemory) =
        processIncrements(discovery, config, loaded.attrMap)

      // Run deletion benchmarks if applicable
      val (delMetrics, delNoInsertMetrics) =
        if config.csv.canHaveDeletions then
          (
            DeletionMetrics(
              List.empty,
              List.empty,
              discovery.fullValidator.stats
            ),
            emptyMetrics
          )
        else runDeletionBenchmarks(discovery, config, loaded, loadedIncrements)

      BenchmarkResult(
        configName = configName,
        initTimeMs = initTime,
        incrementTimes = incTimes,
        maxMemoryUsedIncrements = incMemory,
        deletionTimes = delMetrics.times,
        maxMemoryUsedDeletions = delMetrics.memory,
        validatorStatsDeletion = delMetrics.stats,
        deletionTimesNoInsert = delNoInsertMetrics.times,
        maxMemoryUsedDeletionsNoInsert = delNoInsertMetrics.memory,
        validatorStatsDeletionNoInsert = delNoInsertMetrics.stats,
        totalTimeMs = initTime + incTimes.sum + delMetrics.times.sum,
        success = true
      )
    }.recover { case e =>
      logger.error(s"Benchmark failed for $configName", e)
      BenchmarkResult(
        configName = configName,
        errorMessage = Some(e.getMessage)
      )
    }.get

  case class DeletionMetrics(
      times: List[Long],
      memory: List[Long],
      stats: OdValidatorStats
  )
  private val emptyMetrics =
    DeletionMetrics(List.empty, List.empty, OdValidatorStats())

  // Type alias for increment tuples
  private type IncrementTuple =
    (RecordOperation, Option[structures.RecordId], IndexedSeq[TupleValue])

  private def processIncrements(
      discovery: IncrementalOdDiscovery,
      config: AppConfig,
      attrMap: Map[String, Int]
  ): (
      mutable.ArrayBuffer[(String, IndexedSeq[IncrementTuple])],
      List[Long],
      List[Long]
  ) =
    val loaded = mutable.ArrayBuffer[(String, IndexedSeq[IncrementTuple])]()
    val times = mutable.ArrayBuffer[Long]()
    val memory = mutable.ArrayBuffer[Long]()

    config.data.increments.zipWithIndex.foreach { case (csvPath, idx) =>
      val (inc, _) = CsvLoader.loadIntegers(csvPath, config.csv)
      loaded += ((csvPath, inc))

      val ((statsSeq, _), time) = timed { discovery.applyIncrement(inc, false) }
      val mem = statsSeq.lastOption.map(_.maxMemoryUsedBytes).getOrElse(0L)
      times += time; memory += mem

      config.results.increments.lift(idx).foreach { resultPath =>
        val expected = OdLoader.loadOds(resultPath, attrMap).toSet
        OdComparison.validate(
          discovery.odIndex.iterator.toSet,
          expected,
          s"increment $idx"
        )
      }
    }
    (loaded, times.toList, memory.toList)

  private def runDeletionBenchmarks(
      discovery: IncrementalOdDiscovery,
      config: AppConfig,
      loaded: utils.LoadedData,
      increments: mutable.ArrayBuffer[(String, IndexedSeq[IncrementTuple])]
  )(using
      AttributeIndexed[String],
      AlgoConfig
  ): (DeletionMetrics, DeletionMetrics) =
    logger.info("Starting deletion benchmarks...")

    // Extract tuple values from increments in reverse order
    val groups: Seq[Seq[IndexedSeq[TupleValue]]] = increments.reverse.map {
      case (_, inc) =>
        inc.map { case (_, _, t) => t }.toSeq
    }.toSeq
    val resultPaths = increments.indices.reverse.map { idx =>
      if idx == 0 then config.results.initial
      else config.results.increments.lift(idx - 1)
    }.toSeq

    // Run with existing discovery (after insertions)
    val (delTimes, delMem) = runDeletions(
      discovery,
      groups,
      resultPaths,
      loaded.attrMap,
      increments.size
    )
    val afterInsertMetrics =
      DeletionMetrics(delTimes, delMem, discovery.fullValidator.stats)
    logger.info(
      s"Stats after deletions (with insertions): ${afterInsertMetrics.stats}"
    )

    // Run deletion-only benchmark
    System.gc(); Thread.sleep(1000)
    logger.info("Starting deletion-only benchmark...")

    val allIncTuples = increments.flatMap(_._2).map { case (_, _, t) => t }
    val fullData: IndexedSeq[IncrementTuple] =
      (loaded.initialData ++ allIncTuples.map(t =>
        (RecordOperation.Insert, None, t)
      )).toIndexedSeq
    val finalOds =
      OdLoader.loadOds(config.results.increments.last, loaded.attrMap)
    val freshDiscovery = IncrementalOdDiscovery(
      initialDataset = fullData,
      odIndex = OdIndex(loaded.numAttributes, finalOds),
      canHaveDeletions = config.csv.canHaveDeletions
    )

    val (delTimesNoIns, delMemNoIns) = runDeletions(
      freshDiscovery,
      groups,
      resultPaths,
      loaded.attrMap,
      increments.size
    )
    val noInsertMetrics = DeletionMetrics(
      delTimesNoIns,
      delMemNoIns,
      freshDiscovery.fullValidator.stats
    )
    logger.info(
      s"Stats after deletions (no insertions): ${noInsertMetrics.stats}"
    )

    (afterInsertMetrics, noInsertMetrics)

  private def runDeletions(
      discovery: IncrementalOdDiscovery,
      groups: Seq[Seq[IndexedSeq[TupleValue]]],
      resultPaths: Seq[Option[String]],
      attrMap: Map[String, Int],
      totalIncrements: Int
  ): (List[Long], List[Long]) =
    val times = mutable.ArrayBuffer[Long]()
    val memory = mutable.ArrayBuffer[Long]()

    groups.zipWithIndex.foreach { case (group, reverseIdx) =>
      val idx = totalIncrements - 1 - reverseIdx
      val deletionInc: IndexedSeq[IncrementTuple] = Random
        .shuffle(
          group.map(t =>
            (RecordOperation.Delete, Option.empty[structures.RecordId], t)
          )
        )
        .toIndexedSeq

      val ((statsSeq, _), time) = timed {
        discovery.applyIncrement(deletionInc, false)
      }
      val mem = statsSeq.lastOption.map(_.maxMemoryUsedBytes).getOrElse(0L)
      times += time; memory += mem
      logger.info(s"Deleted increment $idx (${group.size} rows)")

      resultPaths.lift(reverseIdx).flatten.foreach { path =>
        val expected = OdLoader.loadOds(path, attrMap).toSet
        OdComparison.validate(
          discovery.odIndex.iterator.toSet,
          expected,
          s"deletion $idx"
        )
      }
    }
    (times.toList, memory.toList)

  private def timed[T](block: => T): (T, Long) =
    val start = System.currentTimeMillis()
    val result = block
    (result, System.currentTimeMillis() - start)

  private def saveBenchmarkReport(results: List[BenchmarkResult]): Unit =
    implicit val encoder: Encoder[BenchmarkResult] = deriveEncoder
    val json = Json.obj(
      "generatedAt" -> Json.fromString(java.time.Instant.now().toString),
      "totalConfigs" -> Json.fromInt(results.length),
      "results" -> Json.fromValues(results.map(_.asJson))
    )
    Files.write(Path.of("benchmark_report.json"), json.spaces2.getBytes())
    logger.info("Benchmark report saved to benchmark_report.json")
