import munit.FunSuite

import config.{AppConfigLoader, CsvOptions}
import io.CsvLoader

import java.nio.file.{Files, Path}

class ConfigCsvTest extends FunSuite:
  var file: Path = null

  override def beforeEach(context: BeforeEach): Unit = {
    file = Files.createTempFile(null, ".json")
  }

  override def afterEach(context: AfterEach): Unit = {
    Files.deleteIfExists(file)
  }

  def columnNamesNoHeader(n: Int): Map[String, Int] =
    (0 until n).map(i => ('A' + i).toChar.toString -> i).toMap

  test("config: load minimal with defaults") {
    Files.writeString(
      file,
      """
      |{
      |  "data": { "initial": "/tmp/data.csv" },
      |  "results": { "initial": "/tmp/results.csv.txt" }
      |}
      |""".stripMargin
    )

    val cfg = AppConfigLoader.load(file.toString)
    assertEquals(cfg.data.initial, "/tmp/data.csv")
    assertEquals(cfg.data.increments, Nil)
    assertEquals(cfg.results.initial, "/tmp/results.csv.txt")
    assertEquals(cfg.results.increments, Nil)
    assertEquals(cfg.csv.delimiter, ",")
    assertEquals(cfg.csv.hasHeader, false)
  }

  test("config: load full with increments and csv options") {
    val json =
      """
      |{
      |  "data": {
      |    "initial": "/tmp/initial.csv",
      |    "increments": ["/tmp/inc1.csv", "/tmp/inc2.csv"]
      |  },
      |  "results": {
      |    "initial": "/tmp/results_initial.csv.txt",
      |    "increments": ["/tmp/results_inc1.csv.txt", "/tmp/results_inc2.csv.txt"]
      |  },
      |  "csv": { "delimiter": ";", "hasHeader": true }
      |}
      |""".stripMargin
    Files.writeString(file, json)
    val cfg = AppConfigLoader.load(file.toString)
    assertEquals(cfg.data.initial, "/tmp/initial.csv")
    assertEquals(cfg.data.increments, List("/tmp/inc1.csv", "/tmp/inc2.csv"))
    assertEquals(cfg.results.initial, "/tmp/results_initial.csv.txt")
    assertEquals(
      cfg.results.increments,
      List("/tmp/results_inc1.csv.txt", "/tmp/results_inc2.csv.txt")
    )
    assertEquals(cfg.csv.delimiter, ";")
    assertEquals(cfg.csv.hasHeader, true)
  }

  test("config: missing data should fail") {
    val json =
      """
      |{
      |  "csv": { "delimiter": "," }
      |}
      |""".stripMargin
    Files.writeString(file, json)
    intercept[Throwable] {
      AppConfigLoader.load(file.toString)
    }
  }

  test("csv: load without header, comma delimiter") {
    val csv =
      """
      |1,2,3
      |4,5,6
      |""".stripMargin.trim
    Files.writeString(file, csv)
    val (data, colMap) = CsvLoader.loadIntegers(
      file.toString,
      CsvOptions(canHaveDeletions = false)
    )
    assertEquals(data.length, 2)
    assertEquals(data(0)._3.toList, List(1, 2, 3))
    assertEquals(data(1)._3.toList, List(4, 5, 6))
    assertEquals(colMap, columnNamesNoHeader(3))
  }

  test("csv: load with header") {
    val csv =
      """
      |a,b,c
      |1,2,3
      |4,5,6
      |""".stripMargin.trim
    Files.writeString(file, csv)
    val (data, colMap) =
      CsvLoader.loadIntegers(
        file.toString,
        CsvOptions(hasHeader = true, canHaveDeletions = false)
      )
    assertEquals(data.length, 2)
    assertEquals(data.head._3.toList, List(1, 2, 3))
    assertEquals(colMap, Map("a" -> 0, "b" -> 1, "c" -> 2))
  }

  test("csv: custom delimiter ';'") {
    val csv =
      """
      |1;2;3
      |4;5;6
      |""".stripMargin.trim
    Files.writeString(file, csv)
    val (data, colMap) =
      CsvLoader.loadIntegers(
        file.toString,
        CsvOptions(delimiter = ";", canHaveDeletions = false)
      )
    assertEquals(data.length, 2)
    assertEquals(data(1)._3.toList, List(4, 5, 6))
    assertEquals(
      colMap,
      columnNamesNoHeader(3)
    ) // default names
  }

  test("csv: missing file should throw") {
    intercept[IllegalArgumentException] {
      CsvLoader.loadIntegers(
        "/path/does/not/exist.csv",
        CsvOptions(canHaveDeletions = false)
      )
    }
  }
