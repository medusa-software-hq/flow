# CCON - Control Code Object Notation

CCON is a tiny tree format built from three element types:
- `string`
- `list`
- `record` (`header` + `content`)

It is designed for machine-to-machine payloads where ASCII control characters are acceptable as structural delimiters.

## Why It Exists

CCON strings are self-delimited by control characters, so normal text does not need JSON-style escaping.
That is the main selling point.

For example, quotes, backslashes, colons, braces, brackets, and slashes can appear in a CCON string as-is.

## Important Assumption

This only works because CCON strings do **not** allow arbitrary control characters.

A CCON string may contain:
- any non-control character
- tab

A CCON string may not contain:
- newline
- carriage return
- null
- any ASCII control code used by the format itself

So CCON is a good fit for payloads such as:
- file paths
- single source-code lines
- identifiers
- short natural-language descriptions

It is not a direct fit for arbitrary binary data or raw multi-line text unless that data is first split or transformed.

## Shape

- `string`: scalar text value
- `list`: ordered sequence of elements
- `record`: pair of `header` and `content`, both arbitrary elements

The exact wire grammar is defined in `CconElement.ebnfGrammarDescription`.
