package software.medusa.flow.core_service.worker.utils

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

class ExecutableDag<ExecutionContextT : Any, NodeOutputT : Any>(
    private val nodes: Set<Node<ExecutionContextT, NodeOutputT>>
) {
  abstract class Node<ExecutionContextT : Any, NodeOutputT : Any> {
    abstract val dependencyNodes: Set<Node<ExecutionContextT, NodeOutputT>>

    context(executionContext: ExecutionContextT)
    abstract suspend fun execute(
        outputByDependencyNode: Map<Node<ExecutionContextT, NodeOutputT>, NodeOutputT>,
    ): NodeOutputT
  }

  context(executionContext: ExecutionContextT)
  suspend fun execute(): Map<Node<ExecutionContextT, NodeOutputT>, NodeOutputT> = coroutineScope {
    ExecutionContext(
            coroutineScope = this,
            executionContext = executionContext,
        )
        .execute()
  }

  private inner class ExecutionContext(
      private val coroutineScope: CoroutineScope,
      private val executionContext: ExecutionContextT,
  ) {
    private val deferredOutputByNode =
        mutableMapOf<Node<ExecutionContextT, NodeOutputT>, Deferred<NodeOutputT>>()

    suspend fun execute(): Map<Node<ExecutionContextT, NodeOutputT>, NodeOutputT> =
        nodes.executeAll()

    private suspend fun Set<Node<ExecutionContextT, NodeOutputT>>.executeAll():
        Map<Node<ExecutionContextT, NodeOutputT>, NodeOutputT> =
        map { node ->
              val deferredOutput = node.ensureIsBeingExecuted()

              coroutineScope.async {
                val output = deferredOutput.await()

                node to output
              }
            }
            .awaitAll()
            .toMap()

    private fun Node<ExecutionContextT, NodeOutputT>.ensureIsBeingExecuted():
        Deferred<NodeOutputT> = deferredOutputByNode.getOrPut(this) { this.startExecution() }

    private fun Node<ExecutionContextT, NodeOutputT>.startExecution(): Deferred<NodeOutputT> {
      val deferredOutputByDependencyNode:
          Deferred<Map<Node<ExecutionContextT, NodeOutputT>, NodeOutputT>> =
          coroutineScope.async {
            dependencyNodes.executeAll()
          }

      return coroutineScope.async {
        val outputByDependencyNode = deferredOutputByDependencyNode.await()

        with(executionContext) {
          execute(
              outputByDependencyNode = outputByDependencyNode,
          )
        }
      }
    }
  }
}
