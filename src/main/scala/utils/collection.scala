package utils

extension [T](xs: Iterable[T])
  def all(predicate: T => Boolean) = !xs.exists(!predicate(_))
