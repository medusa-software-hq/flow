package software.medusa.flow.core_service.worker

import software.medusa.flow.core_service.flows.FlowBlueprint
import software.medusa.flow.core_service.flows.Task
import software.medusa.flow.core_service.flows.TaskId
import software.medusa.flow.core_service.flows.TaskResult
import software.medusa.flow.core_service.worker.utils.ExecutableDag

typealias ExecutableFlowDag = ExecutableDag<FlowExecutionContext, TaskResult>

typealias ExecutableFlowNode = ExecutableDag.Node<FlowExecutionContext, TaskResult>

interface FlowExecutionContext : FlowExecutor.TaskExecutionContext {
  val flowExecutor: FlowExecutor
}

fun FlowBlueprint.compile(): ExecutableFlowDag = taskGraph.compile()

private fun FlowBlueprint.TaskGraph.compile(): ExecutableFlowDag {
  val nodeByTaskId = mutableMapOf<TaskId, ExecutableFlowNode>()

  fun Task.compile(
      inputTaskNodes: Set<ExecutableFlowNode>,
  ): ExecutableFlowNode {
    val taskId = this.id

    return when (val definition = this.definition) {
      is Task.FeatureDefinition -> {
        definition.compileFeatureTask(
            taskId = taskId,
            inputTaskNodes = inputTaskNodes,
        )
      }

      Task.MergeDefinition -> {
        Task.MergeDefinition.compileMergeTask(
            taskId = taskId,
            inputTaskNodes = inputTaskNodes,
        )
      }
    }
  }

  fun Task.ensureIsCompiled(): ExecutableFlowNode {
    val inputTaskNodes: Set<ExecutableFlowNode> =
        inputTaskIds
            .map { sourceTaskId ->
              val sourceTask =
                  taskById[sourceTaskId] ?: error("Invalid task graph: task #${id.raw} is missing")

              sourceTask.ensureIsCompiled()
            }
            .toSet()

    return nodeByTaskId.getOrPut(id) {
      compile(
          inputTaskNodes = inputTaskNodes,
      )
    }
  }

  return ExecutableFlowDag(
      nodes = tasks.map { task -> task.ensureIsCompiled() }.toSet(),
  )
}

private fun Task.FeatureDefinition.compileFeatureTask(
    taskId: TaskId,
    inputTaskNodes: Set<ExecutableFlowNode>,
): ExecutableFlowNode =
    object : ExecutableFlowNode() {
      override val dependencyNodes = inputTaskNodes

      context(executionContext: FlowExecutionContext)
      override suspend fun execute(
          outputByDependencyNode: Map<ExecutableFlowNode, TaskResult>,
      ): TaskResult {
        val inputNodeOutputs = outputByDependencyNode.values

        // Valid feature tasks might have 0 or 1 input nodes
        if (inputNodeOutputs.size > 1) {
          throw IllegalStateException(
              "Invalid task graph: feature task #${taskId.raw} has more than one input node",
          )
        }

        val inputTaskResult = inputNodeOutputs.singleOrNull()

        val inputCommitHash = inputTaskResult?.outputCommitHash

        val featureTaskResult =
            executionContext.flowExecutor.executeFeatureTask(
                taskId = taskId,
                taskDefinition = this@compileFeatureTask,
                inputCommitHash = inputCommitHash,
            )

        return featureTaskResult
      }
    }

@Suppress("UnusedReceiverParameter")
private fun Task.MergeDefinition.compileMergeTask(
    taskId: TaskId,
    inputTaskNodes: Set<ExecutableFlowNode>,
): ExecutableFlowNode =
    object : ExecutableFlowNode() {
      override val dependencyNodes = inputTaskNodes

      context(executionContext: FlowExecutionContext)
      override suspend fun execute(
          outputByDependencyNode: Map<ExecutableFlowNode, TaskResult>,
      ): TaskResult {
        val baseCommitHashes =
            outputByDependencyNode.values.map { taskOutput -> taskOutput.outputCommitHash }.toSet()

        val mergeTaskResult =
            executionContext.flowExecutor.executeMergeTask(
                taskId = taskId,
                baseCommitHashes = baseCommitHashes,
            )

        return mergeTaskResult
      }
    }
