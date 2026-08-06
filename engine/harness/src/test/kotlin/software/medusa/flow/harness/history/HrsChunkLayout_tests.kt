package software.medusa.flow.harness.history

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HrsChunkLayout_tests {
  private val config = HrsChunkConfig(smallChunkSize = 3, bigChunkSize = 8)

  @Test
  fun `an empty log has no chunks`() {
    val layout = HrsChunkLayout.of(delegationCount = 0, config = config)

    assertEquals(0, layout.numSmallChunks)
    assertTrue(layout.bigSummaryChunks.isEmpty())
    assertTrue(layout.smallSummaryChunks.isEmpty())
    assertTrue(layout.fullWindowChunks.isEmpty())
  }

  @Test
  fun `up to two small chunks all render in the full window`() {
    val layout = HrsChunkLayout.of(delegationCount = 6, config = config) // exactly 2 chunks

    assertEquals(2, layout.numSmallChunks)
    assertEquals(listOf(0, 1), layout.fullWindowChunks)
    assertTrue(layout.smallSummaryChunks.isEmpty())
    assertTrue(layout.bigSummaryChunks.isEmpty())
  }

  @Test
  fun `a third small chunk pushes the oldest into a small summary`() {
    val layout = HrsChunkLayout.of(delegationCount = 9, config = config) // 3 chunks

    assertEquals(listOf(0), layout.smallSummaryChunks)
    assertEquals(listOf(1, 2), layout.fullWindowChunks)
    assertTrue(layout.bigSummaryChunks.isEmpty())
    assertEquals(0..2, layout.smallChunkDelegationRange(0))
    assertEquals(6..8, layout.smallChunkDelegationRange(2))
  }

  @Test
  fun `a big chunk forms only once eight small chunks sit fully behind the window`() {
    // 10 small chunks = 30 delegations: window = chunks 8,9; chunks 0..7 form one closed big chunk.
    val layout = HrsChunkLayout.of(delegationCount = 30, config = config)

    assertEquals(10, layout.numSmallChunks)
    assertEquals(listOf(0), layout.bigSummaryChunks)
    assertTrue(layout.smallSummaryChunks.isEmpty())
    assertEquals(listOf(8, 9), layout.fullWindowChunks)
    assertEquals(0..7, layout.bigChunkSmallChunkRange(0))
  }

  @Test
  fun `the three tiers are always disjoint and cover every small chunk`() {
    for (n in 0..60) {
      val layout = HrsChunkLayout.of(delegationCount = n, config = config)

      val coveredByBig =
          layout.bigSummaryChunks.flatMap { layout.bigChunkSmallChunkRange(it).toList() }
      val all = (coveredByBig + layout.smallSummaryChunks + layout.fullWindowChunks).sorted()

      assertEquals(
          (0 until layout.numSmallChunks).toList(),
          all,
          "tiers must exactly cover [0, $n) small chunks",
      )
      assertEquals(all.size, all.toSet().size, "tiers must be disjoint for n=$n")
    }
  }

  @Test
  fun `the full window is never larger than two chunks and never empty once work exists`() {
    for (n in 1..60) {
      val layout = HrsChunkLayout.of(delegationCount = n, config = config)
      assertTrue(layout.fullWindowChunks.size in 1..2, "n=$n window=${layout.fullWindowChunks}")
    }
  }

  @Test
  fun `no close event fires between a small chunk's reports`() {
    for (n in listOf(0, 1, 2, 4, 5, 7, 8, 10, 11)) {
      assertNull(HrsChunkLayout.closeEventAt(delegationCount = n, config = config), "n=$n")
    }
  }

  @Test
  fun `a small chunk close fires on its s-th accepted report, with no big-chunk close yet`() {
    val event = HrsChunkLayout.closeEventAt(delegationCount = 3, config = config)

    assertEquals(HrsChunkCloseEvent(closedSmallChunkIndex = 0, closedBigChunkIndex = null), event)
  }

  @Test
  fun `later small chunk closes also carry no big-chunk close, short of the B-th`() {
    // 21 delegations = 7 small chunks; the 7th closes, but B=8, so no big chunk yet.
    val event = HrsChunkLayout.closeEventAt(delegationCount = 21, config = config)

    assertEquals(HrsChunkCloseEvent(closedSmallChunkIndex = 6, closedBigChunkIndex = null), event)
  }

  @Test
  fun `a big chunk closes exactly when its B-th small chunk closes, cascading from the same event`() {
    // 24 delegations = 8 small chunks (B=8): the 8th small chunk's close is also the 1st big
    // chunk's.
    val event = HrsChunkLayout.closeEventAt(delegationCount = 24, config = config)

    assertEquals(HrsChunkCloseEvent(closedSmallChunkIndex = 7, closedBigChunkIndex = 0), event)
  }

  @Test
  fun `a second big chunk closes on the 16th small chunk, independent of the first`() {
    val event = HrsChunkLayout.closeEventAt(delegationCount = 48, config = config)

    assertEquals(HrsChunkCloseEvent(closedSmallChunkIndex = 15, closedBigChunkIndex = 1), event)
  }
}
