package structures

import scala.collection.mutable

trait Violation:

  def containedTupleIds: Iterator[TupleId]

  /** Removes the given tupleId from the violation and returns whether the
    *
    * violation still holds thereafter
    *
    * @param tupleId
    *
    * the tuple id to remove
    *
    * @return
    *
    * true if the violation still holds after removal, false otherwise
    */

  def holdsAfterRemoval(tupleId: TupleId): Boolean
  def holdsAfterReactivation(
      currentlyContainedTuples: ContainedTuplesSet
  ): Boolean

  /** Replaces oldTupleId with newTupleId in the violation
    *
    * @param oldTupleId
    *   the tuple id to replace
    * @param newTupleId
    *   the new tuple id
    */
  def replaceTupleId(oldTupleId: TupleId, newTupleId: TupleId): Unit

  val noInvalidTuplesBelow: Option[TupleId]

case class SimpleViolation(
    var tuples: (TupleId, TupleId),
    val noInvalidTuplesBelow: Option[TupleId]
) extends Violation:

  override def containedTupleIds: Iterator[TupleId] =
    Iterator(tuples._1, tuples._2)

  override def holdsAfterRemoval(tupleId: TupleId): Boolean = false

  override def holdsAfterReactivation(
      currentlyContainedTuples: ContainedTuplesSet
  ): Boolean =
    currentlyContainedTuples.contains(tuples._1) &&
      currentlyContainedTuples.contains(tuples._2)

  override def replaceTupleId(oldTupleId: TupleId, newTupleId: TupleId): Unit =
    tuples = tuples match
      case (t1, t2) if t1 == oldTupleId => (newTupleId, t2)
      case (t1, t2) if t2 == oldTupleId => (t1, newTupleId)
      case _                            => tuples

// Violation that is found when a new tuple is added that invalidates a previously valid OD

case class InvalidationViolation(
    var newTuple: TupleId,
    val existingTuples: mutable.Set[TupleId]
) extends Violation:

  val noInvalidTuplesBelow: Option[TupleId] = Some(newTuple)

  override def containedTupleIds: Iterator[TupleId] =
    existingTuples.iterator ++ Iterator.single(newTuple)

  override def holdsAfterRemoval(tupleId: TupleId): Boolean =
    if tupleId == newTuple then false
    else
      existingTuples -= tupleId
      existingTuples.nonEmpty

  override def holdsAfterReactivation(
      currentlyContainedTuples: ContainedTuplesSet
  ): Boolean =
    currentlyContainedTuples.contains(newTuple) &&
      existingTuples.exists(currentlyContainedTuples.contains)

  override def replaceTupleId(oldTupleId: TupleId, newTupleId: TupleId): Unit =
    if newTuple == oldTupleId then newTuple = newTupleId
    else if existingTuples.contains(oldTupleId) then
      existingTuples -= oldTupleId
      existingTuples += newTupleId
