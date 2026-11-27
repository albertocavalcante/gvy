<!--
SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
SPDX-License-Identifier: Apache-2.0
-->

# Facts file format, version 1.0

A facts file records what gvy needs to know about one JVM artifact (a JDK image or a jar): its package and class names,
the supertypes and public and protected members of its classes, and the classes it declares as holders of Groovy
extension methods. The file is identified by the SHA-256 of the input bytes. This page is the one place that specifies
the bytes; the module's reader and writer are one implementation of it.

This is format major version 1, minor version 0. Status: experimental, gated on the measurements in
[#2516](https://github.com/albertocavalcante/gvy/issues/2516). The format is not frozen.

## Conventions

| Term         | Meaning                                                                         |
| ------------ | ------------------------------------------------------------------------------- |
| `u8`         | One unsigned byte.                                                              |
| `u16`, `u32` | Unsigned little-endian integers of 2 and 4 bytes.                               |
| `u64`        | A little-endian integer of 8 bytes; the high bit must be clear.                 |
| `varint`     | An unsigned LEB128 integer of 1 to 5 bytes with a value below 2^31 (see below). |
| `string`     | A `varint` index into the string pool (a section, below).                       |
| `string?`    | A `varint` that is 0 for absent, otherwise the string index plus one.           |
| `bytes(n)`   | Exactly `n` raw bytes.                                                          |

A `varint` stores 7 bits per byte, least significant group first, with the high bit set on every byte but the last. It
must be minimal: a multi-byte `varint` never ends in a zero byte. A fifth byte above `0x07`, or a sixth byte, is
malformed.

## File layout

```text
header | section table | section 1 stored bytes | section 2 stored bytes | ...
```

The header and the section table are never compressed. The stored bytes of the sections follow the table in table order
and fill the rest of the file exactly: a file with bytes left over, or too few, is malformed.

### Header

| Field            | Type             | Notes                                                                                                  |
| ---------------- | ---------------- | ------------------------------------------------------------------------------------------------------ |
| magic            | `bytes(4)`       | ASCII `GVYF`.                                                                                          |
| major version    | `u16`            | 1. A reader rejects any other value.                                                                   |
| minor version    | `u16`            | 0. A reader accepts any value: a minor version only adds section ids.                                  |
| input kind       | `u8`             | 0 = JDK image, 1 = jar. Other values are malformed.                                                    |
| input length     | `u64`            | Length in bytes of the input the file describes.                                                       |
| input SHA-256    | `bytes(32)`      | Digest of the input bytes. This, with the length, is the identity of the file.                         |
| producer version | `varint` + UTF-8 | Length in bytes (at most 4096), then the text. Informational.                                          |
| coordinate       | `varint` + UTF-8 | 0 for absent, otherwise the length plus one (at most 4096), then the text. Informational, never a key. |
| section count    | `varint`         | At most 64.                                                                                            |

The header has no checksum of its own. Its lengths and counts are checked structurally; the input SHA-256 is compared
with the caller's expectation when there is one.

### Section table

One 14-byte entry per section:

| Field         | Type  | Notes                                                                           |
| ------------- | ----- | ------------------------------------------------------------------------------- |
| id            | `u8`  | 1 strings, 2 names, 3 types, 4 extensions. A reader skips ids it does not know. |
| codec         | `u8`  | 0 = stored as is, 1 = DEFLATE (a zlib stream, RFC 1950).                        |
| stored length | `u32` | Length in the file. Values of 2^31 and above are oversized.                     |
| raw length    | `u32` | Length after inflating; for codec 0 it equals the stored length.                |
| CRC32C        | `u32` | CRC-32C (Castagnoli) of the raw bytes.                                          |

An id appears at most once. A reader that wants a known section and does not find it rejects the file as malformed. The
raw bytes of a section are what the CRC covers and what the rest of this page describes; the codec is a storage choice
and never changes them.

A reader inflates into a buffer of exactly the raw length, and rejects a stream that yields more or fewer bytes, ends
early or leaves input unread. Before inflating it rejects a raw length above its own cap, and a DEFLATE raw length above
50 times the stored length.

## Sections

Every count is checked against the bytes remaining in the section before anything is allocated for it: a count of `n`
items of at least `m` bytes each needs `n * m` bytes. A section holds exactly its content, with no trailing bytes. Every
index is checked against the size of the table it points into. A flags, release, kind or source value outside the range
stated for it is malformed. The order in which the writer sorts each table is stated below; a reader neither depends on
that order nor checks it, or the uniqueness of keys, except for the string pool.

### 1. Strings

```text
count: varint
count * ( length: varint, bytes(length) )
```

Strings are UTF-8, decoded strictly: malformed sequences, overlong forms and encoded surrogates are malformed. A string
is at most 65535 bytes, the limit of a class-file UTF-8 constant. The strings are unique and strictly increasing when
compared byte by byte as unsigned values, so equal indices mean equal strings and sorting by index is sorting by UTF-8
bytes. Descriptors and signatures are pooled whole, not split.

### 2. Names

```text
package count: varint
package count * ( name: string, module: string? )
class count: varint
class count * ( internal name: string, package: varint, simple name: string, outer class: string?,
                access flags: varint, release: varint )
```

- `package` is an index into the package table above. `name` of a package is dotted (`java.util`, empty for the unnamed
  package); `module` is set for the packages of a JDK image.
- `internal name` and `outer class` use slashes (`java/util/Map$Entry`).
- `access flags` are the 16 JVM bits plus bit 16 (`0x10000`), set when the class is deprecated. The largest value is
  `0x1FFFF`.
- `release` is 0 for the base copy and N for a copy under `META-INF/versions/N`. The largest value is 255.
- Packages are sorted by name, classes by (internal name, release); both by string index. Each key is unique.

### 3. Types

```text
class count: varint
class count * ( internal name: string, release: varint, super class: string?,
                interface count: varint, interface count * string,
                member count: varint, member count * member )

member = kind: u8, access flags: varint, name: string, descriptor: string,
         parameter names: varint, (parameter names - 1) * string   -- 0 = absent, otherwise count plus one
         generic signature: string?
```

- A class here is identified by (internal name, release), as in the names section. The two sections are decoded
  independently, and a reader does not require them to list the same classes.
- Member `kind` is 0 for a field and 1 for a method; constructors and initializers are methods.
- Parameter names are absent when the class file has no `MethodParameters` attribute; present and empty is a method with
  no parameters.
- Classes are sorted by (internal name, release), members by (name, descriptor), both by string index. Each key is
  unique. Interfaces and parameter names keep their declared order.
- Members are the public and protected ones. Every class is included, because a package-private superclass can supply
  inherited public members.

### 4. Extensions

```text
entry count: varint
entry count * ( holder class: string, kind: u8, source: u8, module name: string, module version: string )
```

- `kind` is 0 for instance extensions and 1 for static ones.
- `source` is 0 for a `META-INF/groovy` descriptor, 1 for a `META-INF/services` descriptor and 2 for Groovy's own list
  of core holder classes.
- Entries are sorted by all fields, in the order written, with the strings by index. The extension methods themselves
  are the members of the holder class in the types section.

## Versioning

- A different major version is rejected. Section layouts, codecs and the header layout belong to the major version.
- A minor version may add section ids. It never changes an existing section, the header or the table.
- A reader skips sections whose ids it does not know, without inflating or checking them; their stored bytes still count
  towards the exact file length.

## Determinism

The raw bytes of every section depend only on the content of the model: all strings are pooled and sorted, every table
is sorted by integer keys, and there are no timestamps, paths or hash-ordered structures. Two writers given the same
content produce the same raw bytes and, with codec 0 everywhere, the same file. With DEFLATE the stored bytes belong to
the zlib build that compressed them, so reproducibility is defined over the raw bytes and their CRC32C; a consumer that
needs to verify a stored blob uses a hash of that blob.

## Limits a reader applies

| Limit                               | Value                                         | Rejection                      |
| ----------------------------------- | --------------------------------------------- | ------------------------------ |
| File size                           | Caller's cap (default 64 MiB)                 | oversized                      |
| Raw length of a section             | Caller's cap (default 128 MiB)                | oversized                      |
| Inflate ratio                       | Raw length at most 50 times the stored length | oversized                      |
| String in the pool                  | 65535 bytes                                   | oversized                      |
| Producer version, coordinate        | 4096 bytes                                    | oversized                      |
| Sections in the table               | 64                                            | oversized                      |
| Magic, major version                | `GVYF`, 1                                     | bad magic, unsupported version |
| Truncated header, table or section  | Any read past the end of the file             | truncated                      |
| CRC, varint, UTF-8, lengths, counts | As described above, and values out of range   | corrupt                        |
| String, package index               | Index outside its table                       | index out of range             |
| Caller-supplied input SHA-256       | Differs from the header's                     | input mismatch                 |
