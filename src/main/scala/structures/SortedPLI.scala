package structures
import scala.collection.mutable

import org.roaringbitmap.RoaringBitmap

class SortedPli(val attribute: AttributeId):
  val tupleToValues = mutable.Map[TupleId, TupleValue]()
  // treemap for fast predecessor and successor
  val valueToTuples =
    mutable.TreeMap[TupleValue, RoaringBitmap]()

  def insert(tupleId: TupleId, value: TupleValue): Unit =
    tupleToValues(tupleId) = value
    val bitmap = valueToTuples.getOrElseUpdate(value, new RoaringBitmap())
    bitmap.add(tupleId)

  def remove(tupleId: TupleId): Unit =
    tupleToValues.remove(tupleId) match
      case Some(value) =>
        val tuples = valueToTuples(value)
        tuples.remove(tupleId)
        if tuples.isEmpty then valueToTuples.remove(value)
      case None =>

  def size: Int = tupleToValues.size

extension (plis: Seq[SortedPli])
  def addTuples(data: Seq[Seq[TupleValue]], firstId: TupleId): Unit =
    for
      (tuple, index) <- data.zipWithIndex
      (pli, value) <- plis.zip(tuple)
    do pli.insert(firstId + index, value)
  def removeTuples(tupleIds: Seq[TupleId]): Unit =
    for
      tupleId <- tupleIds
      pli <- plis
    do pli.remove(tupleId)

def buildPliSeq(
    data: Seq[(RecordOperation, Option[RecordId], Seq[TupleValue])],
    numAttributes: Int
): IndexedSeq[SortedPli] =
  val plis = (0 until numAttributes).map(new SortedPli(_))
  plis.addTuples(data.map(_._3), 0)
  plis
