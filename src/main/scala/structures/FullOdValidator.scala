package structures

import scala.collection.immutable.BitSet
import scala.collection.mutable
import scala.jdk.CollectionConverters.*
// import scala.math.Ordering.Implicits.seqOrdering
import scala.util.boundary

import com.typesafe.scalalogging.StrictLogging
import org.roaringbitmap.RoaringBitmap
import io.circe.*
import io.circe.generic.semiauto.*
import config.AlgoConfig

case class OdValidatorStats(
    validationCount: Long = 0,
    contextIteratorsCreated: Long = 0,
    contextIteratorsReused: Long = 0,
    // previously was ContextIteratorSteps (old)
    numValidationsWherePrevExists: Long = 0,
    contextIteratorStepsNew: Long = 0,
    contextIteratorStepsWithoutMerging: Long = 0,
    bitsetsProcessed: Long = 0,
    bitsetsSkippedByLimit: Long = 0,
    directRevalidations: Long = 0,
    normalRevalidations: Long = 0,
    violationsFound: Long = 0,
    revalidatedByInsertion: Long = 0,
    violatedByInsertion: Long = 0
)

object OdValidatorStats:
  given Codec[OdValidatorStats] = deriveCodec[OdValidatorStats]

class FullOdValidator(
    val dataIndex: ContextIndex,
    val plis: IndexedSeq[SortedPli],
    val currentlyContainedTuples: ContainedTuplesSet
)(using val attributeNamesMap: AttributeIndexed[String])
    extends StrictLogging:

  var stats: OdValidatorStats = OdValidatorStats()

  /** Iterate over all clusters in the given context starting from the given
    * values. Wraps around to the beginning after reaching the end and stops
    * when reaching the starting values again.
    * @param context
    *   BitSet of attributes defining the context
    * @param startingValue
    *   Seq of TupleValues defining the starting point
    * @return
    *   an iterator over (values, tupleIds). values
    */
  def contextIterator(
      context: BitSet,
      containedIds: RoaringBitmap
  ): Iterator[RoaringBitmap] =
    if context.isEmpty then Iterator.single(containedIds)
    else

      // val attr = context.maxBy(plis(_).valueToTuples.size)
      val attr = context.head
      val pli = plis(attr)
      // have startVal first to quickly find violations in the same cluster as last
      val it = pli.valueToTuples.valuesIterator
      // val remaining = (context - attr).toArray
      val remaining = context.tail.toArray
      it.flatMap { tupleIds =>
        buildAllCombinations(
          remaining,
          tupleIds
        )
      }.filter(_.getCardinality > 1)

  private def buildAllCombinations(
      remainingAttrs: Array[AttributeId],
      currentIds: RoaringBitmap
  ): Iterator[RoaringBitmap] =
    if remainingAttrs.isEmpty then Iterator.single(currentIds)
    else
      val attr = remainingAttrs.head
      val restAttrs = remainingAttrs.tail
      val valuesToTuples =
        collection.mutable.Map[TupleValue, RoaringBitmap]()
      currentIds.forEach { tupleId =>
        val value = plis(attr).tupleToValues(tupleId)
        valuesToTuples
          .getOrElseUpdate(value, new RoaringBitmap())
          .add(tupleId)
      }
      valuesToTuples.valuesIterator
        .filter(_.getCardinality > 1)
        .flatMap { ids =>
          if restAttrs.isEmpty then Seq(ids)
          else
            buildAllCombinations(
              restAttrs,
              ids
            )
        }

  /** Validates whether an OD holds for the given tuple */

  def constantHolds(
      od: ConstantOd,
      contextIt: IterableOnce[RoaringBitmap],
      previousViolation: Option[Violation] = None
  ): Option[Violation] =
    boundary:
      for
        clusterTuples <- contextIt
        clusterIt = clusterTuples.iterator.asScala
        // because of this, empty clusters are ignored
        firstTupleId <- clusterIt.nextOption()
      do
        val firstValue = plis(od.constant).tupleToValues(firstTupleId)
        for tIdx <- clusterIt do
          val tValue = plis(od.constant).tupleToValues(tIdx)
          if tValue != firstValue then
            boundary.break(
              Some(
                SimpleViolation(
                  (firstTupleId, tIdx),
                  previousViolation.flatMap(_.noInvalidTuplesBelow)
                )
              )
            )

      boundary.break(None)

  /** @param od
    * @param deletedTupleValue
    * @return
    *   None if holds Some(violation) if violated Some(attributeId) if constant
    *   attribute discovered
    */
  def compatibleHolds(
      od: CompatibleOd,
      contextIterator: IterableOnce[RoaringBitmap],
      previousViolation: Option[Violation] = None
      // TODO: investigate if its worth it to collect more violating tuples
  ): (Option[Violation | AttributeId]) =
    boundary:
      val predicate: (Int, Int) => Boolean =
        if od.sameDirection then _ < _ else _ > _
      for clusterTuples <- contextIterator
      do
        val node = ContextNode(
          Seq.empty,
          clusterTuples,
          plis
        )
        var lastExtremeTupleId = -1
        var lastExtremeValue =
          if od.sameDirection then Int.MinValue else Int.MaxValue
        val soA = node.getSortedAttribute(od.attr1)
        for subClusterTuples <- soA.valuesIterator if soA.size > 1
        do
          var currentExtremeTupleId = -1
          var currentExtremeValue =
            if od.sameDirection then Int.MinValue else Int.MaxValue
          for tIdx <- subClusterTuples.iterator.asScala do
            val tValue = plis(od.attr2).tupleToValues(tIdx)
            if predicate(currentExtremeValue, tValue) then
              currentExtremeTupleId = tIdx
              currentExtremeValue = tValue
            if predicate(tValue, lastExtremeValue)
            then
              boundary.break(
                Some(
                  SimpleViolation(
                    (lastExtremeTupleId, tIdx),
                    previousViolation.flatMap(_.noInvalidTuplesBelow)
                  )
                )
              )
          // Update last extreme values after finishing sub-cluster
          lastExtremeTupleId = currentExtremeTupleId
          lastExtremeValue = currentExtremeValue

      // uncomment to disable direct revalidation
      boundary.break(None)
      // boundary.break(constantAttr)

  def findBuildOrder(contexts: Iterable[BitSet]): mutable.Map[BitSet, BitSet] =
    // alternative without optimization:
    // contexts.map(c => c -> BitSet.empty).to(mutable.Map)
    val result = mutable.Map[BitSet, BitSet]()

    // Use SortedSet to maintain order by size (descending)
    implicit val bitSetOrdering: Ordering[BitSet] =
      Ordering.by[BitSet, Int](_.size)
    val (emptyContexts, filtered) = contexts.partitionMap {
      case b if b.isEmpty => Left(b)
      case b              => Right(b)
    }
    if emptyContexts.nonEmpty then result(BitSet.empty) = BitSet.empty
    val remaining = mutable.PriorityQueue.from(filtered)
    val remainingSet = mutable.Set.from(filtered)

    while remaining.nonEmpty do
      val current = remaining.dequeue()
      remainingSet -= current
      logger.debug(s"handling $current")
      if !current.isEmpty && !result.contains(current) then {

        var overlap = BitSet.empty
        var maxByOverlap = BitSet.empty
        val it = remainingSet.iterator
        while it.hasNext do
          val other = it.next
          val otherOverlap = other & current
          if otherOverlap.size > overlap.size then
            maxByOverlap = other
            overlap = otherOverlap

        remainingSet -= maxByOverlap
        // val maxByOverlap = remainingSet
        // .maxBy(v => (v & current).size)
        // val overlap = maxByOverlap & current
        logger.debug(s"maxByOverlap: $maxByOverlap, overlap: $overlap")
        assert(current != overlap)
        result += (current -> overlap)
        if maxByOverlap != overlap then
          logger.debug(s"Also adding $maxByOverlap -> $overlap")
          result += (maxByOverlap -> overlap)

        if overlap.nonEmpty && !result.contains(overlap) && remainingSet.add(
            overlap
          )
        then remaining.enqueue(overlap)

        remaining += overlap

      } // Add overlap back to remaining

    logger.debug(s"Build order for contexts: $result")

    stats = stats.copy(
      contextIteratorStepsNew =
        stats.contextIteratorStepsNew + result.toSeq.map {
          // first attribute is basically free
          case (k, v) if v.isEmpty => k.size - 1
          case (k, v)              => (k.size - v.size)
        }.sum
    )

    result

  def buildContextIterators(
      contextMap: mutable.Map[BitSet, BitSet],
      containedIds: RoaringBitmap
  )(using config: AlgoConfig): mutable.Map[BitSet, Seq[RoaringBitmap]] =
    val result =
      mutable.Map[BitSet, Seq[RoaringBitmap]]()

    val toSeq: Iterator[RoaringBitmap] => Seq[RoaringBitmap] =
      if config.useLazyList then LazyList.from(_) else _.toIndexedSeq

    def buildSeq(current: BitSet): Seq[RoaringBitmap] =
      var ancestor = contextMap.getOrElse(current, BitSet.empty)
      if ancestor == current then ancestor = BitSet.empty
      stats =
        stats.copy(contextIteratorsCreated = stats.contextIteratorsCreated + 1)
      val it =
        if ancestor == BitSet.empty then contextIterator(current, containedIds)
        else
          val ancestorSeq =
            result.getOrElseUpdate(ancestor, buildSeq(ancestor))
          ancestorSeq.iterator.filter(_.getCardinality > 1).flatMap { ids =>
            buildAllCombinations(
              (current -- ancestor).toArray,
              ids
            )
          }
      toSeq(it)

    contextMap.keys.foreach { current =>
      result(current) = buildSeq(current)
    }
    result

  /** Validates multiple ODs with the same context efficiently by reusing the
    * context iterator. The context iterator is created lazily and shared across
    * all ODs.
    * @return
    *   Map from OD to (violation, constant attribute discovered)
    */
  def odHoldsGrouped(
      ods: Iterable[OrderDependency],
      previousViolations: mutable.Map[OrderDependency, Violation],
      odIndex: OdIndex
  )(using
      AlgoConfig
  ): Iterator[(OrderDependency, (Option[Violation | AttributeId]))] =
    // Group ODs by context
    val odsByContext = ods.groupBy(_.context)
    val bitmap = new RoaringBitmap()
    currentlyContainedTuples.iterator.foreach(bitmap.add)
    logger.debug(s"odHoldsGrouped with contexts: ${odsByContext.keys
        .map(v => s"{$v},")}")

    // Track context reuse: contexts with more than one OD are reused
    val contextsReused = odsByContext.count(_._2.size > 1)
    stats = stats.copy(contextIteratorsReused =
      stats.contextIteratorsReused + contextsReused
    )
    stats = stats.copy(
      contextIteratorStepsWithoutMerging =
        stats.contextIteratorStepsWithoutMerging + odsByContext.keys.toSeq
          .map(_.size - 1)
          .sum
    )

    // oldFindBuildOrder(odsByContext.keys.toSeq)

    val buildOrder =
      if summon[AlgoConfig].optimizeBuildOrder then
        findBuildOrder(odsByContext.keys)
      else odsByContext.keys.map(c => c -> BitSet.empty).to(mutable.Map)

    val contextIterators =
      buildContextIterators(buildOrder, bitmap)

    odsByContext.iterator.flatMap { case (context, odsInContext) =>
      val contextData =
        contextIterators(context)
          .filter(_.getCardinality > 1)

      odsInContext.iterator.collect {
        case od if od.isInstanceOf[ConstantOd] || !odIndex.holds(od) =>
          stats = stats.copy(validationCount = stats.validationCount + 1)
          logger.debug(s"Fully validating OD: ${od.toStringWithNames}")
          val prevValidation = previousViolations.get(od)
          // TODO: fix skipping contexts based on previous violations
          // val it = contextData
          val it = prevValidation match
            case Some(prevVal) =>
              stats = stats.copy(
                numValidationsWherePrevExists =
                  stats.numValidationsWherePrevExists + 1
              )
              val limit = prevVal.noInvalidTuplesBelow.getOrElse(-1)
              contextData.iterator.filter { ids =>
                ids.last() >= limit - 1
              }
            case None => contextData

          val result = od match
            case constOd: ConstantOd =>
              constantHolds(constOd, it, prevValidation)
            case compatOd: CompatibleOd =>
              compatibleHolds(compatOd, it, prevValidation)

          result match
            case Some(_: Violation) =>
              stats = stats.copy(violationsFound = stats.violationsFound + 1)
            case Some(_: AttributeId) =>
              stats =
                stats.copy(directRevalidations = stats.directRevalidations + 1)
            case None =>
              stats =
                stats.copy(normalRevalidations = stats.normalRevalidations + 1)

          od -> result
      }
    }
