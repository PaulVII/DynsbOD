package config

import java.nio.file.Files
import java.nio.file.Path

import io.circe.Decoder
import io.circe.DecodingFailure
import io.circe.HCursor
import io.circe.parser.parse

final case class DataPaths(
    initial: Option[String] = None,
    increments: List[String] = List.empty
)

final case class CsvOptions(
    delimiter: String = ",",
    hasHeader: Boolean = false,
    canHaveDeletions: Boolean,
    columnsToInclude: Option[List[String]] = None
)

final case class ResultsPaths(
    initial: Option[String] = None,
    increments: List[String] = List.empty
)

enum DeletionMode:
  case None, AfterInsert, DeleteOnly

object DeletionMode:
  def fromString(s: String): DeletionMode = s.toLowerCase match
    case "none"        => DeletionMode.None
    case "afterinsert" => DeletionMode.AfterInsert
    case "deleteonly"  => DeletionMode.DeleteOnly
    case other =>
      throw new IllegalArgumentException(
        s"Invalid DeletionMode: $other. Valid: none, afterinsert, deleteonly"
      )

final case class AlgoConfig(
    deletionMode: Option[DeletionMode] = None,
    minMaxCacheThreshold: Int = 20,
    skipRevalidationByInsert: Boolean = true,
    useEfficientUpdates: Boolean = true,
    optimizeBuildOrder: Boolean = true,
    showProgressBar: Boolean = true,
    useLazyList: Boolean = false
)

final case class AppConfig(
    data: DataPaths,
    results: ResultsPaths,
    output: String,
    csv: CsvOptions,
    algo: AlgoConfig = AlgoConfig(),
    intermediateStatsPath: Option[String] = None,
    perTupleTimingPath: Option[String] = None
)

object AppConfig:
  given Decoder[DataPaths] = Decoder.instance { (c: HCursor) =>
    for
      initial <- c.get[Option[String]]("initial")
      increments <- c.getOrElse[List[String]]("increments")(List.empty)
    yield DataPaths(initial = initial, increments = increments)
  }

  given Decoder[ResultsPaths] = Decoder.instance { (c: HCursor) =>
    for
      initial <- c.get[Option[String]]("initial")
      increments <- c.getOrElse[List[String]]("increments")(List.empty)
    yield ResultsPaths(initial = initial, increments = increments)
  }

  given Decoder[CsvOptions] = Decoder.instance { (c: HCursor) =>
    for
      delimiter <- c.getOrElse[String]("delimiter")(",")
      hasHeader <- c.getOrElse[Boolean]("hasHeader")(false)
      canHaveDeletions <- c.getOrElse[Boolean]("canHaveDeletions")(false)
      columnsToInclude <- c.getOrElse[Option[List[String]]]("columnsToInclude")(
        None
      )
    yield CsvOptions(
      delimiter = delimiter,
      hasHeader = hasHeader,
      canHaveDeletions = canHaveDeletions,
      columnsToInclude = columnsToInclude
    )
  }

  given Decoder[DeletionMode] = Decoder.instance { (c: HCursor) =>
    c.as[String].map(DeletionMode.fromString)
  }

  given Decoder[AlgoConfig] = Decoder.instance { (c: HCursor) =>
    val allowedKeys = Set(
      "deletionMode",
      "minMaxCacheThreshold",
      "skipRevalidationByInsert",
      "useEfficientUpdates",
      "optimizeBuildOrder",
      "showProgressBar",
      "useLazyList"
    )
    val unknownKeys = c.keys.getOrElse(Vector.empty).filterNot(allowedKeys)
    if unknownKeys.nonEmpty then
      Left(
        DecodingFailure(
          s"Unexpected AlgoConfig keys: ${unknownKeys.mkString(", ")}",
          c.history
        )
      )
    else
      for
        deletionMode <- c.getOrElse[Option[DeletionMode]]("deletionMode")(None)
        minMaxCacheThreshold <- c.getOrElse[Int]("minMaxCacheThreshold")(20)
        skipRevalidationByInsert <- c.getOrElse[Boolean](
          "skipRevalidationByInsert"
        )(false)
        useEfficientUpdates <- c.getOrElse[Boolean](
          "useEfficientUpdates"
        )(true)
        optimizeBuildOrder <- c.getOrElse[Boolean]("optimizeBuildOrder")(true)
        showProgressBar <- c.getOrElse[Boolean]("showProgressBar")(true)
        useLazyList <- c.getOrElse[Boolean]("useLazyList")(true)
      yield AlgoConfig(
        deletionMode = deletionMode,
        minMaxCacheThreshold = minMaxCacheThreshold,
        skipRevalidationByInsert = skipRevalidationByInsert,
        useEfficientUpdates = useEfficientUpdates,
        optimizeBuildOrder = optimizeBuildOrder,
        showProgressBar = showProgressBar,
        useLazyList = useLazyList
      )
  }

  given Decoder[AppConfig] = Decoder.instance { (c: HCursor) =>
    for
      data <- c.get[DataPaths]("data")
      results <- c.get[ResultsPaths]("results")
      output <- c.getOrElse[String]("output")("")
      csv <- c.get[CsvOptions]("csv")
      algo <- c.getOrElse[AlgoConfig]("algo")(AlgoConfig())
      intermediateStatsPath <- c.getOrElse[Option[String]](
        "intermediateStatsPath"
      )(None)
      perTupleTimingPath <- c.getOrElse[Option[String]](
        "perTupleTimingPath"
      )(None)
    yield AppConfig(
      data = data,
      results = results,
      output = output,
      csv = csv,
      algo = algo,
      intermediateStatsPath = intermediateStatsPath,
      perTupleTimingPath = perTupleTimingPath
    )
  }

object AppConfigLoader:
  private def readFile(path: String): String =
    val p = Path.of(path)
    new String(Files.readAllBytes(p))

  def load(configPathOrJson: String): AppConfig =
    // If starts with '{', treat as JSON string; otherwise as file path
    val raw =
      if configPathOrJson.trim.startsWith("{") then configPathOrJson
      else readFile(configPathOrJson)

    val json = parse(raw).fold(throw _, identity)
    json.as[AppConfig].fold(throw _, identity)
