<!--
SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
SPDX-License-Identifier: Apache-2.0
-->

# Facts file format, version 1.0

A facts file records what gvy needs to know about one JVM artifact (a JDK image or a jar): its package and class names,
the supertypes and public and protected members of its classes, and the classes it declares as holders of Groovy
extension methods. The file is identified by the SHA-256 of the input bytes. This page is the one place that specifies
the bytes, the rejections and their reasons; the module's reader and writer are one implementation of it.

This is format major version 1, minor version 0. Status: experimental, gated on the measurements in
[#2516](https://github.com/albertocavalcante/gvy/issues/2516). The format is not frozen.

## Conventions

| Term         | Meaning                                                                         |
| ------------ | ------------------------------------------------------------------------------- |
| `u8`         | One unsigned byte.                                                              |
| `u16`, `u32` | Unsigned little-endian integers of 2 and 4 bytes.                               |
| `u64`        | A little-endian integer of 8 bytes; the high bit must be clear.                 |
| `varint`     | An unsigned LEB128 integer of 1 to 5 bytes with a value below 2^31 (see below). |
| `string`     | A `varint` index into the string pool the section uses (see Sections).          |
| `string?`    | A `varint` that is 0 for absent, otherwise the string index plus one.           |
| `bytes(n)`   | Exactly `n` raw bytes.                                                          |

A `varint` stores 7 bits per byte, least significant group first, with the high bit set on every byte but the last. It
must be minimal: a multi-byte `varint` never ends in a zero byte. A fifth byte above `0x07`, or a sixth byte, is
malformed. The largest value, 2^31 - 1, is `FF FF FF FF 07`.

**Text.** Every string is UTF-8 as RFC 3629 defines it, decoded strictly. A decoder rejects every byte sequence that is
not well formed: bytes `C0`, `C1` and `F5` to `FF`, truncated or unexpected continuation bytes, overlong forms, the
encoded surrogates U+D800 to U+DFFF and code points above U+10FFFF. It accepts U+0000 as the single byte `00` (not the
overlong `C0 80`), the noncharacters U+FDD0 to U+FDEF and U+xFFFE and U+xFFFF, and U+FEFF, which is not a byte order
mark here. A model string is UTF-16, so an unpaired surrogate cannot be represented; the writer refuses one.

**CRC-32C.** The Castagnoli polynomial `0x1EDC6F41` (reflected `0x82F63B78`), initial value `0xFFFFFFFF`, input and
output reflected, final XOR `0xFFFFFFFF`, as `java.util.zip.CRC32C` computes it. The check value of the ASCII bytes
`123456789` is `0xE3069283`. A checksum is stored as a `u32`.

## File layout

```text
header | section table | section 1 stored bytes | section 2 stored bytes | ...
```

The header and the section table are never compressed. The stored bytes of the sections follow the table in table order
and fill the rest of the file exactly: a file with too few bytes is truncated, and one with bytes left over is corrupt.

### Header

| Field            | Type             | Notes                                                                                                               |
| ---------------- | ---------------- | ------------------------------------------------------------------------------------------------------------------- |
| magic            | `bytes(4)`       | ASCII `GVYF`.                                                                                                       |
| major version    | `u16`            | 1. A reader rejects any other value.                                                                                |
| minor version    | `u16`            | 0. A reader accepts any value: a minor version only adds section ids.                                               |
| input kind       | `u8`             | 0 = JDK image, 1 = jar. Other values are corrupt.                                                                   |
| input length     | `u64`            | Length in bytes of the input the file describes.                                                                    |
| input SHA-256    | `bytes(32)`      | Digest of the input bytes. This, with the length, is the identity of the file.                                      |
| producer version | `varint` + UTF-8 | The length in bytes (at most 4096), then the text. Informational.                                                   |
| coordinate       | `varint` + UTF-8 | 0 for absent, otherwise the length plus one, then the text. Informational, never a key. The length is at most 4096. |
| section count    | `varint`         | At most 64.                                                                                                         |

The header has no checksum of its own, and no other part of the file covers it either: a damaged or forged file can
carry any digest or length. A reader that needs the file to describe a given input compares the header's SHA-256 with
the digest of that input, which it must take from a source it trusts. The header's lengths and counts are checked
structurally.

### Section table

One 14-byte entry per section:

| Field         | Type  | Notes                                                                                                    |
| ------------- | ----- | -------------------------------------------------------------------------------------------------------- |
| id            | `u8`  | 1 names pool, 2 names, 3 type pool, 4 types, 5 extensions. A reader skips ids it does not know.          |
| codec         | `u8`  | 0 = stored as is, 1 = DEFLATE (a zlib stream, RFC 1950).                                                 |
| stored length | `u32` | Length in the file. Values of 2^31 and above are oversized.                                              |
| raw length    | `u32` | Length after inflating; for codec 0 it equals the stored length. Values of 2^31 and above are oversized. |
| CRC32C        | `u32` | CRC-32C of the raw bytes.                                                                                |

An id appears at most once, known or not: a repeated id is corrupt. A reader checks both `u32` lengths of every row when
it opens the file, for ids it does not know as well. A reader that decodes a known section and does not find it rejects
that call as corrupt; sections it does not need still load. The raw bytes of a section are what the CRC covers and what
the rest of this page describes; the codec is a storage choice and never changes them. A codec other than 0 and 1 on a
known section is corrupt.

A reader inflates into a buffer of exactly the raw length, and rejects a stream that yields more or fewer bytes, ends
early, leaves input unread or asks for a preset dictionary. Before inflating it rejects a raw length above its own cap,
and a DEFLATE raw length above 50 times the stored length. A writer whose DEFLATE output would break that ratio stores
the section with codec 0 instead.

**What a writer emits.** One row for each of the five sections, in ascending id order, which is also the order of their
stored bytes, and no other id. The minor version is the one of this page (0). Codec 0 or 1 per section as the caller
chooses, subject to the fallback above. The header carries the model's producer version, input kind, coordinate, length
and digest as given.

## Sections

A section is decoded from its raw bytes. The five sections follow the dependencies, so that a reader that wants the
names never decodes a string only the types need, and one that wants the types never decodes the extensions:

| Group      | Sections              | Decoding them needs                                         |
| ---------- | --------------------- | ----------------------------------------------------------- |
| Names      | 1 names pool, 2 names | The pool, then the names section                            |
| Types      | 3 type pool, 4 types  | The names group, then the type pool, then the types section |
| Extensions | 5 extensions          | The names and types groups, then the section                |

- A `string` in the names or extensions section indexes the **names pool** (section 1).
- A `string` or `string?` in the types section indexes the **type pool** (section 3). The class a types entry describes
  is not a string: it is an index into the names section's class table.
- The pools are separate, so a string both groups use, such as a superclass name that is also a class, appears in each.
- A pool may hold a string nothing refers to; a reader neither requires nor checks that. A writer pools only strings
  that are referred to.

**Counts.** Every count is checked against the bytes remaining in the section before anything is allocated for it: a
count of `n` items of at least `m` bytes each needs `n * m` bytes, and a reader tests `n <= remaining / m` with integer
division. The minimum encoded size `m` of each repeated item is:

| Item                       | Minimum bytes | Item           | Minimum bytes |
| -------------------------- | ------------- | -------------- | ------------- |
| Section table row (header) | 14            | Types entry    | 4             |
| Pool string                | 1             | Interface      | 1             |
| Package                    | 2             | Member         | 6             |
| Class (names section)      | 6             | Parameter name | 1             |
| Extension entry            | 5             |                |               |

A count the remaining bytes cannot hold is truncated in the header's section table and corrupt in a section. A section
holds exactly its content, with no trailing bytes. Every index is checked against the size of the table it points into.
A flags, release, kind or source value outside the range stated for it is corrupt. A read past the end of a section's
raw bytes is corrupt, not truncated: truncation means the file ends too soon.

**Decoded items.** A reader bounds the objects it builds, not only the bytes it inflates. Each of these counts as one
item: a pooled string, a package, a class of the names section, an extension entry, a types entry, an interface, a
member and a parameter name. A reader charges the count of each repeated item against one budget per opened file, after
the count has passed the byte check and before it allocates anything for it, and rejects a count that overspends the
budget as oversized. A section is decoded, and so charged, once per file, however often it is asked for, and always
after the sections before it in the decoding order. The default budget of the reference reader is 3,000,000 items: a
full JDK image needs about one million (about 40 thousand classes, counted in the names and in the types section, 60
thousand interface references, 500 thousand members, 250 thousand pooled strings and 100 thousand parameter names), and
3,000,000 items take about 324 MB of heap at the estimated 108 bytes per item. The contents of the strings are outside
the budget. They are bounded by the pools' raw bytes: a Java string takes at most about twice its UTF-8 bytes, so the
two pools together take about 256 MiB at the default section cap of 64 MiB.

**Order of keys.** Every table's keys must be strictly increasing, so a table is written one way and a key is unique.
The key is stated with each table below; a key that is repeated or smaller than the one before it is corrupt. A reader
checks it after it has read the fields of the item, including the items nested in it.

### 1 and 3. Names pool and type pool

```text
count: varint
count * ( length: varint, bytes(length) )
```

Strings are UTF-8 as described under Text. A string is at most 65535 bytes, the limit of a class-file UTF-8 constant; a
longer length is oversized, checked before the bytes are read. The strings are strictly increasing when compared byte by
byte as unsigned values (a prefix sorts first), which makes them unique, so sorting by index is sorting by UTF-8 bytes.
The order is checked before the bytes are decoded. Descriptors and signatures are pooled whole, not split.

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
- `internal name` and `outer class` use slashes (`java/util/Map$Entry`). The `simple name` is empty for an anonymous
  class. The `outer class` is the declaring class of a member class, and for a local or anonymous class the class its
  `EnclosingMethod` attribute names.
- `access flags` are the 16 JVM bits plus bit 16 (`0x10000`), set when the class is deprecated. The largest value is
  `0x1FFFF`. For a class with an `InnerClasses` entry for itself they are the flags of that entry, which is what
  `Class.getModifiers()` reports, so `static`, `private` and `protected`, which a class file cannot hold, are kept. A
  class without such an entry is top-level and has the flags of its class file.
- `release` is 0 for the base copy and N for a copy under `META-INF/versions/N`. The largest value is 255.
- Keys: a package by its name's pool index; a class by (internal name pool index, release).

### 4. Types

```text
entry count: varint
entry count * ( class: varint, super class: string?,
                interface count: varint, interface count * string,
                member count: varint, member count * member )

member = kind: u8, access flags: varint, name: string, descriptor: string,
         parameter names: varint, (parameter names - 1) * string,  -- 0 = absent, otherwise count plus one
         generic signature: string?
```

- `class` indexes the class table of the names section, which names the class (internal name and release) the entry
  describes. A class may have no entry.
- Member `kind` is 0 for a field and 1 for a method; constructors and initializers are methods.
- Parameter names are absent when the class file has no usable `MethodParameters` attribute (missing, or with a count
  that differs from the descriptor's); present and empty is a method with no parameters. An empty name is a valid pool
  string and means the attribute names no parameter at that position.
- `access flags` and `release` follow the names section: at most `0x1FFFF` and 255.
- Interfaces and parameter names keep their declared order and have no key.
- Keys: an entry by its `class` index; a member by (name pool index, descriptor pool index) within its class.
- Members are the public and protected ones. Every class is included, because a package-private superclass can supply
  inherited public members.

### 5. Extensions

```text
entry count: varint
entry count * ( holder class: string, kind: u8, source: u8, module name: string, module version: string )
```

- `kind` is 0 for instance extensions and 1 for static ones.
- `source` is 0 for a `META-INF/groovy` descriptor, 1 for a `META-INF/services` descriptor and 2 for Groovy's own list
  of core holder classes.
- Key: all fields in the order written, `kind` and `source` by their codes and the strings by pool index. The extension
  methods themselves are the members of the holder class in the types section.

## Order of checks

A file that breaks several rules in one place is rejected for the first of these, so readers report the same reason.

**Opening.**

1. The file is above the caller's cap: oversized.
2. The magic: a byte that differs is a bad magic; a file that is a proper prefix of the magic is truncated.
3. Both version fields are read, then the major version is compared: an unsupported version. A file shorter than the
   version fields is truncated.
4. The header fields, in order. A read past the end of the file is truncated; an unknown input kind, a length with the
   high bit set, a malformed varint (the producer length, the coordinate field or the section count) or invalid UTF-8 is
   corrupt; a string above 4096 bytes is oversized, checked before it is read; a section count above 64 is oversized,
   and a table that does not fit in the file (count above remaining bytes divided by 14) is truncated.
5. The table rows, in order, stored length before raw length: a length of 2^31 or more is oversized.
6. The stored lengths together are more than the bytes left: truncated; fewer: corrupt. Then an id twice: corrupt.
7. The caller's expected input SHA-256 differs: input mismatch.

**Decoding.** Whichever call asks, sections are decoded in this order: names pool, names, type pool, types, extensions.
A call decodes a prefix of that order, and the first rejection ends it, so the budget left at each section does not
depend on which calls came before:

| Call       | Decodes                             |
| ---------- | ----------------------------------- |
| names      | names pool, names                   |
| types      | names pool, names, type pool, types |
| extensions | all five                            |
| all        | all five                            |

For one section:

1. The section is missing: corrupt.
2. Its raw length is above the caller's cap: oversized.
3. The codec. Unknown: corrupt. Codec 0 with a stored length that differs from the raw length: corrupt. Codec 1: a raw
   length above 50 times the stored length is oversized; then any inflate failure, a length that differs from the raw
   length, unread input or a preset dictionary is corrupt.
4. The CRC-32C of the raw bytes differs: corrupt.
5. The content, in reading order. For a repeated item: a malformed varint is corrupt, a count the bytes cannot hold is
   corrupt, then a count that overspends the decoded-item budget is oversized. For an item: its fields in the order
   written (an index outside its table is out of range; a flags, release, kind or source value out of range is corrupt;
   in a pool, a string above 65535 bytes is oversized, an order violation is corrupt, then invalid UTF-8 is corrupt),
   then, after its nested items, its key out of order is corrupt. At the end of the section, a byte left over is
   corrupt.

## Versioning

- A different major version is rejected. Section layouts, codecs and the header layout belong to the major version.
- A minor version may add section ids. It never changes an existing section, the header or the table.
- A reader skips sections whose ids it does not know, without inflating or checking their content; their rows are
  checked like any other row, and their stored bytes count towards the exact file length.

## Determinism

The raw bytes of every section depend only on the content of the model: all strings are pooled and sorted, every table
is sorted by integer keys, and there are no timestamps, paths or hash-ordered structures. Two writers given the same
content produce the same raw bytes and, with codec 0 everywhere, the same file. With DEFLATE the stored bytes belong to
the zlib build that compressed them, so reproducibility is defined over the raw bytes and their CRC-32C; a consumer that
needs to verify a stored blob uses a hash of that blob.

## Limits a reader applies

| Limit                               | Value                                         | Rejection                      |
| ----------------------------------- | --------------------------------------------- | ------------------------------ |
| File size                           | Caller's cap (default 64 MiB)                 | oversized                      |
| Raw length of a section             | Caller's cap (default 64 MiB)                 | oversized                      |
| Inflate ratio                       | Raw length at most 50 times the stored length | oversized                      |
| Decoded items                       | Caller's cap (default 3,000,000)              | oversized                      |
| String in a pool                    | 65535 bytes                                   | oversized                      |
| Producer version, coordinate        | 4096 bytes                                    | oversized                      |
| Sections in the table               | 64                                            | oversized                      |
| Table row length fields             | Below 2^31                                    | oversized                      |
| Magic, major version                | `GVYF`, 1                                     | bad magic, unsupported version |
| Truncated header, table or sections | Any read past the end of the file             | truncated                      |
| CRC, varint, UTF-8, lengths, counts | As described above, and values out of range   | corrupt                        |
| Key order, repeated id              | Strictly increasing keys; each id once        | corrupt                        |
| Pool, package or class index        | Index outside its table                       | index out of range             |
| Caller-supplied input SHA-256       | Differs from the header's                     | input mismatch                 |
