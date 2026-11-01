# recordbuf custom-options and the protobuf namespace

The `java_interface` and `java_enum_interface` options that recordbuf supports are
**custom options defined by recordbuf itself**, not by protobuf. This document
records *why* they live in recordbuf's own `recordbuf` package rather than in
protobuf's `google.protobuf.*` namespace, and what that means for consumers.

## Where the extensions live

The two extensions are declared in `options/src/main/proto/recordbuf_options.proto`:

```proto
syntax = "proto2";

import "google/protobuf/descriptor.proto";

package recordbuf;

extend google.protobuf.MessageOptions {
  repeated string java_interface = 50000;
}

extend google.protobuf.EnumOptions {
  repeated string java_enum_interface = 50001;
}
```

- **Two extension numbers.** `java_interface` is on `MessageOptions` (50000);
  `java_enum_interface` is on `EnumOptions` (50001).
- **Why two distinct symbols.** A single extension symbol cannot be declared on
  both `MessageOptions` and `EnumOptions` without an "already defined" collision,
  so the message and enum variants use distinct singular names.
- **Why two field numbers.** 50000–50001 sit in the "user-defined" range above
  protobuf's reserved range, and are distinct so the two extensions never collide
  on the wire.

## Why not `google.protobuf.*`

`recordbuf` deliberately does **not** declare its options in the
`google.protobuf` package. That namespace is owned by the protobuf project;
adding extensions there would (a) collide with `protoc`'s own descriptor
definitions when `recordbuf_options.proto` is compiled alongside the protobuf API
files, and (b) suggest the option is a first-class protobuf feature, which it is
not. Keeping the extensions in the recordbuf-owned `recordbuf` package makes the
provenance unambiguous: the option is a recordbuf feature that happens to extend
the standard `MessageOptions` / `EnumOptions`.

## Consumer usage

- The consumer `.proto` imports the extension definition:
  `import "recordbuf_options.proto";` and keeps that file on the protoc include
  path (`-I`).
- The option is written by its recordbuf-package name, e.g.
  `option (recordbuf.java_interface) = "com.example.Foo";`.

### The leading-dot caveat

protoc resolves a custom-option name starting from the **innermost** scope and
walking outward. If a consumer's file is itself nested under a `recordbuf`
package — for example recordbuf's own test package
`bijnagte.recordbuf.comgen.iface` — the bare name `recordbuf.java_interface` is
resolved *inside* the consumer package first and fails. In that case, qualify the
option from the outermost scope with a leading dot:

```proto
option (.recordbuf.java_interface) = "bijnagte.recordbuf.comgen.iface.Marker";
```

Files **not** nested under `recordbuf` need no leading dot; the bare
`(recordbuf.java_interface)` resolves from the outermost scope automatically.

## What recordbuf does with the option

The plugin reads the option values off the descriptor's `UnknownFieldSet`
(the `repeated string` unknown field at tag 50000 / 50001) and emits the
fully-qualified names as an `implements` clause on the generated record/enum.
It never loads or type-checks the named interfaces (no classpath coupling), and
the generated code adds no import for them — the Java compiler owns satisfaction.
