package software.medusa.flow.core_service.worker.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

class ExecutableDagTests {
  @Test
  fun testExecute() = runTest {
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

    val outputByNode = with(Unit) { ExecutableDag(nodes = setOf(targetNode)).execute() }

    assertEquals(listOf("A", "B", "C"), executionOrder)
    assertEquals("c", outputByNode[targetNode])
  }
}
