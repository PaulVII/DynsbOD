package utils

import config.AppConfig
import io.{CsvLoader, OdLoader}
import structures.{
  AttributeIndexed,
  ConstantOd,
  OdIndex,
  OrderDependency,
  RecordId,
  RecordOperation,
  TupleValue
}

import scala.collection.immutable.BitSet

/** Loaded data bundle containing initial dataset, attribute mappings, and ODs
  */
case class LoadedData(
    initialData: IndexedSeq[
      (RecordOperation, Option[RecordId], IndexedSeq[TupleValue])
    ],
    attrMap: Map[String, Int],
    attributeNames: AttributeIndexed[String],
    initialOds: Seq[OrderDependency]
):
  def numAttributes: Int = attrMap.size
  def createOdIndex(using AttributeIndexed[String]): OdIndex =
    OdIndex(numAttributes, initialOds)

object DataLoader:

  /** Load initial data and ODs from config.
    *
    * If no initial data path is provided, attribute names are inferred from
    * first increment. If no initial ODs path is provided, trivial constant ODs
    * are used.
    */
  def loadFromConfig(config: AppConfig): LoadedData =
    val (data, attrMap) = config.data.initial match
      case Some(path) => CsvLoader.loadIntegers(path, config.csv)
      case None =>
        val firstIncrement = config.data.increments.headOption.getOrElse(
          throw new IllegalArgumentException(
            "Either initial data or at least one increment must be provided"
          )
        )
        val (_, map) = CsvLoader.loadIntegers(firstIncrement, config.csv)
        (IndexedSeq.empty, map)

    val attributeNames = buildAttributeNames(attrMap)

    val ods = config.results.initial match
      case Some(path) => OdLoader.loadOds(path, attrMap)
      case None       => trivialOds(attrMap.size)

    LoadedData(data, attrMap, attributeNames, ods)

  /** Load for deletion-only mode: all increments pre-loaded, final ODs as
    * starting point
    */
  def loadForDeletionOnly(config: AppConfig): LoadedData =
    val (baseData, attrMap) = config.data.initial match
      case Some(path) => CsvLoader.loadIntegers(path, config.csv)
      case None =>
        val firstIncrement = config.data.increments.headOption.getOrElse(
          throw new IllegalArgumentException(
            "Either initial data or at least one increment must be provided"
          )
        )
        val (_, map) = CsvLoader.loadIntegers(firstIncrement, config.csv)
        (IndexedSeq.empty, map)

    // Load all increments and combine
    val allData = baseData ++ config.data.increments.flatMap { path =>
      CsvLoader.loadIntegers(path, config.csv)._1
    }

    val attributeNames = buildAttributeNames(attrMap)
    val finalOds = OdLoader.loadOds(config.results.increments.last, attrMap)

    LoadedData(allData, attrMap, attributeNames, finalOds)

  /** Build attribute name lookup array from map */
  def buildAttributeNames(attrMap: Map[String, Int]): AttributeIndexed[String] =
    val arr = Array.fill[String](attrMap.size)("?")
    attrMap.foreach { case (name, id) => arr(id) = name }
    arr

  /** Create trivial constant ODs (empty context → each attribute is constant)
    */
  def trivialOds(numAttributes: Int): Seq[OrderDependency] =
    (0 until numAttributes).map(attr => ConstantOd(BitSet.empty, attr))
