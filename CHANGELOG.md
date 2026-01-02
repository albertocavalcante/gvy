# Changelog

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
