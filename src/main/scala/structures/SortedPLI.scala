package structures
import scala.collection.mutable

import org.roaringbitmap.RoaringBitmap

/** Maps tuple ids to the value of one attribute.
  *
  * Tuple ids are handed out consecutively, so a direct array beats a hash map
  * on both counts that matter here: it is read in the innermost loop of every
  * validation path, and it holds one entry per tuple per attribute, where a
  * boxed `Map[Int, Int]` spends roughly 40 bytes on what fits in four.
  */
final class TupleValues:
  private var values: Array[TupleValue] = new Array[TupleValue](1024)
  // one bit per tuple id, so a removed id is distinguishable from a stale slot
  private var present: Array[Long] = new Array[Long](1024 >>> 6)
  private var count: Int = 0

  private def ensureCapacity(id: TupleId): Unit =
    if id >= values.length then
      var newSize = values.length
      while id >= newSize do newSize <<= 1
      values = java.util.Arrays.copyOf(values, newSize)
      present = java.util.Arrays.copyOf(present, newSize >>> 6)

  inline def apply(id: TupleId): TupleValue = values(id)

  def contains(id: TupleId): Boolean =
    id < values.length && (present(id >>> 6) & (1L << id)) != 0L

  def update(id: TupleId, value: TupleValue): Unit =
    ensureCapacity(id)
    if (present(id >>> 6) & (1L << id)) == 0L then
      present(id >>> 6) |= 1L << id
      count += 1
    values(id) = value

  /** Clears `id`; returns whether it was present. */
  def remove(id: TupleId): Boolean =
    if !contains(id) then false
    else
      present(id >>> 6) &= ~(1L << id)
      count -= 1
      true

  def size: Int = count

class SortedPli(val attribute: AttributeId):
  val tupleToValues = TupleValues()
  // treemap for fast predecessor and successor
  val valueToTuples =
    mutable.TreeMap[TupleValue, RoaringBitmap]()

  def insert(tupleId: TupleId, value: TupleValue): Unit =
    tupleToValues(tupleId) = value
    val bitmap = valueToTuples.getOrElseUpdate(value, new RoaringBitmap())
    bitmap.add(tupleId)

  def remove(tupleId: TupleId): Unit =
    if tupleToValues.contains(tupleId) then
      val value = tupleToValues(tupleId)
      tupleToValues.remove(tupleId)
      val tuples = valueToTuples(value)
      tuples.remove(tupleId)
      if tuples.isEmpty then valueToTuples.remove(value)

  def size: Int = tupleToValues.size

extension (plis: Seq[SortedPli])
  /** Single-tuple variants, used on the per-tuple hot path where the wrapping
    * and zipping of the bulk versions would dominate the actual work.
    */
  def addTuple(tuple: Seq[TupleValue], id: TupleId): Unit =
    var attr = 0
    while attr < plis.length do
      plis(attr).insert(id, tuple(attr))
      attr += 1

  def removeTuple(id: TupleId): Unit =
    var attr = 0
    while attr < plis.length do
      plis(attr).remove(id)
      attr += 1

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
