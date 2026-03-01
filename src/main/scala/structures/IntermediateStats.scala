package structures

case class IntermediateStats(
    val currentHighestTupleId: TupleId,
    val lastOperationId: Int,
    val currentConstantOds: Int,
    val currentConstantNonOds: Int,
    val currentComptibleOds: Int,
    val currentComptibleNonOds: Int,
    val timeElapsedMs: Int,
    val odValidatorStats: OdValidatorStats,
    val numCacheEvictions: Int,
    val maxMemoryUsedBytes: Long,
    val currentMemoryUsedBytes: Long,
    val contextIndexContexts: Long,
    val numViolationsTracked: Long
)
