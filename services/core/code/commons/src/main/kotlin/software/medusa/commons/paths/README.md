# Unix path model

This document describes the design scope, assumptions, and limitations of the `UnixPath` model.

## Overview

A Unix-style path is modeled here as a sequence of names, also called path components. Each name
refers to a file that is a direct child of the directory referred to by the previous name. A file
may be either a regular file or a directory.

The model distinguishes two kinds of paths:

- **absolute paths**, which are interpreted relative to the root directory
- **relative paths**, which are interpreted relative to some other path or filesystem context

The model also distinguishes two kinds of names:

- **literal names**, which correspond to ordinary file names
- **symbolic names**, which represent the conventional Unix special names `"."` and `".."`

This is a model of Unix-style path syntax, not a complete model of filesystem path resolution.

## Scope of the abstraction

The goal of this abstraction is to represent Unix-style paths in a way that is explicit and
type-safe while remaining close to conventional path syntax.

The model is intentionally limited:

- it represents whether a path is absolute or relative
- it represents whether a path component is literal or symbolic
- it does not model POSIX special paths beginning with `"//"`
- it does not model arbitrary binary file names
- it does not model filesystem resolution behavior such as symbolic-link traversal

In other words, this abstraction is primarily about path structure and syntax, not about proving
what file a path resolves to on a real system.

## Textual representation

The conventional textual representation of a relative Unix path is a sequence of names separated by
the slash (`/`) character, for example:

- `path/to/file.txt`
- `.`
- `../src/main.kt`

The conventional textual representation of an absolute Unix path is a slash followed by the textual
representation of a relative path, for example:

- `/usr/local/bin`
- `/tmp/file.txt`

The root directory is represented by:

- `/`

## Why path semantics are closely tied to strings

Unix-style path semantics are strongly coupled to string representation. There are very few
restrictions on file names in the abstract; many practical restrictions arise from how Unix-like
systems interpret path strings.

The most important consequences are described below.

## Slash cannot be part of a file name

The slash character (`/`) is treated as a path-component separator.

From the perspective of a Unix-like OS, this is not so much a matter of slash being forbidden in a
file name, but of there being no way to ask the system for such a file by using conventional path
syntax. If a program asks the OS to create a file at:

- `n/a.txt`

the system interprets that as a file named `a.txt` inside a directory named `n`.

For this reason, slash is not representable inside a path component in this model.

## NUL cannot be part of a file name in practice

Unix-like kernels are traditionally implemented in C, where strings are terminated by the NUL byte
(`0x00`).

Again, this is not so much a matter of NUL being conceptually forbidden in a file name, but of
there being no practical way to express such a name through conventional system interfaces. If a
program attempts to pass a path containing a NUL byte, the path is effectively truncated at that
point.

This model stores names as `String`, so arbitrary binary names are unrepresentable anyway.

## `"."` and `".."` are special names

Unix-like systems give special meaning to two path components:

- `"."` refers to the current directory
- `".."` refers to the parent directory

As with slash and NUL, the important practical point is that these names do not behave like ordinary
literal names when interpreted by the OS as path components.

For that reason, this model represents them explicitly as symbolic names rather than ordinary
literal names.

## Empty names are not allowed

A path component cannot be empty.

For example:

- `/foo/` ends with an empty component
- `/tmp//foo` contains an empty component between the two slashes

In practice, Unix-like systems often collapse repeated slashes or treat trailing slashes specially
depending on the operation being performed. This model does not assign semantic meaning to empty
components and does not represent them as names.

## Consecutive slashes and POSIX special paths

In ordinary Unix practice, consecutive slashes are usually treated the same as a single slash.
However, POSIX permits systems to assign special meaning to pathnames beginning with exactly two
slashes, such as:

- `//host/path`

This creates a possible third category beyond relative and absolute paths: so-called special
absolute paths.

This model does not represent such paths. Paths beginning with `"//"` are considered
unrepresentable.

## Encoding considerations

Unix-like operating systems generally interpret pathnames as byte sequences rather than strings with
a required character encoding.

In modern practice, UTF-8 is the most common encoding for human-readable paths, but the OS does not
generally require it. This creates a distinction between:

- path length measured in bytes
- path length measured in Unicode code points

These measures may differ.

This model stores names as Kotlin `String`, so it represents only Unicode text. Arbitrary byte
sequences are therefore outside its scope.

One useful property of valid UTF-8 is that it does not accidentally contain the byte values used for
slash (`0x2F`) or NUL (`0x00`) as part of a multibyte sequence.

## Symbolic links and path equivalence

Filesystem path semantics become much more subtle in the presence of symbolic links.

If symbolic links did not exist, then on an immutable filesystem we could reason much more directly
about path identity:

- two canonical absolute paths would refer to the same file only if they were textually identical
- a file would have a unique canonical absolute path

With symbolic links, neither statement is generally true:

- distinct absolute paths may resolve to the same file
- a file may be reachable through multiple distinct paths
- a path containing `".."` may resolve differently depending on symbolic-link traversal rules

Because of this, the model does not try to define path equivalence in terms of resolved filesystem
identity.

## Current working directory

A relative path does not identify a specific file on its own. On a real Unix-like system, resolving
a relative path usually depends on a process-specific current working directory.

That is an important part of practical filesystem behavior, but it is not modeled directly here.

## Length limits

Real filesystems and system interfaces impose length limits.

Two common limits are:

- **NAME_MAX**: maximum length of a single path component, often 255 bytes
- **PATH_MAX**: maximum total path length for certain APIs, often 4096 bytes

These limits depend on the platform, filesystem, and system call involved. In particular, some
systems allow paths longer than `PATH_MAX` to be traversed incrementally.

This model does not enforce such limits.

## What this model does represent

This model explicitly represents:

- absolute vs relative paths
- path components as structured names
- literal names vs symbolic names
- root as a distinct absolute path
- empty relative path as a representable relative-path value

## What this model does not represent

This model intentionally does not represent:

- POSIX special paths beginning with `"//"`
- arbitrary binary file names
- filesystem-specific resolution behavior
- symbolic-link traversal semantics
- canonicalization as filesystem truth
- current-working-directory state
- filesystem-specific path length rules

## Design intent

This abstraction should be understood as a structured representation of Unix-style path syntax with
a few important semantic distinctions made explicit.

It is not intended to answer questions like:

- do these two paths resolve to the same file?
- is this path canonical on a particular filesystem?
- what happens when symbolic links are traversed?
- how does a particular kernel interpret this edge case?

Those questions require additional filesystem and OS context beyond what this model captures.
