package structures

// does not change during update, specified by data source
type RecordId = Int

// uniquely identifies a tuple in its current state. increases on update
type TupleId = Int

type TupleValue = Int

type AttributeId = Int

enum RecordOperation:
  case Insert
  case Update
  case Delete
