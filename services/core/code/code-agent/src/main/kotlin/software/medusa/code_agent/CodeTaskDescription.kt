package software.medusa.code_agent

import software.medusa.commons.code.CodeBlock

@JvmInline
value class CodeTaskDescription(
    val content: CodeBlock,
)
