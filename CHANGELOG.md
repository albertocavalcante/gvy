# Changelog

## [0.3.2](https://github.com/albertocavalcante/groovy-lsp/compare/v0.3.1...v0.3.2) (2025-12-17)


### Features

* implement Flow-based diagnostic provider architecture ([#191](https://github.com/albertocavalcante/groovy-lsp/issues/191)) ([0d5ea82](https://github.com/albertocavalcante/groovy-lsp/commit/0d5ea823b9cdae4e5509a73484e846d7002add15))
* Jenkins semantic tokens for enhanced syntax highlighting ([#184](https://github.com/albertocavalcante/groovy-lsp/issues/184)) ([55a003c](https://github.com/albertocavalcante/groovy-lsp/commit/55a003c45843c384a69433cfaf475a527057761e))
* **jenkins:** enhanced vars/ support with semantic tokens and hover docs ([#190](https://github.com/albertocavalcante/groovy-lsp/issues/190)) ([f335ab6](https://github.com/albertocavalcante/groovy-lsp/commit/f335ab6a3644862cf8a9faae466bd692309547ee))
* **jenkins:** implement layered plugin discovery ([#188](https://github.com/albertocavalcante/groovy-lsp/issues/188)) ([bf84cc9](https://github.com/albertocavalcante/groovy-lsp/commit/bf84cc954df06985481434a2733a9e839e79c87d))

## [0.3.1](https://github.com/albertocavalcante/groovy-lsp/compare/v0.3.0...v0.3.1) (2025-12-16)


### Bug Fixes

* **deps:** update dependency com.github.javaparser:javaparser-core to v3.27.1 ([#181](https://github.com/albertocavalcante/groovy-lsp/issues/181)) ([79f3515](https://github.com/albertocavalcante/groovy-lsp/commit/79f3515526730959fb19e151cb1c0bd2bcef65ff))
* **formatter:** preserve shebangs in Groovy scripts ([#183](https://github.com/albertocavalcante/groovy-lsp/issues/183)) ([1d72f2a](https://github.com/albertocavalcante/groovy-lsp/commit/1d72f2af361fd7ba7cf2154761ddacf391b47a26))

## [0.3.0](https://github.com/albertocavalcante/groovy-lsp/compare/v0.2.0...v0.3.0) (2025-12-16)


### ⚠ BREAKING CHANGES

* **codeaction:** implement textDocument/codeAction for imports, formatting, and lint fixes ([#114](https://github.com/albertocavalcante/groovy-lsp/issues/114))

### Features

* add bundled Jenkins metadata scaffold ([#145](https://github.com/albertocavalcante/groovy-lsp/issues/145)) ([69d1aad](https://github.com/albertocavalcante/groovy-lsp/commit/69d1aad785693eb4139d32c611423756a8f50e23))
* add core infrastructure for CodeNarc lint fixes ([#158](https://github.com/albertocavalcante/groovy-lsp/issues/158)) ([e0d5083](https://github.com/albertocavalcante/groovy-lsp/commit/e0d5083d631cb5e7fc722efc3c934e3f06c85f13))
* add groovydoc extraction to hover and signature help ([#112](https://github.com/albertocavalcante/groovy-lsp/issues/112)) ([22cfeca](https://github.com/albertocavalcante/groovy-lsp/commit/22cfecafe2207a697d73896d48a7c1d31e4cb835))
* add Jenkins map-key completions and expanded metadata ([#148](https://github.com/albertocavalcante/groovy-lsp/issues/148)) ([791a15d](https://github.com/albertocavalcante/groovy-lsp/commit/791a15d3bf6a489c56d9cd728d385f19089a1c99))
* add support for goto definition in JAR dependencies ([#142](https://github.com/albertocavalcante/groovy-lsp/issues/142)) ([b53887b](https://github.com/albertocavalcante/groovy-lsp/commit/b53887b4b8500c76d35ecb4a9de0f42f2c823e32))
* build tool abstraction and Maven support ([#168](https://github.com/albertocavalcante/groovy-lsp/issues/168)) ([88b2bb9](https://github.com/albertocavalcante/groovy-lsp/commit/88b2bb92ac05514e5bb85a7add2a05837b2285b2))
* **codeaction:** implement textDocument/codeAction for imports, formatting, and lint fixes ([#114](https://github.com/albertocavalcante/groovy-lsp/issues/114)) ([a4661b6](https://github.com/albertocavalcante/groovy-lsp/commit/a4661b639b2bbd79630ba8a54e12fddd9fd85ef9))
* complete CodeNarc lint fixes Phase 4 - safety validation and integration ([#164](https://github.com/albertocavalcante/groovy-lsp/issues/164)) ([d385a3d](https://github.com/albertocavalcante/groovy-lsp/commit/d385a3d0ae68551ebe6a385c8109fb1b031bcdda))
* complete Phase 2 import cleanup fixes for CodeNarc lint ([#162](https://github.com/albertocavalcante/groovy-lsp/issues/162)) ([43e1574](https://github.com/albertocavalcante/groovy-lsp/commit/43e1574e43c9e9383c3a6bca84fd77395d1a4689))
* default to recursive ast visitor in lsp ([#139](https://github.com/albertocavalcante/groovy-lsp/issues/139)) ([8d99ead](https://github.com/albertocavalcante/groovy-lsp/commit/8d99eade4bc0cead370efbf511ae9ea0c9f6092f))
* **definition:** integrate SourceNavigationService for lazy source JAR fetching ([#176](https://github.com/albertocavalcante/groovy-lsp/issues/176)) ([495b502](https://github.com/albertocavalcante/groovy-lsp/commit/495b5029b0d75aa1d9c94cf678f095bca14b666a))
* fix race condition and symbol resolution for goto-definition ([#141](https://github.com/albertocavalcante/groovy-lsp/issues/141)) ([a32958f](https://github.com/albertocavalcante/groovy-lsp/commit/a32958f25d16117ffd895d03bbf141273d34e40e))
* implement CodeNarc lint fixes Phase 1 - whitespace handlers ([#161](https://github.com/albertocavalcante/groovy-lsp/issues/161)) ([63b6608](https://github.com/albertocavalcante/groovy-lsp/commit/63b6608d6d901084aa9694b83091ae4b6d189102))
* Implement GDK and Type Parameter completion ([#143](https://github.com/albertocavalcante/groovy-lsp/issues/143)) ([8664629](https://github.com/albertocavalcante/groovy-lsp/commit/86646296f34f3d97fd22d58fd3d901236f634355))
* implement TrailingWhitespace fix handler ([#159](https://github.com/albertocavalcante/groovy-lsp/issues/159)) ([461d2a8](https://github.com/albertocavalcante/groovy-lsp/commit/461d2a80b59c5399bf8d32850b21a7e8454f303c))
* implement UnnecessaryPublicModifier lint fix handler ([#163](https://github.com/albertocavalcante/groovy-lsp/issues/163)) ([ce71af1](https://github.com/albertocavalcante/groovy-lsp/commit/ce71af1c7f81660dac8b75b7e2abb0cbbc226bf1))
* improve java source navigation with accurate line numbers and javadoc hover ([#177](https://github.com/albertocavalcante/groovy-lsp/issues/177)) ([2f16a3a](https://github.com/albertocavalcante/groovy-lsp/commit/2f16a3a32842649cc64c13805f484af8dbf4d93e))
* improve type inference with generics support and fix forEach completion ([#144](https://github.com/albertocavalcante/groovy-lsp/issues/144)) ([db6b45e](https://github.com/albertocavalcante/groovy-lsp/commit/db6b45eb653181b13d279406e1877fa58abeed79))
* **jenkins:** add first-class Jenkins pipeline support with @Library resolution and GDSL metadata ([#123](https://github.com/albertocavalcante/groovy-lsp/issues/123)) ([b1160c1](https://github.com/albertocavalcante/groovy-lsp/commit/b1160c1f2e617c485e98dd78bf1e2b522127803a))
* **jenkins:** add version-aware plugin resolution and documentation provider ([#175](https://github.com/albertocavalcante/groovy-lsp/issues/175)) ([a940862](https://github.com/albertocavalcante/groovy-lsp/commit/a940862a2544b8cc6583beedc98930b6a096831a))
* **jenkins:** auto-add src directory to classpath for shared libraries ([#170](https://github.com/albertocavalcante/groovy-lsp/issues/170)) ([39d1529](https://github.com/albertocavalcante/groovy-lsp/commit/39d1529633d9c371679aa3c394617479b78877bb))
* **jenkins:** expand Jenkins step metadata and add hover documentation ([#174](https://github.com/albertocavalcante/groovy-lsp/issues/174)) ([0734f19](https://github.com/albertocavalcante/groovy-lsp/commit/0734f192257e7fb6b29fc033430d5d56e39aa5d0))
* Maven Embedder integration with build tool consolidation ([#171](https://github.com/albertocavalcante/groovy-lsp/issues/171)) ([a0f6dfb](https://github.com/albertocavalcante/groovy-lsp/commit/a0f6dfb5e2320721eb6fa2d91f3da09c245af2fe))
* **parser:** add full groovy operator support to recursive visitor ([#133](https://github.com/albertocavalcante/groovy-lsp/issues/133)) ([4188412](https://github.com/albertocavalcante/groovy-lsp/commit/4188412d394d584e49d97694488bdcbdbd8023a7))
* recursive AST visitor with comprehensive parity testing and documentation ([#132](https://github.com/albertocavalcante/groovy-lsp/issues/132)) ([813a1cf](https://github.com/albertocavalcante/groovy-lsp/commit/813a1cf01d507683615e01213efc10f9d09e876d))
* **rename:** implement workspace-wide rename refactoring ([#109](https://github.com/albertocavalcante/groovy-lsp/issues/109)) ([fa2d8ea](https://github.com/albertocavalcante/groovy-lsp/commit/fa2d8ea0eb8ff8dce25d13b2af38777f9fbe2dd2))
* surface bundled Jenkins step completions ([#147](https://github.com/albertocavalcante/groovy-lsp/issues/147)) ([b45777f](https://github.com/albertocavalcante/groovy-lsp/commit/b45777f23113d6764dd259e0d14c2df998b61183))
* use JavaParser for Java source inspection with hermetic tests ([#180](https://github.com/albertocavalcante/groovy-lsp/issues/180)) ([739ba29](https://github.com/albertocavalcante/groovy-lsp/commit/739ba29afd887b12fd91bcd5eb806375faf259be))


### Bug Fixes

* bundle groovy-macro to resolve AbstractMethodError ([#169](https://github.com/albertocavalcante/groovy-lsp/issues/169)) ([16aeda5](https://github.com/albertocavalcante/groovy-lsp/commit/16aeda52662dfacc8ec7469557ad3503e52dded3))
* **deps:** update dependency ch.qos.logback:logback-classic to v1.5.22 ([#154](https://github.com/albertocavalcante/groovy-lsp/issues/154)) ([27e65ad](https://github.com/albertocavalcante/groovy-lsp/commit/27e65ad062ef25c0adedf3712e63f42a80c1bd09))
* **deps:** update dependency com.fasterxml.jackson:jackson-bom to v2.20.1 ([#90](https://github.com/albertocavalcante/groovy-lsp/issues/90)) ([d2b2019](https://github.com/albertocavalcante/groovy-lsp/commit/d2b20190e46d7e4f92c58f39daf12a198261e096))
* **deps:** update dependency io.mockk:mockk to v1.14.7 ([#151](https://github.com/albertocavalcante/groovy-lsp/issues/151)) ([010e314](https://github.com/albertocavalcante/groovy-lsp/commit/010e314458fb792be09febb439115de0b51189f3))
* **deps:** update dependency net.jqwik:jqwik to v1.9.3 ([#94](https://github.com/albertocavalcante/groovy-lsp/issues/94)) ([ecc8b9f](https://github.com/albertocavalcante/groovy-lsp/commit/ecc8b9feb6235b85a9822a1c1c8593e5dbe662b4))
* **deps:** update dependency org.codenarc:codenarc to v3.7.0-groovy-4.0 ([#93](https://github.com/albertocavalcante/groovy-lsp/issues/93)) ([647e0fb](https://github.com/albertocavalcante/groovy-lsp/commit/647e0fb34e4c04766079907dc425616d62c7a186))
* **deps:** update dependency org.jetbrains.kotlinx:kotlinx-serialization-json to v1.9.0 ([#146](https://github.com/albertocavalcante/groovy-lsp/issues/146)) ([863958d](https://github.com/albertocavalcante/groovy-lsp/commit/863958d28bf6cc88f66e426fee572b6b44cac455))
* **deps:** update junit-framework monorepo ([#58](https://github.com/albertocavalcante/groovy-lsp/issues/58)) ([5900a0b](https://github.com/albertocavalcante/groovy-lsp/commit/5900a0b5be33cfa0c218bbcc9c5f493889bdd158))
* **deps:** update junit-framework monorepo to v6 ([#37](https://github.com/albertocavalcante/groovy-lsp/issues/37)) ([d37ecb7](https://github.com/albertocavalcante/groovy-lsp/commit/d37ecb7a57f55604ed0672209bafa7b832dcab7e))
* **deps:** update mavenresolver to v1.9.25 ([#172](https://github.com/albertocavalcante/groovy-lsp/issues/172)) ([7a1b343](https://github.com/albertocavalcante/groovy-lsp/commit/7a1b343eeecca966695a364d43c362fea047b9b8))
* ensure resource cleanup in CLI commands to prevent CI hangs ([#102](https://github.com/albertocavalcante/groovy-lsp/issues/102)) ([de2138b](https://github.com/albertocavalcante/groovy-lsp/commit/de2138b265840fa7fe792b7af4b9834e7cfefb0b))
* isolate e2e gradle cache shutdown ([#126](https://github.com/albertocavalcante/groovy-lsp/issues/126)) ([dca8ed9](https://github.com/albertocavalcante/groovy-lsp/commit/dca8ed929d466faa8a85d43db7e69cc09508045c))
* **release:** add timeout, default to self-hosted, and fix jar paths ([#106](https://github.com/albertocavalcante/groovy-lsp/issues/106)) ([1bfc5e4](https://github.com/albertocavalcante/groovy-lsp/commit/1bfc5e4363c301dd4b7116d819eadc42f265d131))
* repair nightly workflow indentation ([#128](https://github.com/albertocavalcante/groovy-lsp/issues/128)) ([a57308c](https://github.com/albertocavalcante/groovy-lsp/commit/a57308c9f545e982f01eef51dafb9566a5ef6762))
* resolve configuration cache violation in printVersion task ([#98](https://github.com/albertocavalcante/groovy-lsp/issues/98)) ([e6c955e](https://github.com/albertocavalcante/groovy-lsp/commit/e6c955e7ff5e8cbce25cda7cedebc7e20cc40c58))
* stabilize gradle classpath and navigation ([#167](https://github.com/albertocavalcante/groovy-lsp/issues/167)) ([7add06d](https://github.com/albertocavalcante/groovy-lsp/commit/7add06db9260e8cda8af6f7c88f8672c63388064))


### Performance Improvements

* optimize CI test execution and improve observability ([#101](https://github.com/albertocavalcante/groovy-lsp/issues/101)) ([4d626d0](https://github.com/albertocavalcante/groovy-lsp/commit/4d626d02828832e77282f64604bc3867d9b038fe))
* **parser:** optimize recursive visitor allocation using inline functions ([#135](https://github.com/albertocavalcante/groovy-lsp/issues/135)) ([4133bf8](https://github.com/albertocavalcante/groovy-lsp/commit/4133bf81bdabe3d64e6dc0fe21c91ef74069e386))

## [0.2.0](https://github.com/albertocavalcante/groovy-lsp/compare/v0.1.0...v0.2.0) (2025-11-23)


### ⚠ BREAKING CHANGES

* Real Groovy compilation with AST-based language features ([#3](https://github.com/albertocavalcante/groovy-lsp/issues/3))

### Features

* add build configuration for CodeNarc integration ([#40](https://github.com/albertocavalcante/groovy-lsp/issues/40)) ([7d954ca](https://github.com/albertocavalcante/groovy-lsp/commit/7d954caf1e2f593e9cd9fe8e97fc638debdf5dc0))
* add core CodeNarc integration infrastructure ([#44](https://github.com/albertocavalcante/groovy-lsp/issues/44)) ([0d9f1a8](https://github.com/albertocavalcante/groovy-lsp/commit/0d9f1a83f0db30cb3c9d99364da061a285354017))
* add e2e tests / fix workspace compilation and duplicate workspace diagnostics ([#60](https://github.com/albertocavalcante/groovy-lsp/issues/60)) ([94ff474](https://github.com/albertocavalcante/groovy-lsp/commit/94ff4744c01e70350c15c06cd0d61b49bbcb2f8c))
* add gradle-pre-commit-git-hooks with auto-fix capabilities ([#30](https://github.com/albertocavalcante/groovy-lsp/issues/30)) ([a0b40ff](https://github.com/albertocavalcante/groovy-lsp/commit/a0b40ff7fd3fe5b8ee409f51ead01cefb939cb19))
* add initial groovy parser module ([#64](https://github.com/albertocavalcante/groovy-lsp/issues/64)) ([c6a7d3a](https://github.com/albertocavalcante/groovy-lsp/commit/c6a7d3a029c40c87d6eeea2c41f5af2a7069dcc6))
* add Kotlin LSP installer script for VS Code compatible editors ([#97](https://github.com/albertocavalcante/groovy-lsp/issues/97)) ([d7270ff](https://github.com/albertocavalcante/groovy-lsp/commit/d7270ffd6ccb58fb4f84e6d7af9683d5925c2ba9))
* add lintFix task for auto-correcting code quality issues ([#27](https://github.com/albertocavalcante/groovy-lsp/issues/27)) ([a3f51f8](https://github.com/albertocavalcante/groovy-lsp/commit/a3f51f8e17d7894a144e3ef8734cdf14755a4a01))
* add Makefile with development shortcuts ([#33](https://github.com/albertocavalcante/groovy-lsp/issues/33)) ([0db7744](https://github.com/albertocavalcante/groovy-lsp/commit/0db77442873d7cbc58ed18c88d4319d4e50c5c4b))
* add OpenRewrite formatter and telemetry instrumentation ([#56](https://github.com/albertocavalcante/groovy-lsp/issues/56)) ([a3a11a4](https://github.com/albertocavalcante/groovy-lsp/commit/a3a11a4eb05319a29ea7d5fd855756cc0c6b72b4))
* add RHEL-based devcontainer ([#85](https://github.com/albertocavalcante/groovy-lsp/issues/85)) ([7d26276](https://github.com/albertocavalcante/groovy-lsp/commit/7d26276dd92dce864a243031edb68832d981df31))
* add signature help support ([#62](https://github.com/albertocavalcante/groovy-lsp/issues/62)) ([8af682c](https://github.com/albertocavalcante/groovy-lsp/commit/8af682c1f6ddf604113102e455b99823fa98103b))
* add type definition support for Groovy LSP ([#28](https://github.com/albertocavalcante/groovy-lsp/issues/28)) ([cefe0c8](https://github.com/albertocavalcante/groovy-lsp/commit/cefe0c8f864e5beda1a6f805fb516b5e3f4c2a42))
* advertise formatting and strengthen lsp coverage ([#59](https://github.com/albertocavalcante/groovy-lsp/issues/59)) ([a5b0f37](https://github.com/albertocavalcante/groovy-lsp/commit/a5b0f37327376c05857394b41170fdee3da6078a))
* **ci:** enable build scans and improve yaml formatting ([#55](https://github.com/albertocavalcante/groovy-lsp/issues/55)) ([d35b7e1](https://github.com/albertocavalcante/groovy-lsp/commit/d35b7e1ae6910e7c66e00d50381a07d1b34beed8))
* **devcontainer:** enhance devcontainer with Starship, GH CLI, and modern tools ([#86](https://github.com/albertocavalcante/groovy-lsp/issues/86)) ([52521a9](https://github.com/albertocavalcante/groovy-lsp/commit/52521a99cc2ff8d70f30679bfbb3ce81db823d95))
* extract ast/resolution package from original PR [#35](https://github.com/albertocavalcante/groovy-lsp/issues/35) ([#48](https://github.com/albertocavalcante/groovy-lsp/issues/48)) ([926d22b](https://github.com/albertocavalcante/groovy-lsp/commit/926d22b26fbb9afbd1f77fc6f26fcc45260aa750))
* implement centralized coordinate system for LSP/Groovy conversion ([#43](https://github.com/albertocavalcante/groovy-lsp/issues/43)) ([0d2164f](https://github.com/albertocavalcante/groovy-lsp/commit/0d2164f8e483cb1c17a7ee4e3f249b2821700d71))
* implement gopls-inspired CLI commands ([#96](https://github.com/albertocavalcante/groovy-lsp/issues/96)) ([5fd6689](https://github.com/albertocavalcante/groovy-lsp/commit/5fd66898a4f17d6b258f33652aee6a225569ad64))
* Improve build and test process ([#53](https://github.com/albertocavalcante/groovy-lsp/issues/53)) ([21b7d86](https://github.com/albertocavalcante/groovy-lsp/commit/21b7d865cc8348eb7f0c86e0eaec836266cdb733))
* initial Groovy LSP implementation ([2cb1190](https://github.com/albertocavalcante/groovy-lsp/commit/2cb1190bd2345becf27add226241b34b18eeda34))
* integrate SonarCloud coverage reporting in CI workflow ([#29](https://github.com/albertocavalcante/groovy-lsp/issues/29)) ([7e42933](https://github.com/albertocavalcante/groovy-lsp/commit/7e429330c72675541e2fde6aeb4c82494d8d3a99))
* non-blocking Gradle dependency resolution ([#19](https://github.com/albertocavalcante/groovy-lsp/issues/19)) ([0107e1d](https://github.com/albertocavalcante/groovy-lsp/commit/0107e1d93e85adb04bd13584acd01132516dfbac))
* provider architecture with comprehensive AST utilities and LSP features ([#5](https://github.com/albertocavalcante/groovy-lsp/issues/5)) ([916c6e1](https://github.com/albertocavalcante/groovy-lsp/commit/916c6e1ef7f994478643d97260dc7b943cc6d1d3))
* Real Groovy compilation with AST-based language features ([#3](https://github.com/albertocavalcante/groovy-lsp/issues/3)) ([5d03312](https://github.com/albertocavalcante/groovy-lsp/commit/5d033129dea9fd2950906ccbb33f89868fcbaff0))
* **tools:** add interactive issue wizard ([#82](https://github.com/albertocavalcante/groovy-lsp/issues/82)) ([61e08b7](https://github.com/albertocavalcante/groovy-lsp/commit/61e08b73ce87d01cf1befc850ff783515cf23fda))


### Bug Fixes

* **deps:** update dependency ch.qos.logback:logback-classic to v1.5.18 ([#7](https://github.com/albertocavalcante/groovy-lsp/issues/7)) ([cd9201a](https://github.com/albertocavalcante/groovy-lsp/commit/cd9201ae427b8ea844b50169cf4fc29ac9487669))
* **deps:** update dependency ch.qos.logback:logback-classic to v1.5.21 ([#38](https://github.com/albertocavalcante/groovy-lsp/issues/38)) ([0d7a95b](https://github.com/albertocavalcante/groovy-lsp/commit/0d7a95ba8f436525a48e9ef585738d76dad3dd9b))
* **deps:** update dependency com.jayway.jsonpath:json-path to v2.10.0 ([#91](https://github.com/albertocavalcante/groovy-lsp/issues/91)) ([16a6e3c](https://github.com/albertocavalcante/groovy-lsp/commit/16a6e3c287e0774e5a80f7744ae5c1e019c125e4))
* **deps:** update dependency io.mockk:mockk to v1.14.6 ([#41](https://github.com/albertocavalcante/groovy-lsp/issues/41)) ([1e8266a](https://github.com/albertocavalcante/groovy-lsp/commit/1e8266afbb90b88b30e31dd582c671bb8ea4f6ab))
* **deps:** update dependency org.assertj:assertj-core to v3.27.6 ([#42](https://github.com/albertocavalcante/groovy-lsp/issues/42)) ([23d1025](https://github.com/albertocavalcante/groovy-lsp/commit/23d10255ba72c23f664b984bec9f04b55e7a918c))
* **deps:** update dependency org.codenarc:codenarc to v3.6.0 ([#45](https://github.com/albertocavalcante/groovy-lsp/issues/45)) ([0879ecf](https://github.com/albertocavalcante/groovy-lsp/commit/0879ecfbcabc86f1ebc44fb9095b3bfaa0257650))
* **deps:** update dependency org.gradle:gradle-tooling-api to v9.2.1 ([#76](https://github.com/albertocavalcante/groovy-lsp/issues/76)) ([cf4d78b](https://github.com/albertocavalcante/groovy-lsp/commit/cf4d78b0e5f39fdfb558dd84293e420e81e75c41))
* **deps:** update dependency org.junit.jupiter:junit-jupiter to v5.13.4 ([#16](https://github.com/albertocavalcante/groovy-lsp/issues/16)) ([d06e4af](https://github.com/albertocavalcante/groovy-lsp/commit/d06e4af48b1b405998a71060c9828d8d9f17e73c))
* **deps:** update dependency org.junit.jupiter:junit-jupiter to v5.14.0 ([#36](https://github.com/albertocavalcante/groovy-lsp/issues/36)) ([8a1b8ad](https://github.com/albertocavalcante/groovy-lsp/commit/8a1b8addd312ab7a1a9f58ba67c16a1e5a7a0138))
* **deps:** update dependency org.openrewrite:rewrite-groovy to v8.66.1 ([#57](https://github.com/albertocavalcante/groovy-lsp/issues/57)) ([e3185e5](https://github.com/albertocavalcante/groovy-lsp/commit/e3185e50effad073cd687e6860925b2bde96f3c9))
* **deps:** update dependency org.openrewrite:rewrite-groovy to v8.67.0 ([#77](https://github.com/albertocavalcante/groovy-lsp/issues/77)) ([728a3a4](https://github.com/albertocavalcante/groovy-lsp/commit/728a3a4f2bbd83759db1c5deb23c6d6ade9b21ff))
* **deps:** update dependency org.slf4j:slf4j-api to v2.0.17 ([#12](https://github.com/albertocavalcante/groovy-lsp/issues/12)) ([2dff2cf](https://github.com/albertocavalcante/groovy-lsp/commit/2dff2cf14a1eb1364e2f8ebcbd9738c2b53e9a1f))
* **deps:** update groovy monorepo to v4.0.29 ([#52](https://github.com/albertocavalcante/groovy-lsp/issues/52)) ([afab171](https://github.com/albertocavalcante/groovy-lsp/commit/afab171f0480f01ede014548a534f52ef776fd7e))
* implement CodeNarc diagnostic triplication fix ([#46](https://github.com/albertocavalcante/groovy-lsp/issues/46)) ([9db68e6](https://github.com/albertocavalcante/groovy-lsp/commit/9db68e611a80f19f75fe41b3743bbc9e10723537))
* resolve critical detekt issues and failing hover test ([#14](https://github.com/albertocavalcante/groovy-lsp/issues/14)) ([998343c](https://github.com/albertocavalcante/groovy-lsp/commit/998343cd08bc22c227ea7dc8301470fce938fa98))
* resolve detekt code quality warnings ([#26](https://github.com/albertocavalcante/groovy-lsp/issues/26)) ([d91d452](https://github.com/albertocavalcante/groovy-lsp/commit/d91d45281f46796bc6b9a27fee2e5eef373ecb6a))
