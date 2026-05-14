package software.medusa.flow.core_service.worker.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

class ExecutableDagTests {
  @Test
  fun testExecuteJoined() = runTest {
    val executionOrder = mutableListOf<String>()

    val depNodeA =
        object : ExecutableDag.Node<Unit, String>() {
          override val dependencyNodes = emptySet<ExecutableDag.Node<Unit, String>>()

          context(executionContext: Unit)
          override suspend fun execute(
              outputByDependencyNode: Map<ExecutableDag.Node<Unit, String>, String>,
          ): String {
            executionOrder += "A"
            return "a"
          }
        }

    val depNodeB =
        object : ExecutableDag.Node<Unit, String>() {
          override val dependencyNodes = emptySet<ExecutableDag.Node<Unit, String>>()

          context(executionContext: Unit)
          override suspend fun execute(
              outputByDependencyNode: Map<ExecutableDag.Node<Unit, String>, String>,
          ): String {
            executionOrder += "B"
            return "b"
          }
        }

    val targetNode =
        object : ExecutableDag.Node<Unit, String>() {
          override val dependencyNodes = setOf(depNodeA, depNodeB)

          context(executionContext: Unit)
          override suspend fun execute(
              outputByDependencyNode: Map<ExecutableDag.Node<Unit, String>, String>,
          ): String {
            executionOrder += "C"
            assertEquals(setOf("a", "b"), outputByDependencyNode.values.toSet())
            return "c"
          }
        }

    val nodes = setOf(depNodeA, depNodeB, targetNode)

    val outputByNode = with(Unit) { ExecutableDag(nodes = nodes).execute() }

    assertEquals(listOf("A", "B", "C"), executionOrder)

    assertEquals(
        mapOf(
            depNodeA to "a",
            depNodeB to "b",
            targetNode to "c",
        ),
        outputByNode,
    )
  }

  @Test
  fun testExecuteSplit() = runTest {
    val executionOrder = mutableListOf<String>()

    val sourceNode: ExecutableDag.Node<Unit, String> =
        object : ExecutableDag.Node<Unit, String>() {
          override val dependencyNodes = emptySet<ExecutableDag.Node<Unit, String>>()

          context(executionContext: Unit)
          override suspend fun execute(
              outputByDependencyNode: Map<ExecutableDag.Node<Unit, String>, String>,
          ): String {
            executionOrder += "A"

            return "a"
          }
        }

    val sinkNode1: ExecutableDag.Node<Unit, String> =
        object : ExecutableDag.Node<Unit, String>() {
          override val dependencyNodes = setOf(sourceNode)

          context(executionContext: Unit)
          override suspend fun execute(
              outputByDependencyNode: Map<ExecutableDag.Node<Unit, String>, String>,
          ): String {
            executionOrder += "B"

            assertEquals(mapOf(sourceNode to "a"), outputByDependencyNode)

            return "b"
          }
        }

    val sinkNode2: ExecutableDag.Node<Unit, String> =
        object : ExecutableDag.Node<Unit, String>() {
          override val dependencyNodes = setOf(sourceNode)

          context(executionContext: Unit)
          override suspend fun execute(
              outputByDependencyNode: Map<ExecutableDag.Node<Unit, String>, String>,
          ): String {
            executionOrder += "C"

            assertEquals(mapOf(sourceNode to "a"), outputByDependencyNode)

            return "c"
          }
        }

    val nodes = setOf(sourceNode, sinkNode1, sinkNode2)

    val outputByNode = with(Unit) { ExecutableDag(nodes = nodes).execute() }

    assertEquals("A", executionOrder[0])

    val tailExecutionOrder = executionOrder.drop(1).toSet()

    assertEquals(setOf("B", "C"), tailExecutionOrder)

    assertEquals(
        mapOf(
            sourceNode to "a",
            sinkNode1 to "b",
            sinkNode2 to "c",
        ),
        outputByNode,
    )
  }
}
