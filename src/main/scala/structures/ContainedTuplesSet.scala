package structures

import org.roaringbitmap.RoaringBitmap

/** The set of tuple IDs currently present in the dataset.
  *
  * Backed by a Roaring bitmap, which answers every query this algorithm needs —
  * membership, cardinality, rank and ordered iteration from a lower bound — and
  * additionally *is* the bitmap that full OD validation partitions, so no
  * per-validation copy has to be built.
  */
class ContainedTuplesSet:
  private val ids = new RoaringBitmap()

  def add(id: Int): Unit = ids.add(id)

  def remove(id: Int): Unit = ids.remove(id)

  def contains(id: Int): Boolean = ids.contains(id)

  /** Number of contained IDs strictly greater than `id` */
  def countGreaterThan(id: Int): Int =
    // rank counts IDs <= id
    ids.getCardinality - ids.rank(id)

  /** Iterator over all contained IDs strictly greater than `id` */
  def iteratorGreaterThan(id: Int): Iterator[Int] =
    val it = ids.getIntIterator
    it.advanceIfNeeded(id + 1)
    new Iterator[Int]:
      def hasNext: Boolean = it.hasNext
      def next(): Int = it.next()

  def iterator: Iterator[Int] =
    val it = ids.getIntIterator
    new Iterator[Int]:
      def hasNext: Boolean = it.hasNext
      def next(): Int = it.next()

  def size: Int = ids.getCardinality

  /** The live bitmap of contained tuple IDs. Read-only: callers must not
    * mutate it, as it is the set itself rather than a copy.
    */
  def asBitmap: RoaringBitmap = ids
