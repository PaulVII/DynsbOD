package structures

import scala.collection.immutable.BitSet
import scala.collection.mutable
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

      val ordered = bySelectivityDesc(context.toArray)
      val pli = plis(ordered(0))
      // have startVal first to quickly find violations in the same cluster as last
      val it = pli.valueToTuples.valuesIterator
      it.flatMap { tupleIds =>
        buildAllCombinations(
          ordered,
          1,
          tupleIds
        )
      }.filter(_.getCardinality > 1)

  /** Attributes ordered by descending number of distinct values.
    *
    * Every refinement level discards the tuples that fall into singleton
    * clusters, so splitting by the most selective attribute first leaves the
    * deeper levels with the least data to touch. The resulting partition is the
    * same whatever the order.
    */
  private def bySelectivityDesc(attrs: Array[AttributeId]): Array[AttributeId] =
    attrs.sortBy(attr => -plis(attr).valueToTuples.size)

  private def buildAllCombinations(
      attrs: Array[AttributeId],
      from: Int,
      currentIds: RoaringBitmap
  ): Iterator[RoaringBitmap] =
    if from >= attrs.length then Iterator.single(currentIds)
    else
      // hoisted out of the loop: this used to be re-resolved per tuple
      val values = plis(attrs(from)).tupleToValues
      // A Map[TupleValue, _] boxes the key on every lookup, which cost ~20% of
      // deletion time on wide-and-long datasets. LongMap keeps it primitive —
      // but only as a lookup index: the groups are also collected in insertion
      // order, because iterating a sparse open-addressing table has to walk its
      // empty slots too and was itself worth ~12%.
      val index = new mutable.LongMap[RoaringBitmap]()
      val groups = mutable.ArrayBuffer[RoaringBitmap]()
      currentIds.forEach { tupleId =>
        val key = values(tupleId).toLong
        val existing = index.getOrNull(key)
        if existing != null then existing.add(tupleId)
        else
          val group = new RoaringBitmap()
          group.add(tupleId)
          index(key) = group
          groups += group
      }
      val next = from + 1
      groups.iterator
        .filter(_.getCardinality > 1)
        .flatMap { ids =>
          if next >= attrs.length then Iterator.single(ids)
          else
            buildAllCombinations(
              attrs,
              next,
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
      val constantValues = plis(od.constant).tupleToValues
      for clusterTuples <- contextIt do
        val it = clusterTuples.getIntIterator
        // empty clusters are ignored
        if it.hasNext then
          val firstTupleId = it.next()
          val firstValue = constantValues(firstTupleId)
          while it.hasNext do
            val tIdx = it.next()
            if constantValues(tIdx) != firstValue then
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
      val ascending = od.sameDirection
      val attr1Values = plis(od.attr1).tupleToValues
      val attr2Values = plis(od.attr2).tupleToValues
      for clusterTuples <- contextIterator
      do
        // Group the cluster's tuples by their attr1 value, ascending. Packing
        // (value, tupleId) into a long and sorting primitives is markedly
        // cheaper than the TreeMap of RoaringBitmaps this used to build — and
        // throw away — for every cluster.
        val size = clusterTuples.getCardinality
        val sorted = new Array[Long](size)
        var i = 0
        val ids = clusterTuples.getIntIterator
        while ids.hasNext do
          val id = ids.next()
          sorted(i) = (attr1Values(id).toLong << 32) | (id.toLong & 0xffffffffL)
          i += 1
        java.util.Arrays.sort(sorted)

        // a swap needs two distinct attr1 values
        if size > 1 && (sorted(0) >> 32) != (sorted(size - 1) >> 32) then
          var lastExtremeTupleId = -1
          var lastExtremeValue = if ascending then Int.MinValue else Int.MaxValue
          var idx = 0
          while idx < size do
            val subClusterValue = sorted(idx) >> 32
            var currentExtremeTupleId = -1
            var currentExtremeValue =
              if ascending then Int.MinValue else Int.MaxValue
            // one sub-cluster: the tuples sharing this attr1 value
            while idx < size && (sorted(idx) >> 32) == subClusterValue do
              val tIdx = sorted(idx).toInt
              val tValue = attr2Values(tIdx)
              if (if ascending then currentExtremeValue < tValue
                  else currentExtremeValue > tValue)
              then
                currentExtremeTupleId = tIdx
                currentExtremeValue = tValue
              if (if ascending then tValue < lastExtremeValue
                  else tValue > lastExtremeValue)
              then
                boundary.break(
                  Some(
                    SimpleViolation(
                      (lastExtremeTupleId, tIdx),
                      previousViolation.flatMap(_.noInvalidTuplesBelow)
                    )
                  )
                )
              idx += 1
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

    // The pool of contexts still available as a partner. Each context is kept
    // alongside its word representation so that the quadratic search below
    // measures overlaps with popcounts over primitive words, instead of
    // allocating a BitSet per candidate pair.
    val stride = utils.wordsFor(plis.length)
    val poolSets = mutable.ArrayBuffer[BitSet]()
    val poolMasks = mutable.ArrayBuffer[Array[Long]]()
    val scratch = new Array[Long](stride)

    def poolAdd(c: BitSet): Unit =
      val m = new Array[Long](stride)
      utils.writeWords(c, m, stride)
      poolSets += c
      poolMasks += m

    def poolRemoveAt(i: Int): Unit =
      val last = poolSets.size - 1
      poolSets(i) = poolSets(last)
      poolMasks(i) = poolMasks(last)
      poolSets.dropRightInPlace(1)
      poolMasks.dropRightInPlace(1)

    /** Index of an entry with exactly these words, or -1. */
    def poolIndexOf(q: Array[Long]): Int =
      var i = 0
      while i < poolSets.size do
        val m = poolMasks(i)
        var w = 0
        while w < stride && m(w) == q(w) do w += 1
        if w == stride then return i
        i += 1
      -1

    /** Drops both entries, largest index first so the swap-with-last used by
      * `poolRemoveAt` cannot invalidate the other one.
      */
    def poolRemoveBoth(a: Int, b: Int): Unit =
      val (hi, lo) = if a >= b then (a, b) else (b, a)
      if hi >= 0 then poolRemoveAt(hi)
      if lo >= 0 && lo != hi then poolRemoveAt(lo)

    // `contexts` are map keys and therefore already distinct
    var sawEmpty = false
    for c <- contexts do
      if c.isEmpty then sawEmpty = true else poolAdd(c)
    if sawEmpty then result(BitSet.empty) = BitSet.empty

    // Order by size (descending); comparisons are on Ints, not on BitSets.
    implicit val bitSetOrdering: Ordering[BitSet] =
      Ordering.by[BitSet, Int](_.size)
    val remaining = mutable.PriorityQueue.from(poolSets)

    while remaining.nonEmpty do
      val current = remaining.dequeue()
      logger.debug(s"handling $current")
      utils.writeWords(current, scratch, stride)
      val selfIdx = poolIndexOf(scratch)
      if selfIdx >= 0 then poolRemoveAt(selfIdx)
      if !current.isEmpty && !result.contains(current) then {

        // `current` is the largest remaining context, so any partner overlaps
        // it in at most current.size - 1 attributes; stop as soon as that is
        // reached.
        val bestPossible = current.size - 1
        var bestSize = 0
        var bestIdx = -1
        var i = 0
        while i < poolSets.size && bestSize < bestPossible do
          val m = poolMasks(i)
          var w = 0
          var inter = 0
          while w < stride do
            inter += java.lang.Long.bitCount(scratch(w) & m(w))
            w += 1
          if inter > bestSize then
            bestSize = inter
            bestIdx = i
          i += 1

        val maxByOverlap = if bestIdx >= 0 then poolSets(bestIdx) else BitSet.empty
        val overlap = if bestIdx >= 0 then maxByOverlap & current else BitSet.empty
        if bestIdx >= 0 then poolRemoveAt(bestIdx)

        logger.debug(s"maxByOverlap: $maxByOverlap, overlap: $overlap")
        assert(current != overlap)
        result += (current -> overlap)
        if maxByOverlap != overlap then
          logger.debug(s"Also adding $maxByOverlap -> $overlap")
          result += (maxByOverlap -> overlap)

        if overlap.nonEmpty && !result.contains(overlap) then
          utils.writeWords(overlap, scratch, stride)
          if poolIndexOf(scratch) < 0 then
            poolAdd(overlap)
            remaining.enqueue(overlap)

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
          // hoisted: this used to be recomputed for every cluster
          val refineBy = bySelectivityDesc((current -- ancestor).toArray)
          ancestorSeq.iterator.filter(_.getCardinality > 1).flatMap { ids =>
            buildAllCombinations(
              refineBy,
              0,
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
    // read-only; only ever iterated or intersected from here on
    val bitmap = currentlyContainedTuples.asBitmap
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

      // Validate Constants first: once X: [] ↦ A is revalidated, every
      // Compatible X: A ~ B holds by Propagate and is skipped by the guard
      // below without touching the data. They share the context's partitions,
      // so reordering them costs nothing.
      val (constants, compatibles) =
        odsInContext.toSeq.partition(_.isInstanceOf[ConstantOd])

      (constants.iterator ++ compatibles.iterator).collect {
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
