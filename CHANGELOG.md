# Changelog

## [0.5.5](https://github.com/albertocavalcante/gvy/compare/v0.5.4...v0.5.5) (2026-11-07)


### Features

* make silent compile degradation visible ([#1143](https://github.com/albertocavalcante/gvy/issues/1143)) ([0fbc8d3](https://github.com/albertocavalcante/gvy/commit/0fbc8d3a0f5908f8c66273383d3e0e0661d45ecd))
* **parser:** stub missing classes so Groovy resolves everything else ([#1146](https://github.com/albertocavalcante/gvy/issues/1146)) ([0dfae21](https://github.com/albertocavalcante/gvy/commit/0dfae21bd88cef207720d1d05ca6e02a0c2667da))
* **semantics:** attribute names inside closures passed to delegate-neutral GDK calls ([#1152](https://github.com/albertocavalcante/gvy/issues/1152)) ([b73e1d6](https://github.com/albertocavalcante/gvy/commit/b73e1d631bfddfb948f2db5994de0030e83b126a))


### Bug Fixes

* **deps:** update dependency com.github.javaparser:javaparser-core to v3.28.2 ([#1127](https://github.com/albertocavalcante/gvy/issues/1127)) ([c563ac7](https://github.com/albertocavalcante/gvy/commit/c563ac7cde5c663218d89eff120c8a67848c8f4c))
* **deps:** update dependency io.github.classgraph:classgraph to v4.8.199 ([#1128](https://github.com/albertocavalcante/gvy/issues/1128)) ([4b28832](https://github.com/albertocavalcante/gvy/commit/4b28832df3116eea27d74c1a6f75eea474e7a08b))
* **deps:** update dependency io.mockk:mockk to v1.14.11 ([#1133](https://github.com/albertocavalcante/gvy/issues/1133)) ([da33e7c](https://github.com/albertocavalcante/gvy/commit/da33e7c0c09eaac120fc6012ceb40d9090761b2c))
* **deps:** update dependency org.assertj:assertj-core to v3.27.7 ([#1134](https://github.com/albertocavalcante/gvy/issues/1134)) ([7609fe3](https://github.com/albertocavalcante/gvy/commit/7609fe3e996ce0f01b6a117ac3abd29475921c90))
* **deps:** update dependency org.slf4j:slf4j-api to v2.0.20 ([#1135](https://github.com/albertocavalcante/gvy/issues/1135)) ([ca118dd](https://github.com/albertocavalcante/gvy/commit/ca118dd06209f392a6b598c1d637c8634bce18dc))
* **deps:** update groovy monorepo to v4.0.33 ([#1136](https://github.com/albertocavalcante/gvy/issues/1136)) ([896c044](https://github.com/albertocavalcante/gvy/commit/896c0442301ac497527c8c8136d612b47e471d26))
* **deps:** update wire to v5.5.1 ([#1138](https://github.com/albertocavalcante/gvy/issues/1138)) ([f706b66](https://github.com/albertocavalcante/gvy/commit/f706b66850b2d7557017f55652acc4dffdd0b640))
* **gls:** index every workspace file semantically, and keep the index current ([#1155](https://github.com/albertocavalcante/gvy/issues/1155)) ([ba88cbf](https://github.com/albertocavalcante/gvy/commit/ba88cbfbfa62faf038775cda408811295c98e4d2))
* **gls:** keep the symbol index in step with file content ([#1153](https://github.com/albertocavalcante/gvy/issues/1153)) ([362d85f](https://github.com/albertocavalcante/gvy/commit/362d85f11c482a1dfb6017bf38a88d79987ba44e))
* **lsp:** apply live settings and preserve Windows URIs ([#1156](https://github.com/albertocavalcante/gvy/issues/1156)) ([a77d27c](https://github.com/albertocavalcante/gvy/commit/a77d27cabab4e75d9405d68fcf4b9eb1897450f3))
* **lsp:** preserve exact references without AST caches ([#1157](https://github.com/albertocavalcante/gvy/issues/1157)) ([e7535bb](https://github.com/albertocavalcante/gvy/commit/e7535bb5e6d0f33a975f76d7ef8cfcc2249ee83d))
* **lsp:** retain client versions and reject stale changes ([#1158](https://github.com/albertocavalcante/gvy/issues/1158)) ([fa6d967](https://github.com/albertocavalcante/gvy/commit/fa6d967581a39746c56a5a1b3bff7e57d2b0459d))
* **parser:** keep a broken workspace file from costing every file its types ([#1140](https://github.com/albertocavalcante/gvy/issues/1140)) ([b5b1149](https://github.com/albertocavalcante/gvy/commit/b5b1149b0b86b18202e6dc156510a172ad186d51))
* **semantics:** give every symbol a distinct id and attribute only certain references ([#1130](https://github.com/albertocavalcante/gvy/issues/1130)) ([b63b783](https://github.com/albertocavalcante/gvy/commit/b63b7834369b9324f5b03b2264e3aabc7f36dfcb))
* **semantics:** make SemanticDB ranges half-open LSP ranges ([#1154](https://github.com/albertocavalcante/gvy/issues/1154)) ([f46f371](https://github.com/albertocavalcante/gvy/commit/f46f371e6f7c97890090b8888b0ada40858e0838))


### Performance Improvements

* **gls:** bound the compilation cache with LRU eviction ([#1125](https://github.com/albertocavalcante/gvy/issues/1125)) ([ce8ce12](https://github.com/albertocavalcante/gvy/commit/ce8ce12abedd67cb758c1ec58149825e60013e83))

## [0.5.4](https://github.com/albertocavalcante/gvy/compare/v0.5.3...v0.5.4) (2026-11-02)


### Features

* **gls:** find references and incoming calls across files ([#1112](https://github.com/albertocavalcante/gvy/issues/1112)) ([8dc2518](https://github.com/albertocavalcante/gvy/commit/8dc2518593e2dacbcba1aa15872c7cc20bffff86))


### Bug Fixes

* **gls:** debounce diagnostics, exit when the editor dies, enforce maxWorkspaceFiles ([#1108](https://github.com/albertocavalcante/gvy/issues/1108)) ([96946d7](https://github.com/albertocavalcante/gvy/commit/96946d78408dbda3e28b545a722927e9aff37dd5))
* **gls:** report the identifier, and only reachable calls, as references ([#1111](https://github.com/albertocavalcante/gvy/issues/1111)) ([bace154](https://github.com/albertocavalcante/gvy/commit/bace154c330f212b5159cecdcab0ff990cea2aad))


### Performance Improvements

* **gls:** stop compiles when the request that started them is cancelled ([#1123](https://github.com/albertocavalcante/gvy/issues/1123)) ([413bdb7](https://github.com/albertocavalcante/gvy/commit/413bdb792d159c66998c76109a26eb4975fc8819))


### Reverts

* **vscode:** restore pnpm.onlyBuiltDependencies in the extension package.json ([#1117](https://github.com/albertocavalcante/gvy/issues/1117)) ([251dbcc](https://github.com/albertocavalcante/gvy/commit/251dbcc9f87c66b71f2bf4349655e0564ebf03ea))

## [0.5.3](https://github.com/albertocavalcante/gvy/compare/v0.5.2...v0.5.3) (2026-11-02)


### Features

* **vscode:** add groovy.server.vmargs and document GitHub release installs ([#1106](https://github.com/albertocavalcante/gvy/issues/1106)) ([d98b340](https://github.com/albertocavalcante/gvy/commit/d98b340ee0cc32bd417252eab59baf56c831f850))


### Bug Fixes

* **bsp:** replay compilation events to clients that join a running compile ([#1102](https://github.com/albertocavalcante/gvy/issues/1102)) ([5222af9](https://github.com/albertocavalcante/gvy/commit/5222af9924a3ff9bc0c3ac78f4b194b8e96d4487))
* **gls:** compile each file together with the other open, unsaved files ([#1103](https://github.com/albertocavalcante/gvy/issues/1103)) ([94c39a4](https://github.com/albertocavalcante/gvy/commit/94c39a4fb54e74f46a15a36dc29f09a6dacdde7a))
* **gls:** make rename replace only the symbol's name ([#1105](https://github.com/albertocavalcante/gvy/issues/1105)) ([80529f0](https://github.com/albertocavalcante/gvy/commit/80529f0b244173856d71d5404f097b8819812881))

## [0.5.2](https://github.com/albertocavalcante/gvy/compare/v0.5.1...v0.5.2) (2026-11-02)


### Bug Fixes

* **vscode:** run Gradle tests whose names or paths cmd keeps literal in quotes ([#1094](https://github.com/albertocavalcante/gvy/issues/1094)) ([1184e87](https://github.com/albertocavalcante/gvy/commit/1184e8727b6ccae6bacebf40c319519beb0a4751))

## [0.5.1](https://github.com/albertocavalcante/gvy/compare/v0.5.0...v0.5.1) (2026-11-02)


### Bug Fixes

* **bazel:** put the runtime jars Gradle gets transitively on test classpaths ([#1092](https://github.com/albertocavalcante/gvy/issues/1092)) ([2becf58](https://github.com/albertocavalcante/gvy/commit/2becf58a8c8a89944eb272347ae6281e34ce7f82))
* **release:** build release artifacts from gls/ with pnpm ([#1091](https://github.com/albertocavalcante/gvy/issues/1091)) ([ff5161d](https://github.com/albertocavalcante/gvy/commit/ff5161dd821f9b2eda80847e7d3149c79a6f6c95))

## [0.5.0](https://github.com/albertocavalcante/gvy/compare/v0.4.8...v0.5.0) (2026-11-02)


### ⚠ BREAKING CHANGES

* **indexer:** every method symbol with parameters changes in SCIP and LSIF output; consumers must re-index.
* **codenarc:** Jenkins ruleset no longer includes generic CodeNarc rules
* **ext:** Extension now requires pnpm instead of npm
* **cli:** None - CLI interface remains compatible

### Features

* add make targets to install extension to VS Code forks ([#486](https://github.com/albertocavalcante/gvy/issues/486)) ([dec71bc](https://github.com/albertocavalcante/gvy/commit/dec71bc356740b353b27454cd8e1f62c8314a01d))
* Add SCIP/LSIF index export support ([#579](https://github.com/albertocavalcante/gvy/issues/579)) ([918d21c](https://github.com/albertocavalcante/gvy/commit/918d21c812a01013f9a163eb4895a17b9f92ba45))
* add server log level configuration with VSCode UX ([#509](https://github.com/albertocavalcante/gvy/issues/509)) ([ba86cf6](https://github.com/albertocavalcante/gvy/commit/ba86cf6412e891289c5fae5d66a0207220251efe))
* **agent:** exclude lock files from AI diff analysis ([#966](https://github.com/albertocavalcante/gvy/issues/966)) ([12319b5](https://github.com/albertocavalcante/gvy/commit/12319b525aa93faba2646e852967cf791480bb4d))
* **arch:** introduce ProjectStrategy pattern for project-type decoupling ([#724](https://github.com/albertocavalcante/gvy/issues/724)) ([e05aac1](https://github.com/albertocavalcante/gvy/commit/e05aac1bb111ce1cce8a8b7cc898e35a67e1c372)), closes [#700](https://github.com/albertocavalcante/gvy/issues/700)
* **bsp:** add Maven BSP server implementation ([#814](https://github.com/albertocavalcante/gvy/issues/814)) ([69098c1](https://github.com/albertocavalcante/gvy/commit/69098c13a1b0a037d335ec9b8e4659b4c4855bb0))
* **bsp:** add reusable BSP core infrastructure ([#709](https://github.com/albertocavalcante/gvy/issues/709)) ([31ada3f](https://github.com/albertocavalcante/gvy/commit/31ada3f072565d5e332a3d988fd226cc865df662))
* **build-tool:** add ErrorMessages with actionable user guidance ([#685](https://github.com/albertocavalcante/gvy/issues/685)) ([a1ccbd3](https://github.com/albertocavalcante/gvy/commit/a1ccbd389a47a5adb490a8586b96344d093ebde4))
* **build-tool:** add Warning status and WorkspaceResolution.status field ([#687](https://github.com/albertocavalcante/gvy/issues/687)) ([6f05ff3](https://github.com/albertocavalcante/gvy/commit/6f05ff3df70b618e615e88af28f80c35992fd1d7))
* **build-tool:** reliability and error handling improvements ([#682](https://github.com/albertocavalcante/gvy/issues/682)) ([9cb377a](https://github.com/albertocavalcante/gvy/commit/9cb377ae2d2aa1a68209869132e07c745efc8bea))
* **build:** add Bazel 9 dual build system setup ([#822](https://github.com/albertocavalcante/gvy/issues/822)) ([9bd61d3](https://github.com/albertocavalcante/gvy/commit/9bd61d3da3185785f2ed030dceaf20768ef0cbb7))
* **build:** add Buck2 as third build system for comparison ([#1036](https://github.com/albertocavalcante/gvy/issues/1036)) ([6f343fc](https://github.com/albertocavalcante/gvy/commit/6f343fc59413be4c2e53c5bd16eb2a319a263bd9))
* **build:** add JVM dependency management via jvmdeer ([#1040](https://github.com/albertocavalcante/gvy/issues/1040)) ([b59820b](https://github.com/albertocavalcante/gvy/commit/b59820babff5e0dada4d07119ef8ebc6857d4f4a))
* **build:** add Kotlin 2.3.0 toolchain for Buck2 ([#1039](https://github.com/albertocavalcante/gvy/issues/1039)) ([8c75212](https://github.com/albertocavalcante/gvy/commit/8c75212ceb770353bf57d9c20409649974278adc))
* **ci:** add claude auto-fix workflow for automated bug fixes ([#560](https://github.com/albertocavalcante/gvy/issues/560)) ([741de64](https://github.com/albertocavalcante/gvy/commit/741de649b10dd67419c119e7d24e23b4b972c8b5))
* **cli:** Migrate to Clikt with NO_COLOR support ([#470](https://github.com/albertocavalcante/gvy/issues/470)) ([00f0550](https://github.com/albertocavalcante/gvy/commit/00f0550583cfdd29cf835b73894628def32232de))
* **codeaction:** add SpaceBeforeOpeningBrace quick fix ([#752](https://github.com/albertocavalcante/gvy/issues/752)) ([9ba78ee](https://github.com/albertocavalcante/gvy/commit/9ba78eefad54ecf04d1c73eb65803f067fc8847b))
* **codeaction:** add UnnecessaryGString quick fix ([#784](https://github.com/albertocavalcante/gvy/issues/784)) ([5e9b45d](https://github.com/albertocavalcante/gvy/commit/5e9b45d9f7cd294a295b1ea3a3914f5a672183bb))
* **codenarc:** implement AST-aware diagnostic positioning (Phase 1 POC) ([#720](https://github.com/albertocavalcante/gvy/issues/720)) ([37b7202](https://github.com/albertocavalcante/gvy/commit/37b720201b016e1f83f6838ec06292c0e0814e75)), closes [#674](https://github.com/albertocavalcante/gvy/issues/674)
* **commands:** sync Claude/Gemini commands with .agent workflows ([#823](https://github.com/albertocavalcante/gvy/issues/823)) ([e903ad3](https://github.com/albertocavalcante/gvy/commit/e903ad3535b24afc0ee5ba2b231b1595a18d03c4))
* **completion:** add AST-based completion context detection ([#756](https://github.com/albertocavalcante/gvy/issues/756)) ([0a90cc0](https://github.com/albertocavalcante/gvy/commit/0a90cc0f4570799e544c2943a64236c97012fa7f)), closes [#657](https://github.com/albertocavalcante/gvy/issues/657)
* **completion:** add CompletionPriority for consistent sorting ([#1035](https://github.com/albertocavalcante/gvy/issues/1035)) ([194101b](https://github.com/albertocavalcante/gvy/commit/194101b5e95e904eb026b704e8300a43febbc8d7))
* **completion:** add fallback completions and deduplicate keywords ([#863](https://github.com/albertocavalcante/gvy/issues/863)) ([37450e8](https://github.com/albertocavalcante/gvy/commit/37450e842c77ed114563fbb354bc7ef761cbbb26)), closes [#854](https://github.com/albertocavalcante/gvy/issues/854) [#857](https://github.com/albertocavalcante/gvy/issues/857) [#859](https://github.com/albertocavalcante/gvy/issues/859)
* **config:** switch default engine to Core ([#531](https://github.com/albertocavalcante/gvy/issues/531)) ([#536](https://github.com/albertocavalcante/gvy/issues/536)) ([cc7621c](https://github.com/albertocavalcante/gvy/commit/cc7621c984fae55b85206498bbcb33c416be412d))
* **core:** expose declaration AST node in resolution API ([#529](https://github.com/albertocavalcante/gvy/issues/529)) ([#534](https://github.com/albertocavalcante/gvy/issues/534)) ([57f754c](https://github.com/albertocavalcante/gvy/commit/57f754c7413bfe8ab4bcf873732a3edcf733b06a))
* **core:** functionality for contextual completions ([#530](https://github.com/albertocavalcante/gvy/issues/530)) ([#535](https://github.com/albertocavalcante/gvy/issues/535)) ([99aba40](https://github.com/albertocavalcante/gvy/commit/99aba40e68cb795e6d0ef5adeea4fdaf685a370a))
* **definition:** enable cross-file resolution and enhance semantic model ([#702](https://github.com/albertocavalcante/gvy/issues/702)) ([be12443](https://github.com/albertocavalcante/gvy/commit/be124439db9a376141d5c08d82ec22a4617cba06))
* **deps:** add dependency tree view for VS Code ([#899](https://github.com/albertocavalcante/gvy/issues/899)) ([37b371e](https://github.com/albertocavalcante/gvy/commit/37b371e3ed02e72cf7303e2c06e7176f17494dcd))
* **diagnostics:** Add custom rules framework with analysis type classification ([#583](https://github.com/albertocavalcante/gvy/issues/583)) ([37ef294](https://github.com/albertocavalcante/gvy/commit/37ef294ed7dbcdd10c85751e6ef20dec3aa43967))
* **diagnostics:** add SARIF output format for check command ([#718](https://github.com/albertocavalcante/gvy/issues/718)) ([76eb189](https://github.com/albertocavalcante/gvy/commit/76eb1890cdb4403906f1c685356dbb7f7c1d24a7))
* **engine:** add parser engine adapter infrastructure ([#525](https://github.com/albertocavalcante/gvy/issues/525)) ([09a2a01](https://github.com/albertocavalcante/gvy/commit/09a2a018c002897dad219d290d50d75314243427))
* **engine:** implement CoreDefinitionService and CoreCompletionService ([#532](https://github.com/albertocavalcante/gvy/issues/532)) ([2d70f3b](https://github.com/albertocavalcante/gvy/commit/2d70f3b13347b18cc3290543f55198e113fd1e2e))
* **engine:** integrate OpenRewrite parser into LSP and Viz ([#626](https://github.com/albertocavalcante/gvy/issues/626)) ([5956e30](https://github.com/albertocavalcante/gvy/commit/5956e30cd7b0dd140d616d0b929d510ecde94e24))
* **errors:** surface JDK/Groovy compatibility errors to users ([#746](https://github.com/albertocavalcante/gvy/issues/746)) ([9e4da9a](https://github.com/albertocavalcante/gvy/commit/9e4da9a613b9c7aef00f96371054dda11933e65c))
* **ext:** auto-build local JAR in monorepo by default ([#484](https://github.com/albertocavalcante/gvy/issues/484)) ([4ffd31d](https://github.com/albertocavalcante/gvy/commit/4ffd31d4eae5383bb90201c1ac9fb07e72be8fbe))
* **extension:** add CodeLens provider for test Run|Debug buttons ([#735](https://github.com/albertocavalcante/gvy/issues/735)) ([7938f7b](https://github.com/albertocavalcante/gvy/commit/7938f7b164669affe1580869cd1710aed18a2612)), closes [#731](https://github.com/albertocavalcante/gvy/issues/731)
* **ext:** improve Java detection UX with jdk-utils and login shell fallback ([#488](https://github.com/albertocavalcante/gvy/issues/488)) ([02c5d3e](https://github.com/albertocavalcante/gvy/commit/02c5d3e01fa6950df839d1ec1a53359ea921bb76))
* **ext:** status bar excellence - smart visibility, menus, Language Status ([#490](https://github.com/albertocavalcante/gvy/issues/490)) ([9b4a67e](https://github.com/albertocavalcante/gvy/commit/9b4a67ed2bbaa1449c831d9b0579ba60077dc7a8))
* **gls:** implement textDocument/documentHighlight ([#474](https://github.com/albertocavalcante/gvy/issues/474)) ([713498b](https://github.com/albertocavalcante/gvy/commit/713498bbd04c905a953f03691b2597444d20f8d5))
* **gradle:** add toolchain provisioning error detection ([#679](https://github.com/albertocavalcante/gvy/issues/679)) ([ae764bb](https://github.com/albertocavalcante/gvy/commit/ae764bb0e311edc9bb1e9cd346f50f73877e7e1a))
* **gradle:** implement robust JDK compatibility and ship workflow ([#590](https://github.com/albertocavalcante/gvy/issues/590)) ([cab5e5e](https://github.com/albertocavalcante/gvy/commit/cab5e5e03cbf1bfdfcea5a60e67931c5b5af05cf))
* **groovy-common:** implement GroovyDoc parser ([#695](https://github.com/albertocavalcante/gvy/issues/695)) ([858f1f4](https://github.com/albertocavalcante/gvy/commit/858f1f45d8e5cadff14826ff4ee8980031c7398c))
* **groovy-jenkins:** Fix Library parsing with shebangs ([#506](https://github.com/albertocavalcante/gvy/issues/506)) ([bbee50a](https://github.com/albertocavalcante/gvy/commit/bbee50a476a5e55e28740029efb6211e1a154707))
* **groovyparser-core:** Add standalone Groovy parser library ([#517](https://github.com/albertocavalcante/gvy/issues/517)) ([4ebf769](https://github.com/albertocavalcante/gvy/commit/4ebf76958fd94f4dca9e37d8e610b31903985973))
* **hover:** phase 1 - groovydoc integration and inline tags ([#713](https://github.com/albertocavalcante/gvy/issues/713)) ([26ea879](https://github.com/albertocavalcante/gvy/commit/26ea87984909f6674e93810454335e44ecb9e7ae)), closes [#706](https://github.com/albertocavalcante/gvy/issues/706)
* **hover:** Phase 3 - visual hierarchy and documentation sections ([#734](https://github.com/albertocavalcante/gvy/issues/734)) ([7f4811d](https://github.com/albertocavalcante/gvy/commit/7f4811d5849d147e5ab0e69e7f7af7fa3e3f0bbe)), closes [#727](https://github.com/albertocavalcante/gvy/issues/727)
* **hover:** Phase 4 - Enhanced Hover Strategies ([#737](https://github.com/albertocavalcante/gvy/issues/737)) ([d8ccae3](https://github.com/albertocavalcante/gvy/commit/d8ccae3701c92e301954f0c604ba7918d1b09112)), closes [#728](https://github.com/albertocavalcante/gvy/issues/728)
* Implement E2E Testing Infrastructure for Jenkins Static Extractor ([#519](https://github.com/albertocavalcante/gvy/issues/519)) ([e4f0bae](https://github.com/albertocavalcante/gvy/commit/e4f0bae76e7a9858cc5c4015acfe3f4a0f4b3dce))
* improve codebase health and automate pipeline schema ([#612](https://github.com/albertocavalcante/gvy/issues/612)) ([bf55bcd](https://github.com/albertocavalcante/gvy/commit/bf55bcdfa5bfa2446dfd334d647013ee7caab310))
* improve error handling UX with polymorphic ErrorDetails ([#505](https://github.com/albertocavalcante/gvy/issues/505)) ([c2e4d4a](https://github.com/albertocavalcante/gvy/commit/c2e4d4a0336b08c1a0d25842fc1bd1a5836097c8))
* integrate JenkinsMetadataService and static metadata ([#523](https://github.com/albertocavalcante/gvy/issues/523)) ([4badcf4](https://github.com/albertocavalcante/gvy/commit/4badcf415e9fcefbbe7eb215f83f8fc2b0328254))
* **jenkins:** expand pipeline metadata with credentials, kubernetes, and workflow steps ([#595](https://github.com/albertocavalcante/gvy/issues/595)) ([96e77a8](https://github.com/albertocavalcante/gvy/commit/96e77a85f361b091d15c7c0697bbdc289c602d47))
* **jenkins:** implement context-aware declarative pipeline completions ([#603](https://github.com/albertocavalcante/gvy/issues/603)) ([1a3a86c](https://github.com/albertocavalcante/gvy/commit/1a3a86c1ce1aa21d5b707faff144484fc69674de))
* **jenkins:** implement stub generator for missing types ([#510](https://github.com/albertocavalcante/gvy/issues/510)) ([725e91d](https://github.com/albertocavalcante/gvy/commit/725e91d2e6d97e42a230cc20acd35c1d8b754692))
* **lsp:** add bounded workspace selection infrastructure ([#773](https://github.com/albertocavalcante/gvy/issues/773)) ([8510cff](https://github.com/albertocavalcante/gvy/commit/8510cffaed5610f23d67e68fc744529e02b49552)), closes [#743](https://github.com/albertocavalcante/gvy/issues/743)
* **lsp:** add configuration fingerprinting for cache coherency ([#771](https://github.com/albertocavalcante/gvy/issues/771)) ([fad2a3b](https://github.com/albertocavalcante/gvy/commit/fad2a3bc52b71ffd6b1988353f94ff2bcd42a7d1))
* **lsp:** add groovy/getBuildToolInfo request for build tool detection ([#719](https://github.com/albertocavalcante/gvy/issues/719)) ([7ada866](https://github.com/albertocavalcante/gvy/commit/7ada866b3da3809fe7ebc99827ba03ebdad3793c)), closes [#715](https://github.com/albertocavalcante/gvy/issues/715)
* **lsp:** add language engine abstraction layer ([#511](https://github.com/albertocavalcante/gvy/issues/511)) ([7ce3101](https://github.com/albertocavalcante/gvy/commit/7ce3101ad297c0c5feedcdacdafdd95131504833))
* **lsp:** add semantic token support for method calls ([#766](https://github.com/albertocavalcante/gvy/issues/766)) ([c3beda6](https://github.com/albertocavalcante/gvy/commit/c3beda6c526aaf4875e2e0660679264cf55aa791)), closes [#762](https://github.com/albertocavalcante/gvy/issues/762)
* **lsp:** add visual dimming for unused imports ([#789](https://github.com/albertocavalcante/gvy/issues/789)) ([343b2f7](https://github.com/albertocavalcante/gvy/commit/343b2f7d94be97c40e932ffa327d5dd8dcdd3316))
* **lsp:** AST Explorer & CodeLens Fixes ([#625](https://github.com/albertocavalcante/gvy/issues/625)) ([fc0bc2d](https://github.com/albertocavalcante/gvy/commit/fc0bc2d5eb0bc5ffbbcc70b3505d191e92ddf0e3))
* **lsp:** enhance parser fault tolerance and definition navigation precision ([#866](https://github.com/albertocavalcante/gvy/issues/866)) ([bfef840](https://github.com/albertocavalcante/gvy/commit/bfef840e82aa6e0720390ddc8251de6022a8502a))
* **lsp:** extend semantic tokens to all Groovy files ([#757](https://github.com/albertocavalcante/gvy/issues/757)) ([3b2b64a](https://github.com/albertocavalcante/gvy/commit/3b2b64a1fac4daa9260ded8f672e6040f29569e0))
* **lsp:** fix JNA warnings and improve build tool detection ([#739](https://github.com/albertocavalcante/gvy/issues/739)) ([0befdae](https://github.com/albertocavalcante/gvy/commit/0befdae427afe9d72df3960895ec32445bcbf0dd))
* **lsp:** Implement Advanced Type-Aware Signature Help ([#468](https://github.com/albertocavalcante/gvy/issues/468)) ([273c357](https://github.com/albertocavalcante/gvy/commit/273c357c39470347118273b652752873d621a924))
* **lsp:** Implement Call Hierarchy ([#565](https://github.com/albertocavalcante/gvy/issues/565)) ([e400e51](https://github.com/albertocavalcante/gvy/commit/e400e5132fa9765fa11e2b377a4f1c262d5dcf13))
* **lsp:** implement core Inlay Hints infrastructure ([#569](https://github.com/albertocavalcante/gvy/issues/569)) ([c604927](https://github.com/albertocavalcante/gvy/commit/c60492790fe9d283980c894b48807a78506f8ed5))
* **lsp:** implement folding range support ([#503](https://github.com/albertocavalcante/gvy/issues/503)) ([a6dcef2](https://github.com/albertocavalcante/gvy/commit/a6dcef2b2d99e3e21f716568b386bb694002f218))
* **lsp:** implement textDocument/implementation ([#413](https://github.com/albertocavalcante/gvy/issues/413)) ([#475](https://github.com/albertocavalcante/gvy/issues/475)) ([1fcff7e](https://github.com/albertocavalcante/gvy/commit/1fcff7ed1ba06345b317b9343aba3b38b1908de6))
* **lsp:** implement textDocument/signatureHelp ([#467](https://github.com/albertocavalcante/gvy/issues/467)) ([a543253](https://github.com/albertocavalcante/gvy/commit/a5432538317e1cbaa107726ded2fb365c39e48f4))
* **lsp:** implement transitive hierarchy support for implementation provider ([#477](https://github.com/albertocavalcante/gvy/issues/477)) ([694023d](https://github.com/albertocavalcante/gvy/commit/694023dc9c2ff863f83c6240e90b1492461747cb))
* **lsp:** improve GDK and classpath method resolution ([#829](https://github.com/albertocavalcante/gvy/issues/829)) ([63166fe](https://github.com/albertocavalcante/gvy/commit/63166fe09bbdb4746909d9bd269689dd57ea53de))
* **lsp:** integrate bounded workspace selection into CompilationOrchestrator ([#777](https://github.com/albertocavalcante/gvy/issues/777)) ([ac7d8b3](https://github.com/albertocavalcante/gvy/commit/ac7d8b359135d3f58d7ff1db70df4b83a5a331d2))
* **lsp:** integrate GroovySemanticTokenProvider into TextDocumentService ([#765](https://github.com/albertocavalcante/gvy/issues/765)) ([bf95991](https://github.com/albertocavalcante/gvy/commit/bf95991f86ca3f80a0339c7847863ac88662cab2)), closes [#761](https://github.com/albertocavalcante/gvy/issues/761)
* **lsp:** resolve cross-file inlay hints ([#574](https://github.com/albertocavalcante/gvy/issues/574)) ([9e63452](https://github.com/albertocavalcante/gvy/commit/9e634525cdf8f352b66e79ff0c746663af94f803))
* migrate logging to kotlin-logging for idiomatic Kotlin ([#831](https://github.com/albertocavalcante/gvy/issues/831)) ([270a3cb](https://github.com/albertocavalcante/gvy/commit/270a3cbc51f064cc98db40295cbd8c9703eacb57))
* **openrewrite:** add 17 new CodeNarc recipes (Waves 5-6) ([#675](https://github.com/albertocavalcante/gvy/issues/675)) ([91ec3b4](https://github.com/albertocavalcante/gvy/commit/91ec3b497f1e4b2047e2297997b921cc3c6ed326))
* **parser:** add ParseMode for isolated on-demand compilation ([#768](https://github.com/albertocavalcante/gvy/issues/768)) ([110c0ba](https://github.com/albertocavalcante/gvy/commit/110c0ba575bfb2e75ec614a9d944caf468dece59)), closes [#743](https://github.com/albertocavalcante/gvy/issues/743)
* **parser:** add parser/api module with common interfaces ([#558](https://github.com/albertocavalcante/gvy/issues/558)) ([d49f34a](https://github.com/albertocavalcante/gvy/commit/d49f34a5dd13d0d8fc4eabc84b5fe9fdf4f62966))
* **parser:** add TokenEditDistance for incremental parsing ([#754](https://github.com/albertocavalcante/gvy/issues/754)) ([9595f6e](https://github.com/albertocavalcante/gvy/commit/9595f6ebd040c90fa1227984501a252c1b9f30e8))
* **parser:** implement ParserProvider in native and core modules ([#559](https://github.com/albertocavalcante/gvy/issues/559)) ([e0bb7d0](https://github.com/albertocavalcante/gvy/commit/e0bb7d0fcabab8beda72151d2e80bd04f8946ccf))
* **reports:** create reports module with shared utilities ([#935](https://github.com/albertocavalcante/gvy/issues/935)) ([7e35f7e](https://github.com/albertocavalcante/gvy/commit/7e35f7e4bcc13561b7e937518b4c737d276b9f52))
* **reports:** create reports/coverage module and move jacoco parser ([#941](https://github.com/albertocavalcante/gvy/issues/941)) ([14bb402](https://github.com/albertocavalcante/gvy/commit/14bb402ac2217ee0aa0cb03f7fdab9e96e79a3ff))
* **reports:** create reports/results module with test result parsing ([#942](https://github.com/albertocavalcante/gvy/issues/942)) ([c994b6d](https://github.com/albertocavalcante/gvy/commit/c994b6d6d7b68bd8f8a59ce697e9e91e80105575))
* **rewrite-codenarc:** add openrewrite recipes for codenarc auto-fixes ([#667](https://github.com/albertocavalcante/gvy/issues/667)) ([323f1ac](https://github.com/albertocavalcante/gvy/commit/323f1ac4775ad661e5b1dd8e6fd20fdc1b10b1d4))
* **schemas:** create Wire protobuf module for type sharing ([#944](https://github.com/albertocavalcante/gvy/issues/944)) ([a30f84d](https://github.com/albertocavalcante/gvy/commit/a30f84d5577bd7333312335ed591d325fe26a67c))
* **semantics:** add DeclarationWalker for centralized block traversal and map key completion ([#839](https://github.com/albertocavalcante/gvy/issues/839)) ([916b5ee](https://github.com/albertocavalcante/gvy/commit/916b5eee68f2ab18f6ac8c2442b0708e7493d52c))
* **semantics:** add delegation resolution for implicit receivers ([#755](https://github.com/albertocavalcante/gvy/issues/755)) ([9062959](https://github.com/albertocavalcante/gvy/commit/9062959842372edd9604222b62edb16f70a7f1a5)), closes [#638](https://github.com/albertocavalcante/gvy/issues/638)
* **semantics:** add OpenRewrite adapter (Phase 5) ([#693](https://github.com/albertocavalcante/gvy/issues/693)) ([7b0f85b](https://github.com/albertocavalcante/gvy/commit/7b0f85b9219173435e11f4a839c6160c775f6d8b))
* **semantics:** complete Either-based calculator migration ([#705](https://github.com/albertocavalcante/gvy/issues/705)) ([8fb9f0f](https://github.com/albertocavalcante/gvy/commit/8fb9f0f7fc421c903ab0239bef066129f0205331))
* **semantics:** Full Cross-File Type Resolution ([#615](https://github.com/albertocavalcante/gvy/issues/615)) ([#659](https://github.com/albertocavalcante/gvy/issues/659)) ([c64a3be](https://github.com/albertocavalcante/gvy/commit/c64a3be3694be08f36bd5b8871ff7c251bcb5805))
* **semantics:** implement foundational types and LUB logic ([#634](https://github.com/albertocavalcante/gvy/issues/634)) ([795f32b](https://github.com/albertocavalcante/gvy/commit/795f32bca128fbf63318f8896bf3403ffa99a4a4))
* **semantics:** migrate Closure, List, Map, Elvis, Ternary calculators to Either ([#699](https://github.com/albertocavalcante/gvy/issues/699)) ([30ebcb6](https://github.com/albertocavalcante/gvy/commit/30ebcb648c2accfc50818695c7f7522779242ae3))
* **semantics:** Phase 4 - LSP Migration Infrastructure ([#648](https://github.com/albertocavalcante/gvy/issues/648)) ([32af67e](https://github.com/albertocavalcante/gvy/commit/32af67e099cfb8bc5dce3a15e3ab84916384c03e))
* **semantics:** Phase 5 - Complete type resolution migration ([#654](https://github.com/albertocavalcante/gvy/issues/654)) ([5da5f5e](https://github.com/albertocavalcante/gvy/commit/5da5f5e85251a155c183807abbe74c3382b9a7f3))
* **semantics:** Phase 6 - Either-based error handling foundation ([#686](https://github.com/albertocavalcante/gvy/issues/686)) ([d66f643](https://github.com/albertocavalcante/gvy/commit/d66f64367dd1249fc0245f767b971a76d5e4ed84))
* **semantics:** Phase 6 - Field & Method Resolution ([#655](https://github.com/albertocavalcante/gvy/issues/655)) ([1bff42f](https://github.com/albertocavalcante/gvy/commit/1bff42f842514e2facd2b2cd3c99c1cf2906785b))
* **semantics:** Phase 7 - DSL Parser Framework ([#694](https://github.com/albertocavalcante/gvy/issues/694)) ([79572ca](https://github.com/albertocavalcante/gvy/commit/79572cab82db0f80a84cda968e272e9456f51fb5))
* **semantics:** Phase 7 - Full Cross-File Type Resolution ([#615](https://github.com/albertocavalcante/gvy/issues/615)) ([c64a3be](https://github.com/albertocavalcante/gvy/commit/c64a3be3694be08f36bd5b8871ff7c251bcb5805))
* **semantics:** refactor calculators with idiomacy improvements ([#636](https://github.com/albertocavalcante/gvy/issues/636)) ([9e17b0b](https://github.com/albertocavalcante/gvy/commit/9e17b0ba5e3015873e7fb7dd35a834dbf97fcbeb))
* **semantics:** start Either-based calculator migration ([#698](https://github.com/albertocavalcante/gvy/issues/698)) ([a84fbb2](https://github.com/albertocavalcante/gvy/commit/a84fbb2debba4da79f1881d7909feb97fb9802ae))
* **semantics:** support method overload resolution in DelegationResolver ([#767](https://github.com/albertocavalcante/gvy/issues/767)) ([8f295c9](https://github.com/albertocavalcante/gvy/commit/8f295c9f444f3ddc2ecc9c870cd8d49baa06478c)), closes [#760](https://github.com/albertocavalcante/gvy/issues/760)
* server-driven status notifications with file counts ([#493](https://github.com/albertocavalcante/gvy/issues/493)) ([09acabb](https://github.com/albertocavalcante/gvy/commit/09acabb9a7175c58c916144308679d66ad81285a))
* **testing:** add coverage CodeLens and improve test UX ([#887](https://github.com/albertocavalcante/gvy/issues/887)) ([dd4c468](https://github.com/albertocavalcante/gvy/commit/dd4c468d10c68ea656b154fc30e122868a226264))
* **testing:** add Maven coverage support with JaCoCo ([#741](https://github.com/albertocavalcante/gvy/issues/741)) ([cb7bc66](https://github.com/albertocavalcante/gvy/commit/cb7bc66d9246b4b244162357737483899db945ad)), closes [#733](https://github.com/albertocavalcante/gvy/issues/733)
* **testing:** Create groovy-test-utils module for shared test infrastructure ([#797](https://github.com/albertocavalcante/gvy/issues/797)) ([8dc3012](https://github.com/albertocavalcante/gvy/commit/8dc301230a9a1eec1409cee4fbdff3cb36c9d1a4))
* **testing:** improve test runner with LSP CodeLens and result matching ([#908](https://github.com/albertocavalcante/gvy/issues/908)) ([861a393](https://github.com/albertocavalcante/gvy/commit/861a393d5d475b372a9ce33e7b8bcfa50c289b76))
* **testing:** LSP-centric test results and coverage parsing ([#884](https://github.com/albertocavalcante/gvy/issues/884)) ([b669d6d](https://github.com/albertocavalcante/gvy/commit/b669d6d0d8ef304004672d29794e1aa2bb808338))
* Unified LSP Implementation (Symbols, Definition, Completion) ([#526](https://github.com/albertocavalcante/gvy/issues/526)) ([2f83cc2](https://github.com/albertocavalcante/gvy/commit/2f83cc29247df460a00e50edac7aa9557b77566e))
* **viz:** desktop UI and UX overhaul ([#627](https://github.com/albertocavalcante/gvy/issues/627)) ([714b09f](https://github.com/albertocavalcante/gvy/commit/714b09fd550e9eacc206ee0a963ef69e42316c63))
* **viz:** Groovy AST Visualizer - Phase 1 (Compose Desktop) ([#602](https://github.com/albertocavalcante/gvy/issues/602)) ([750ea98](https://github.com/albertocavalcante/gvy/commit/750ea9805c0b77decfdb592cbed60baab104716a))
* **viz:** implement desktop app makefile ([#608](https://github.com/albertocavalcante/gvy/issues/608)) ([a83b27f](https://github.com/albertocavalcante/gvy/commit/a83b27f682312e65668c77033a6560b7f7e1378e))
* **vscode:** add project build JDK configuration ([#881](https://github.com/albertocavalcante/gvy/issues/881)) ([a2cd10b](https://github.com/albertocavalcante/gvy/commit/a2cd10b4812840308d7ff91b5ccb81f9aff66b49)), closes [#879](https://github.com/albertocavalcante/gvy/issues/879)
* **vscode:** improve Java toolchain error UX with smart actions ([#786](https://github.com/albertocavalcante/gvy/issues/786)) ([ed86d0e](https://github.com/albertocavalcante/gvy/commit/ed86d0e4d83d53c4331b8503d422c8af978197f1))
* **vscode:** improve JDK configuration UX with actionable warnings ([#824](https://github.com/albertocavalcante/gvy/issues/824)) ([c9471cf](https://github.com/albertocavalcante/gvy/commit/c9471cf776088645f216cf11d583d0b2437b9431))
* **workflow:** add /solve workflow ([#541](https://github.com/albertocavalcante/gvy/issues/541)) ([fb5a844](https://github.com/albertocavalcante/gvy/commit/fb5a84439b52425a397f279832d6f638359b2a79))


### Bug Fixes

* add pre-flight JDK/Gradle compatibility check ([#501](https://github.com/albertocavalcante/gvy/issues/501)) ([8e2f04a](https://github.com/albertocavalcante/gvy/commit/8e2f04ad9c98877cc13b24c7071fe3f3eecf1ce9))
* **agent/pr:** improve scope validation and add multi-provider AI support ([#939](https://github.com/albertocavalcante/gvy/issues/939)) ([84205ab](https://github.com/albertocavalcante/gvy/commit/84205ab10def1356f3c47a875d14d929cd43688a))
* **bsp:** address TooGenericExceptionCaught and complexity violations ([#940](https://github.com/albertocavalcante/gvy/issues/940)) ([232f2e9](https://github.com/albertocavalcante/gvy/commit/232f2e9799cf9bdab9c7913cedfed53247f5fa22)), closes [#926](https://github.com/albertocavalcante/gvy/issues/926)
* **build:** exclude Ktor/kotlinx-serialization from shadowJar minimize ([#570](https://github.com/albertocavalcante/gvy/issues/570)) ([71a6663](https://github.com/albertocavalcante/gvy/commit/71a6663ae80698b5595801206348f04465e52fc5))
* **ci:** correct working directory in extension nightly workflow ([#791](https://github.com/albertocavalcante/gvy/issues/791)) ([5074491](https://github.com/albertocavalcante/gvy/commit/5074491a197db915582be986d372357bd0b83076))
* **ci:** drop reserved GITHUB_TOKEN secret in Claude workflows ([#1061](https://github.com/albertocavalcante/gvy/issues/1061)) ([bbd7626](https://github.com/albertocavalcante/gvy/commit/bbd76264d18b21a101a918add0a2bde666f7ff7f))
* **ci:** output proper json in failure summary ([#1000](https://github.com/albertocavalcante/gvy/issues/1000)) ([2e91f34](https://github.com/albertocavalcante/gvy/commit/2e91f3411b62edd2873c8672c56f25b2ecad3c9b))
* **ci:** skip JDK 17 on Windows ARM in release smoke tests ([#462](https://github.com/albertocavalcante/gvy/issues/462)) ([4fbb7c8](https://github.com/albertocavalcante/gvy/commit/4fbb7c8a17f3a6d057bcfb579582d6ebc8eb5d4d))
* **codenarc:** reduce Jenkins diagnostic noise by 90% ([#495](https://github.com/albertocavalcante/gvy/issues/495)) ([7deb777](https://github.com/albertocavalcante/gvy/commit/7deb77792ecf3b8a35199b91bd796d769328e15b))
* **compilation:** compile the open buffer when a request beats didOpen ([#1078](https://github.com/albertocavalcante/gvy/issues/1078)) ([f06366e](https://github.com/albertocavalcante/gvy/commit/f06366ea11ca5feb57ee5adde0ccb74e435f1db2))
* **completion:** avoid duplicate static import prefix ([#853](https://github.com/albertocavalcante/gvy/issues/853)) ([22c54df](https://github.com/albertocavalcante/gvy/commit/22c54df1963a3dee1c0b517c649d0b378717ba22))
* **completion:** refine import completions ([#573](https://github.com/albertocavalcante/gvy/issues/573)) ([1fd5aa4](https://github.com/albertocavalcante/gvy/commit/1fd5aa4bfa1c153e732249c9dc91f90daf176032))
* **completion:** resilient fallback and dedupe ([#860](https://github.com/albertocavalcante/gvy/issues/860)) ([3aa5938](https://github.com/albertocavalcante/gvy/commit/3aa59382d42ff092126be3b0856b6a98104d9bc4))
* **copybara:** keep the mirrored package.json valid JSON ([#1075](https://github.com/albertocavalcante/gvy/issues/1075)) ([ce259b2](https://github.com/albertocavalcante/gvy/commit/ce259b2d4889b724eed07f7b369c574f4030a9bb))
* Default to latest LSP release, auto-detect monorepo builds ([#476](https://github.com/albertocavalcante/gvy/issues/476)) ([3ff40e0](https://github.com/albertocavalcante/gvy/commit/3ff40e0757caacda64bf4e17aab6bf2b7cd45765))
* **definition:** resolve cross-file classes via ensureCompiled on-demand ([#711](https://github.com/albertocavalcante/gvy/issues/711)) ([72d935f](https://github.com/albertocavalcante/gvy/commit/72d935f7bd0f8c75d1a913ec2c6ea3abad1c2760))
* **definition:** resolve static method calls on class names ([#1034](https://github.com/albertocavalcante/gvy/issues/1034)) ([2bf331f](https://github.com/albertocavalcante/gvy/commit/2bf331fb3792cce6ea5f70d4586fa9047ded5c7c)), closes [#813](https://github.com/albertocavalcante/gvy/issues/813)
* **deps:** replace deprecated Aether ServiceLocator ([#606](https://github.com/albertocavalcante/gvy/issues/606)) ([9d41322](https://github.com/albertocavalcante/gvy/commit/9d413225fd7934cc4d832e1c65a2c4e25e895756))
* **deps:** update dependency ch.qos.logback:logback-classic to v1.5.24 ([#692](https://github.com/albertocavalcante/gvy/issues/692)) ([c34e45e](https://github.com/albertocavalcante/gvy/commit/c34e45ebf29eb496f4845c93942a4eb6a20744bb))
* **deps:** update dependency ch.qos.logback:logback-classic to v1.5.25 ([#979](https://github.com/albertocavalcante/gvy/issues/979)) ([828a83b](https://github.com/albertocavalcante/gvy/commit/828a83b21a3cd126ef0780a32550c314160c2ab5))
* **deps:** update dependency com.github.ajalt.clikt:clikt to v5.1.0 ([#1002](https://github.com/albertocavalcante/gvy/issues/1002)) ([da9bc11](https://github.com/albertocavalcante/gvy/commit/da9bc11cbdd18bed679d652fbb8c6607d48a6c51))
* **deps:** update dependency com.github.javaparser:javaparser-core to v3.28.0 ([#792](https://github.com/albertocavalcante/gvy/issues/792)) ([7d78308](https://github.com/albertocavalcante/gvy/commit/7d783085b3b49e18483d6f394105dd87ed7db12c))
* **deps:** update dependency io.github.oshai:kotlin-logging-jvm to v7.0.14 ([#846](https://github.com/albertocavalcante/gvy/issues/846)) ([bb7adaf](https://github.com/albertocavalcante/gvy/commit/bb7adafc72e0f8e0d8bc8b31b6ed8fb7bd3adb3d))
* **deps:** update dependency org.openrewrite:rewrite-groovy to v8.70.3 ([#507](https://github.com/albertocavalcante/gvy/issues/507)) ([faa6cda](https://github.com/albertocavalcante/gvy/commit/faa6cdacefc0f96d3a8eb1959901f7c948c974d2))
* **deps:** update dependency org.openrewrite:rewrite-groovy to v8.70.4 ([#527](https://github.com/albertocavalcante/gvy/issues/527)) ([9b4ffa3](https://github.com/albertocavalcante/gvy/commit/9b4ffa37b2eb62ddd0f1f348c3bed0adae6101b7))
* **deps:** update dependency org.openrewrite:rewrite-java-17 to v8.71.0 ([#708](https://github.com/albertocavalcante/gvy/issues/708)) ([36bf442](https://github.com/albertocavalcante/gvy/commit/36bf442d2caa25f693dd084c04a3e46c19a7a3ef))
* **deps:** update dependency org.ow2.asm:asm to v9.9.1 ([#847](https://github.com/albertocavalcante/gvy/issues/847)) ([6ccb84f](https://github.com/albertocavalcante/gvy/commit/6ccb84ff10b9e08fd8bcaf5953db4063f8733c92))
* **deps:** update gradle-tooling-api to 9.8.2 ([#1006](https://github.com/albertocavalcante/gvy/issues/1006)) ([924c7cc](https://github.com/albertocavalcante/gvy/commit/924c7cce59a63ddcd577faabf4e93d69fbe6acae))
* **deps:** update junit-framework monorepo to v6.0.2 ([#684](https://github.com/albertocavalcante/gvy/issues/684)) ([4c35c46](https://github.com/albertocavalcante/gvy/commit/4c35c464eed7acfd7b486ae34524b02a6e702a78))
* **deps:** update wire to v5.5.0 ([#852](https://github.com/albertocavalcante/gvy/issues/852)) ([796703a](https://github.com/albertocavalcante/gvy/commit/796703afb9f7523fdc1eef838550743bf52f402c))
* **diagnostics:** detect .class literals in constructor arguments as used imports ([#837](https://github.com/albertocavalcante/gvy/issues/837)) ([a0aef0c](https://github.com/albertocavalcante/gvy/commit/a0aef0cd70f5f432d8d0abad61c8bb055749039a))
* **diagnostics:** propagate cancellations and guard spock paths ([#593](https://github.com/albertocavalcante/gvy/issues/593)) ([fbad349](https://github.com/albertocavalcante/gvy/commit/fbad349e6dfa308e2d9a2bdffc1cb48f0a19af18))
* **diagnostics:** register diagnostic jobs before starting them ([#1062](https://github.com/albertocavalcante/gvy/issues/1062)) ([683f9af](https://github.com/albertocavalcante/gvy/commit/683f9af1b99b3375a6f39127469a94937b69d238))
* **docs:** restore docs pages and upgrade to Starlight 0.42 / Astro 7 ([#1051](https://github.com/albertocavalcante/gvy/issues/1051)) ([f2a4369](https://github.com/albertocavalcante/gvy/commit/f2a436978dbfc9baf319c8b3a87971ccdd6b6633))
* **docs:** upgrade Starlight for array social config ([#1050](https://github.com/albertocavalcante/gvy/issues/1050)) ([498909b](https://github.com/albertocavalcante/gvy/commit/498909b698c723963e798dc6ebacb2860dea5325))
* **docs:** upgrade Starlight to 0.34+ for array social config ([498909b](https://github.com/albertocavalcante/gvy/commit/498909b698c723963e798dc6ebacb2860dea5325))
* **docs:** use bun instead of npm for docs build ([#1049](https://github.com/albertocavalcante/gvy/issues/1049)) ([61a2947](https://github.com/albertocavalcante/gvy/commit/61a294728dfeee0b3db7ce50c06ed13dede1d85b))
* **e2e:** resolve flaky nightly build tests ([#1041](https://github.com/albertocavalcante/gvy/issues/1041)) ([6f1888b](https://github.com/albertocavalcante/gvy/commit/6f1888b4c87af55a3a13579ce4cd0e627e7089f0))
* **e2e:** resolve three failing e2e tests ([#888](https://github.com/albertocavalcante/gvy/issues/888)) ([68d3822](https://github.com/albertocavalcante/gvy/commit/68d38228dd4dcb503e48c52fcb582814d4c32e39))
* **e2e:** update server status check to use health/quiescent fields ([#502](https://github.com/albertocavalcante/gvy/issues/502)) ([7c40ccf](https://github.com/albertocavalcante/gvy/commit/7c40ccfb79d41ba46af4947aebeb43f018e3e776))
* **errors:** propagate Gradle resolution failures to client with actionable UI ([#750](https://github.com/albertocavalcante/gvy/issues/750)) ([fcaa0d4](https://github.com/albertocavalcante/gvy/commit/fcaa0d447020fd0ed088a9194ee19cb9a8c8c467))
* **ext:** package gls.jar in VSIX and migrate to pnpm ([#479](https://github.com/albertocavalcante/gvy/issues/479)) ([e943afd](https://github.com/albertocavalcante/gvy/commit/e943afd380dbf6c2aea1062761aa9004ec49f0df))
* **ext:** use --no-dependencies flag for vsce package with pnpm ([#487](https://github.com/albertocavalcante/gvy/issues/487)) ([c38317f](https://github.com/albertocavalcante/gvy/commit/c38317ff77b6e9c05bf559aa81961b582e56b9eb))
* **gdk:** resolve real parameter names from source JARs ([#832](https://github.com/albertocavalcante/gvy/issues/832)) ([454aad9](https://github.com/albertocavalcante/gvy/commit/454aad9d46a0629f02e8902bc3a92b0f67467c12)), closes [#830](https://github.com/albertocavalcante/gvy/issues/830)
* **groovy-common:** implement cache eviction for lineBreakCache ([#946](https://github.com/albertocavalcante/gvy/issues/946)) ([e76f459](https://github.com/albertocavalcante/gvy/commit/e76f459101edce53a092d3f6ea3615c8b9d37fce)), closes [#920](https://github.com/albertocavalcante/gvy/issues/920)
* **groovy-common:** remove deprecated GroovyDocParser in favor of Groovydoc ([#850](https://github.com/albertocavalcante/gvy/issues/850)) ([f41cb54](https://github.com/albertocavalcante/gvy/commit/f41cb548160f000c883b18865ff5e260ef7a7368))
* **groovyparser-core:** resolve thread-safety and singleton issues ([#522](https://github.com/albertocavalcante/gvy/issues/522)) ([bde7a96](https://github.com/albertocavalcante/gvy/commit/bde7a9639779da494fcc946f0c89fb7359737d0c))
* **indexer:** encode SCIP method signatures ([#1053](https://github.com/albertocavalcante/gvy/issues/1053)) ([a2dcf32](https://github.com/albertocavalcante/gvy/commit/a2dcf322bca5a0ca7b48ba6b4dc92a993356ff61))
* **inlayhints:** prevent ClassNotFoundException for invalid type names ([#783](https://github.com/albertocavalcante/gvy/issues/783)) ([61bfe22](https://github.com/albertocavalcante/gvy/commit/61bfe22e8ebfcbca55272d27b8ef7626d5925437))
* **inlay:** prevent NPE in parameter hint scoring ([#1045](https://github.com/albertocavalcante/gvy/issues/1045)) ([b4fcc5e](https://github.com/albertocavalcante/gvy/commit/b4fcc5e4858420d530e23559dc0de841ff62a849))
* **jenkins:** extract step names from bytecode for deterministic resolution ([#840](https://github.com/albertocavalcante/gvy/issues/840)) ([9b89963](https://github.com/albertocavalcante/gvy/commit/9b89963e0e91cc8470e72ce0b938fb4ae3902bda)), closes [#834](https://github.com/albertocavalcante/gvy/issues/834)
* **jenkins:** implement resilient navigation for global variables ([#793](https://github.com/albertocavalcante/gvy/issues/793)) ([0b6e2ef](https://github.com/albertocavalcante/gvy/commit/0b6e2ef93817a26b6873ce6410a7b3f2ff830112)), closes [#742](https://github.com/albertocavalcante/gvy/issues/742)
* **jenkins:** remove redundant identical conditional branches ([#611](https://github.com/albertocavalcante/gvy/issues/611)) ([44e03c2](https://github.com/albertocavalcante/gvy/commit/44e03c235f320537db6fc4f4943e459abb00b805))
* **jenkins:** remove redundant identical conditional branches in registerStaticMetadata ([44e03c2](https://github.com/albertocavalcante/gvy/commit/44e03c235f320537db6fc4f4943e459abb00b805))
* **lint:** resolve detekt issues blocking CI ([#795](https://github.com/albertocavalcante/gvy/issues/795)) ([11b66a0](https://github.com/albertocavalcante/gvy/commit/11b66a070bdc3ffa2192ea26c070cc42c7abaa1a))
* **lint:** resolve detekt quick-wins and line length violations ([#916](https://github.com/albertocavalcante/gvy/issues/916)) ([ba3db72](https://github.com/albertocavalcante/gvy/commit/ba3db722fe6d679c2e12c4689b19df59a3d9f20b))
* **lsp:** address concurrency, caching, and generics parsing issues ([#661](https://github.com/albertocavalcante/gvy/issues/661)) ([cb77535](https://github.com/albertocavalcante/gvy/commit/cb775352face5a083c616c3f526fcb26398697ff)), closes [#8](https://github.com/albertocavalcante/gvy/issues/8)
* **lsp:** address PR [#878](https://github.com/albertocavalcante/gvy/issues/878) review comments and critical bugs ([#883](https://github.com/albertocavalcante/gvy/issues/883)) ([4aedf1a](https://github.com/albertocavalcante/gvy/commit/4aedf1ad04515bba6b141c1912f6c22a59a15448))
* **lsp:** address unused import dimming review feedback ([#819](https://github.com/albertocavalcante/gvy/issues/819)) ([f1683ae](https://github.com/albertocavalcante/gvy/commit/f1683ae314029b5a1c1e360a8336531a500c3f5c))
* **lsp:** avoid workspace source fanout in on-demand compile ([#744](https://github.com/albertocavalcante/gvy/issues/744)) ([5d8cda4](https://github.com/albertocavalcante/gvy/commit/5d8cda4c43241fb090dcffbb502b66ea1d297dd5))
* **lsp:** comprehensive fixes for completion context, definition ranges, and semantic tokens ([#876](https://github.com/albertocavalcante/gvy/issues/876)) ([e25e0d8](https://github.com/albertocavalcante/gvy/commit/e25e0d85eb6e11dd301ccf22704fcba463f8d137))
* **lsp:** correct range end column calculation for LSP spec compliance ([#653](https://github.com/albertocavalcante/gvy/issues/653)) ([fdb63c9](https://github.com/albertocavalcante/gvy/commit/fdb63c93e613729855305fc4bb428a88257f39f9))
* **lsp:** ensure all documents compiled before skipping definition wait ([#810](https://github.com/albertocavalcante/gvy/issues/810)) ([baf90fc](https://github.com/albertocavalcante/gvy/commit/baf90fc952e10814e42a86a4e74c29a0af5b042b))
* **lsp:** ensure deterministic iteration order in DependencyGraph ([#930](https://github.com/albertocavalcante/gvy/issues/930)) ([9d03afb](https://github.com/albertocavalcante/gvy/commit/9d03afb4e87f421af9cbeb185b567bf0d272e941))
* **lsp:** fix map variable completion showing keywords instead of methods ([#656](https://github.com/albertocavalcante/gvy/issues/656)) ([568fd76](https://github.com/albertocavalcante/gvy/commit/568fd76c1cd4de99565e6336d6a32d843d690c5f)), closes [#642](https://github.com/albertocavalcante/gvy/issues/642)
* **lsp:** handle untitled URI scheme gracefully ([#751](https://github.com/albertocavalcante/gvy/issues/751)) ([b79cfc5](https://github.com/albertocavalcante/gvy/commit/b79cfc577c5d6698382cd57374d2b4fee517d6eb))
* **lsp:** improve AST node resolution for nested method calls ([#471](https://github.com/albertocavalcante/gvy/issues/471)) ([89800d2](https://github.com/albertocavalcante/gvy/commit/89800d214a34c23134649ae55240dcb61d80945c))
* **lsp:** improve hover and inlay hints ([#582](https://github.com/albertocavalcante/gvy/issues/582)) ([fe061ca](https://github.com/albertocavalcante/gvy/commit/fe061caacea1ca9f4be93496cfd62261d5ef09fd))
* **lsp:** resolve compilation errors and warnings in tests ([#658](https://github.com/albertocavalcante/gvy/issues/658)) ([d948937](https://github.com/albertocavalcante/gvy/commit/d9489373fb4478c5eb0bb0fb6a1711e1fb33d910))
* **lsp:** resolve cross-file definition race condition ([#794](https://github.com/albertocavalcante/gvy/issues/794)) ([c87c272](https://github.com/albertocavalcante/gvy/commit/c87c272fbf7cf1d1987831d4ec97074f42005c29)), closes [#749](https://github.com/albertocavalcante/gvy/issues/749)
* **lsp:** resolve Kotlin compiler warnings in groovy-lsp module ([#838](https://github.com/albertocavalcante/gvy/issues/838)) ([30cbe9e](https://github.com/albertocavalcante/gvy/commit/30cbe9eccd72ec69659f369548efbafbb7f69aca))
* **lsp:** resolve Kotlin compiler warnings in test files ([#842](https://github.com/albertocavalcante/gvy/issues/842)) ([c1cfdde](https://github.com/albertocavalcante/gvy/commit/c1cfddeb4216915d199a470211836115183143d1))
* **lsp:** scope Jenkins options completions ([#580](https://github.com/albertocavalcante/gvy/issues/580)) ([ed473d2](https://github.com/albertocavalcante/gvy/commit/ed473d2654cf5ef8ca681501d2dd826172a7571d))
* **lsp:** test execution for external files and diagnostic positioning ([#666](https://github.com/albertocavalcante/gvy/issues/666)) ([c8cdda3](https://github.com/albertocavalcante/gvy/commit/c8cdda327ab6b8069c2c405b86159fe5fb4cfb1c))
* **lsp:** unify output channels to fix status bar logs command ([#504](https://github.com/albertocavalcante/gvy/issues/504)) ([6a78933](https://github.com/albertocavalcante/gvy/commit/6a78933813bacdcfa263448b06243834941090e2))
* **lsp:** use fully-qualified name for global class resolution ([#710](https://github.com/albertocavalcante/gvy/issues/710)) ([8245c7a](https://github.com/albertocavalcante/gvy/commit/8245c7a3ca92ddc331766b765cb60b683f3b1d7e))
* **make:** track client package.json changes for smart install ([#489](https://github.com/albertocavalcante/gvy/issues/489)) ([56f1ad8](https://github.com/albertocavalcante/gvy/commit/56f1ad81d39dd4e5d255c70a11cf0d1e48d1ea7c))
* **meta:** correct repository coordinates to albertocavalcante/gvy ([#911](https://github.com/albertocavalcante/gvy/issues/911)) ([a863a7b](https://github.com/albertocavalcante/gvy/commit/a863a7ba9b5828e4a73ced66ec0acad8b2b4fdfb))
* **parser-core:** unblock lintFix by addressing detekt violations ([#600](https://github.com/albertocavalcante/gvy/issues/600)) ([c76a472](https://github.com/albertocavalcante/gvy/commit/c76a472162390d4edbdd8948e312e25337d243f4))
* **parser/native:** add visitor coverage for missing expression types ([#989](https://github.com/albertocavalcante/gvy/issues/989)) ([11919ee](https://github.com/albertocavalcante/gvy/commit/11919eecbc40e03c8ae1d87942a858c7b028cd26)), closes [#983](https://github.com/albertocavalcante/gvy/issues/983)
* **parser/native:** correct expression coverage test failures ([#993](https://github.com/albertocavalcante/gvy/issues/993)) ([76d9d9e](https://github.com/albertocavalcante/gvy/commit/76d9d9ea00284b343ca196f296508712e91bebc3))
* **parser:** add TransformFilteringClassLoader to prevent NoClassDefFoundError ([#494](https://github.com/albertocavalcante/gvy/issues/494)) ([e3b2027](https://github.com/albertocavalcante/gvy/commit/e3b20275ee22712e1053edb5f25b7bd6182e6d37))
* **parser:** disable global AST transformations to prevent NoClassDefFoundError ([#491](https://github.com/albertocavalcante/gvy/issues/491)) ([b2b93c8](https://github.com/albertocavalcante/gvy/commit/b2b93c805761ece290c6a6333280c7073696816b))
* **parser:** handle NoClassDefFoundError in Symbol.Class.from() ([#787](https://github.com/albertocavalcante/gvy/issues/787)) ([123714f](https://github.com/albertocavalcante/gvy/commit/123714fe97eb63523c2cf42e1979aa5d5ebc6bce))
* **parser:** implement PackageNode visitation in native parser ([#610](https://github.com/albertocavalcante/gvy/issues/610)) ([8a5436b](https://github.com/albertocavalcante/gvy/commit/8a5436b1426ffc0af29d27496c20f958ccf18d7e))
* **parser:** resolve same-named variables by scope ([#835](https://github.com/albertocavalcante/gvy/issues/835)) ([621078b](https://github.com/albertocavalcante/gvy/commit/621078bc333e02a7c63e61e9190ec914938cd922))
* **pr:** add resilient diff retrieval with fallback strategies ([#1015](https://github.com/albertocavalcante/gvy/issues/1015)) ([64e5808](https://github.com/albertocavalcante/gvy/commit/64e58083ec7ea42c84a74b17d1c1d1ab08d1f11d))
* **release:** count v0.5.0 changes from v0.4.8 and title the PR with its version ([#1087](https://github.com/albertocavalcante/gvy/issues/1087)) ([839604c](https://github.com/albertocavalcante/gvy/commit/839604cc81773eae6e10e269086e50e2894cceb0))
* resolve Kotlin compiler warnings across modules ([#848](https://github.com/albertocavalcante/gvy/issues/848)) ([e0b9518](https://github.com/albertocavalcante/gvy/commit/e0b95188aa9ae249dfb2c836044d5cfb66a03aac))
* **resources:** prevent resource leaks in processes, streams, and caches ([#917](https://github.com/albertocavalcante/gvy/issues/917)) ([6ee02bb](https://github.com/albertocavalcante/gvy/commit/6ee02bb45e08c2d5bcc07f812b163b36a24c9bb7))
* **rewrite-codenarc:** address detekt violations ([#928](https://github.com/albertocavalcante/gvy/issues/928)) ([6d21345](https://github.com/albertocavalcante/gvy/commit/6d2134512d885d29093f4ee597f97ac909876540)), closes [#924](https://github.com/albertocavalcante/gvy/issues/924)
* **sarif:** use local variables for delegated property smart casts ([#722](https://github.com/albertocavalcante/gvy/issues/722)) ([d42507e](https://github.com/albertocavalcante/gvy/commit/d42507ee1320bb865db6e151b41dd3ff4fccd58b))
* **scripts:** add OOM resilience and smart diff compression to pr.py ([#1032](https://github.com/albertocavalcante/gvy/issues/1032)) ([0ca58f7](https://github.com/albertocavalcante/gvy/commit/0ca58f7c20f23472823cf1394c76c17119ccff9d))
* **scripts:** prevent OOM crashes on large PR diffs with auto-detection ([#1017](https://github.com/albertocavalcante/gvy/issues/1017)) ([958c6dd](https://github.com/albertocavalcante/gvy/commit/958c6ddb585e7b33a2f65db053285b6834e0c985))
* **scripts:** validate prompt size and clarify oom guidance ([#1027](https://github.com/albertocavalcante/gvy/issues/1027)) ([cee67b1](https://github.com/albertocavalcante/gvy/commit/cee67b12c6b83ac3f8b2a4065c09f47de43322e4))
* **security:** mitigate command injection in Claude workflow ([#1043](https://github.com/albertocavalcante/gvy/issues/1043)) ([d0a5d5d](https://github.com/albertocavalcante/gvy/commit/d0a5d5d664757af04f996e0b2f13c2b86443f53a))
* **semantic-tokens:** improve syntax highlighting with theme mappings and receiver tokenization ([#872](https://github.com/albertocavalcante/gvy/issues/872)) ([77b3f2c](https://github.com/albertocavalcante/gvy/commit/77b3f2c9f1cd7553469ec5b3bf642a63f1fdb925))
* **semantics/core:** add LRU eviction to TypeLub ancestor cache ([#932](https://github.com/albertocavalcante/gvy/issues/932)) ([42b6609](https://github.com/albertocavalcante/gvy/commit/42b660990c3bb41383559be420fd5941a7ab11da))
* **semantics/native:** use weakhashmap for context cache to prevent memory leaks ([#933](https://github.com/albertocavalcante/gvy/issues/933)) ([7212094](https://github.com/albertocavalcante/gvy/commit/72120940c8b57f4afd4e1853df5ff449755e56d7))
* **semantics:** correct method declaration semantic token positions ([#778](https://github.com/albertocavalcante/gvy/issues/778)) ([8c87e97](https://github.com/albertocavalcante/gvy/commit/8c87e972e2caf4ae7ecd4bb8919a3a1ebf96ecb3)), closes [#769](https://github.com/albertocavalcante/gvy/issues/769)
* **semantics:** handle generic types with/without spaces in method declarations ([#968](https://github.com/albertocavalcante/gvy/issues/968)) ([c062584](https://github.com/albertocavalcante/gvy/commit/c06258434614204f5e68c451705ad739042367c2))
* **semantics:** implement cache eviction for ReflectionAccess caches ([#947](https://github.com/albertocavalcante/gvy/issues/947)) ([fb02a48](https://github.com/albertocavalcante/gvy/commit/fb02a48c15045bde448da974df95648f357a59cf)), closes [#921](https://github.com/albertocavalcante/gvy/issues/921)
* **semantics:** make GroovySemanticDB consistent under concurrency ([#1071](https://github.com/albertocavalcante/gvy/issues/1071)) ([2b58fe0](https://github.com/albertocavalcante/gvy/commit/2b58fe04c816e1cd44ceea3598308b27a127e918))
* **semantics:** start method name search from declaration column ([#972](https://github.com/albertocavalcante/gvy/issues/972)) ([04a20f8](https://github.com/albertocavalcante/gvy/commit/04a20f80740a25de010cc136f96df400fd9d1cc0))
* support closure parameters in document highlight ([#478](https://github.com/albertocavalcante/gvy/issues/478)) ([67635b1](https://github.com/albertocavalcante/gvy/commit/67635b183acc86175087070d9a1152216d912cf1))
* **test:** add missing workspacemanager import in testdiscoveryprovidertest ([#971](https://github.com/albertocavalcante/gvy/issues/971)) ([5981ee3](https://github.com/albertocavalcante/gvy/commit/5981ee335648a9a40dcc2f3fc950df4b9ecdeff5))
* **test:** handle ProgramResult in MainTest captureOutput ([#725](https://github.com/albertocavalcante/gvy/issues/725)) ([e725a75](https://github.com/albertocavalcante/gvy/commit/e725a75456680bfdc5a7017321401fa09e87d26e))
* **testing:** improve test execution UX and add Maven support ([#717](https://github.com/albertocavalcante/gvy/issues/717)) ([cbd401f](https://github.com/albertocavalcante/gvy/commit/cbd401f2b07ec19c132645bc8d4ec82abd3f0ff4))
* **test:** resolve race condition in SynchronizingTestLanguageClient ([#472](https://github.com/albertocavalcante/gvy/issues/472)) ([e85fe5e](https://github.com/albertocavalcante/gvy/commit/e85fe5eda639277133cdb8dbb2fb450e41026898))
* **thread-safety:** prevent race conditions in semantic DB and caches ([#915](https://github.com/albertocavalcante/gvy/issues/915)) ([8125fff](https://github.com/albertocavalcante/gvy/commit/8125fff104a2dd6dd09a06c10d34bde6d04ecc83))
* use correct spotless task and robust monorepo detection ([c3a6985](https://github.com/albertocavalcante/gvy/commit/c3a6985b2f64600ab116b5ed03f2fab040ce5e92))
* use correct spotless task name in make jar ([#485](https://github.com/albertocavalcante/gvy/issues/485)) ([c3a6985](https://github.com/albertocavalcante/gvy/commit/c3a6985b2f64600ab116b5ed03f2fab040ce5e92))
* **vscode:** address code review issues and improve resilience ([#960](https://github.com/albertocavalcante/gvy/issues/960)) ([680d7fa](https://github.com/albertocavalcante/gvy/commit/680d7fa438cb7e1817e3fbfc8e19e95caf93d313))
* **vscode:** address PR[#786](https://github.com/albertocavalcante/gvy/issues/786) review feedback ([#821](https://github.com/albertocavalcante/gvy/issues/821)) ([6668df3](https://github.com/albertocavalcante/gvy/commit/6668df3d68a53696c31a0e8d4f3b5d29cf029a74))
* **vscode:** eliminate explicit any usage and improve type safety ([#977](https://github.com/albertocavalcante/gvy/issues/977)) ([196eb07](https://github.com/albertocavalcante/gvy/commit/196eb07e8d9ec4e8ff0276a6fbec4ddabf6ee87e))
* **vscode:** improve JDK config UX clarity and add tests ([#828](https://github.com/albertocavalcante/gvy/issues/828)) ([26fc5d4](https://github.com/albertocavalcante/gvy/commit/26fc5d4c14e60665d8b36df29a93d83e699d84bd))
* **vscode:** port the extension correctness audit onto [#1063](https://github.com/albertocavalcante/gvy/issues/1063) ([#1076](https://github.com/albertocavalcante/gvy/issues/1076)) ([c8c353b](https://github.com/albertocavalcante/gvy/commit/c8c353be531eacd1df96aa343d83c0bb5717667f))
* **vscode:** secure test execution and pin server artifacts ([#1063](https://github.com/albertocavalcante/gvy/issues/1063)) ([13b291a](https://github.com/albertocavalcante/gvy/commit/13b291ac3cb03357362681651e1b7253c4dbfdcc))
* **vscode:** use actual class line for test suite gutter icon position ([#970](https://github.com/albertocavalcante/gvy/issues/970)) ([97d4af3](https://github.com/albertocavalcante/gvy/commit/97d4af361f0bb7450598e54084cb04fa55ba8841))
* **workflow:** add language specification to commit format code block ([#542](https://github.com/albertocavalcante/gvy/issues/542)) ([1943e17](https://github.com/albertocavalcante/gvy/commit/1943e1731634073102dd8c11e8981f795f5c41ba))
* **workflows:** address review feedback from pr [#823](https://github.com/albertocavalcante/gvy/issues/823) ([#973](https://github.com/albertocavalcante/gvy/issues/973)) ([d716084](https://github.com/albertocavalcante/gvy/commit/d7160842e49478e37595cc1a5dd9c99e9f8d254f))


### Performance Improvements

* **algorithms:** optimize hot path lookups with caching and binary search ([#919](https://github.com/albertocavalcante/gvy/issues/919)) ([ec787bf](https://github.com/albertocavalcante/gvy/commit/ec787bfb57dfd4b076f64a1117c8f46dbf32a8ac))
* **data-structures:** optimize caches, add determinism, reduce allocations ([#918](https://github.com/albertocavalcante/gvy/issues/918)) ([1bc6271](https://github.com/albertocavalcante/gvy/commit/1bc62711358742f7df66db30c8388207c93f1b48))

## [0.4.8](https://github.com/albertocavalcante/groovy-devtools/compare/v0.4.7...v0.4.8) (2025-12-27)

### Features

- add cross-platform smoke tests and simplify JAR naming
  ([#400](https://github.com/albertocavalcante/groovy-devtools/issues/400))
  ([6da4140](https://github.com/albertocavalcante/groovy-devtools/commit/6da41407fafeb293448d28d29e6392e1e895cc12))
- gate GDSL execution behind config flag ([#390](https://github.com/albertocavalcante/groovy-devtools/issues/390))
  ([1dc3d46](https://github.com/albertocavalcante/groovy-devtools/commit/1dc3d46a545d6f96cddb8021bc71f8d8ed7aee3e))
- gate release on smoke tests and add SLSA provenance attestation
  ([#403](https://github.com/albertocavalcante/groovy-devtools/issues/403))
  ([83f8923](https://github.com/albertocavalcante/groovy-devtools/commit/83f892309702a1d22ebd6573a3774d3876945e49))
- **jenkins:** deterministic vars ordering ([#434](https://github.com/albertocavalcante/groovy-devtools/issues/434))
  ([8dac781](https://github.com/albertocavalcante/groovy-devtools/commit/8dac7815d3bacb2beaa5c2fcb0099a70e3592137))
- **jenkins:** stabilize metadata ordering ([#439](https://github.com/albertocavalcante/groovy-devtools/issues/439))
  ([097ce1d](https://github.com/albertocavalcante/groovy-devtools/commit/097ce1d2db58f9f9f44924e88003b0dc6a760f2a))
- **lsp:** add classpath indexer ([#442](https://github.com/albertocavalcante/groovy-devtools/issues/442))
  ([7b97419](https://github.com/albertocavalcante/groovy-devtools/commit/7b9741907498420c71280dc4133202af13f85e9c))
- **lsp:** add Groovy version resolution ([#406](https://github.com/albertocavalcante/groovy-devtools/issues/406))
  ([a370a5b](https://github.com/albertocavalcante/groovy-devtools/commit/a370a5b068f50a43b100784f794df8513e2cd730))
- **lsp:** add worker handshake protocol ([#409](https://github.com/albertocavalcante/groovy-devtools/issues/409))
  ([72b9b8b](https://github.com/albertocavalcante/groovy-devtools/commit/72b9b8bbe28f44ec86471fdcdd54f6a351b6756b))
- **lsp:** add worker router ([#416](https://github.com/albertocavalcante/groovy-devtools/issues/416))
  ([7ceb8fb](https://github.com/albertocavalcante/groovy-devtools/commit/7ceb8fb1aca0c59fda150a3c01e5d0f240b09a7c))
- **lsp:** allow configurable worker descriptors
  ([#428](https://github.com/albertocavalcante/groovy-devtools/issues/428))
  ([47e1b2b](https://github.com/albertocavalcante/groovy-devtools/commit/47e1b2b91eaad8bcbaa257eadf1e8c5f43b5c0e5))
- **lsp:** clear caches on worker switch ([#424](https://github.com/albertocavalcante/groovy-devtools/issues/424))
  ([11e255d](https://github.com/albertocavalcante/groovy-devtools/commit/11e255dd6c132b796332a818f79b1aed08a017fe))
- **lsp:** route parsing via worker sessions ([#423](https://github.com/albertocavalcante/groovy-devtools/issues/423))
  ([5baa28b](https://github.com/albertocavalcante/groovy-devtools/commit/5baa28bc3fc24b74a5774f1fd940dfe85c458ece))
- **lsp:** select worker during startup ([#419](https://github.com/albertocavalcante/groovy-devtools/issues/419))
  ([b0a1d04](https://github.com/albertocavalcante/groovy-devtools/commit/b0a1d045f729404f932ef62571ef5666ba8a4334))
- **lsp:** select workers by version range ([#411](https://github.com/albertocavalcante/groovy-devtools/issues/411))
  ([331844b](https://github.com/albertocavalcante/groovy-devtools/commit/331844b9e70ea7a2e50239c2412bc3406c364e4f))
- **lsp:** stabilize classpath completions ([#438](https://github.com/albertocavalcante/groovy-devtools/issues/438))
  ([567cfae](https://github.com/albertocavalcante/groovy-devtools/commit/567cfae1025d9ebb7cf48e3ea032211bae3f9206))
- migrate to lefthook ([#398](https://github.com/albertocavalcante/groovy-devtools/issues/398))
  ([b04c600](https://github.com/albertocavalcante/groovy-devtools/commit/b04c600d784c1f9236be596b5ecf550206b793e5))
- **parser:** add AST query parser ([#447](https://github.com/albertocavalcante/groovy-devtools/issues/447))
  ([5e643cf](https://github.com/albertocavalcante/groovy-devtools/commit/5e643cf8d9c582c4e0c5ea005e334ac13508f704))
- **parser:** track AST children ([#446](https://github.com/albertocavalcante/groovy-devtools/issues/446))
  ([1bb917b](https://github.com/albertocavalcante/groovy-devtools/commit/1bb917beb671869fe49dd11d24888082e0326098))
- **tests:** add enhanced E2E scenarios for references, rename, and completion
  ([#429](https://github.com/albertocavalcante/groovy-devtools/issues/429))
  ([578c5da](https://github.com/albertocavalcante/groovy-devtools/commit/578c5da9f522d70d2690ce4471d767126bb96831))

### Bug Fixes

- add version number to release-please PR titles
  ([#399](https://github.com/albertocavalcante/groovy-devtools/issues/399))
  ([0aa853e](https://github.com/albertocavalcante/groovy-devtools/commit/0aa853e433483e404b782f73f247b4a1c1853c8c))
- **ci:** align nightly jar name ([#452](https://github.com/albertocavalcante/groovy-devtools/issues/452))
  ([e8dfad7](https://github.com/albertocavalcante/groovy-devtools/commit/e8dfad7f63d760257c22ccf072a9773b23c63b34))
- **ci:** harden GitHub Actions against ref injection attacks
  ([#435](https://github.com/albertocavalcante/groovy-devtools/issues/435))
  ([82cf1d6](https://github.com/albertocavalcante/groovy-devtools/commit/82cf1d662cf0bb00c0bde41ac909a4ccb2609719))
- **ci:** rebuild shadow jar for verification ([#450](https://github.com/albertocavalcante/groovy-devtools/issues/450))
  ([2429402](https://github.com/albertocavalcante/groovy-devtools/commit/2429402966f194de89267ef14d85b4701e1d58db))
- **deps:** update dependency org.openrewrite:rewrite-groovy to v8.70.1
  ([#394](https://github.com/albertocavalcante/groovy-devtools/issues/394))
  ([deedfab](https://github.com/albertocavalcante/groovy-devtools/commit/deedfab1ad489139b52a83b4a1caefb260624132))
- **deps:** update dependency org.openrewrite:rewrite-groovy to v8.70.2
  ([#402](https://github.com/albertocavalcante/groovy-devtools/issues/402))
  ([d0241d4](https://github.com/albertocavalcante/groovy-devtools/commit/d0241d4bfb92fa22d046a85fb2d163affb7332d0))
- log CancellationException at `DEBUG` level in DiagnosticsService
  ([#451](https://github.com/albertocavalcante/groovy-devtools/issues/451))
  ([014b83f](https://github.com/albertocavalcante/groovy-devtools/commit/014b83f71df20b29f8dc42f2603ffe9d3d14e737))
- narrow editors server ignore ([#391](https://github.com/albertocavalcante/groovy-devtools/issues/391))
  ([ff01ada](https://github.com/albertocavalcante/groovy-devtools/commit/ff01adaa8be3d318215ed0a1125df5d766a7f311))
- prevent zip slip in source jar extraction ([#381](https://github.com/albertocavalcante/groovy-devtools/issues/381))
  ([760020d](https://github.com/albertocavalcante/groovy-devtools/commit/760020dfd493d42ebe3a70093f6fbecaba4c6413))

## [0.4.7](https://github.com/albertocavalcante/groovy-devtools/compare/v0.4.6...v0.4.7) (2025-12-26)

### Features

- centralized release script and pinned actions
  ([#358](https://github.com/albertocavalcante/groovy-devtools/issues/358))
  ([6dbcfc3](https://github.com/albertocavalcante/groovy-devtools/commit/6dbcfc3cc07beddc039d6918637a22012c8fd0a1))

### Bug Fixes

- enable groovy/status readiness notifications ([#369](https://github.com/albertocavalcante/groovy-devtools/issues/369))
  ([40755ec](https://github.com/albertocavalcante/groovy-devtools/commit/40755ec8961d23e15de71a82a3ab5f22b97b93a7))
- **infra:** use single-string format for copybara add_header
  ([#354](https://github.com/albertocavalcante/groovy-devtools/issues/354))
  ([9715e46](https://github.com/albertocavalcante/groovy-devtools/commit/9715e463c417753c3eadad1f335f96c1e7d8da5d))

## [0.4.6](https://github.com/albertocavalcante/groovy-devtools/compare/v0.4.5...v0.4.6) (2025-12-26)

### Features

- **ci:** allow manual trigger for mirror workflow
  ([#330](https://github.com/albertocavalcante/groovy-devtools/issues/330))
  ([662c275](https://github.com/albertocavalcante/groovy-devtools/commit/662c275417e6ff673b8a9df08ff6aad24368558b))
- **ci:** default to GitHub-hosted runners in nightly/release workflows
  ([#321](https://github.com/albertocavalcante/groovy-devtools/issues/321))
  ([3ce956b](https://github.com/albertocavalcante/groovy-devtools/commit/3ce956bb327a6fb68b2040bfc10da5c668c5135f))
- implement groovy/status notification for server readiness
  ([#315](https://github.com/albertocavalcante/groovy-devtools/issues/315))
  ([ff5df51](https://github.com/albertocavalcante/groovy-devtools/commit/ff5df5183a42a118655985c08ef5dbbc86239cba)),
  closes [#314](https://github.com/albertocavalcante/groovy-devtools/issues/314)
- integrate vscode-groovy extension into monorepo
  ([6309756](https://github.com/albertocavalcante/groovy-devtools/commit/63097566621be0225d59e3706cebaa51546a483a))
- integrate vscode-groovy extension into monorepo
  ([#325](https://github.com/albertocavalcante/groovy-devtools/issues/325))
  ([3757e89](https://github.com/albertocavalcante/groovy-devtools/commit/3757e891ce598a9290c1266ed63416426079c80f))
- **release:** Final Polish (Manifest, Config, Homebrew)
  ([#352](https://github.com/albertocavalcante/groovy-devtools/issues/352))
  ([ac1fe4b](https://github.com/albertocavalcante/groovy-devtools/commit/ac1fe4ba87ba2f76bc468b6e50675894ac3dd7f8))
- Unified Release Model & Groovy DevTools Brand
  ([#350](https://github.com/albertocavalcante/groovy-devtools/issues/350))
  ([f7d657e](https://github.com/albertocavalcante/groovy-devtools/commit/f7d657e0f2c47082dc92a542a161a2207c8b61ff))
- unified release model + gvy extension rebrand
  ([#347](https://github.com/albertocavalcante/groovy-devtools/issues/347))
  ([57c4136](https://github.com/albertocavalcante/groovy-devtools/commit/57c4136d85168674574f681ad7dc3cdb839f9442))

### Bug Fixes

- add null checks in LSPToolService methods
  ([4cf9f46](https://github.com/albertocavalcante/groovy-devtools/commit/4cf9f4691973fa72d3bdaf66a4ac4f92a20dd0f4))
- address bug and critical code smells ([#318](https://github.com/albertocavalcante/groovy-devtools/issues/318))
  ([7615ce7](https://github.com/albertocavalcante/groovy-devtools/commit/7615ce7b51a2cb7f452f5a04b88379d44c4ac34f))
- **ci:** update copybara-action to v1.2.5 ([#329](https://github.com/albertocavalcante/groovy-devtools/issues/329))
  ([a9a7777](https://github.com/albertocavalcante/groovy-devtools/commit/a9a777716838f0768828bbf30312f80e97636f02))
- **ci:** use correct input 'custom_config' for copybara action
  ([#332](https://github.com/albertocavalcante/groovy-devtools/issues/332))
  ([1d68455](https://github.com/albertocavalcante/groovy-devtools/commit/1d68455aaf5420a24a8b69dec30d5c25201371dd))
- **ci:** use pattern matching for OS detection in release workflow
  ([#324](https://github.com/albertocavalcante/groovy-devtools/issues/324))
  ([adaa260](https://github.com/albertocavalcante/groovy-devtools/commit/adaa260e4c089eaf5e2c5cd76cf79da27bdc0f50))
- **copybara:** preserve Closes/Fixes and add read-only warning
  ([#335](https://github.com/albertocavalcante/groovy-devtools/issues/335))
  ([b5b28ab](https://github.com/albertocavalcante/groovy-devtools/commit/b5b28ab4a710324d3f2099141d3dba6114358daa))
- correct VSIX path in release workflow
  ([e5d9079](https://github.com/albertocavalcante/groovy-devtools/commit/e5d907975d69234f2e564b8b483d4ba324dce222))
- improve branch coverage accuracy
  ([755b42e](https://github.com/albertocavalcante/groovy-devtools/commit/755b42e017856825494adb8321e6b8193d9df528))
- include resources/ in vscodeignore allowlist
  ([705b66d](https://github.com/albertocavalcante/groovy-devtools/commit/705b66d9879c198da636d47912bd59e634543128))
- **infra:** use SSH URL for copybara destination
  ([#333](https://github.com/albertocavalcante/groovy-devtools/issues/333))
  ([6808c89](https://github.com/albertocavalcante/groovy-devtools/commit/6808c893d74ec69962ff6643fc405942a6f9ecfa))
- make KernelExecutor a functional interface ([#323](https://github.com/albertocavalcante/groovy-devtools/issues/323))
  ([67df06b](https://github.com/albertocavalcante/groovy-devtools/commit/67df06ba0081d0f3b4a63100ccc35b8f4012e5df))
- make SpockGenerator tests platform-agnostic
  ([426b557](https://github.com/albertocavalcante/groovy-devtools/commit/426b557d1f9927a11d0af797e7f49dc809dbbe40))
- merge nested if statements in CompletionProvider
  ([#322](https://github.com/albertocavalcante/groovy-devtools/issues/322))
  ([7936259](https://github.com/albertocavalcante/groovy-devtools/commit/7936259ec2d7617999c3a0f028b063bad3a848b5))
- reset regex lastIndex in CoverageService to avoid skipping matches
  ([d236f8f](https://github.com/albertocavalcante/groovy-devtools/commit/d236f8f69eb983b7c31a4a051c1fc8424a5e0579))
- resolve automated review findings
  ([c9967b5](https://github.com/albertocavalcante/groovy-devtools/commit/c9967b5293c20038308af52b28dd1558e6f91564))
- resolve lint errors in AI features and tests
  ([27905e9](https://github.com/albertocavalcante/groovy-devtools/commit/27905e94f785f3306d2db088019f2c76cc23fe94))
- **security:** add tag validation to prevent script injection
  ([#336](https://github.com/albertocavalcante/groovy-devtools/issues/336))
  ([ff87914](https://github.com/albertocavalcante/groovy-devtools/commit/ff8791414d3305d99bb82902c1d4d60a229e14d2))
- **security:** address workflow security vulnerabilities
  ([#317](https://github.com/albertocavalcante/groovy-devtools/issues/317))
  ([b3dd802](https://github.com/albertocavalcante/groovy-devtools/commit/b3dd802b8eb9a0a799164d89324b317ef6eb85c7))
- **test:** correct CoverageService relative path and resolve TS2349
  ([8b0bfe9](https://github.com/albertocavalcante/groovy-devtools/commit/8b0bfe9e7514c7a6979e58a1045113311baef5dc))
- **test:** simplify CoverageService file mocks to be unconditional
  ([3353871](https://github.com/albertocavalcante/groovy-devtools/commit/3353871f9d36cb6b736e05b511bfc6bd05fd0c57))
- **test:** use default import for proxyquire to resolve TS2349
  ([4192c25](https://github.com/albertocavalcante/groovy-devtools/commit/4192c257cc1e094f9ee3dd0ef35830fecbde47e3))
- **test:** use noCallThru() in CoverageService test to force vscode mocking
  ([4919f44](https://github.com/albertocavalcante/groovy-devtools/commit/4919f442fa47e1aff51821d1f33cbcdff6306ff3))
- **test:** use sinon.match for paths in CoverageService test to support Windows
  ([7265429](https://github.com/albertocavalcante/groovy-devtools/commit/7265429e2bcd4c8800db60a4f1ebdb8439592cca))

## [0.4.5](https://github.com/albertocavalcante/groovy-lsp/compare/v0.4.4...v0.4.5) (2025-12-24)

### Features

- **e2e:** add typed support for custom @JsonRequest methods
  ([#313](https://github.com/albertocavalcante/groovy-lsp/issues/313))
  ([0ab8129](https://github.com/albertocavalcante/groovy-lsp/commit/0ab8129c473a772a43f29b46e37aafcc31101a43))

### Bug Fixes

- robust classloader isolation and test discovery fixes
  ([#311](https://github.com/albertocavalcante/groovy-lsp/issues/311))
  ([f5d442b](https://github.com/albertocavalcante/groovy-lsp/commit/f5d442b69eebf5dd954984e4a013f9da80c97864))

## [0.4.4](https://github.com/albertocavalcante/groovy-lsp/compare/v0.4.3...v0.4.4) (2025-12-24)

### Features

- add /review workflow for PR review and feedback ([#297](https://github.com/albertocavalcante/groovy-lsp/issues/297))
  ([5a6ba37](https://github.com/albertocavalcante/groovy-lsp/commit/5a6ba372a15e9c0f594391034c55c8bcc600b311))
- Codebase Health Improvements (Batch 1) ([#308](https://github.com/albertocavalcante/groovy-lsp/issues/308))
  ([2b8ad1c](https://github.com/albertocavalcante/groovy-lsp/commit/2b8ad1c3c71af9642d5b4d6133f7e87d42c9ef54))
- Improve Kotlin LSP Installation Script ([#309](https://github.com/albertocavalcante/groovy-lsp/issues/309))
  ([2f3d01b](https://github.com/albertocavalcante/groovy-lsp/commit/2f3d01b2fe6b4e0f5aa5f0703040eff8b3a6832f))
- **lsp:** AST-based property completions for Jenkins globals
  ([#289](https://github.com/albertocavalcante/groovy-lsp/issues/289))
  ([bec8ad7](https://github.com/albertocavalcante/groovy-lsp/commit/bec8ad7e88ec211d7c46c1de5101a38fa3c86af9))
- **lsp:** intelligent parameter sorting and enrichment expansion
  ([#287](https://github.com/albertocavalcante/groovy-lsp/issues/287))
  ([8dea56d](https://github.com/albertocavalcante/groovy-lsp/commit/8dea56d21e407f3af377416c849eba51d32d481c))
- setup cross-platform /next slash command ([#302](https://github.com/albertocavalcante/groovy-lsp/issues/302))
  ([b561030](https://github.com/albertocavalcante/groovy-lsp/commit/b561030ea538c96c417c6ec01192a8c132ec5b7c))
- Type Inference Improvements ([#307](https://github.com/albertocavalcante/groovy-lsp/issues/307))
  ([74f1cd2](https://github.com/albertocavalcante/groovy-lsp/commit/74f1cd2c203f3cce5aaf6e36be9f5650d91ca928))

### Bug Fixes

- **ci:** enable e2e tests on PRs ([#299](https://github.com/albertocavalcante/groovy-lsp/issues/299))
  ([2495ab8](https://github.com/albertocavalcante/groovy-lsp/commit/2495ab831d4d94e325a9413daef16b44a08379a4))
- **ci:** remove invalid predicate-quantifier from paths-filter
  ([#298](https://github.com/albertocavalcante/groovy-lsp/issues/298))
  ([81f259e](https://github.com/albertocavalcante/groovy-lsp/commit/81f259e74d2ed2751b479ee3a578f584c94f8558))
- **e2e:** handle null objects in ScenarioContext assertions
  ([#300](https://github.com/albertocavalcante/groovy-lsp/issues/300))
  ([2914af8](https://github.com/albertocavalcante/groovy-lsp/commit/2914af8c19863dbcc127443cfae09c3ce1e25bb4))
- explicit gradle toolchain configuration and build health improvements
  ([#301](https://github.com/albertocavalcante/groovy-lsp/issues/301))
  ([60c78c1](https://github.com/albertocavalcante/groovy-lsp/commit/60c78c19a17c130356e1a3a19167029f4f047621))
- **groovy-jenkins:** Deep Merging, Lazy Resolution & Cleanup
  ([#306](https://github.com/albertocavalcante/groovy-lsp/issues/306))
  ([fa21fff](https://github.com/albertocavalcante/groovy-lsp/commit/fa21fffb985af710c2ac45e48465b33f851ab984))
- remove unused variables and imports ([#296](https://github.com/albertocavalcante/groovy-lsp/issues/296))
  ([46ffb39](https://github.com/albertocavalcante/groovy-lsp/commit/46ffb398354953155f802dd75fe9cefd22622e71))

## [0.4.3](https://github.com/albertocavalcante/groovy-lsp/compare/v0.4.2...v0.4.3) (2025-12-22)

### Features

- **jenkins:** integrate enrichment metadata into LSP providers
  ([#284](https://github.com/albertocavalcante/groovy-lsp/issues/284))
  ([3a70470](https://github.com/albertocavalcante/groovy-lsp/commit/3a70470126b00752ef9211859190dc95d89e2115))
- **lsp:** smart parameter snippets with type-aware templates
  ([#286](https://github.com/albertocavalcante/groovy-lsp/issues/286))
  ([72e8c1b](https://github.com/albertocavalcante/groovy-lsp/commit/72e8c1bfe7f3743b58e0117cb7bde2b3eff69c40))

## [0.4.2](https://github.com/albertocavalcante/groovy-lsp/compare/v0.4.1...v0.4.2) (2025-12-22)

### Features

- add groovy-testing module and refactor test runner architecture
  ([#281](https://github.com/albertocavalcante/groovy-lsp/issues/281))
  ([de38199](https://github.com/albertocavalcante/groovy-lsp/commit/de38199cdd956b2a7049971304b7c7450c0075aa))
- Add JUnit 5 Support ([#282](https://github.com/albertocavalcante/groovy-lsp/issues/282))
  ([ac4ebd1](https://github.com/albertocavalcante/groovy-lsp/commit/ac4ebd1bc3c9723af1ab1d812d46829d20c5b635))
- add Test Runner LSP support for Spock specifications
  ([#279](https://github.com/albertocavalcante/groovy-lsp/issues/279))
  ([a091015](https://github.com/albertocavalcante/groovy-lsp/commit/a091015532944c4bb60ed6ab45d6617d02d5d680))
- **junit4:** implement JUnit 4 support ([#283](https://github.com/albertocavalcante/groovy-lsp/issues/283))
  ([3280397](https://github.com/albertocavalcante/groovy-lsp/commit/32803976fddc827f0a240a56bb813c41a3589151))

## [0.4.1](https://github.com/albertocavalcante/groovy-lsp/compare/v0.4.0...v0.4.1) (2025-12-21)

### Features

- add Jenkins enrichment metadata ([#266](https://github.com/albertocavalcante/groovy-lsp/issues/266))
  ([0ee5206](https://github.com/albertocavalcante/groovy-lsp/commit/0ee5206d59edab2c91c9785e7b7313da9ab8628e))
- add Jenkins GDSL extractor tooling ([#265](https://github.com/albertocavalcante/groovy-lsp/issues/265))
  ([9a69ef1](https://github.com/albertocavalcante/groovy-lsp/commit/9a69ef106a11a67fbb2e5ae8b98ddc5eb21280cb))
- add Jenkins IntelliSense architecture and TDD foundation
  ([#257](https://github.com/albertocavalcante/groovy-lsp/issues/257))
  ([f09a68a](https://github.com/albertocavalcante/groovy-lsp/commit/f09a68abf8f71eaace4954aaf2e23948d2fcae72))
- add Jenkins metadata foundation data structures (PR [#1](https://github.com/albertocavalcante/groovy-lsp/issues/1))
  ([#263](https://github.com/albertocavalcante/groovy-lsp/issues/263))
  ([109bcf2](https://github.com/albertocavalcante/groovy-lsp/commit/109bcf23bffc45a15762ada029649e854d103b99))
- improve Jenkins GDSL extraction and add E2E tests ([#261](https://github.com/albertocavalcante/groovy-lsp/issues/261))
  ([88101ea](https://github.com/albertocavalcante/groovy-lsp/commit/88101eabad661c764ad0ad5a838671294826eece))
- **jupyter:** add groovy-jupyter kernel module (Phase 1A)
  ([#262](https://github.com/albertocavalcante/groovy-lsp/issues/262))
  ([9498405](https://github.com/albertocavalcante/groovy-lsp/commit/949840560abbc992fe532ddeac36e4122dee6748))
- **jupyter:** add kernel packaging and installation script
  ([#274](https://github.com/albertocavalcante/groovy-lsp/issues/274))
  ([541af98](https://github.com/albertocavalcante/groovy-lsp/commit/541af983f52fbbb622a25765f39773f759fca9e3))
- **jupyter:** implement Connection and Heartbeat handlers (Phase 1B.2)
  ([#268](https://github.com/albertocavalcante/groovy-lsp/issues/268))
  ([3a6d7b7](https://github.com/albertocavalcante/groovy-lsp/commit/3a6d7b7bf6eae582ab8993791ba72b6b0f5f0a65))
- **jupyter:** implement ExecuteHandler and StreamPublisher (Phase 1B.4)
  ([#270](https://github.com/albertocavalcante/groovy-lsp/issues/270))
  ([882ee07](https://github.com/albertocavalcante/groovy-lsp/commit/882ee074c14eb1d419490bf81aff53bdc1faf17f))
- **jupyter:** implement KernelInfoHandler and StatusPublisher (Phase 1B.3)
  ([#269](https://github.com/albertocavalcante/groovy-lsp/issues/269))
  ([9d76e07](https://github.com/albertocavalcante/groovy-lsp/commit/9d76e07ba6002c506846d62388cfd217cf850180))
- **jupyter:** implement KernelServer and ShutdownHandler
  ([#271](https://github.com/albertocavalcante/groovy-lsp/issues/271))
  ([04acd6c](https://github.com/albertocavalcante/groovy-lsp/commit/04acd6c1168db52682cd69eeae06bcb414703c75))
- **jupyter:** implement message dispatch in KernelServer
  ([#273](https://github.com/albertocavalcante/groovy-lsp/issues/273))
  ([ae03ce4](https://github.com/albertocavalcante/groovy-lsp/commit/ae03ce43fd59349c4944feda524e4a49e8dbc5ba))
- **jupyter:** implement Wire Protocol for ZMQ messages (Phase 1B.1)
  ([#267](https://github.com/albertocavalcante/groovy-lsp/issues/267))
  ([9c6fb72](https://github.com/albertocavalcante/groovy-lsp/commit/9c6fb729609da7b17a0b99b2a0259d0e5109822b))
- **lsp:** add groovy/discoverTests custom method for Spock test discovery
  ([#277](https://github.com/albertocavalcante/groovy-lsp/issues/277))
  ([5ffa1e5](https://github.com/albertocavalcante/groovy-lsp/commit/5ffa1e5e33989467bde98bf7af1e0caef7a5fe80))

### Bug Fixes

- add Apache Ivy dependency for Grape/Grab support in Jupyter kernel
  ([#276](https://github.com/albertocavalcante/groovy-lsp/issues/276))
  ([14c6d8e](https://github.com/albertocavalcante/groovy-lsp/commit/14c6d8e58b01b4a19faaebb7aa9c22d1e235cf79))
- **deps:** update dependency ch.qos.logback:logback-classic to v1.5.23
  ([#278](https://github.com/albertocavalcante/groovy-lsp/issues/278))
  ([a49cf92](https://github.com/albertocavalcante/groovy-lsp/commit/a49cf9280f5ce07b4f167cee4fe093a4dc80e0a0))

## [0.4.0](https://github.com/albertocavalcante/groovy-lsp/compare/v0.3.4...v0.4.0) (2025-12-21)

### ⚠ BREAKING CHANGES

- implement v0.4.0 P1 features - workspace indexing, library sources, auto-import, and config watching
  ([#256](https://github.com/albertocavalcante/groovy-lsp/issues/256))

### Features

- add BSP client support for Bazel, sbt, and Mill ([#253](https://github.com/albertocavalcante/groovy-lsp/issues/253))
  ([b584cad](https://github.com/albertocavalcante/groovy-lsp/commit/b584cad231f7bd28850e89dcbfbe945fda21b441))
- add comprehensive test monitoring infrastructure ([#251](https://github.com/albertocavalcante/groovy-lsp/issues/251))
  ([13bf442](https://github.com/albertocavalcante/groovy-lsp/commit/13bf4424d46498bffda6825dd22a86fbdb2aa12a))
- add Gradle build strategy configuration and BSP integration docs
  ([#254](https://github.com/albertocavalcante/groovy-lsp/issues/254))
  ([74e8733](https://github.com/albertocavalcante/groovy-lsp/commit/74e8733cd5fa0b0c40ef0240533d42a449ae5fce))
- **ci:** add local-macos runner support and auto-setup script
  ([#247](https://github.com/albertocavalcante/groovy-lsp/issues/247))
  ([ce34339](https://github.com/albertocavalcante/groovy-lsp/commit/ce343390d7ad88100612bb77647968c9ac00b73e))
- **ci:** add runner specs display in job summary ([#246](https://github.com/albertocavalcante/groovy-lsp/issues/246))
  ([7c34415](https://github.com/albertocavalcante/groovy-lsp/commit/7c34415c910b25308b408ce052761e7494d2d7d5))
- **common:** add groovy-common module with shared functional utilities
  ([#207](https://github.com/albertocavalcante/groovy-lsp/issues/207))
  ([8a5e14e](https://github.com/albertocavalcante/groovy-lsp/commit/8a5e14e00e82892af758b7ea006310b9001a7725))
- implement v0.4.0 P1 features - workspace indexing, library sources, auto-import, and config watching
  ([#256](https://github.com/albertocavalcante/groovy-lsp/issues/256))
  ([4ae7ad0](https://github.com/albertocavalcante/groovy-lsp/commit/4ae7ad040379ab9eafa550fa85f6dd4c1e3c960d))
- **infra:** add runner image selection and fix region validation
  ([#240](https://github.com/albertocavalcante/groovy-lsp/issues/240))
  ([dffaeb5](https://github.com/albertocavalcante/groovy-lsp/commit/dffaeb58c8c7d649013be920965ccad667deb9c3))
- **infra:** Enhance runner infrastructure with auto-cleanup and better specs
  ([#237](https://github.com/albertocavalcante/groovy-lsp/issues/237))
  ([892aacb](https://github.com/albertocavalcante/groovy-lsp/commit/892aacb0b1e693d513bd5be776773126add9ac15))
- **infra:** expose runner ip and update scopes ([#226](https://github.com/albertocavalcante/groovy-lsp/issues/226))
  ([c5ac42e](https://github.com/albertocavalcante/groovy-lsp/commit/c5ac42e70f1c25e0aefe967f4547849fdb39b788))
- **infra:** mark runner ips as sensitive output ([#228](https://github.com/albertocavalcante/groovy-lsp/issues/228))
  ([3865697](https://github.com/albertocavalcante/groovy-lsp/commit/3865697152703c4fc63c5bee9331bfd72aa80fe4))
- **infra:** prune offline runners before destroying
  ([#242](https://github.com/albertocavalcante/groovy-lsp/issues/242))
  ([8d93bd5](https://github.com/albertocavalcante/groovy-lsp/commit/8d93bd508c2bbc03f4a7b0cbd1a75b9f9d190349))
- Integrates Magalu Cloud Self-Hosted Runner ([#214](https://github.com/albertocavalcante/groovy-lsp/issues/214))
  ([a60951e](https://github.com/albertocavalcante/groovy-lsp/commit/a60951ef3219c1f5d0625c498eba49a1db00c294))
- **lsp:** wire GroovyTokenIndex into Spock completion gating
  ([#211](https://github.com/albertocavalcante/groovy-lsp/issues/211))
  ([f110795](https://github.com/albertocavalcante/groovy-lsp/commit/f1107951a80d1ae96cf1394895f7220c28c418da))
- **parser:** add compilePhase option ([#202](https://github.com/albertocavalcante/groovy-lsp/issues/202))
  ([04e4161](https://github.com/albertocavalcante/groovy-lsp/commit/04e4161c11bc24425fd480dbf63f54a88369c13f))
- **parser:** implement deterministic GroovyTokenIndex using lexer classification
  ([#210](https://github.com/albertocavalcante/groovy-lsp/issues/210))
  ([10e2a43](https://github.com/albertocavalcante/groovy-lsp/commit/10e2a438cb1732fe76ef6f6d1e6aab70670a5f38))
- scaffold spock support ([#200](https://github.com/albertocavalcante/groovy-lsp/issues/200))
  ([122967e](https://github.com/albertocavalcante/groovy-lsp/commit/122967e02e34349cf3a7f0f413a5ae1fee48f5dd))
- **spock:** add AST-based spec detection ([#203](https://github.com/albertocavalcante/groovy-lsp/issues/203))
  ([228fa39](https://github.com/albertocavalcante/groovy-lsp/commit/228fa394a1fc46e0e96d95f6a4bc5c9906e290b1))
- **spock:** add SpockBlockIndex for AST-based block detection
  ([#212](https://github.com/albertocavalcante/groovy-lsp/issues/212))
  ([c3c5856](https://github.com/albertocavalcante/groovy-lsp/commit/c3c5856f3b5dfd3126f5db0c93e9b7d31418ba57))

### Bug Fixes

- add CodeNarc Jenkins diagnostics support and fix ruleset resolution
  ([#255](https://github.com/albertocavalcante/groovy-lsp/issues/255))
  ([6b5e488](https://github.com/albertocavalcante/groovy-lsp/commit/6b5e48898546832743baa5ca561d80687e639cfd))
- **build-tool:** resolve detekt issues ([#239](https://github.com/albertocavalcante/groovy-lsp/issues/239))
  ([ed21f9c](https://github.com/albertocavalcante/groovy-lsp/commit/ed21f9ca1a71d112f52dbb6934990df6d309e839))
- **ci:** add checkout step and set magalu as default runner
  ([#235](https://github.com/albertocavalcante/groovy-lsp/issues/235))
  ([6f43f8d](https://github.com/albertocavalcante/groovy-lsp/commit/6f43f8d2c26cadca11bfe4d04bd1caab105ddc17))
- **ci:** add workflow_dispatch to concurrency group
  ([#248](https://github.com/albertocavalcante/groovy-lsp/issues/248))
  ([94aa77b](https://github.com/albertocavalcante/groovy-lsp/commit/94aa77bf4d138388396b1129a10accdc89d90c06))
- **ci:** auto-create TFC workspace on first provision
  ([#222](https://github.com/albertocavalcante/groovy-lsp/issues/222))
  ([b4edf00](https://github.com/albertocavalcante/groovy-lsp/commit/b4edf00dd130ea7f013fb54a1f18bf2d590c2395))
- **e2e:** fix failing tests and remove star imports
  ([#243](https://github.com/albertocavalcante/groovy-lsp/issues/243))
  ([b18cc7f](https://github.com/albertocavalcante/groovy-lsp/commit/b18cc7fff2733ceec2b6363a0cef248b6ac4c411))
- flatten os matrix strategy in ci.yml to fix yaml syntax
  ([#231](https://github.com/albertocavalcante/groovy-lsp/issues/231))
  ([72fe831](https://github.com/albertocavalcante/groovy-lsp/commit/72fe831be67f862de7b0cb4210a23ba1ce5db019))
- **gradle:** improve error logging when dependency resolution fails
  ([#213](https://github.com/albertocavalcante/groovy-lsp/issues/213))
  ([b30e0d5](https://github.com/albertocavalcante/groovy-lsp/commit/b30e0d5d70d90c75eb7b1ed1b5448d5046575f5b))
- **infra:** add missing changes from PR [#214](https://github.com/albertocavalcante/groovy-lsp/issues/214)
  ([#217](https://github.com/albertocavalcante/groovy-lsp/issues/217))
  ([a9220e7](https://github.com/albertocavalcante/groovy-lsp/commit/a9220e74dbb1f3c5de52c345dc61a509da96b5d4))
- **infra:** update module ref to latest commit (76b92b2)
  ([#224](https://github.com/albertocavalcante/groovy-lsp/issues/224))
  ([85640ec](https://github.com/albertocavalcante/groovy-lsp/commit/85640eca58a040a01f5c8c0c38e98b16bc9e6d2e))
- **infra:** upgrade Terraform to 1.11.4 for WriteOnly attribute support
  ([#223](https://github.com/albertocavalcante/groovy-lsp/issues/223))
  ([ba71b1e](https://github.com/albertocavalcante/groovy-lsp/commit/ba71b1ed21f647377a8122f7ef56ea19ffc035fb))
- **infra:** use cloud block instead of remote backend
  ([#220](https://github.com/albertocavalcante/groovy-lsp/issues/220))
  ([c0ad2e0](https://github.com/albertocavalcante/groovy-lsp/commit/c0ad2e02a6ee99f2b167e4bd8089febc2aaa237b))
- **lsp:** compile on-demand for definition ([#204](https://github.com/albertocavalcante/groovy-lsp/issues/204))
  ([c6e3faf](https://github.com/albertocavalcante/groovy-lsp/commit/c6e3faf1d6fdb3bb8f9cdde82739390794bbbd9a))
- resolve thread starvation deadlock in ConcurrencyStressTest
  ([#250](https://github.com/albertocavalcante/groovy-lsp/issues/250))
  ([6bea244](https://github.com/albertocavalcante/groovy-lsp/commit/6bea2443b8d23c4060e9aa9bff0bc653c686cc2c))

### Performance Improvements

- **ci:** eliminate polling waste with event-driven runner provisioning
  ([#245](https://github.com/albertocavalcante/groovy-lsp/issues/245))
  ([18ccf89](https://github.com/albertocavalcante/groovy-lsp/commit/18ccf89fa89a6a1b7a3ed57b2d5824dd83b4079c))
- **gradle:** optimize JVM args for 16GB runner ([#238](https://github.com/albertocavalcante/groovy-lsp/issues/238))
  ([500fe14](https://github.com/albertocavalcante/groovy-lsp/commit/500fe14d32b80890060c8489da72f8eb38220893))

## [0.3.4](https://github.com/albertocavalcante/groovy-lsp/compare/v0.3.3...v0.3.4) (2025-12-17)

### Features

- add exponential backoff retry for Gradle lock timeout
  ([#198](https://github.com/albertocavalcante/groovy-lsp/issues/198))
  ([876fb3e](https://github.com/albertocavalcante/groovy-lsp/commit/876fb3e8a6bf8a827d4dd219b4073bc7157e3352))
- **jenkins:** improve vars completion and definition behavior
  ([#197](https://github.com/albertocavalcante/groovy-lsp/issues/197))
  ([37ffa9e](https://github.com/albertocavalcante/groovy-lsp/commit/37ffa9eaa0e109ecbbf520a48602bc104b3c9ec0))

## [0.3.3](https://github.com/albertocavalcante/groovy-lsp/compare/v0.3.2...v0.3.3) (2025-12-17)

### Bug Fixes

- **deps:** update mavenembedder to v3.9.12 ([#189](https://github.com/albertocavalcante/groovy-lsp/issues/189))
  ([8182094](https://github.com/albertocavalcante/groovy-lsp/commit/81820946d9ec4173ee014987f365bfb78f2830d1))

### Performance Improvements

- **codenarc:** cache ruleset files and speed up tests
  ([#194](https://github.com/albertocavalcante/groovy-lsp/issues/194))
  ([8eb6de9](https://github.com/albertocavalcante/groovy-lsp/commit/8eb6de95c88762f020f5a7d8b7279450a0ef814b))

## [0.3.2](https://github.com/albertocavalcante/groovy-lsp/compare/v0.3.1...v0.3.2) (2025-12-17)

### Features

- implement Flow-based diagnostic provider architecture
  ([#191](https://github.com/albertocavalcante/groovy-lsp/issues/191))
  ([0d5ea82](https://github.com/albertocavalcante/groovy-lsp/commit/0d5ea823b9cdae4e5509a73484e846d7002add15))
- Jenkins semantic tokens for enhanced syntax highlighting
  ([#184](https://github.com/albertocavalcante/groovy-lsp/issues/184))
  ([55a003c](https://github.com/albertocavalcante/groovy-lsp/commit/55a003c45843c384a69433cfaf475a527057761e))
- **jenkins:** enhanced vars/ support with semantic tokens and hover docs
  ([#190](https://github.com/albertocavalcante/groovy-lsp/issues/190))
  ([f335ab6](https://github.com/albertocavalcante/groovy-lsp/commit/f335ab6a3644862cf8a9faae466bd692309547ee))
- **jenkins:** implement layered plugin discovery ([#188](https://github.com/albertocavalcante/groovy-lsp/issues/188))
  ([bf84cc9](https://github.com/albertocavalcante/groovy-lsp/commit/bf84cc954df06985481434a2733a9e839e79c87d))

## [0.3.1](https://github.com/albertocavalcante/groovy-lsp/compare/v0.3.0...v0.3.1) (2025-12-16)

### Bug Fixes

- **deps:** update dependency com.github.javaparser:javaparser-core to v3.27.1
  ([#181](https://github.com/albertocavalcante/groovy-lsp/issues/181))
  ([79f3515](https://github.com/albertocavalcante/groovy-lsp/commit/79f3515526730959fb19e151cb1c0bd2bcef65ff))
- **formatter:** preserve shebangs in Groovy scripts
  ([#183](https://github.com/albertocavalcante/groovy-lsp/issues/183))
  ([1d72f2a](https://github.com/albertocavalcante/groovy-lsp/commit/1d72f2af361fd7ba7cf2154761ddacf391b47a26))

## [0.3.0](https://github.com/albertocavalcante/groovy-lsp/compare/v0.2.0...v0.3.0) (2025-12-16)

### ⚠ BREAKING CHANGES

- **codeaction:** implement textDocument/codeAction for imports, formatting, and lint fixes
  ([#114](https://github.com/albertocavalcante/groovy-lsp/issues/114))

### Features

- add bundled Jenkins metadata scaffold ([#145](https://github.com/albertocavalcante/groovy-lsp/issues/145))
  ([69d1aad](https://github.com/albertocavalcante/groovy-lsp/commit/69d1aad785693eb4139d32c611423756a8f50e23))
- add core infrastructure for CodeNarc lint fixes ([#158](https://github.com/albertocavalcante/groovy-lsp/issues/158))
  ([e0d5083](https://github.com/albertocavalcante/groovy-lsp/commit/e0d5083d631cb5e7fc722efc3c934e3f06c85f13))
- add groovydoc extraction to hover and signature help
  ([#112](https://github.com/albertocavalcante/groovy-lsp/issues/112))
  ([22cfeca](https://github.com/albertocavalcante/groovy-lsp/commit/22cfecafe2207a697d73896d48a7c1d31e4cb835))
- add Jenkins map-key completions and expanded metadata
  ([#148](https://github.com/albertocavalcante/groovy-lsp/issues/148))
  ([791a15d](https://github.com/albertocavalcante/groovy-lsp/commit/791a15d3bf6a489c56d9cd728d385f19089a1c99))
- add support for goto definition in JAR dependencies
  ([#142](https://github.com/albertocavalcante/groovy-lsp/issues/142))
  ([b53887b](https://github.com/albertocavalcante/groovy-lsp/commit/b53887b4b8500c76d35ecb4a9de0f42f2c823e32))
- build tool abstraction and Maven support ([#168](https://github.com/albertocavalcante/groovy-lsp/issues/168))
  ([88b2bb9](https://github.com/albertocavalcante/groovy-lsp/commit/88b2bb92ac05514e5bb85a7add2a05837b2285b2))
- **codeaction:** implement textDocument/codeAction for imports, formatting, and lint fixes
  ([#114](https://github.com/albertocavalcante/groovy-lsp/issues/114))
  ([a4661b6](https://github.com/albertocavalcante/groovy-lsp/commit/a4661b639b2bbd79630ba8a54e12fddd9fd85ef9))
- complete CodeNarc lint fixes Phase 4 - safety validation and integration
  ([#164](https://github.com/albertocavalcante/groovy-lsp/issues/164))
  ([d385a3d](https://github.com/albertocavalcante/groovy-lsp/commit/d385a3d0ae68551ebe6a385c8109fb1b031bcdda))
- complete Phase 2 import cleanup fixes for CodeNarc lint
  ([#162](https://github.com/albertocavalcante/groovy-lsp/issues/162))
  ([43e1574](https://github.com/albertocavalcante/groovy-lsp/commit/43e1574e43c9e9383c3a6bca84fd77395d1a4689))
- default to recursive ast visitor in lsp ([#139](https://github.com/albertocavalcante/groovy-lsp/issues/139))
  ([8d99ead](https://github.com/albertocavalcante/groovy-lsp/commit/8d99eade4bc0cead370efbf511ae9ea0c9f6092f))
- **definition:** integrate SourceNavigationService for lazy source JAR fetching
  ([#176](https://github.com/albertocavalcante/groovy-lsp/issues/176))
  ([495b502](https://github.com/albertocavalcante/groovy-lsp/commit/495b5029b0d75aa1d9c94cf678f095bca14b666a))
- fix race condition and symbol resolution for goto-definition
  ([#141](https://github.com/albertocavalcante/groovy-lsp/issues/141))
  ([a32958f](https://github.com/albertocavalcante/groovy-lsp/commit/a32958f25d16117ffd895d03bbf141273d34e40e))
- implement CodeNarc lint fixes Phase 1 - whitespace handlers
  ([#161](https://github.com/albertocavalcante/groovy-lsp/issues/161))
  ([63b6608](https://github.com/albertocavalcante/groovy-lsp/commit/63b6608d6d901084aa9694b83091ae4b6d189102))
- Implement GDK and Type Parameter completion ([#143](https://github.com/albertocavalcante/groovy-lsp/issues/143))
  ([8664629](https://github.com/albertocavalcante/groovy-lsp/commit/86646296f34f3d97fd22d58fd3d901236f634355))
- implement TrailingWhitespace fix handler ([#159](https://github.com/albertocavalcante/groovy-lsp/issues/159))
  ([461d2a8](https://github.com/albertocavalcante/groovy-lsp/commit/461d2a80b59c5399bf8d32850b21a7e8454f303c))
- implement UnnecessaryPublicModifier lint fix handler
  ([#163](https://github.com/albertocavalcante/groovy-lsp/issues/163))
  ([ce71af1](https://github.com/albertocavalcante/groovy-lsp/commit/ce71af1c7f81660dac8b75b7e2abb0cbbc226bf1))
- improve java source navigation with accurate line numbers and javadoc hover
  ([#177](https://github.com/albertocavalcante/groovy-lsp/issues/177))
  ([2f16a3a](https://github.com/albertocavalcante/groovy-lsp/commit/2f16a3a32842649cc64c13805f484af8dbf4d93e))
- improve type inference with generics support and fix forEach completion
  ([#144](https://github.com/albertocavalcante/groovy-lsp/issues/144))
  ([db6b45e](https://github.com/albertocavalcante/groovy-lsp/commit/db6b45eb653181b13d279406e1877fa58abeed79))
- **jenkins:** add first-class Jenkins pipeline support with @Library resolution and GDSL metadata
  ([#123](https://github.com/albertocavalcante/groovy-lsp/issues/123))
  ([b1160c1](https://github.com/albertocavalcante/groovy-lsp/commit/b1160c1f2e617c485e98dd78bf1e2b522127803a))
- **jenkins:** add version-aware plugin resolution and documentation provider
  ([#175](https://github.com/albertocavalcante/groovy-lsp/issues/175))
  ([a940862](https://github.com/albertocavalcante/groovy-lsp/commit/a940862a2544b8cc6583beedc98930b6a096831a))
- **jenkins:** auto-add src directory to classpath for shared libraries
  ([#170](https://github.com/albertocavalcante/groovy-lsp/issues/170))
  ([39d1529](https://github.com/albertocavalcante/groovy-lsp/commit/39d1529633d9c371679aa3c394617479b78877bb))
- **jenkins:** expand Jenkins step metadata and add hover documentation
  ([#174](https://github.com/albertocavalcante/groovy-lsp/issues/174))
  ([0734f19](https://github.com/albertocavalcante/groovy-lsp/commit/0734f192257e7fb6b29fc033430d5d56e39aa5d0))
- Maven Embedder integration with build tool consolidation
  ([#171](https://github.com/albertocavalcante/groovy-lsp/issues/171))
  ([a0f6dfb](https://github.com/albertocavalcante/groovy-lsp/commit/a0f6dfb5e2320721eb6fa2d91f3da09c245af2fe))
- **parser:** add full groovy operator support to recursive visitor
  ([#133](https://github.com/albertocavalcante/groovy-lsp/issues/133))
  ([4188412](https://github.com/albertocavalcante/groovy-lsp/commit/4188412d394d584e49d97694488bdcbdbd8023a7))
- recursive AST visitor with comprehensive parity testing and documentation
  ([#132](https://github.com/albertocavalcante/groovy-lsp/issues/132))
  ([813a1cf](https://github.com/albertocavalcante/groovy-lsp/commit/813a1cf01d507683615e01213efc10f9d09e876d))
- **rename:** implement workspace-wide rename refactoring
  ([#109](https://github.com/albertocavalcante/groovy-lsp/issues/109))
  ([fa2d8ea](https://github.com/albertocavalcante/groovy-lsp/commit/fa2d8ea0eb8ff8dce25d13b2af38777f9fbe2dd2))
- surface bundled Jenkins step completions ([#147](https://github.com/albertocavalcante/groovy-lsp/issues/147))
  ([b45777f](https://github.com/albertocavalcante/groovy-lsp/commit/b45777f23113d6764dd259e0d14c2df998b61183))
- use JavaParser for Java source inspection with hermetic tests
  ([#180](https://github.com/albertocavalcante/groovy-lsp/issues/180))
  ([739ba29](https://github.com/albertocavalcante/groovy-lsp/commit/739ba29afd887b12fd91bcd5eb806375faf259be))

### Bug Fixes

- bundle groovy-macro to resolve AbstractMethodError
  ([#169](https://github.com/albertocavalcante/groovy-lsp/issues/169))
  ([16aeda5](https://github.com/albertocavalcante/groovy-lsp/commit/16aeda52662dfacc8ec7469557ad3503e52dded3))
- **deps:** update dependency ch.qos.logback:logback-classic to v1.5.22
  ([#154](https://github.com/albertocavalcante/groovy-lsp/issues/154))
  ([27e65ad](https://github.com/albertocavalcante/groovy-lsp/commit/27e65ad062ef25c0adedf3712e63f42a80c1bd09))
- **deps:** update dependency com.fasterxml.jackson:jackson-bom to v2.20.1
  ([#90](https://github.com/albertocavalcante/groovy-lsp/issues/90))
  ([d2b2019](https://github.com/albertocavalcante/groovy-lsp/commit/d2b20190e46d7e4f92c58f39daf12a198261e096))
- **deps:** update dependency io.mockk:mockk to v1.14.7
  ([#151](https://github.com/albertocavalcante/groovy-lsp/issues/151))
  ([010e314](https://github.com/albertocavalcante/groovy-lsp/commit/010e314458fb792be09febb439115de0b51189f3))
- **deps:** update dependency net.jqwik:jqwik to v1.9.3
  ([#94](https://github.com/albertocavalcante/groovy-lsp/issues/94))
  ([ecc8b9f](https://github.com/albertocavalcante/groovy-lsp/commit/ecc8b9feb6235b85a9822a1c1c8593e5dbe662b4))
- **deps:** update dependency org.codenarc:codenarc to v3.7.0-groovy-4.0
  ([#93](https://github.com/albertocavalcante/groovy-lsp/issues/93))
  ([647e0fb](https://github.com/albertocavalcante/groovy-lsp/commit/647e0fb34e4c04766079907dc425616d62c7a186))
- **deps:** update dependency org.jetbrains.kotlinx:kotlinx-serialization-json to v1.9.0
  ([#146](https://github.com/albertocavalcante/groovy-lsp/issues/146))
  ([863958d](https://github.com/albertocavalcante/groovy-lsp/commit/863958d28bf6cc88f66e426fee572b6b44cac455))
- **deps:** update junit-framework monorepo ([#58](https://github.com/albertocavalcante/groovy-lsp/issues/58))
  ([5900a0b](https://github.com/albertocavalcante/groovy-lsp/commit/5900a0b5be33cfa0c218bbcc9c5f493889bdd158))
- **deps:** update junit-framework monorepo to v6 ([#37](https://github.com/albertocavalcante/groovy-lsp/issues/37))
  ([d37ecb7](https://github.com/albertocavalcante/groovy-lsp/commit/d37ecb7a57f55604ed0672209bafa7b832dcab7e))
- **deps:** update mavenresolver to v1.9.25 ([#172](https://github.com/albertocavalcante/groovy-lsp/issues/172))
  ([7a1b343](https://github.com/albertocavalcante/groovy-lsp/commit/7a1b343eeecca966695a364d43c362fea047b9b8))
- ensure resource cleanup in CLI commands to prevent CI hangs
  ([#102](https://github.com/albertocavalcante/groovy-lsp/issues/102))
  ([de2138b](https://github.com/albertocavalcante/groovy-lsp/commit/de2138b265840fa7fe792b7af4b9834e7cfefb0b))
- isolate e2e gradle cache shutdown ([#126](https://github.com/albertocavalcante/groovy-lsp/issues/126))
  ([dca8ed9](https://github.com/albertocavalcante/groovy-lsp/commit/dca8ed929d466faa8a85d43db7e69cc09508045c))
- **release:** add timeout, default to self-hosted, and fix jar paths
  ([#106](https://github.com/albertocavalcante/groovy-lsp/issues/106))
  ([1bfc5e4](https://github.com/albertocavalcante/groovy-lsp/commit/1bfc5e4363c301dd4b7116d819eadc42f265d131))
- repair nightly workflow indentation ([#128](https://github.com/albertocavalcante/groovy-lsp/issues/128))
  ([a57308c](https://github.com/albertocavalcante/groovy-lsp/commit/a57308c9f545e982f01eef51dafb9566a5ef6762))
- resolve configuration cache violation in printVersion task
  ([#98](https://github.com/albertocavalcante/groovy-lsp/issues/98))
  ([e6c955e](https://github.com/albertocavalcante/groovy-lsp/commit/e6c955e7ff5e8cbce25cda7cedebc7e20cc40c58))
- stabilize gradle classpath and navigation ([#167](https://github.com/albertocavalcante/groovy-lsp/issues/167))
  ([7add06d](https://github.com/albertocavalcante/groovy-lsp/commit/7add06db9260e8cda8af6f7c88f8672c63388064))

### Performance Improvements

- optimize CI test execution and improve observability
  ([#101](https://github.com/albertocavalcante/groovy-lsp/issues/101))
  ([4d626d0](https://github.com/albertocavalcante/groovy-lsp/commit/4d626d02828832e77282f64604bc3867d9b038fe))
- **parser:** optimize recursive visitor allocation using inline functions
  ([#135](https://github.com/albertocavalcante/groovy-lsp/issues/135))
  ([4133bf8](https://github.com/albertocavalcante/groovy-lsp/commit/4133bf81bdabe3d64e6dc0fe21c91ef74069e386))

## [0.2.0](https://github.com/albertocavalcante/groovy-lsp/compare/v0.1.0...v0.2.0) (2025-11-23)

### ⚠ BREAKING CHANGES

- Real Groovy compilation with AST-based language features
  ([#3](https://github.com/albertocavalcante/groovy-lsp/issues/3))

### Features

- add build configuration for CodeNarc integration ([#40](https://github.com/albertocavalcante/groovy-lsp/issues/40))
  ([7d954ca](https://github.com/albertocavalcante/groovy-lsp/commit/7d954caf1e2f593e9cd9fe8e97fc638debdf5dc0))
- add core CodeNarc integration infrastructure ([#44](https://github.com/albertocavalcante/groovy-lsp/issues/44))
  ([0d9f1a8](https://github.com/albertocavalcante/groovy-lsp/commit/0d9f1a83f0db30cb3c9d99364da061a285354017))
- add e2e tests / fix workspace compilation and duplicate workspace diagnostics
  ([#60](https://github.com/albertocavalcante/groovy-lsp/issues/60))
  ([94ff474](https://github.com/albertocavalcante/groovy-lsp/commit/94ff4744c01e70350c15c06cd0d61b49bbcb2f8c))
- add gradle-pre-commit-git-hooks with auto-fix capabilities
  ([#30](https://github.com/albertocavalcante/groovy-lsp/issues/30))
  ([a0b40ff](https://github.com/albertocavalcante/groovy-lsp/commit/a0b40ff7fd3fe5b8ee409f51ead01cefb939cb19))
- add initial groovy parser module ([#64](https://github.com/albertocavalcante/groovy-lsp/issues/64))
  ([c6a7d3a](https://github.com/albertocavalcante/groovy-lsp/commit/c6a7d3a029c40c87d6eeea2c41f5af2a7069dcc6))
- add Kotlin LSP installer script for VS Code compatible editors
  ([#97](https://github.com/albertocavalcante/groovy-lsp/issues/97))
  ([d7270ff](https://github.com/albertocavalcante/groovy-lsp/commit/d7270ffd6ccb58fb4f84e6d7af9683d5925c2ba9))
- add lintFix task for auto-correcting code quality issues
  ([#27](https://github.com/albertocavalcante/groovy-lsp/issues/27))
  ([a3f51f8](https://github.com/albertocavalcante/groovy-lsp/commit/a3f51f8e17d7894a144e3ef8734cdf14755a4a01))
- add Makefile with development shortcuts ([#33](https://github.com/albertocavalcante/groovy-lsp/issues/33))
  ([0db7744](https://github.com/albertocavalcante/groovy-lsp/commit/0db77442873d7cbc58ed18c88d4319d4e50c5c4b))
- add OpenRewrite formatter and telemetry instrumentation
  ([#56](https://github.com/albertocavalcante/groovy-lsp/issues/56))
  ([a3a11a4](https://github.com/albertocavalcante/groovy-lsp/commit/a3a11a4eb05319a29ea7d5fd855756cc0c6b72b4))
- add RHEL-based devcontainer ([#85](https://github.com/albertocavalcante/groovy-lsp/issues/85))
  ([7d26276](https://github.com/albertocavalcante/groovy-lsp/commit/7d26276dd92dce864a243031edb68832d981df31))
- add signature help support ([#62](https://github.com/albertocavalcante/groovy-lsp/issues/62))
  ([8af682c](https://github.com/albertocavalcante/groovy-lsp/commit/8af682c1f6ddf604113102e455b99823fa98103b))
- add type definition support for Groovy LSP ([#28](https://github.com/albertocavalcante/groovy-lsp/issues/28))
  ([cefe0c8](https://github.com/albertocavalcante/groovy-lsp/commit/cefe0c8f864e5beda1a6f805fb516b5e3f4c2a42))
- advertise formatting and strengthen lsp coverage ([#59](https://github.com/albertocavalcante/groovy-lsp/issues/59))
  ([a5b0f37](https://github.com/albertocavalcante/groovy-lsp/commit/a5b0f37327376c05857394b41170fdee3da6078a))
- **ci:** enable build scans and improve yaml formatting
  ([#55](https://github.com/albertocavalcante/groovy-lsp/issues/55))
  ([d35b7e1](https://github.com/albertocavalcante/groovy-lsp/commit/d35b7e1ae6910e7c66e00d50381a07d1b34beed8))
- **devcontainer:** enhance devcontainer with Starship, GH CLI, and modern tools
  ([#86](https://github.com/albertocavalcante/groovy-lsp/issues/86))
  ([52521a9](https://github.com/albertocavalcante/groovy-lsp/commit/52521a99cc2ff8d70f30679bfbb3ce81db823d95))
- extract ast/resolution package from original PR [#35](https://github.com/albertocavalcante/groovy-lsp/issues/35)
  ([#48](https://github.com/albertocavalcante/groovy-lsp/issues/48))
  ([926d22b](https://github.com/albertocavalcante/groovy-lsp/commit/926d22b26fbb9afbd1f77fc6f26fcc45260aa750))
- implement centralized coordinate system for LSP/Groovy conversion
  ([#43](https://github.com/albertocavalcante/groovy-lsp/issues/43))
  ([0d2164f](https://github.com/albertocavalcante/groovy-lsp/commit/0d2164f8e483cb1c17a7ee4e3f249b2821700d71))
- implement gopls-inspired CLI commands ([#96](https://github.com/albertocavalcante/groovy-lsp/issues/96))
  ([5fd6689](https://github.com/albertocavalcante/groovy-lsp/commit/5fd66898a4f17d6b258f33652aee6a225569ad64))
- Improve build and test process ([#53](https://github.com/albertocavalcante/groovy-lsp/issues/53))
  ([21b7d86](https://github.com/albertocavalcante/groovy-lsp/commit/21b7d865cc8348eb7f0c86e0eaec836266cdb733))
- initial Groovy LSP implementation
  ([2cb1190](https://github.com/albertocavalcante/groovy-lsp/commit/2cb1190bd2345becf27add226241b34b18eeda34))
- integrate SonarCloud coverage reporting in CI workflow
  ([#29](https://github.com/albertocavalcante/groovy-lsp/issues/29))
  ([7e42933](https://github.com/albertocavalcante/groovy-lsp/commit/7e429330c72675541e2fde6aeb4c82494d8d3a99))
- non-blocking Gradle dependency resolution ([#19](https://github.com/albertocavalcante/groovy-lsp/issues/19))
  ([0107e1d](https://github.com/albertocavalcante/groovy-lsp/commit/0107e1d93e85adb04bd13584acd01132516dfbac))
- provider architecture with comprehensive AST utilities and LSP features
  ([#5](https://github.com/albertocavalcante/groovy-lsp/issues/5))
  ([916c6e1](https://github.com/albertocavalcante/groovy-lsp/commit/916c6e1ef7f994478643d97260dc7b943cc6d1d3))
- Real Groovy compilation with AST-based language features
  ([#3](https://github.com/albertocavalcante/groovy-lsp/issues/3))
  ([5d03312](https://github.com/albertocavalcante/groovy-lsp/commit/5d033129dea9fd2950906ccbb33f89868fcbaff0))
- **tools:** add interactive issue wizard ([#82](https://github.com/albertocavalcante/groovy-lsp/issues/82))
  ([61e08b7](https://github.com/albertocavalcante/groovy-lsp/commit/61e08b73ce87d01cf1befc850ff783515cf23fda))

### Bug Fixes

- **deps:** update dependency ch.qos.logback:logback-classic to v1.5.18
  ([#7](https://github.com/albertocavalcante/groovy-lsp/issues/7))
  ([cd9201a](https://github.com/albertocavalcante/groovy-lsp/commit/cd9201ae427b8ea844b50169cf4fc29ac9487669))
- **deps:** update dependency ch.qos.logback:logback-classic to v1.5.21
  ([#38](https://github.com/albertocavalcante/groovy-lsp/issues/38))
  ([0d7a95b](https://github.com/albertocavalcante/groovy-lsp/commit/0d7a95ba8f436525a48e9ef585738d76dad3dd9b))
- **deps:** update dependency com.jayway.jsonpath:json-path to v2.10.0
  ([#91](https://github.com/albertocavalcante/groovy-lsp/issues/91))
  ([16a6e3c](https://github.com/albertocavalcante/groovy-lsp/commit/16a6e3c287e0774e5a80f7744ae5c1e019c125e4))
- **deps:** update dependency io.mockk:mockk to v1.14.6
  ([#41](https://github.com/albertocavalcante/groovy-lsp/issues/41))
  ([1e8266a](https://github.com/albertocavalcante/groovy-lsp/commit/1e8266afbb90b88b30e31dd582c671bb8ea4f6ab))
- **deps:** update dependency org.assertj:assertj-core to v3.27.6
  ([#42](https://github.com/albertocavalcante/groovy-lsp/issues/42))
  ([23d1025](https://github.com/albertocavalcante/groovy-lsp/commit/23d10255ba72c23f664b984bec9f04b55e7a918c))
- **deps:** update dependency org.codenarc:codenarc to v3.6.0
  ([#45](https://github.com/albertocavalcante/groovy-lsp/issues/45))
  ([0879ecf](https://github.com/albertocavalcante/groovy-lsp/commit/0879ecfbcabc86f1ebc44fb9095b3bfaa0257650))
- **deps:** update dependency org.gradle:gradle-tooling-api to v9.2.1
  ([#76](https://github.com/albertocavalcante/groovy-lsp/issues/76))
  ([cf4d78b](https://github.com/albertocavalcante/groovy-lsp/commit/cf4d78b0e5f39fdfb558dd84293e420e81e75c41))
- **deps:** update dependency org.junit.jupiter:junit-jupiter to v5.13.4
  ([#16](https://github.com/albertocavalcante/groovy-lsp/issues/16))
  ([d06e4af](https://github.com/albertocavalcante/groovy-lsp/commit/d06e4af48b1b405998a71060c9828d8d9f17e73c))
- **deps:** update dependency org.junit.jupiter:junit-jupiter to v5.14.0
  ([#36](https://github.com/albertocavalcante/groovy-lsp/issues/36))
  ([8a1b8ad](https://github.com/albertocavalcante/groovy-lsp/commit/8a1b8addd312ab7a1a9f58ba67c16a1e5a7a0138))
- **deps:** update dependency org.openrewrite:rewrite-groovy to v8.66.1
  ([#57](https://github.com/albertocavalcante/groovy-lsp/issues/57))
  ([e3185e5](https://github.com/albertocavalcante/groovy-lsp/commit/e3185e50effad073cd687e6860925b2bde96f3c9))
- **deps:** update dependency org.openrewrite:rewrite-groovy to v8.67.0
  ([#77](https://github.com/albertocavalcante/groovy-lsp/issues/77))
  ([728a3a4](https://github.com/albertocavalcante/groovy-lsp/commit/728a3a4f2bbd83759db1c5deb23c6d6ade9b21ff))
- **deps:** update dependency org.slf4j:slf4j-api to v2.0.17
  ([#12](https://github.com/albertocavalcante/groovy-lsp/issues/12))
  ([2dff2cf](https://github.com/albertocavalcante/groovy-lsp/commit/2dff2cf14a1eb1364e2f8ebcbd9738c2b53e9a1f))
- **deps:** update groovy monorepo to v4.0.29 ([#52](https://github.com/albertocavalcante/groovy-lsp/issues/52))
  ([afab171](https://github.com/albertocavalcante/groovy-lsp/commit/afab171f0480f01ede014548a534f52ef776fd7e))
- implement CodeNarc diagnostic triplication fix ([#46](https://github.com/albertocavalcante/groovy-lsp/issues/46))
  ([9db68e6](https://github.com/albertocavalcante/groovy-lsp/commit/9db68e611a80f19f75fe41b3743bbc9e10723537))
- resolve critical detekt issues and failing hover test
  ([#14](https://github.com/albertocavalcante/groovy-lsp/issues/14))
  ([998343c](https://github.com/albertocavalcante/groovy-lsp/commit/998343cd08bc22c227ea7dc8301470fce938fa98))
- resolve detekt code quality warnings ([#26](https://github.com/albertocavalcante/groovy-lsp/issues/26))
  ([d91d452](https://github.com/albertocavalcante/groovy-lsp/commit/d91d45281f46796bc6b9a27fee2e5eef373ecb6a))
