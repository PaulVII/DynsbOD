package utils

import scala.collection.immutable.BitSet
import scala.collection.mutable

extension (b: BitSet.type)
  def ones(numAttributes: Int): BitSet =
    b(0 until numAttributes*)

/** Number of 64-bit words needed to hold bits `0 until numBits`. */
inline def wordsFor(numBits: Int): Int = math.max(1, (numBits + 63) >>> 6)

/** Copies the bits of `bs` into the first `stride` words of `out`.
  *
  * `BitSet.toBitMask` builds a fresh `Array[Long]` on every call, so callers
  * that compare one set against many should do this once per query instead of
  * once per comparison.
  */
def writeWords(bs: BitSet, out: Array[Long], stride: Int): Unit =
  val mask = bs.toBitMask
  val shared = math.min(stride, mask.length)
  System.arraycopy(mask, 0, out, 0, shared)
  var w = shared
  while w < stride do
    out(w) = 0L
    w += 1
  // Guard the class invariant: no element may lie outside the declared universe
  while w < mask.length do
    require(mask(w) == 0L, s"BitSet $bs exceeds the ${stride * 64}-bit universe")
    w += 1

/** Order-independent hash over the words of a set, so we never fall back to
  * `MurmurHash3.unorderedHash`, which walks (and boxes) every element.
  */
private def hashWords(words: Array[Long], offset: Int, stride: Int): Int =
  var h = 0
  var w = 0
  while w < stride do
    val v = words(offset + w)
    h = h * 31 + (v ^ (v >>> 32)).toInt
    w += 1
  h

/** Fast subset check avoiding boxing overhead. Uses the underlying Long array
  * directly via BitSet.toBitMask.
  */
extension (a: BitSet)
  inline def fastSubsetOf(b: BitSet): Boolean =
    val aMask = a.toBitMask
    val bMask = b.toBitMask
    var i = 0
    var isSubset = true
    // Check all words in a; any bit in a not in b means not a subset
    while i < aMask.length && isSubset do
      val aWord = aMask(i)
      val bWord = if i < bMask.length then bMask(i) else 0L
      if (aWord & bWord) != aWord then isSubset = false
      i += 1
    isSubset

/** One popcount bucket of a [[SizeBucketedBitSets]].
  *
  * The sets are held twice: as `BitSet`s, which is what callers get back, and
  * as a flat `Array[Long]` with a fixed `stride` of words per set. All scans
  * run over the flat array, so a subset test is a handful of primitive `and`s
  * with no allocation, no indirection and no boxing — for universes of up to 64
  * attributes (`stride == 1`) it is a single machine word comparison.
  */
private final class WordBucket(val stride: Int):
  // Start empty: an index holds one bucket per possible popcount, and on wide
  // relations most of them never receive an element.
  private var sets: Array[BitSet] = Array.empty
  private var words: Array[Long] = Array.emptyLongArray
  private var hashes: Array[Int] = Array.emptyIntArray
  private var count: Int = 0

  inline def size: Int = count
  inline def apply(i: Int): BitSet = sets(i)

  private def grow(): Unit =
    val newCap = if sets.length == 0 then 4 else sets.length * 2
    sets = java.util.Arrays.copyOf(sets, newCap)
    words = java.util.Arrays.copyOf(words, newCap * stride)
    hashes = java.util.Arrays.copyOf(hashes, newCap)

  /** Index of the set equal to `q`, or -1. */
  def indexOf(q: Array[Long]): Int =
    val h = hashWords(q, 0, stride)
    var i = 0
    while i < count do
      if hashes(i) == h && equalsAt(i, q) then return i
      i += 1
    -1

  private def equalsAt(i: Int, q: Array[Long]): Boolean =
    val base = i * stride
    var w = 0
    while w < stride do
      if words(base + w) != q(w) then return false
      w += 1
    true

  /** Whether the set stored at `i` is a superset of `q`. */
  private def isSupersetAt(i: Int, q: Array[Long]): Boolean =
    val base = i * stride
    var w = 0
    while w < stride do
      val qw = q(w)
      if (words(base + w) & qw) != qw then return false
      w += 1
    true

  /** Whether the set stored at `i` is a subset of `q`. */
  private def isSubsetAt(i: Int, q: Array[Long]): Boolean =
    val base = i * stride
    var w = 0
    while w < stride do
      val sw = words(base + w)
      if (sw & q(w)) != sw then return false
      w += 1
    true

  /** Appends `bs` (whose words are `q`); returns false if already present. */
  def add(bs: BitSet, q: Array[Long]): Boolean =
    if indexOf(q) >= 0 then false
    else
      if count == sets.length then grow()
      sets(count) = bs
      System.arraycopy(q, 0, words, count * stride, stride)
      hashes(count) = hashWords(q, 0, stride)
      count += 1
      true

  /** Removes by index, swapping the last entry into the hole. */
  def removeAt(i: Int): Unit =
    val last = count - 1
    if i != last then
      sets(i) = sets(last)
      System.arraycopy(words, last * stride, words, i * stride, stride)
      hashes(i) = hashes(last)
    sets(last) = null
    count = last

  def remove(q: Array[Long]): Boolean =
    val i = indexOf(q)
    if i < 0 then false
    else
      removeAt(i)
      true

  def hasSupersetOf(q: Array[Long]): Boolean =
    var i = 0
    while i < count do
      if isSupersetAt(i, q) then return true
      i += 1
    false

  def hasSubsetOf(q: Array[Long]): Boolean =
    var i = 0
    while i < count do
      if isSubsetAt(i, q) then return true
      i += 1
    false

  def collectSupersetsOf(q: Array[Long], out: mutable.ArrayBuffer[BitSet]): Unit =
    var i = 0
    while i < count do
      if isSupersetAt(i, q) then out += sets(i)
      i += 1

  def collectSubsetsOf(q: Array[Long], out: mutable.ArrayBuffer[BitSet]): Unit =
    var i = 0
    while i < count do
      if isSubsetAt(i, q) then out += sets(i)
      i += 1

  /** Removes and collects every stored superset of `q`. */
  def removeSupersetsOf(q: Array[Long], out: mutable.ArrayBuffer[BitSet]): Int =
    var removed = 0
    var i = 0
    while i < count do
      if isSupersetAt(i, q) then
        out += sets(i)
        removeAt(i) // swaps the last entry into i, so do not advance
        removed += 1
      else i += 1
    removed

  /** Removes and collects every stored subset of `q`. */
  def removeSubsetsOf(q: Array[Long], out: mutable.ArrayBuffer[BitSet]): Int =
    var removed = 0
    var i = 0
    while i < count do
      if isSubsetAt(i, q) then
        out += sets(i)
        removeAt(i)
        removed += 1
      else i += 1
    removed

  def clear(): Unit =
    java.util.Arrays.fill(sets.asInstanceOf[Array[Object]], null)
    count = 0

  def exists(p: BitSet => Boolean): Boolean =
    var i = 0
    while i < count do
      if p(sets(i)) then return true
      i += 1
    false

  def foreach(f: BitSet => Unit): Unit =
    var i = 0
    while i < count do
      f(sets(i))
      i += 1

  def iterator: Iterator[BitSet] = sets.iterator.take(count)

/** A collection of BitSets bucketed by popcount (size) for efficient
  * subset/superset queries. Subset queries only check smaller-or-equal buckets.
  * Superset queries only check larger-or-equal buckets.
  *
  * All elements must be subsets of `0 until maxSize`.
  */
final class SizeBucketedBitSets(val maxSize: Int):
  private val stride = wordsFor(maxSize)
  private val buckets: Array[WordBucket] =
    Array.fill(maxSize + 1)(new WordBucket(stride))
  // Scans never nest, so one scratch buffer per instance is enough to keep
  // every query allocation-free.
  private val scratch: Array[Long] = new Array[Long](stride)
  private var totalSize: Int = 0

  inline def size: Int = totalSize
  inline def isEmpty: Boolean = totalSize == 0
  inline def nonEmpty: Boolean = totalSize > 0

  private inline def load(bs: BitSet): Array[Long] =
    writeWords(bs, scratch, stride)
    scratch

  def contains(bs: BitSet): Boolean =
    buckets(bs.size).indexOf(load(bs)) >= 0

  def add(bs: BitSet): Boolean =
    val added = buckets(bs.size).add(bs, load(bs))
    if added then totalSize += 1
    added

  def +=(bs: BitSet): this.type =
    add(bs)
    this

  def remove(bs: BitSet): Boolean =
    val removed = buckets(bs.size).remove(load(bs))
    if removed then totalSize -= 1
    removed

  def -=(bs: BitSet): this.type =
    remove(bs)
    this

  def clear(): Unit =
    var i = 0
    while i <= maxSize do
      buckets(i).clear()
      i += 1
    totalSize = 0

  /** Check if any stored BitSet is a subset of the query. Only checks buckets
    * with size <= query.size.
    */
  def hasSubsetOf(query: BitSet): Boolean =
    val q = load(query)
    val querySize = query.size
    var b = 0
    while b <= querySize do
      if buckets(b).hasSubsetOf(q) then return true
      b += 1
    false

  /** Check if any stored BitSet is a superset of the query. Only checks buckets
    * with size >= query.size.
    */
  def hasSupersetOf(query: BitSet): Boolean =
    val q = load(query)
    var b = query.size
    while b <= maxSize do
      if buckets(b).hasSupersetOf(q) then return true
      b += 1
    false

  /** Find all stored BitSets that are supersets of the query (query included). */
  def findSupersetsOf(query: BitSet): mutable.ArrayBuffer[BitSet] =
    val result = mutable.ArrayBuffer[BitSet]()
    val q = load(query)
    var b = query.size
    while b <= maxSize do
      buckets(b).collectSupersetsOf(q, result)
      b += 1
    result

  /** Find all stored BitSets that are subsets of the query (query included). */
  def findSubsetsOf(query: BitSet): mutable.ArrayBuffer[BitSet] =
    val result = mutable.ArrayBuffer[BitSet]()
    val q = load(query)
    val querySize = query.size
    var b = 0
    while b <= querySize do
      buckets(b).collectSubsetsOf(q, result)
      b += 1
    result

  /** Remove all stored BitSets that are strict supersets of the query. A
    * superset of equal popcount is the query itself, so the scan can start one
    * bucket above it.
    */
  def removeSupersetsOf(query: BitSet): mutable.ArrayBuffer[BitSet] =
    val removed = mutable.ArrayBuffer[BitSet]()
    val q = load(query)
    var b = query.size + 1
    while b <= maxSize do
      totalSize -= buckets(b).removeSupersetsOf(q, removed)
      b += 1
    removed

  /** Remove all stored BitSets that are strict subsets of the query. */
  def removeSubsetsOf(query: BitSet): mutable.ArrayBuffer[BitSet] =
    val removed = mutable.ArrayBuffer[BitSet]()
    val q = load(query)
    val querySize = query.size
    var b = 0
    while b < querySize do
      totalSize -= buckets(b).removeSubsetsOf(q, removed)
      b += 1
    removed

  /** Check if any element satisfies predicate */
  def exists(p: BitSet => Boolean): Boolean =
    var b = 0
    while b <= maxSize do
      if buckets(b).exists(p) then return true
      b += 1
    false

  def foreach(f: BitSet => Unit): Unit =
    var b = 0
    while b <= maxSize do
      buckets(b).foreach(f)
      b += 1

  def iterator: Iterator[BitSet] =
    buckets.iterator.flatMap(_.iterator)

  def toSeq: Seq[BitSet] =
    val result = mutable.ArrayBuffer[BitSet]()
    foreach(result += _)
    result.toSeq

  override def toString: String =
    s"SizeBucketedBitSets(${toSeq.mkString(", ")})"
