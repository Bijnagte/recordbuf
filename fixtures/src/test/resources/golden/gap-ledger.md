# Gap ledger

This ledger records each construct the golden corpus cannot cover yet and each conformance scenario recordbuf does not satisfy, with the reason. A construct gap is commented out in the vendored `test_messages_proto3.proto` with a `// GAP(<id>)` marker whose id matches its row here; a conformance-only row has no marker, because it is not a proto construct. `GapLedgerTest` fails when a marker has no row, when a construct row has no marker, or when an id appears twice. Each gap closes in its own change (R16): uncomment the construct, fix the generator, add its goldens, and delete its row in that same change.

| id | construct or scenario | formats | kind | reason |
| --- | --- | --- | --- | --- |
