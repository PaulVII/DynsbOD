package utils

class ProgressBarIterator[A](it: Iterable[A], barLength: Int = 50)
    extends Iterator[A]:

  private val total: Int = it.knownSize
  private val printSteps = if total > 500 then total / 1000 else 1
  // private val printSteps = 1

  private var current: Int = 0
  private val iterator = it.iterator
  private val startTime: Long = System.currentTimeMillis()
  private var lastUpdateTime: Long = startTime

  def hasNext: Boolean = current < total

  def next(): A =
    if current % printSteps == 0 || total - current < 2 || System
        .currentTimeMillis() - lastUpdateTime > 1000
    then updateProgress()
    if hasNext then
      current += 1
      val item = iterator.next()
      if current == total then updateProgress()
      item
    else throw new NoSuchElementException("No more elements")

  private def updateProgress(): Unit =
    val percent = current.toDouble / total
    val filledLength = (barLength * percent).toInt
    val bar = "=" * filledLength + "-" * (barLength - filledLength)
    lastUpdateTime = System.currentTimeMillis()
    val elapsedTime = (lastUpdateTime - startTime) / 1000.0
    val itemsPerSecond = if elapsedTime > 0 then current / elapsedTime else 0.0

    val speedInfo =
      if itemsPerSecond >= 1.0 then f"Speed: ${itemsPerSecond}%.2f items/s"
      else
        val secondsPerItem = if current > 0 then elapsedTime / current else 0.0
        f"Speed: ${secondsPerItem}%.2f s/item"

    print(f"\rProgress: |$bar| ${percent * 100}%.2f%% ($current/${total match
        case -1 => "?"
        case n  => n
      }) $speedInfo")
    if current >= total then println("\nCompleted!")
