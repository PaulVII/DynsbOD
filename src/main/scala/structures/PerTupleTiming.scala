package structures

case class PerTupleTiming(
    operationId: Int,
    operationType: String, // "insert", "delete", "update_delete", "update_insert"
    tupleId: Int,
    recordId: Option[RecordId],
    timeNanos: Long
)
