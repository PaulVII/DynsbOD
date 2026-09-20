package structures
import scala.collection.immutable.BitSet
import scala.collection.mutable

import com.typesafe.scalalogging.StrictLogging
import utils.ones
import utils.fastSubsetOf
import utils.SizeBucketedBitSets
import scala.collection.mutable.ArrayBuffer
import config.AlgoConfig
import config.DeletionMode

def printOds(bss: Seq[BitSet], key: OdKey)(using
    attributeNamesMap: AttributeIndexed[String]
): String =
  bss
    .map(bs => key.buildOd(bs).toStringWithNames)
    .mkString("[", ", ", "]")

class MaxNonOdIndex(
    val numAttributes: Int,
    val odIndex: OdIndex,
    val currentlyIncludedTupleIds: ContainedTuplesSet,
    val canHaveDeletions: Boolean
)(using val attributeNamesMap: AttributeIndexed[String], algoConfig: AlgoConfig)
    extends StrictLogging:
  // Map from RHS to all LHS that determine it
  val maxNonOds =
    mutable.Map[OdKey, SizeBucketedBitSets]()

  val needDeletionData = canHaveDeletions || algoConfig.deletionMode == Some(
    DeletionMode.AfterInsert
  ) || algoConfig.deletionMode == Some(DeletionMode.DeleteOnly)

  def isInitialized: Boolean =
    maxNonOds.nonEmpty

  val nonOdsByViolation =
    mutable.Map[TupleId, mutable.ArrayBuffer[(OrderDependency, Violation)]]()

  // currently maximal non-ODS without known violation data
  val nonOdsWithoutViolation =
    mutable.Set[OrderDependency]()
  // this contains all known violation data
  // some of it may be outdated, some of the ODs may no longer be maximal non-ODs
  val nonOdsWithViolation = mutable.Map[OrderDependency, Violation]()

  def removeFromTracking(
      od: OrderDependency
  ): Unit =
    nonOdsWithViolation.get(od) match
      case Some(violation) =>
        // logger.debug(
        //   s"Removing non-OD ${od.toStringWithNames} from tracking with violation $violation"
        // )
        for tupleId <- violation.containedTupleIds do
          nonOdsByViolation.get(tupleId) match
            case Some(list) =>
              list --= list.filter(_._1 == od)
            case None => ()
      case None =>
        nonOdsWithoutViolation -= od
      // logger.debug(
      //   s"Removing non-OD ${od.toStringWithNames} from tracking without violation"
      // )

  def addToTracking(
      od: OrderDependency
  ): Unit =
    nonOdsWithViolation.get(od) match
      case Some(violation) =>
        for tupleId <- violation.containedTupleIds do
          val list = nonOdsByViolation.getOrElseUpdate(
            tupleId,
            mutable.ArrayBuffer.empty[(OrderDependency, Violation)]
          )
          list.append((od, violation))
      case None =>
        nonOdsWithoutViolation += od

  def isTracked(od: OrderDependency): Boolean =
    if nonOdsWithoutViolation.contains(od) then true
    else
      val violation = nonOdsWithViolation.get(od)
      violation match
        case Some(v) =>
          v.containedTupleIds.exists(tId =>
            nonOdsByViolation.contains(tId) && nonOdsByViolation(tId).contains(
              od
            )
          )
        case None => false

  def updateViolationData(
      od: OrderDependency,
      violation: Option[Violation],
      updateTracking: Boolean = true
  ): Unit =
    // assert(updateTracking || !isTracked(od))
    // logger.debug(
    //   s"Updating violation data for non-OD ${od.toStringWithNames} to ${violation
    //       .map(_.toString)
    //       .getOrElse("no violation")}"
    // )
    if updateTracking then removeFromTracking(od)
    violation match
      case Some(v) => nonOdsWithViolation(od) = v
      case None    => nonOdsWithViolation.remove(od)
    if updateTracking then addToTracking(od)

  def verifyMaxNonOds(
      od: OrderDependency,
      keys: Seq[OdKey],
      odsBeforeUpdate: Map[OdKey, Seq[BitSet]],
      operationName: String,
      argument: String
  ): Unit =
    for key <- keys do
      val odsAfterUpdate = calculateNonOds(key)
      for nonOd <- maxNonOds(key).iterator do
        assert(
          !odIndex.holds(key.buildOd(nonOd)),
          s"OD ${key.buildOd(nonOd).toStringWithNames} should not be in maximal non-ODs for key ${key.toStringWith} as it is valid. Current maximal non-ODs: ${printOds(
              maxNonOds(key).toSeq,
              key
            )}. Ground-up calculation: ${printOds(
              odsAfterUpdate.toSeq,
              key
            )}. Ods before update: ${printOds(
              odsBeforeUpdate(key).toSeq,
              key
            )}. Operation: $operationName"
        )
      val currentSet = maxNonOds(key).toSeq.toSet
      val afterSet = odsAfterUpdate.toSeq.toSet
      if currentSet != afterSet then
        val beforeSet = odsBeforeUpdate(key).toSet
        val missing = afterSet -- currentSet
        val unexpected = currentSet -- afterSet
        val shouldBeAdded = afterSet -- beforeSet
        val shouldBeRemoved = beforeSet -- afterSet
        logger.error(
          s"After handling $operationName of non-OD ${od.toStringWithNames} ($od), maximal non-ODs for key ${key.toStringWith} do not match ground-up calculation.\nCurrent: ${printOds(
              maxNonOds(key).toSeq,
              key
            )}\nGround-up: ${printOds(odsAfterUpdate.toSeq, key)}\n. Ods before update: ${printOds(odsBeforeUpdate(key).toSeq, key)}"
        )
        logger.error(
          s"Missing ODs: ${printOds(missing.toSeq, key)}; Unexpected ODs: ${printOds(unexpected.toSeq, key)}"
        )
        logger.error(
          s"Should be added: ${printOds(shouldBeAdded.toSeq, key)}; Should be removed: ${printOds(shouldBeRemoved.toSeq, key)}"
        )
        assert(false)

  def handleValidation(
      od: OrderDependency,
      deletedTupleId: TupleId,
      isCalledInternally: Boolean = false
  ): Unit =
    logger.debug(
      s"Handling (re)validation of non-OD ${od.toStringWithNames}"
    )
    var keysToInsertInto = Seq(OdKey(od))
    od match
      case ConstantOd(_, attr) =>
        keysToInsertInto = attr
          .allDependentCompatibles(numAttributes, exclude = od.context)
          .toSeq ++ keysToInsertInto
      case _ => ()
    odIndex.handleRevalidation(od)

    for key <- keysToInsertInto do
      val (removedNonOds, addedNonOds) =
        handleAddedOd(od.context, maxNonOds(key))
      logger.debug(
        s"After revalidation of OD ${od.toStringWithNames} in key ${key.toStringWith}, \n added maximal non-ODs:${printOds(addedNonOds.toSeq, key)}, \nremoved maximal non-ODs: ${printOds(
            removedNonOds.toSeq,
            key
          )}"
      )
      // assert(!addedNonOds.exists(nonOd => odIndex.holds(key.buildOd(nonOd))))
      updateViolationTrackingFor(
        removedNonOds,
        addedNonOds,
        key,
        deletedTupleId
      )

    // if isCalledInternally then
    //   assert(!nonOdsWithoutViolation.exists(odIndex.holds))
    //   val odsBeforeUpdate =
    //     keysToInsertInto.map(key => (key, calculateNonOds(key))).toMap
    //   odIndex.handleRevalidation(od)
    //   verifyMaxNonOds(
    //     od,
    //     keysToInsertInto,
    //     odsBeforeUpdate,
    //     "revalidation",
    //     od.toStringWithNames
    //   )

  def handleInvalidation(
      nonOd: OrderDependency,
      newMinimalOds: Seq[OrderDependency],
      violation: Violation
  ): Unit =
    if needDeletionData then updateViolationData(nonOd, Some(violation), false)
    logger.debug(
      s"Handling invalidation of non-OD ${nonOd.toStringWithNames} with new minimal ODs: ${newMinimalOds
          .map(_.toStringWithNames)
          .mkString(", ")}"
    )
    odIndex.handleViolation(nonOd, newMinimalOds)
    if isInitialized then
      val keysToUpdate = ArrayBuffer(OdKey(nonOd))
      nonOd match
        case ConstantOd(_, attr) =>
          keysToUpdate ++= attr
            .allDependentCompatibles(numAttributes, exclude = nonOd.context)
            .filter(key => !newMinimalOds.contains(key.buildOd(nonOd.context)))
        case _ => ()
      for key <- keysToUpdate do
        // val newNonOds = calculateNonOds(key)
        // val odsBefore = mutable.Set.from(maxNonOds(key))
        val minOdNode = odIndex.odNodes(key)
        val includedAttrs = key.includedAttributes.to(BitSet)
        val minOds = minOdNode.iterateMinOds ++ minOdNode.propagateOds
          .flatMap(
            // _.iterateMinOds
              _.iterateMinOds.filter(bs => (bs & includedAttrs).isEmpty)
          )
        val (removedOds, addedOds) = handleInvalidationIncremental(
          nonOd.context,
          maxNonOds(key),
          minOds,
          key
        )
        // assert(nonOdsIncremental == newNonOds)
        updateViolationTrackingFor(
          removedOds,
          addedOds,
          key,
          -1
        )

  /** @return
    *   the maximal non-ODs this removed from and added to `nonOds`
    */
  def handleInvalidationIncremental(
      newNonOd: BitSet,
      nonOds: SizeBucketedBitSets,
      ods: Iterator[BitSet],
      key: OdKey
  ): (mutable.ArrayBuffer[BitSet], mutable.ArrayBuffer[BitSet]) =
    val allAttributes = (0 until numAttributes).toSet -- key.includedAttributes
    // Sort by size ascending, then incrementally keep only minimal hyperedges.
    // For each candidate, we only check against already-accepted (smaller/equal) hyperedges.
    // distinct removes duplicates before sorting (cheaper than toSet.toSeq)
    val mustNotContain = ods
      .map(_ -- newNonOd)

    val minHittingSets = MMCS.enumerateMinimalHittingSets(
      Hypergraph.minimal(
        allAttributes -- newNonOd,
        mustNotContain
      )
    )
    val newNonOds =
      minHittingSets.map(allAttributes -- _).map(BitSet.fromSpecific)

    // Report the delta directly instead of letting the caller diff a snapshot
    // of the whole collection taken before and after.
    val added = mutable.ArrayBuffer[BitSet]()
    val removed = mutable.ArrayBuffer[BitSet]()
    for newNonOd <- newNonOds do
      val wasInserted = nonOds.add(newNonOd)
      if wasInserted then
        added += newNonOd
        for gone <- nonOds.removeSubsetsOf(newNonOd) do
          // a context added earlier in this same loop and dropped again nets out
          val addedIdx = added.indexOf(gone)
          if addedIdx >= 0 then added.remove(addedIdx) else removed += gone
    (removed, added)

  def handleAddedOd(
      newOd: BitSet,
      nonOds: SizeBucketedBitSets
  ): (mutable.ArrayBuffer[BitSet], mutable.ArrayBuffer[BitSet]) =
    val removedOds = mutable.ArrayBuffer[BitSet]()
    val addedOds = mutable.ArrayBuffer[BitSet]()
    // logger.debug(
    //   s"addToOdGroup: Adding OD ${newOdKey.buildOd(newOd).toStringWithNames} to maximal non-ODs"
    // )

    // Find violated non-ODs using bucketed superset search
    val violatedNonOds = nonOds.findSupersetsOf(newOd)
    for bs <- violatedNonOds do
      // logger.debug(
      //   s"addToOdGroup: Removing non-OD ${newOdKey.buildOd(bs).toStringWithNames}"
      // )
      nonOds.remove(bs)
      removedOds += bs
      for attr <- newOd do
        val candidate = bs - attr
        // we do not account for constants not being maximal by a compatible here, as this
        // is difficult and has no real advantage
        // Check if candidate has a superset already in nonOds (would make it non-maximal)
        val isMaximal = !nonOds.hasSupersetOf(candidate)
        if isMaximal then
          // logger.debug(
          //   s"addToOdGroup: Adding newly constructed non-OD ${newOdKey.buildOd(candidate).toStringWithNames}"
          // )
          addedOds += candidate
          nonOds += candidate
    (removedOds, addedOds)

  def updateViolationTrackingFor(
      removedOds: Iterable[BitSet],
      addedOds: Iterable[BitSet],
      key: OdKey,
      deletedTupleId: TupleId
  ): Unit =
    for od <- removedOds.map(key.buildOd) do removeFromTracking(od)
    for od <- addedOds.map(key.buildOd) do
      nonOdsWithViolation.get(od) match
        // only add if violation still holds. The contained tuples could have been deleted
        // while the violation was not actively being checked.
        case Some(violation)
            if !violation.holdsAfterReactivation(
              currentlyIncludedTupleIds
            ) =>
          // logger.debug(
          //   s"Violation $violation of  ${od.toStringWithNames} no longer holds"
          // )
          updateViolationData(od, None)
        case _ =>
          addToTracking(od)
    // assert(
    //  nonOdsWithoutViolation.forall(od =>
    //    maxNonOds(OdKey(od)).contains(od.context)
    //  )
    // )

  /** @param key
    *   the key for which to recalculate maximal non-ODs
    * @param minOds
    *   node that contains current valid minimal ODs
    * @return
    *   the calculated maximal non-ODs for the key
    */
  def calculateNonOds(
      key: OdKey
  ): SizeBucketedBitSets =
    val minOds = odIndex.getOrCreateOdNode(key)
    val allAttrs = BitSet.ones(numAttributes)
    val newNonOds = SizeBucketedBitSets(numAttributes)
    newNonOds += (allAttrs -- key.includedAttributes)

    // add both all ODs for this key, and all Constants that prevent minimality of some non-ODs
    val toBeAdded = (minOds.iterateMinOds ++ minOds.propagateOds.flatMap(
      _.iterateMinOds
    )).toSet

    toBeAdded.foreach(handleAddedOd(_, newNonOds))

    logger.debug(
      s"Calculated maximal non-ODs for key ${key.toStringWith}: ${newNonOds.toSeq
          .map(v => key.buildOd(v).toStringWithNames)
          .mkString(", ")}"
    )

    logger.debug(
      s"current minimal ODs for key ${key.toStringWith}: ${minOds.iterateMinOds
          .map(od => od.toStringWithNames)
          .mkString(", ")}"
    )

    newNonOds

  def initNonOds(deletedTupleId: TupleId): Unit =
    val allAttrs = (0 until numAttributes)

    val keys: IndexedSeq[OdKey] = (
      // for compatibles compatibles
      for
        x <- allAttrs
        y <- allAttrs
        if x < y
        dir <- Seq(true, false)
      yield CompatibleKey(x, y, dir)
    ) ++
      // for constants
      allAttrs
    // calculate constants
    keys.foreach(key =>
      maxNonOds(key) = calculateNonOds(key)
      logger.debug(
        s"Initializing non-ODS for $key to ${maxNonOds(key)} based on minOds ${odIndex.getOrCreateOdNode(key).iterateMinOds.toSeq}"
      )
      updateViolationTrackingFor(
        Seq.empty[BitSet],
        maxNonOds(key).toSeq,
        key,
        deletedTupleId
      )
    )

  def sizes: (Int, Int) =
    if isInitialized then
      val (constants, compatibles) = maxNonOds.partitionMap {
        case (key, node) if key.isInstanceOf[Int] => Left(node.size)
        case (key, node)                          => Right(node.size)
      }
      (constants.sum, compatibles.sum)
    else (0, 0)

    // assert(!nonOdsWithoutViolation.exists(odIndex.holds))
