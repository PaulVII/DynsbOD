package utils

import scala.collection.immutable.BitSet
import scala.collection.mutable

extension (b: BitSet.type)
  def ones(numAttributes: Int): BitSet =
    b(0 until numAttributes*)

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

  /** Fast equality check avoiding boxing overhead. Compares underlying Long
    * arrays directly.
    */
  inline def fastEquals(b: BitSet): Boolean =
    val aMask = a.toBitMask
    val bMask = b.toBitMask
    val maxLen = math.max(aMask.length, bMask.length)
    var i = 0
    var isEqual = true
    while i < maxLen && isEqual do
      val aWord = if i < aMask.length then aMask(i) else 0L
      val bWord = if i < bMask.length then bMask(i) else 0L
      if aWord != bWord then isEqual = false
      i += 1
    isEqual

/** A set backed by ArrayBuffer for fast iteration, with HashSet for O(1)
  * membership. Iteration is array-based (no iterator allocation in while
  * loops). Add/remove/contains are O(1) average via the HashSet.
  */
class FastBitSetIterSet:
  private val arr = mutable.ArrayBuffer[BitSet]()
  private val set = mutable.HashSet[BitSet]()

  inline def size: Int = arr.size
  inline def isEmpty: Boolean = arr.isEmpty
  inline def nonEmpty: Boolean = arr.nonEmpty

  inline def apply(i: Int): BitSet = arr(i)

  def contains(elem: BitSet): Boolean = set.contains(elem)

  /** Fast indexOf using fastEquals to avoid boxing */
  private def fastIndexOf(elem: BitSet): Int =
    var i = 0
    while i < arr.size do
      if arr(i).fastEquals(elem) then return i
      i += 1
    -1

  /** Add element, returns true if it was not already present */
  def add(elem: BitSet): Boolean =
    if set.add(elem) then
      arr += elem
      true
    else false

  def +=(elem: BitSet): this.type =
    add(elem)
    this

  /** Remove element, returns true if it was present */
  def remove(elem: BitSet): Boolean =
    if set.remove(elem) then
      // Swap with last for O(1) removal from array
      val idx = fastIndexOf(elem)
      if idx >= 0 then
        val last = arr.size - 1
        if idx != last then arr(idx) = arr(last)
        arr.dropRightInPlace(1)
      true
    else false

  def -=(elem: BitSet): this.type =
    remove(elem)
    this

  def clear(): Unit =
    arr.clear()
    set.clear()

  /** In-place filter, keeps only elements satisfying predicate */
  def filterInPlace(p: BitSet => Boolean): Unit =
    var i = 0
    while i < arr.size do
      if !p(arr(i)) then
        set.remove(arr(i))
        val last = arr.size - 1
        if i != last then arr(i) = arr(last)
        arr.dropRightInPlace(1)
        // Don't increment i, check the swapped element
      else i += 1

  /** Check if any element satisfies predicate - uses fast array iteration */
  inline def exists(p: BitSet => Boolean): Boolean =
    var i = 0
    var found = false
    while i < arr.size && !found do
      if p(arr(i)) then found = true
      i += 1
    found

  /** Filter and collect elements satisfying predicate */
  def filter(p: BitSet => Boolean): mutable.ArrayBuffer[BitSet] =
    val result = mutable.ArrayBuffer[BitSet]()
    var i = 0
    while i < arr.size do
      if p(arr(i)) then result += arr(i)
      i += 1
    result

  def iterator: Iterator[BitSet] = arr.iterator

  def toSeq: Seq[BitSet] = arr.toSeq

  override def toString: String = arr.mkString("FastBitSetIterSet(", ", ", ")")

/** A collection of BitSets bucketed by popcount (size) for efficient
  * subset/superset queries. Subset queries only check smaller-or-equal buckets.
  * Superset queries only check larger-or-equal buckets.
  */
class SizeBucketedBitSets(val maxSize: Int):
  private val buckets: Array[FastBitSetIterSet] =
    Array.fill(maxSize + 1)(FastBitSetIterSet())
  private var totalSize: Int = 0

  inline def size: Int = totalSize
  inline def isEmpty: Boolean = totalSize == 0
  inline def nonEmpty: Boolean = totalSize > 0

  def contains(bs: BitSet): Boolean =
    buckets(bs.size).contains(bs)

  def add(bs: BitSet): Boolean =
    val added = buckets(bs.size).add(bs)
    if added then totalSize += 1
    added

  def +=(bs: BitSet): this.type =
    add(bs)
    this

  def remove(bs: BitSet): Boolean =
    val removed = buckets(bs.size).remove(bs)
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
    val querySize = query.size
    var bucketIdx = 0
    while bucketIdx <= querySize && bucketIdx <= maxSize do
      val bucket = buckets(bucketIdx)
      var i = 0
      while i < bucket.size do
        if bucket(i).fastSubsetOf(query) then return true
        i += 1
      bucketIdx += 1
    false

  /** Check if any stored BitSet is a superset of the query. Only checks buckets
    * with size >= query.size.
    */
  def hasSupersetOf(query: BitSet): Boolean =
    val querySize = query.size
    var bucketIdx = querySize
    while bucketIdx <= maxSize do
      val bucket = buckets(bucketIdx)
      var i = 0
      while i < bucket.size do
        if query.fastSubsetOf(bucket(i)) then return true
        i += 1
      bucketIdx += 1
    false

  /** Find all stored BitSets that are supersets of the query. */
  def findSupersetsOf(query: BitSet): mutable.ArrayBuffer[BitSet] =
    val result = mutable.ArrayBuffer[BitSet]()
    val querySize = query.size
    var bucketIdx = querySize
    while bucketIdx <= maxSize do
      val bucket = buckets(bucketIdx)
      var i = 0
      while i < bucket.size do
        if query.fastSubsetOf(bucket(i)) then result += bucket(i)
        i += 1
      bucketIdx += 1
    result

  /** Find all stored BitSets that are subsets of the query. */
  def findSubsetsOf(query: BitSet): mutable.ArrayBuffer[BitSet] =
    val result = mutable.ArrayBuffer[BitSet]()
    val querySize = query.size
    var bucketIdx = 0
    while bucketIdx <= querySize && bucketIdx <= maxSize do
      val bucket = buckets(bucketIdx)
      var i = 0
      while i < bucket.size do
        if bucket(i).fastSubsetOf(query) then result += bucket(i)
        i += 1
      bucketIdx += 1
    result

  /** Remove all stored BitSets that are supersets of the query. */
  def removeSupersetsOf(query: BitSet): mutable.ArrayBuffer[BitSet] =
    val removed = mutable.ArrayBuffer[BitSet]()
    val querySize = query.size
    var bucketIdx = querySize
    while bucketIdx <= maxSize do
      val bucket = buckets(bucketIdx)
      val toRemove = mutable.ArrayBuffer[BitSet]()
      var i = 0
      while i < bucket.size do
        val bs = bucket(i)
        if query.fastSubsetOf(bs) && !bs.fastEquals(query) then toRemove += bs
        i += 1
      for bs <- toRemove do
        bucket.remove(bs)
        totalSize -= 1
        removed += bs
      bucketIdx += 1
    removed

  /** Remove all stored BitSets that are subsets of the query. */
  def removeSubsetsOf(query: BitSet): mutable.ArrayBuffer[BitSet] =
    val removed = mutable.ArrayBuffer[BitSet]()
    val querySize = query.size
    var bucketIdx = 0
    while bucketIdx <= querySize && bucketIdx <= maxSize do
      val bucket = buckets(bucketIdx)
      val toRemove = mutable.ArrayBuffer[BitSet]()
      var i = 0
      while i < bucket.size do
        val bs = bucket(i)
        if bs.fastSubsetOf(query) && !bs.fastEquals(query) then toRemove += bs
        i += 1
      for bs <- toRemove do
        bucket.remove(bs)
        totalSize -= 1
        removed += bs
      bucketIdx += 1
    removed

  /** Check if any element satisfies predicate */
  def exists(p: BitSet => Boolean): Boolean =
    var bucketIdx = 0
    while bucketIdx <= maxSize do
      if buckets(bucketIdx).exists(p) then return true
      bucketIdx += 1
    false

  /** In-place filter, keeps only elements satisfying predicate */
  def filterInPlace(p: BitSet => Boolean): Unit =
    var bucketIdx = 0
    while bucketIdx <= maxSize do
      val bucket = buckets(bucketIdx)
      val oldSize = bucket.size
      bucket.filterInPlace(p)
      totalSize -= (oldSize - bucket.size)
      bucketIdx += 1

  /** Filter and collect elements satisfying predicate */
  def filter(p: BitSet => Boolean): mutable.ArrayBuffer[BitSet] =
    val result = mutable.ArrayBuffer[BitSet]()
    var bucketIdx = 0
    while bucketIdx <= maxSize do
      val bucket = buckets(bucketIdx)
      var i = 0
      while i < bucket.size do
        if p(bucket(i)) then result += bucket(i)
        i += 1
      bucketIdx += 1
    result

  def iterator: Iterator[BitSet] =
    buckets.iterator.flatMap(_.iterator)

  def toSeq: Seq[BitSet] =
    val result = mutable.ArrayBuffer[BitSet]()
    var bucketIdx = 0
    while bucketIdx <= maxSize do
      result ++= buckets(bucketIdx).toSeq
      bucketIdx += 1
    result.toSeq

  override def toString: String =
    s"SizeBucketedBitSets(${toSeq.mkString(", ")})"
