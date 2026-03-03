package com.iriver.essentiaanalyzer.model

data class WarningGroup(
  val algorithm: String,
  val messages: List<String>,
)

fun groupWarningsByAlgorithm(errors: List<AnalysisError>): List<WarningGroup> {
  return errors
    .filterNot(::isFatalAnalysisError)
    .groupBy { it.algorithm.ifBlank { "unknown" } }
    .toSortedMap()
    .map { (algorithm, grouped) ->
      WarningGroup(
        algorithm = algorithm,
        messages = grouped.map { it.message.ifBlank { "unknown error" } },
      )
    }
}
