package structures

// A small marker type alias indicating the array is indexed by AttributeId (Int)
// AttributeId elsewhere is assumed to be Int. We use an opaque type if desired later.
// For now this is a simple alias to keep refactors light.
type AttributeIndexed[T] = Array[T]

object AttributeIndexed:
  import scala.reflect.ClassTag
  inline def apply[T: ClassTag](size: Int)(init: => T): AttributeIndexed[T] =
    Array.fill(size)(init)
  inline def fromSeq[T: ClassTag](values: Seq[T]): AttributeIndexed[T] =
    Array.from(values)
