# SPAN API OpenAPI spec

`span-api.json` is SPAN's official OpenAPI document for the panel's REST API, as published in
[spanio/SPAN-API-Client-Docs](https://github.com/spanio/SPAN-API-Client-Docs) at
`specs/r202645/openapi.json` (panel firmware release r202645). It is the same document a panel
serves at `http://span-<serial>.local/api/openapi.json`. SPAN publishes it under the MIT No
Attribution license.

The build generates the Java client from it with OpenAPI Generator (see `core/pom.xml`): the
`SpanPanelApi` interface and its request and response records, in
`io.github.chelin1974.spanhome.span.api`. Only the endpoints the bridge calls are generated.

To move to a newer firmware release, copy that release's `openapi.json` over this file, update
the release named above and the `span-api.release` property in `core/pom.xml`, and rebuild.
