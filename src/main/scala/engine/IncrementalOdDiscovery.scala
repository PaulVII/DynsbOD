package engine

import scala.collection.immutable.BitSet
import scala.collection.mutable

import com.typesafe.scalalogging.StrictLogging
import structures.*
import utils.ProgressBarIterator
import utils.ones
// import org.slf4j.LoggerFactory
// import ch.qos.logback.classic.{Level, Logger}
// import scala.jdk.CollectionConverters.*
import config.AlgoConfig

// HyOD imports
// import hyod.{HyOD, CanonicalOD}
// import dependencydiscover.predicate.Operator
import org.roaringbitmap.RoaringBitmap

final class IncrementalOdDiscovery(
    val initialDataset: IndexedSeq[
      (RecordOperation, Option[RecordId], IndexedSeq[TupleValue])
    ],
    val odIndex: OdIndex,
    val canHaveDeletions: Boolean = false
)(using val attributeNamesMap: AttributeIndexed[String], algoConfig: AlgoConfig)
    extends StrictLogging:

  val numAttributes: Int = attributeNamesMap.length

  val plis: IndexedSeq[SortedPli] = buildPliSeq(initialDataset, numAttributes)
  val dataIndex = ContextIndex(plis, odIndex.iterator.toArray)
  var currentHighestId: TupleId = initialDataset.size
  val currentlyContainedTuples = ContainedTuplesSet()
  for (i <- initialDataset.indices) do currentlyContainedTuples.add(i)
  val fullValidator = FullOdValidator(dataIndex, plis, currentlyContainedTuples)
  val maxNonOdIndex =
    MaxNonOdIndex(
      numAttributes,
      odIndex,
      currentlyContainedTuples,
      canHaveDeletions
    )

  var numCacheEvictions = 0
  var numFullCacheEvictions = 0

  /** Scratch space for collecting violating tuples. Reused across calls, since
    * an OD validation usually finds none and should not allocate; results are
    * copied out before the buffer is handed to the next validation.
    */
  private val violationScratch = mutable.ArrayBuffer[TupleId]()

  // Maps to track relationship between tuple IDs (position-based, changes on update)
  // and record IDs (stable identifiers from the data source)
  private val tupleIdToRecordId = mutable.Map[TupleId, RecordId]()
  private val recordIdToTupleId = mutable.Map[RecordId, TupleId]()

  // Initialize maps from initial dataset
  for ((_, recordIdOpt, _), tupleId) <- initialDataset.zipWithIndex do
    recordIdOpt.foreach { recordId =>
      tupleIdToRecordId(tupleId) = recordId
      recordIdToTupleId(recordId) = tupleId
    }

  /** Make invalidated OD valid again by expanding its context. For each
    * violating tuple, determine which attributes differ from the inserted
    * tuple. The new context must contain at least one of these attributes. Use
    * the MMCS algorithm to find all minimal combinations of attributes that
    * fulfill this.
    *
    * @param invalidatedOd
    * @param invalidatingTuple
    * @param violatingTuples
    * @return
    *   set of new valid ODs (with expanded context). Not necessarily minimal
    */
  def expandContext(
      invalidatedOd: structures.OrderDependency,
      invalidatingTuple: IndexedSeq[TupleValue],
      violatingTuples: Iterable[TupleId]
  ): Set[OrderDependency] =
    val availableCols = BitSet.ones(numAttributes) -- invalidatedOd.includedCols
    val differenceSets = violatingTuples.map { vid =>
      availableCols.filter { col =>
        invalidatingTuple(col) != plis(col).tupleToValues(vid)
      }
    }

    MMCS
      .enumerateMinimalHittingSets(
        Hypergraph.minimal(availableCols, differenceSets)
      )
      .map(ext => invalidatedOd.copyWithContext(invalidatedOd.context ++ ext))

  def constructCompatibleFromInvalidConstant(
      invalidatedConstant: ConstantOd,
      invalidatingTuple: IndexedSeq[TupleValue],
      itData: IterationData,
      constantViolations: Iterable[TupleId]
  ): Seq[OrderDependency] =
    val availableCols =
      BitSet.ones(numAttributes) -- invalidatedConstant.includedCols

    // Compared against each derived Compatible's violations below. Built once,
    // as a set, so the comparison is not an accidental Set-vs-Seq mismatch
    // (which is never equal and silently disables the pruning).
    val constantViolationSet = constantViolations.toSet

    val ods = for
      col <- availableCols.toSeq
      sameDirection <- Seq(true, false)
      newOd = CompatibleOd(
        invalidatedConstant.context,
        invalidatedConstant.constant,
        col,
        sameDirection
      )
    // if one direction was valid without adding more attributes, the other
    // direction will have the same violating tuples as the FD, therefore it
    // will not yield anything useful and all will be nonminimal by the newly
    // generated Constants
    yield getViolatingCompatible(invalidatingTuple, newOd, itData) match
      case invalids if invalids.isEmpty => Set(newOd)
      case invalids
          if invalids.size == constantViolationSet.size
            && invalids.forall(constantViolationSet.contains) =>
        Set.empty
      case invalids =>
        // this includes many redundant ODs, but makes sure
        // we find all ODs with contexts between the previous context
        // and the newly constructed constants (minimal hitting sets)
        expandContext(newOd, invalidatingTuple, invalids)

    ods.flatten.distinct

  def generateNewlyMinimalOds(
      invalidatingTuple: IndexedSeq[TupleValue],
      violatingTuples: Iterable[TupleId],
      itData: IterationData
  ): mutable.ArrayBuffer[OrderDependency] =
    // Changed from Set to ArrayBuffer to preserve order. It is important that constants are added first
    val newlyMinimals = mutable.ArrayBuffer.empty[OrderDependency]
    newlyMinimals ++= expandContext(
      itData.od,
      invalidatingTuple,
      violatingTuples
    )

    itData.od match
      case c: ConstantOd =>
        newlyMinimals ++=
          constructCompatibleFromInvalidConstant(
            c,
            invalidatingTuple,
            itData,
            violatingTuples: Iterable[TupleId]
          )
      case _ =>

    logger.debug(
      s"Found ${newlyMinimals.size} new valid ODs: ${newlyMinimals.map(_.toStringWithNames).mkString(", ")}"
    )
    newlyMinimals

  /** Return tuple IDs of all tuples that violate the given order dependency
    * after insertion of tuple t with id.
    *
    * @param sortedAttribute
    *   attribute on which the OD is sorted. This should only contain the tuples
    *   that exist in the context of the OD
    * @param t
    *   tuple to test
    * @param od
    *   the constant OD to test
    * @return
    *   set of tuple IDs that violate the OD
    */
  def getViolatingConstant(
      t: IndexedSeq[TupleValue],
      od: ConstantOd,
      sortedAttribute: SortedAttribute
  ): Iterable[TupleId] =
    sortedAttribute.toSeq.collect {
      case (value, cluster) if value != t(od.constant) =>
        cluster.toArray
    }.flatten

  /** Return tuple IDs of all tuples that violate the given order dependency
    * after insertion of tuple t.
    *
    * @param t
    *   tuple to test
    * @param context
    *   context of the OD
    * @param attr1
    *   attribute on which the OD is sorted
    * @param attr2
    *   attribute that should follow the order of attr1
    * @param sameDirection
    *   whether the order is ascending (true) or descending (false)
    * @return
    *   set of tuple IDs that violate the OD
    */
  private def checkDirectionForViolations(
      t: IndexedSeq[TupleValue],
      sortedAttrId: Int,
      otherAttrId: Int,
      nextCluster: TupleValue => Option[(TupleValue, RoaringBitmap)],
      tShouldBeSmaller: Boolean,
      minMaxCache: MinMaxCache,
      invalidTuples: mutable.ArrayBuffer[TupleId]
  ): Unit =
    var currentValue = t(sortedAttrId)
    var nextClusterMightHaveInvalids = true

    while nextClusterMightHaveInvalids do
      nextCluster(currentValue) match
        case Some((nextValue, cluster)) =>
          val it = cluster.iterator
          val potentiallyHasInvalids = minMaxCache.couldHaveViolations(
            sortedAttrId,
            nextValue,
            otherAttrId,
            cluster.getCardinality,
            tShouldBeSmaller,
            t
          )

          var currentMin = Int.MaxValue
          var currentMax = Int.MinValue
          var minId = -1
          var maxId = -1
          while potentiallyHasInvalids && it.hasNext do
            val id = it.next()
            val otherValue = plis(otherAttrId).tupleToValues(id)
            if otherValue < currentMin then
              currentMin = otherValue
              minId = id
            if otherValue > currentMax then
              currentMax = otherValue
              maxId = id
            val isViolating =
              if tShouldBeSmaller
              then otherValue < t(otherAttrId)
              else otherValue > t(otherAttrId)
            if isViolating then invalidTuples += id
            else nextClusterMightHaveInvalids = false

          currentValue = nextValue

          if cluster.getCardinality >= algoConfig.minMaxCacheThreshold && potentiallyHasInvalids
          then
            minMaxCache.createNewMinMaxValue(
              sortedAttrId,
              currentValue,
              otherAttrId,
              currentMin,
              currentMax,
              minId,
              maxId
            )

        case None =>
          nextClusterMightHaveInvalids = false

  private def getViolatingCompatible(
      t: IndexedSeq[TupleValue],
      // it is important that we do not use a CompatibleOd here, as we need
      // ensure which attribute is used as base for finding violations when
      // expanding an invalid ConstantOd
      od: CompatibleOd,
      itData: IterationData
  ): IndexedSeq[TupleId] =

    // if od.context
    //     == Set(7, 3, 9) && od.attr1 == 0 && od.attr2 == 10
    // then println("debug")

    val IterationData(_, sortedAttrId, sortedAttribute, minMaxCache) =
      itData
    val otherAttrId = if od.attr1 == sortedAttrId then od.attr2 else od.attr1

    val invalidTuples = violationScratch
    invalidTuples.clear()

    val progressivelySmallerAttr1Clusters = sortedAttribute.maxBefore
    // we don't need the actual value here, can just call next
    val it = sortedAttribute.iteratorFrom(t(sortedAttrId) + 1)
    val progressivelyLargerAttr1Clusters = (_: TupleValue) => it.nextOption()

    // First direction: lower values, checking if other attr is larger
    checkDirectionForViolations(
      t,
      sortedAttrId,
      otherAttrId,
      progressivelyLargerAttr1Clusters,
      od.sameDirection,
      minMaxCache,
      invalidTuples
    )
    if invalidTuples.isEmpty then
      // Second direction: higher values, checking if other attr is smaller
      checkDirectionForViolations(
        t,
        sortedAttrId,
        otherAttrId,
        progressivelySmallerAttr1Clusters,
        !od.sameDirection,
        minMaxCache,
        invalidTuples
      )

    // copied out, since the scratch buffer is reused by the next call
    if invalidTuples.isEmpty then IndexedSeq.empty
    else invalidTuples.toIndexedSeq

  def getViolating(
      t: IndexedSeq[TupleValue],
      itData: IterationData
  ): Iterable[TupleId] =
    itData.od match
      case c: ConstantOd => getViolatingConstant(t, c, itData.sortedAttr)
      case c: CompatibleOd =>
        getViolatingCompatible(
          t,
          c,
          itData
        )

  def handleTupleInsertion(
      tuple: IndexedSeq[TupleValue],
      id: TupleId,
      changedAttributes: Option[BitSet] = None
  ): Unit =
    // if id == 121 then
    //   val root = LoggerFactory
    //     .getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)
    //     .asInstanceOf[Logger]
    //   root.setLevel(Level.DEBUG)
    logger.debug(s"Inserting tuple $id: $tuple")
    // if currentHighestId == 244
    // then logger.debug("Debug breakpoint")
    plis.addTuple(tuple, id)
    currentlyContainedTuples.add(id)

    // dataIndex.insertTuple(id, tuple)
    // toArray doesn't cost performance but makes flame graph useful since no recursion needed
    for itData <- dataIndex.insertAndIterate(id, tuple) do
      // Only check ODs where includedCols overlaps with changedAttributes (if provided)
      val shouldCheck = changedAttributes match
        case Some(changed) => itData.od.includedCols.exists(changed.contains)
        case None          => true

      if shouldCheck then
        // logger.debug(s"Validating od ${itData.od} on tuple $id")
        val violatingTuples =
          getViolating(tuple, itData)
        if violatingTuples.nonEmpty then
          logger.debug(
            s"Violation of OD ${itData.od.toStringWithNames} by tuple $id: ${violatingTuples
                .mkString(", ")}"
          )

          val newOds = generateNewlyMinimalOds(
            tuple,
            violatingTuples,
            itData
          )
          // odIndex.handleViolation(itData.od, newOds.toSeq)
          maxNonOdIndex.handleInvalidation(
            itData.od,
            newOds.toSeq,
            InvalidationViolation(
              id,
              mutable.Set(violatingTuples.toSeq*)
            )
          )
    end for
    finishIteration()
  end handleTupleInsertion

  def tryRevalidateByInserts(
      nonOd: OrderDependency,
      noInvalidTuplesBelow: Int
  ): Option[Violation] =
    logger.debug(
      s"InsertionRevalidation: Trying to revalidate non-OD ${nonOd.toStringWithNames} by simulating inserts starting from tuple ID $noInvalidTuplesBelow"
    )
    var violation: Option[Violation] = None
    val it =
      currentlyContainedTuples.iteratorGreaterThan(noInvalidTuplesBelow)
    while it.hasNext && violation.isEmpty do
      val id = it.next()
      val tupleValue =
        (0 until numAttributes).map(attr => plis(attr).tupleToValues(id))
      val itData = dataIndex.getRevalidationData(id, nonOd)
      val violatingTuples =
        getViolating(tupleValue, itData)
      if violatingTuples.nonEmpty then
        logger.debug(
          s"InsertionRevalidation: Violation of OD ${nonOd.toStringWithNames} by reinserted tuple $id: ${violatingTuples
              .mkString(", ")}"
        )
        violation = Some(
          InvalidationViolation(
            id,
            mutable.Set(violatingTuples.toSeq*)
          )
        )
    end while
    if violation.isEmpty then
      logger.debug(
        s"InsertionRevalidation: Non-OD ${nonOd.toStringWithNames} is now valid after reinsertion"
      )
    violation

  def getTupleId(tupleValue: IndexedSeq[TupleValue]): TupleId =
    try {
      val sortedData = plis
        .zip(tupleValue)
        .map { (pli, value) =>
          pli.valueToTuples(value)
        }
        .sortBy(_.getCardinality)
      val candidateIds = sortedData.head.clone()

      val it = sortedData.tail.iterator
      while it.hasNext && candidateIds.getCardinality > 1 do
        val pli = it.next()
        candidateIds.and(pli)
      end while
      if candidateIds.isEmpty then
        throw new IllegalArgumentException(
          s"Tuple $tupleValue not found"
        )
      candidateIds.last()
    } catch {
      case e => throw RuntimeException(s"Error finding tuple $tupleValue: $e")
    }
  end getTupleId

  def handleTupleDeletion(
      tuple: IndexedSeq[TupleValue],
      id: TupleId,
      changedAttributes: Option[BitSet] = None,
      newTupleId: Option[TupleId] = None
  ): Unit =
    logger.debug(s"Deleting tuple $id: $tuple")
    currentlyContainedTuples.remove(id)
    plis.removeTuple(id)
    dataIndex.removeTupleId(id, tuple)
    var didMaxNonOdsChange = true

    // should always be set if there are deletions

    if !maxNonOdIndex.isInitialized then maxNonOdIndex.initNonOds(id)

    val previousViolations = mutable.Map[OrderDependency, Violation]()

    // remove violations that no longer hold. new violations will be searched in while loop below
    maxNonOdIndex.nonOdsByViolation.get(id) match
      case Some(odList) =>
        odList.toArray.foreach { (node, violation) =>
          // Determine if we should remove or update this violation
          val shouldRemove = changedAttributes match
            case Some(changed) if newTupleId.isDefined =>
              // For updates: only remove if includedCols overlaps with changedAttributes
              node.includedCols.exists(changed.contains)
            case _ => true // Normal deletion: always check for removal

          if shouldRemove then
            if !violation.holdsAfterRemoval(id) then
              maxNonOdIndex.updateViolationData(node, None)
              previousViolations(node) = violation
              logger.debug(
                s"Violation $violation of ${node.toStringWithNames} is now valid after deletion of tuple $id"
              )
          else
            // For updates where OD doesn't overlap with changed attributes:
            // update the violation to use the new tuple ID
            newTupleId.foreach { newId =>
              violation.replaceTupleId(id, newId)
              logger.debug(
                s"Updated violation of ${node.toStringWithNames}: replaced tuple $id with $newId"
              )
            }
        }

        maxNonOdIndex.nonOdsByViolation.remove(id)
        newTupleId.foreach { newId =>
          val updatedViolations = odList.filter { (node, _) =>
            changedAttributes.exists(changed =>
              !node.includedCols.exists(changed.contains)
            )
          }
          if updatedViolations.nonEmpty then
            maxNonOdIndex.nonOdsByViolation(newId) = updatedViolations
        }

      case None =>

    var invalidations: Int = 0
    var revalidations: Int = 0

    def handleRevalidationResult(
        od: OrderDependency,
        result: Option[AttributeId | Violation]
    ) = result match
      case Some(constantAttribute: AttributeId) =>
        invalidations += 1
        logger.debug(
          s"Directly revalidated OD: ${ConstantOd(od.context, constantAttribute).toStringWithNames} while checking ${od.toStringWithNames}"
        )
        // odIndex.handleRevalidation(
        //   ConstantOd(od.context, constantAttribute)
        // )
        maxNonOdIndex.handleValidation(
          ConstantOd(od.context, constantAttribute),
          id
        )
      case Some(violation: Violation) =>
        invalidations += 1
        logger.debug(
          s"Non-OD ${od.toStringWithNames} still invalid after deletion of tuple $id"
        )
        maxNonOdIndex.updateViolationData(od, Some(violation))
      case None =>
        revalidations += 1
        logger.debug(
          s"Non-OD ${od.toStringWithNames} is now valid after deletion of tuple $id"
        )
        didMaxNonOdsChange = true
        logger.debug(
          s"Revalidated OD: ${od.toStringWithNames}"
        )
        // odIndex.handleRevalidation(od)
        maxNonOdIndex.handleValidation(od, id)

    // calculate violations for all non-ODs with no currently known violation
    // If a new OD was proven valid, its maximal non-ODs may now also be valid
    // therefore we repeat until all non-ODs are proven valid or invalid
    var iteration = 0
    var lastNonOdsWithoutViolation = mutable.Set.empty[OrderDependency]
    var toCheck: mutable.Set[OrderDependency] = mutable.Set.empty

    def updateToCheck() =
      if didMaxNonOdsChange then
        toCheck = changedAttributes match
          case None => maxNonOdIndex.nonOdsWithoutViolation
          case Some(changedAttributes) =>
            maxNonOdIndex.nonOdsWithoutViolation.filter(
              _.includedCols.exists(changedAttributes.contains)
            )
        didMaxNonOdsChange = false

    while {
      didMaxNonOdsChange = true
      updateToCheck()
      toCheck.nonEmpty
    } do
      iteration += 1
      invalidations = 0
      revalidations = 0
      logger.debug(
        s"Revalidating ${toCheck.size} of ${maxNonOdIndex.nonOdsWithoutViolation.size} non-ODs after deletion of tuple $id. Iteration: $iteration"
      )
      // if less than 10% of tuples are missing, do revalidation by inserts
      if !algoConfig.skipRevalidationByInsert then
        toCheck.iterator
          // make sure OD is still invalid. Might not be the case if a Constant got revalidated in this iteration hich is more minimal than the compatible
          .filter(!odIndex.holds(_))
          .collect {
            case nonOd if previousViolations.contains(nonOd) =>
              (nonOd, previousViolations(nonOd))
          }
          .filter { (_, violation) =>
            val remainingTuples =
              currentlyContainedTuples.size - (violation.noInvalidTuplesBelow
                .getOrElse(-1) + 1)
            remainingTuples.toFloat / currentlyContainedTuples.size < 0.1 || remainingTuples < 20
          }
          .foreach { (nonOd, violation) =>
            val result = tryRevalidateByInserts(
              nonOd,
              violation.noInvalidTuplesBelow.getOrElse(-1)
            )
            if result == None then
              fullValidator.stats =
                fullValidator.stats.copy(revalidatedByInsertion =
                  fullValidator.stats.revalidatedByInsertion + 1
                )
            else
              fullValidator.stats =
                fullValidator.stats.copy(violatedByInsertion =
                  fullValidator.stats.violatedByInsertion + 1
                )

            handleRevalidationResult(
              nonOd,
              result
            )
          }

        logger.debug(
          s"invalidated ${invalidations}, revalidated ${invalidations} ODs by insertion"
        )
        updateToCheck()
      end if // insertionRevalidation

      // Validate all non-ODs grouped by context for efficiency
      val validationResults = fullValidator.odHoldsGrouped(
        toCheck,
        previousViolations,
        odIndex
      )

      for (od, result) <- validationResults do
        handleRevalidationResult(od, result)

      if lastNonOdsWithoutViolation.nonEmpty && maxNonOdIndex.nonOdsWithoutViolation == lastNonOdsWithoutViolation
      then
        logger.warn(
          s"No progress made in revalidation of non-ODs after deletion of tuple $id. Remaining non-ODs: ${maxNonOdIndex.nonOdsWithoutViolation
              .map(_.toStringWithNames)
              .mkString(", ")}"
        )
        assert(false)
      lastNonOdsWithoutViolation = toCheck.clone()

      logger.debug("Finishing iteration")
      logger.debug(
        s"invalidated ${invalidations}, revalidated ${revalidations} ODs total"
      )
      previousViolations.clear()
      finishIteration()

    end while
  end handleTupleDeletion

  def finishIteration(): Unit =
    odIndex.toRecalculateNodes.foreach { (key, node) =>
      logger.debug(
        s"Recalculating context index node for OD ${key} with ${node.removedMinOds
            .map(_.toString())
            .mkString(", ")} removed and ${node.addedMinOds
            .map(_.toString())
            .mkString(", ")} added"
      )
      node.addedMinOds.foreach { context =>
        dataIndex.registerOd(key.buildOd(context))
      }
      node.addedMinOds.clear
      node.removedMinOds.foreach { context =>
        dataIndex.deregisterOd(key.buildOd(context))
      }
      node.removedMinOds.clear
    }
    odIndex.toRecalculateNodes.clear
    // val odIndexOds = odIndex.iterator.toSet
    // val dataIndexOds = dataIndex.attributeNodes.values.flatMap {
    //  case AttributeNode(nodes, ods, attributeCounts) => ods
    // }.toSet
    // assert(
    //   odIndexOds == dataIndexOds,
    //   s"${odIndexOds -- dataIndexOds}, ${dataIndexOds -- odIndexOds}"
    // )

  def applyIncrement(
      incrementDataset: IndexedSeq[
        (RecordOperation, Option[RecordId], IndexedSeq[TupleValue])
      ],
      collectStats: Boolean,
      collectPerTupleTiming: Boolean = false
  ): (Seq[IntermediateStats], Seq[PerTupleTiming]) =
    val stats = mutable.ArrayBuffer[IntermediateStats]()
    val timings = mutable.ArrayBuffer[PerTupleTiming]()
    val startTime = System.currentTimeMillis()
    var maxMemoryUsed: Long = 0
    val numOperations = incrementDataset.size
    val sampleInterval = // last case never triggers, therefore only collected at end
      if collectStats then math.max(1, numOperations / 200)
      else numOperations + 1
    val dataIterator =
      if algoConfig.showProgressBar then
        ProgressBarIterator(incrementDataset.zipWithIndex)
      else incrementDataset.zipWithIndex
    for (tuple, index) <- dataIterator do
      // remove old ones first to ensure newly created ones are not filtered
      // due to not being minimal
      tuple match
        case (RecordOperation.Delete, recordIdOpt, tupleValue) =>
          val tupleStartTime =
            if collectPerTupleTiming then System.nanoTime() else 0L
          val id = recordIdOpt match
            case Some(recordId) =>
              val tupleId = recordIdToTupleId.getOrElse(
                recordId,
                throw new IllegalArgumentException(
                  s"Record ID $recordId not found in recordIdToTupleId map"
                )
              )
              // Clean up maps
              recordIdToTupleId.remove(recordId)
              tupleIdToRecordId.remove(tupleId)
              tupleId
            case None => getTupleId(tupleValue)
          handleTupleDeletion(tupleValue, id)
          if collectPerTupleTiming then
            val elapsed = System.nanoTime() - tupleStartTime
            timings += PerTupleTiming(
              operationId = index,
              operationType = "delete",
              tupleId = id,
              recordId = recordIdOpt,
              timeNanos = elapsed
            )
        case (RecordOperation.Update, recordIdOpt, newTupleValue) =>
          val recordId = recordIdOpt.getOrElse(
            throw new IllegalArgumentException(
              "Update operations require a record ID"
            )
          )
          val oldTupleId = recordIdToTupleId.getOrElse(
            recordId,
            throw new IllegalArgumentException(
              s"Record ID $recordId not found for update"
            )
          )

          val oldTupleValue = (0 until numAttributes).map(attr =>
            plis(attr).tupleToValues(oldTupleId)
          )

          val changedAttributes =
            if algoConfig.useEfficientUpdates then
              Some(
                BitSet.fromSpecific(
                  (0 until numAttributes).filter(attr =>
                    oldTupleValue(attr) != newTupleValue(attr)
                  )
                )
              )
            else None

          logger.debug(
            s"Updating tuple $oldTupleId (record $recordId): changed attributes ${changedAttributes
                .map(_.mkString(","))
                .getOrElse("all (efficient updates disabled)")}"
          )

          // Delete old tuple, then insert new one
          val deleteStartTime =
            if collectPerTupleTiming then System.nanoTime() else 0L
          handleTupleDeletion(
            oldTupleValue.toIndexedSeq,
            oldTupleId,
            changedAttributes,
            Some(currentHighestId)
          )
          if collectPerTupleTiming then
            val deleteElapsed = System.nanoTime() - deleteStartTime
            timings += PerTupleTiming(
              operationId = index,
              operationType = "update_delete",
              tupleId = oldTupleId,
              recordId = Some(recordId),
              timeNanos = deleteElapsed
            )

          val insertStartTime =
            if collectPerTupleTiming then System.nanoTime() else 0L
          handleTupleInsertion(
            newTupleValue,
            currentHighestId,
            changedAttributes
          )
          if collectPerTupleTiming then
            val insertElapsed = System.nanoTime() - insertStartTime
            timings += PerTupleTiming(
              operationId = index,
              operationType = "update_insert",
              tupleId = currentHighestId,
              recordId = Some(recordId),
              timeNanos = insertElapsed
            )

          // Update maps
          tupleIdToRecordId.remove(oldTupleId)
          tupleIdToRecordId(currentHighestId) = recordId
          recordIdToTupleId(recordId) = currentHighestId

          currentHighestId += 1
        case (RecordOperation.Insert, recordIdOpt, tupleValue) =>
          val tupleStartTime =
            if collectPerTupleTiming then System.nanoTime() else 0L
          handleTupleInsertion(tupleValue, currentHighestId)
          if collectPerTupleTiming then
            val elapsed = System.nanoTime() - tupleStartTime
            timings += PerTupleTiming(
              operationId = index,
              operationType = "insert",
              tupleId = currentHighestId,
              recordId = recordIdOpt,
              timeNanos = elapsed
            )
          // Update maps if record ID is provided
          recordIdOpt.foreach { recordId =>
            tupleIdToRecordId(currentHighestId) = recordId
            recordIdToTupleId(recordId) = currentHighestId
          }
          currentHighestId += 1
      val rt = Runtime.getRuntime
      val currentMemory = rt.totalMemory - rt.freeMemory
      maxMemoryUsed = math.max(maxMemoryUsed, currentMemory)

      // Collect stats at regular intervals and at the last operation
      if ((index + 1) % sampleInterval == 0) || index == numOperations - 1
      then
        val (constants, compatibles) = odIndex.sizes
        val (nonConstants, nonCompatibles) = maxNonOdIndex.sizes
        val currentStats = IntermediateStats(
          currentHighestTupleId = currentHighestId - 1,
          lastOperationId = index,
          currentConstantOds = constants,
          currentConstantNonOds = nonConstants,
          currentComptibleOds = compatibles,
          currentComptibleNonOds = nonCompatibles,
          timeElapsedMs = (System.currentTimeMillis() - startTime).toInt,
          odValidatorStats = fullValidator.stats,
          numCacheEvictions = numCacheEvictions,
          maxMemoryUsedBytes = maxMemoryUsed,
          currentMemoryUsedBytes = currentMemory,
          contextIndexContexts = dataIndex.numContexts,
          numViolationsTracked = maxNonOdIndex.nonOdsWithViolation.size
        )
        stats += currentStats

      // compareWithStaticAlgorithm()

      if (currentMemory.toFloat / Runtime.getRuntime.maxMemory) > 0.75
      then
        logger.warn(
          s"Memory usage is above 75% (${currentMemory / 1024 / 1024} MB of ${Runtime.getRuntime.maxMemory / 1024 / 1024} MB) after event $index. Evicting ContextNodes."
        )
        numCacheEvictions += 1
        // assert(numCacheEvictions < 1000)
        if algoConfig.partialCacheEviction then
          val dropped = dataIndex.evictColdContextNodes()
          System.gc()
          // A sweep only reclaims the cold nodes. If that was not enough, fall
          // back to clearing everything rather than sweeping in a loop.
          if ((rt.totalMemory - rt.freeMemory).toFloat / rt.maxMemory) > 0.75
          then
            logger.warn(s"Sweep dropped $dropped nodes, still full: clearing")
            numFullCacheEvictions += 1
            dataIndex.removeAllContextNodes()
            System.gc()
        else
          numFullCacheEvictions += 1
          dataIndex.removeAllContextNodes()
          System.gc()
        val currentMemoryAfter = rt.totalMemory - rt.freeMemory

        logger.warn(s"After GC trigger: ${currentMemoryAfter / 1024 / 1024}")

    end for
    // compareWithStaticAlgorithm()

    (stats.toSeq, timings.toSeq)

  // def compareWithStaticAlgorithm(): Unit =
  //   val data = currentlyContainedTuples.iterator.toIndexedSeq.map { id =>
  //     (0 until numAttributes).map(plis(_).tupleToValues(id))
  //   }
  //   val timelimit = 30000000
  //   val results: java.util.Set[CanonicalOD] = new HyOD(timelimit).execute(
  //     data.map(_.map(Int.box).asJava).asJava,
  //     attributeNamesMap.toList.asJava
  //   )
  //   logger.debug(s"Data size: ${data.size}")

  //   val filePath = "hyod_temp_data.csv"
  //   import java.nio.file.{Files, Path}
  //   val finalDataLines = scala.collection.mutable.ArrayBuffer[String]()
  //   finalDataLines +=
  //     attributeNamesMap.mkString(",")
  //   finalDataLines ++=
  //     data.map { _.mkString(",") }
  //   Files.write(Path.of(filePath), finalDataLines.asJava)

  //   val ods = results.asScala.map { od =>
  //     if od.left == null then
  //       ConstantOd(
  //         BitSet.fromSpecific(od.context.asScala.map(_.toInt)),
  //         od.right.toInt
  //       )
  //     else
  //       CompatibleOd(
  //         BitSet.fromSpecific(od.context.asScala.map(_.toInt)),
  //         od.left.attribute.toInt,
  //         od.right.toInt,
  //         od.left.operator == Operator.LESSEQUAL
  //       )
  //   }.toSet

  //   val discovered = odIndex.iterator.toSet

  //   logger.debug(
  //     s"Incremental algorithm found ${discovered.size} ODs."
  //   )
  //   val missingOds = ods -- discovered
  //   val unexpectedOds = discovered -- ods
  //   logger.info(
  //     s"Static algorithm found ${ods.size} (${results
  //         .size()})  ODs, we found ${discovered.size} ODs. ${missingOds.size} are missing, ${unexpectedOds.size} are unexpected."
  //   )

  //   if missingOds.nonEmpty || unexpectedOds.nonEmpty then
  //     logger.debug(
  //       s"Missing ODs: ${missingOds.map(_.toStringWithNames).mkString(", ")}"
  //     )
  //     logger.debug(
  //       s"Unexpected ODs: ${unexpectedOds.map(_.toStringWithNames).mkString(", ")}"
  //     )
  //     assert(false)

end IncrementalOdDiscovery
