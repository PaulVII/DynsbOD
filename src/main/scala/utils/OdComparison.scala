package utils

import io.OdLoader
import structures.OrderDependency

/** Utilities for comparing and validating OD sets */
object OdComparison:

  case class OdDiff(
      correct: Set[OrderDependency],
      missing: Set[OrderDependency],
      unexpected: Set[OrderDependency]
  ):
    def isValid: Boolean = missing.isEmpty && unexpected.isEmpty
    def summary: String =
      s"Correct: ${correct.size}, Missing: ${missing.size}, Unexpected: ${unexpected.size}"

  /** Compare found ODs against expected ODs */
  def compare(
      found: Set[OrderDependency],
      expected: Set[OrderDependency]
  ): OdDiff =
    OdDiff(
      correct = found & expected,
      missing = expected -- found,
      unexpected = found -- expected
    )

  /** Load expected ODs from path and compare */
  def compareWithFile(
      found: Set[OrderDependency],
      resultPath: String,
      attrMap: Map[String, Int]
  ): OdDiff =
    val expected = OdLoader.loadOds(resultPath, attrMap).toSet
    compare(found, expected)

  /** Validate ODs match expected, throwing AssertionError on mismatch */
  def validate(
      found: Set[OrderDependency],
      expected: Set[OrderDependency],
      stage: String
  ): Unit =
    val diff = compare(found, expected)
    if diff.missing.nonEmpty then
      throw new AssertionError(
        s"Missing ODs at $stage: ${diff.missing.size} ODs not found"
      )
    if diff.unexpected.nonEmpty then
      throw new AssertionError(
        s"Unexpected ODs at $stage: ${diff.unexpected.size} invalid ODs found"
      )

  /** Format OD diff as a readable report string */
  def formatReport(
      diff: OdDiff,
      phase: String,
      csvPath: String,
      resultPath: Option[String]
  )(using names: structures.AttributeIndexed[String]): String =
    def formatOds(ods: Set[OrderDependency]): String =
      if ods.isEmpty then "None"
      else ods.map(_.toStringWithNames).mkString("\n")

    resultPath match
      case None =>
        diff.correct.map(_.toStringWithNames).mkString("\n")
      case Some(path) =>
        s"""
           |Results file: $path
           |Phase: $phase
           |Processed CSV: $csvPath
           |------------------------------
           |Correct ODs: (${diff.correct.size})
           |${formatOds(diff.correct)}
           |Missing ODs: (${diff.missing.size})
           |${formatOds(diff.missing)}
           |Unexpected ODs: (${diff.unexpected.size})
           |${formatOds(diff.unexpected)}
           |""".stripMargin
