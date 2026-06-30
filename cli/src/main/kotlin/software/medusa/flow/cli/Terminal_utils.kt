package software.medusa.flow.cli

import com.github.ajalt.mordant.rendering.TextColors
import com.github.ajalt.mordant.terminal.Terminal

fun Terminal.printCode(string: String) {
  println(TextColors.gray(0.3)(">>>>"))
  println(TextColors.gray(0.5)(string))
  println(TextColors.gray(0.3)("<<<<"))
}
