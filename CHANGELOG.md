# Changelog

## [0.5.5](https://github.com/albertocavalcante/gvy/compare/v0.5.4...v0.5.5) (2026-11-14)


### Features

* **build:** add strict network policy foundations ([1e879b7](https://github.com/albertocavalcante/gvy/commit/1e879b7e81ebce0b9f3c9b4a59d25d7555c25e0b))
* **build:** carry target-scoped dependency evidence ([#1216](https://github.com/albertocavalcante/gvy/issues/1216)) ([c3637d9](https://github.com/albertocavalcante/gvy/commit/c3637d910d558b4b38252e39a3d6eba084b9c207))
* **build:** produce scoped Gradle source-set dependency snapshots ([4705df7](https://github.com/albertocavalcante/gvy/commit/4705df73450324c67f7408f02509f43e76309db4))
* **codeaction:** keep Spock table alignment while a base spec is broken ([58299b0](https://github.com/albertocavalcante/gvy/commit/58299b0fcb940b79a0098c2e7d377760769e57c4))
* **codenarc:** honour declarative workspace rulesets safely ([5fc89e1](https://github.com/albertocavalcante/gvy/commit/5fc89e1986c0a474a1f6aeb52d42c487b7b72a5e))
* **compilation:** route documents to their target classpath ([9765a59](https://github.com/albertocavalcante/gvy/commit/9765a59d6338f10994357b11734658c4a0763875))
* **completion:** activate Spock completion from the source model ([a6f0f20](https://github.com/albertocavalcante/gvy/commit/a6f0f202325b0dc6508ab1316d047bb2e381af6c))
* **definition:** navigate unroll headers through the selected pattern ([edea562](https://github.com/albertocavalcante/gvy/commit/edea562120c8cedf8af0fd9a2bb7fd5c587f0e9a))
* **e2e:** add stable Linux child ownership primitives ([#1723](https://github.com/albertocavalcante/gvy/issues/1723)) ([a891c28](https://github.com/albertocavalcante/gvy/commit/a891c287e7ff42278f96a8673a1a77dc05feeae2))
* **e2e:** assess observed server runtime ([#1911](https://github.com/albertocavalcante/gvy/issues/1911)) ([b7e6657](https://github.com/albertocavalcante/gvy/commit/b7e6657a923f2fb24c73b1ae1cb48b3088540bac))
* **e2e:** capture bounded original transport bytes ([#1819](https://github.com/albertocavalcante/gvy/issues/1819)) ([40217aa](https://github.com/albertocavalcante/gvy/commit/40217aaa2467f8cd3559ef3ec32cb29aab91e22e))
* **e2e:** execute named checks with sealed outcomes ([#1783](https://github.com/albertocavalcante/gvy/issues/1783)) ([a8a845d](https://github.com/albertocavalcante/gvy/commit/a8a845d0aec1cb9cd637e640d6017ebb046c1051))
* **e2e:** model named observation outcomes ([#1770](https://github.com/albertocavalcante/gvy/issues/1770)) ([b9ecf14](https://github.com/albertocavalcante/gvy/commit/b9ecf14ea4c96039d22dc36a7eea1708de22927d))
* **e2e:** report named outcomes after full execution ([#1795](https://github.com/albertocavalcante/gvy/issues/1795)) ([c8085ce](https://github.com/albertocavalcante/gvy/commit/c8085cec3fdec3fad14edd677b6a698d1d805768))
* **e2e:** track named high-level request checks ([#1799](https://github.com/albertocavalcante/gvy/issues/1799)) ([55c0b45](https://github.com/albertocavalcante/gvy/commit/55c0b4519975c4b36ed56f96d3d271712e1ba4c8))
* **fmt:** refuse formatting edits that change the token stream ([#1581](https://github.com/albertocavalcante/gvy/issues/1581)) ([5b7b007](https://github.com/albertocavalcante/gvy/commit/5b7b007a8f50a0409ffd12318f0b9107eccf04c2))
* **gradle:** observe per-source-set classpaths behind a setting ([a8c322d](https://github.com/albertocavalcante/gvy/commit/a8c322d3b6c3f2bcdffb2e59a36047930bc2e49c))
* **gradle:** report compiler source membership ([#2027](https://github.com/albertocavalcante/gvy/issues/2027)) ([43a091d](https://github.com/albertocavalcante/gvy/commit/43a091dc32ddabac275e7b9a76cc60f681d19ea4))
* **hover:** retire Core hover engine and alias core to native ([81fc6d5](https://github.com/albertocavalcante/gvy/commit/81fc6d521fcf839a153ec5f5d2fe748be9296cc0))
* **index:** detect retired declaration views ([#1810](https://github.com/albertocavalcante/gvy/issues/1810)) ([e9f72f9](https://github.com/albertocavalcante/gvy/commit/e9f72f9d19b4d541d0965772dcabc1be075aa7e9))
* **install:** make main CI installs deterministic ([#1999](https://github.com/albertocavalcante/gvy/issues/1999)) ([7542cd1](https://github.com/albertocavalcante/gvy/commit/7542cd1e63542ae7868aeace9524a20652a14104))
* **jenkins:** complete triggers, parameters and tools entries ([357a268](https://github.com/albertocavalcante/gvy/commit/357a268bed1f04295aefb272c62afa90d0b12aa8))
* **jenkins:** hover for declarative options and sections ([569d99e](https://github.com/albertocavalcante/gvy/commit/569d99e6a71b70fa8c292dddbd5c93d723c3e72a))
* **jenkins:** hover for step parameters and global variables ([aa013c2](https://github.com/albertocavalcante/gvy/commit/aa013c23e21b287648a534a0178506e7efd0111b))
* **jenkins:** offer workspace GDSL steps in completion and hover ([8716fc9](https://github.com/albertocavalcante/gvy/commit/8716fc9e5af8f56d7ff24b2a3b859c6dca9ea093))
* **launcher:** add Kotlin/Native CLI preview ([#1769](https://github.com/albertocavalcante/gvy/issues/1769)) ([80ae0a6](https://github.com/albertocavalcante/gvy/commit/80ae0a651e6726c0d5017441ae170e4e5494e31c))
* **launcher:** support Windows and agent CLI workflows ([#1825](https://github.com/albertocavalcante/gvy/issues/1825)) ([3ed215c](https://github.com/albertocavalcante/gvy/commit/3ed215cf13bf9da73c362084cc0f17e67c9b1678))
* **logging:** add bounded safe metadata helpers ([#1792](https://github.com/albertocavalcante/gvy/issues/1792)) ([641f112](https://github.com/albertocavalcante/gvy/commit/641f1128322115218dff888d2c07326436dec7a4))
* **lsp:** add source-consistent smart selection ([#1177](https://github.com/albertocavalcante/gvy/issues/1177)) ([cf201aa](https://github.com/albertocavalcante/gvy/commit/cf201aa6e1530fd3853e065826cb678cbd358e9a))
* **lsp:** complete source-coherent folding ranges ([#1182](https://github.com/albertocavalcante/gvy/issues/1182)) ([d1c1ec4](https://github.com/albertocavalcante/gvy/commit/d1c1ec4d26e33e2e52dbe513dadec099618ccd94))
* **lsp:** expose bounded server runtime facts ([#1857](https://github.com/albertocavalcante/gvy/issues/1857)) ([85cd9ce](https://github.com/albertocavalcante/gvy/commit/85cd9ce52001079c7944ebb3c27774f108a7b65a))
* **lsp:** expose terminal diagnostics requests ([#1754](https://github.com/albertocavalcante/gvy/issues/1754)) ([07553cf](https://github.com/albertocavalcante/gvy/commit/07553cf9bb4750177bd779dfdacd6c5260899e4d))
* make silent compile degradation visible ([#1143](https://github.com/albertocavalcante/gvy/issues/1143)) ([0fbc8d3](https://github.com/albertocavalcante/gvy/commit/0fbc8d3a0f5908f8c66273383d3e0e0661d45ecd))
* **network:** add guarded HTTP transport ([#2030](https://github.com/albertocavalcante/gvy/issues/2030)) ([ad7cc4c](https://github.com/albertocavalcante/gvy/commit/ad7cc4c9e13fb7faedb8fdf91a633321ca25f853))
* **parser:** stub missing classes so Groovy resolves everything else ([#1146](https://github.com/albertocavalcante/gvy/issues/1146)) ([0dfae21](https://github.com/albertocavalcante/gvy/commit/0dfae21bd88cef207720d1d05ca6e02a0c2667da))
* **perf:** add bounded profiling prerequisite checks ([386223e](https://github.com/albertocavalcante/gvy/commit/386223ef18b4e6de5a422ec242eafde04d54b8a5))
* **perf:** add bounded structural run evidence ([#1730](https://github.com/albertocavalcante/gvy/issues/1730)) ([4efc1eb](https://github.com/albertocavalcante/gvy/commit/4efc1eb4114c8ebc76d6357ce893f3891ed906eb))
* **rename:** prepare validated rename targets ([#1173](https://github.com/albertocavalcante/gvy/issues/1173)) ([ced6907](https://github.com/albertocavalcante/gvy/commit/ced6907a342d25737aa66bce78f58c8283b6faa6))
* **semantics:** attribute names inside closures passed to delegate-neutral GDK calls ([#1152](https://github.com/albertocavalcante/gvy/issues/1152)) ([b73e1d6](https://github.com/albertocavalcante/gvy/commit/b73e1d631bfddfb948f2db5994de0030e83b126a))
* **semantics:** capture immutable declaration views ([#1755](https://github.com/albertocavalcante/gvy/issues/1755)) ([62a011f](https://github.com/albertocavalcante/gvy/commit/62a011f12cdcd9beceef74c63a475814c84e429f))
* **semantics:** lease classpath solver generations ([#1768](https://github.com/albertocavalcante/gvy/issues/1768)) ([223b664](https://github.com/albertocavalcante/gvy/commit/223b6642c585b642dd3e4d35bafcc250bfbf3cc9))
* **semantics:** record the names the index could not attribute ([c583c66](https://github.com/albertocavalcante/gvy/commit/c583c66f78bd163ad14b1629a5006c782cb266d3))
* **sources:** add verified source artifacts and type indexes ([#1934](https://github.com/albertocavalcante/gvy/issues/1934)) ([e95e872](https://github.com/albertocavalcante/gvy/commit/e95e87273fb60334e6ca7524c32ddf7b1f96490c))
* **spock:** classify specifications from AST source context ([376dcbd](https://github.com/albertocavalcante/gvy/commit/376dcbd4507d0d2deca00eb0cd5864c6439d1b2c))
* **spock:** parse unroll names and map placeholder positions ([212167a](https://github.com/albertocavalcante/gvy/commit/212167a897fba9b6d695ca4a73face63de204a03))
* **spock:** resolve data bindings and align tables ([#1204](https://github.com/albertocavalcante/gvy/issues/1204)) ([01b605d](https://github.com/albertocavalcante/gvy/commit/01b605d35ffe5bb760164e6593129e347fea2acd))
* **spock:** trust compiled cross-file spec hierarchies ([e3364fc](https://github.com/albertocavalcante/gvy/commit/e3364fcddda095b9aed6fc13993a7e73b8fddf00))
* **test-client:** apply encoded simultaneous text edits ([#1803](https://github.com/albertocavalcante/gvy/issues/1803)) ([628407f](https://github.com/albertocavalcante/gvy/commit/628407fe2e7c866cf0d7d7ce673b186de0cbba63))
* **test-client:** prepare validated buffer edit plans ([#1806](https://github.com/albertocavalcante/gvy/issues/1806)) ([f1be5e1](https://github.com/albertocavalcante/gvy/commit/f1be5e177bbbff3e6c6948099ed0b5e55755eaf4))
* **test:** add bounded scenario reduction ([c38961f](https://github.com/albertocavalcante/gvy/commit/c38961faab31a3f0f335685fe93260540f09d352))
* **test:** verify owned packaged server launches ([26578e4](https://github.com/albertocavalcante/gvy/commit/26578e4d48678feaa3bc8f03dcfac57793b8f70b))
* **verification:** inspect original protocol evidence ([#1837](https://github.com/albertocavalcante/gvy/issues/1837)) ([68446d6](https://github.com/albertocavalcante/gvy/commit/68446d6e6b3e9cd144ed859fbe6e9c26a9fe3f11))
* **vscode:** recognize GDSL and add authoring snippets ([#2001](https://github.com/albertocavalcante/gvy/issues/2001)) ([f101e5b](https://github.com/albertocavalcante/gvy/commit/f101e5bf5a5a2fae42d0cfcadba838bcfc6e3d14))
* **zed:** add a grammar-free local JAR adapter ([#1816](https://github.com/albertocavalcante/gvy/issues/1816)) ([b0d5a9a](https://github.com/albertocavalcante/gvy/commit/b0d5a9aeaad9b4748d0b1469641f088be21bd79c))
* **zed:** add static Groovy delimiter pairs ([#1841](https://github.com/albertocavalcante/gvy/issues/1841)) ([efc8b2c](https://github.com/albertocavalcante/gvy/commit/efc8b2c1d68c763d3a5e5aa68898b484e2e6acd1))


### Bug Fixes

* **archive:** retain licensed corpus in source tarballs ([#1900](https://github.com/albertocavalcante/gvy/issues/1900)) ([e1fce13](https://github.com/albertocavalcante/gvy/commit/e1fce13ddd4ab477472f44bf8fbf84082d6c9bfd))
* **ast:** bind inspection to document snapshots ([#2033](https://github.com/albertocavalcante/gvy/issues/2033)) ([e85acc3](https://github.com/albertocavalcante/gvy/commit/e85acc3fac8352b6c6eadf6805597626ef77ae58))
* **bazel:** declare parser/native where its helpers are used ([13ee127](https://github.com/albertocavalcante/gvy/commit/13ee1274251033be4749a5307ae1b2f475c92315))
* **bazel:** let kernel-core tests see the internal API ([81add64](https://github.com/albertocavalcante/gvy/commit/81add64b9b38efbdfa4cb2edfc28b9cfb2aa0e9c))
* **bazel:** restore green Bazel tests on main ([77204a1](https://github.com/albertocavalcante/gvy/commit/77204a1f0b34a545d4bfe6442058baec389ce33b))
* **bsp:** reject unimplemented Maven operations ([#1855](https://github.com/albertocavalcante/gvy/issues/1855)) ([741d466](https://github.com/albertocavalcante/gvy/commit/741d466707e1c7526e961a27c20f5c25a422b706))
* **bsp:** retain failures during workspace discovery ([#1862](https://github.com/albertocavalcante/gvy/issues/1862)) ([8ef8649](https://github.com/albertocavalcante/gvy/commit/8ef8649095847affacad0fe6a76fe7c1b1790e08))
* **build-tool:** stamp class directories by their contents ([efd4418](https://github.com/albertocavalcante/gvy/commit/efd44182f4b81eb0d242dac96c671a5e3ec79e45))
* **build:** format source packages without generated output ([#1176](https://github.com/albertocavalcante/gvy/issues/1176)) ([cd06426](https://github.com/albertocavalcante/gvy/commit/cd064262a3450d46be8279671567fbe25682d2b9))
* **build:** make the root lintFix gate pass with module baselines ([40ecb70](https://github.com/albertocavalcante/gvy/commit/40ecb7007969ea30b430c8361c9fdd6b75017964))
* **build:** own watcher sessions and test Java 17 ([#1168](https://github.com/albertocavalcante/gvy/issues/1168)) ([ba6fe93](https://github.com/albertocavalcante/gvy/commit/ba6fe93ddecbd6749524c01eda3f60cd0e4702ac))
* **build:** preserve Logback package versions in jars ([#1659](https://github.com/albertocavalcante/gvy/issues/1659)) ([6d6e0f5](https://github.com/albertocavalcante/gvy/commit/6d6e0f52304a4af18ab2ebcca17ed482328aad16))
* **build:** run licensing before parallel task actions ([#1947](https://github.com/albertocavalcante/gvy/issues/1947)) ([a5e310e](https://github.com/albertocavalcante/gvy/commit/a5e310e5f4cb61e747aa9cb771065dedfe492f01))
* **cancellation:** end cancelled source and indexing requests cleanly ([8dcd0fc](https://github.com/albertocavalcante/gvy/commit/8dcd0fca4f4c9fb6e8467a9e7f6b9631cdc9a0cc))
* **cancellation:** rethrow cancellation at request boundaries ([9ce47f8](https://github.com/albertocavalcante/gvy/commit/9ce47f80e6b8bf5e7ec1c05f9afb61215e54af8b))
* **ci:** attribute lazy Kover warnings ([#1887](https://github.com/albertocavalcante/gvy/issues/1887)) ([2fb106a](https://github.com/albertocavalcante/gvy/commit/2fb106a37c410eb94f390a490119ee94a6f08251))
* **ci:** close runner compatibility and diagnostic gaps ([2714b92](https://github.com/albertocavalcante/gvy/commit/2714b9273401711f428a1ef23e4cc46804721366))
* **ci:** reject inconsistent or noisy REUSE success ([50aa561](https://github.com/albertocavalcante/gvy/commit/50aa56159de62b80af242ecd2e79755e11e8f6bc))
* **ci:** retain bounded runner notice evidence ([8aeedf3](https://github.com/albertocavalcante/gvy/commit/8aeedf3fc90d47cdf8d83646f442ac64401fcf92))
* **classpath:** close replaced class loaders safely ([d14da0a](https://github.com/albertocavalcante/gvy/commit/d14da0a52d35603181f97d666a7c1aa2baddb2e1))
* **codeaction:** key remembered base verdicts on the base's own identity ([5ee921d](https://github.com/albertocavalcante/gvy/commit/5ee921df6c592e740e6b32d1c4d887f66650bec1))
* **codenarc:** choose the lint profile per file ([5fc84f9](https://github.com/albertocavalcante/gvy/commit/5fc84f9d2aae590e42b7715959f1116c9872c1b0))
* **compilation:** bind pending jobs to source snapshots ([#1194](https://github.com/albertocavalcante/gvy/issues/1194)) ([a3b76f1](https://github.com/albertocavalcante/gvy/commit/a3b76f18710ab4d21620c914294c441f6c538dd1))
* **compilation:** keep fence state only for files with work in flight ([253950d](https://github.com/albertocavalcante/gvy/commit/253950d190a1b7fd6b93a701d2184aaeefcccb30))
* **compilation:** keep symbol and semantic facts on source-list change ([b589b8c](https://github.com/albertocavalcante/gvy/commit/b589b8c2c7068558a25f56074cb7687409e7726f))
* **compilation:** keep the newest buffer version on out-of-order commits ([8c50ffe](https://github.com/albertocavalcante/gvy/commit/8c50ffefb8eece746d5a3229afc3a51188a051e6))
* **compilation:** publish the workspace configuration as one immutable model ([f9b97b1](https://github.com/albertocavalcante/gvy/commit/f9b97b13464bc838cc591991c3a21d4b7924516a))
* **compilation:** re-stamp classpath entries between observations ([c78115c](https://github.com/albertocavalcante/gvy/commit/c78115c4b333575748bf881c29aa6cb3e7d5d931))
* **compilation:** reject writes from invalidated compile generations ([4b3ac20](https://github.com/albertocavalcante/gvy/commit/4b3ac20a7b26f646daff52766439fca356be88a9))
* **compilation:** resolve workspace classes in on-demand compiles ([f3fec17](https://github.com/albertocavalcante/gvy/commit/f3fec1722619fdee87413e1f078b16106f7b5d0e))
* **compilation:** skip the fingerprint lookup in watcher retirements ([7705c4e](https://github.com/albertocavalcante/gvy/commit/7705c4ec278be422540f45ca8225fe706b367ed7))
* **compilation:** stop owner cancellations from exhausting waiters ([775aaad](https://github.com/albertocavalcante/gvy/commit/775aaad087c406b6981ee57d9befb65183beb3d0))
* **compilation:** wake waiters when the service retires their job ([a421d04](https://github.com/albertocavalcante/gvy/commit/a421d0415ec5f13995a3ac40a1c60dc17e59413d))
* **completion:** complete inherited members across indexed ancestors ([eaeb856](https://github.com/albertocavalcante/gvy/commit/eaeb856ce34f7f020a5ca821e456b3abfcf463c2))
* **completion:** fall back to detail without labelDetails support ([7157e4a](https://github.com/albertocavalcante/gvy/commit/7157e4a05c2f3792c061b7050d659910a7d8bb59))
* **completion:** never offer the placeholder identifier ([bf3d44e](https://github.com/albertocavalcante/gvy/commit/bf3d44e82ded28c540a6522e11f12a4d727d0ae0))
* **completion:** resolve local symbols on the first request ([f6fcf51](https://github.com/albertocavalcante/gvy/commit/f6fcf510eb7c5652aa4e49fc0471362bd08f31cb))
* **config:** honor effective editor settings ([#1181](https://github.com/albertocavalcante/gvy/issues/1181)) ([7d61981](https://github.com/albertocavalcante/gvy/commit/7d61981f2d2c8ebbec1b0636774794babca739be))
* **coverage:** preserve Boolean coverage without fake counts ([#1779](https://github.com/albertocavalcante/gvy/issues/1779)) ([e75e7b9](https://github.com/albertocavalcante/gvy/commit/e75e7b978420606224913c4fcc991799d0ada6cd))
* **definition:** prefer the exact class name in global resolution ([4eacd2b](https://github.com/albertocavalcante/gvy/commit/4eacd2bdbfa8d1fcfbe223bb3ae6d651d744a4fa))
* **definition:** resolve dotted nested class names ([694b1b2](https://github.com/albertocavalcante/gvy/commit/694b1b2c4a152ff51c68e2ab0de929b4180c9a97))
* **definition:** stop reporting a stack overflow as a missing symbol ([2d00f9f](https://github.com/albertocavalcante/gvy/commit/2d00f9f03517bd55406b79ca74adc854f405567e))
* **deps:** update dependency com.github.javaparser:javaparser-core to v3.28.2 ([#1127](https://github.com/albertocavalcante/gvy/issues/1127)) ([c563ac7](https://github.com/albertocavalcante/gvy/commit/c563ac7cde5c663218d89eff120c8a67848c8f4c))
* **deps:** update dependency io.github.classgraph:classgraph to v4.8.199 ([#1128](https://github.com/albertocavalcante/gvy/issues/1128)) ([4b28832](https://github.com/albertocavalcante/gvy/commit/4b28832df3116eea27d74c1a6f75eea474e7a08b))
* **deps:** update dependency io.github.classgraph:classgraph to v4.8.200 ([#1267](https://github.com/albertocavalcante/gvy/issues/1267)) ([7978203](https://github.com/albertocavalcante/gvy/commit/79782031562d62dd9fcc11859e3ea1f80f2dbf29))
* **deps:** update dependency io.mockk:mockk to v1.14.11 ([#1133](https://github.com/albertocavalcante/gvy/issues/1133)) ([da33e7c](https://github.com/albertocavalcante/gvy/commit/da33e7c0c09eaac120fc6012ceb40d9090761b2c))
* **deps:** update dependency org.assertj:assertj-core to v3.27.7 ([#1134](https://github.com/albertocavalcante/gvy/issues/1134)) ([7609fe3](https://github.com/albertocavalcante/gvy/commit/7609fe3e996ce0f01b6a117ac3abd29475921c90))
* **deps:** update dependency org.slf4j:slf4j-api to v2.0.20 ([#1135](https://github.com/albertocavalcante/gvy/issues/1135)) ([ca118dd](https://github.com/albertocavalcante/gvy/commit/ca118dd06209f392a6b598c1d637c8634bce18dc))
* **deps:** update groovy monorepo to v4.0.33 ([#1136](https://github.com/albertocavalcante/gvy/issues/1136)) ([896c044](https://github.com/albertocavalcante/gvy/commit/896c0442301ac497527c8c8136d612b47e471d26))
* **deps:** update mavenresolver to v1.9.27 ([#1137](https://github.com/albertocavalcante/gvy/issues/1137)) ([8119e32](https://github.com/albertocavalcante/gvy/commit/8119e32dd49f9f39dfa66df321198a9a146cc10c))
* **deps:** update spock-core to v2.5-groovy-4.0 ([9dc9221](https://github.com/albertocavalcante/gvy/commit/9dc922156a3454f3e8218607d4719d6b76e77088))
* **deps:** update wire to v5.5.1 ([#1138](https://github.com/albertocavalcante/gvy/issues/1138)) ([f706b66](https://github.com/albertocavalcante/gvy/commit/f706b66850b2d7557017f55652acc4dffdd0b640))
* **dev:** build verified source extensions ([#1195](https://github.com/albertocavalcante/gvy/issues/1195)) ([4ed7bf6](https://github.com/albertocavalcante/gvy/commit/4ed7bf6502ac189d60a5a9dc0afb3f9bc133ad71))
* **diagnostics:** anchor CodeNarc ranges in original source ([d6f5ca6](https://github.com/albertocavalcante/gvy/commit/d6f5ca666daa40786114ba92636a5770ced4dc6c))
* **diagnostics:** capture provider Jenkins mode ([#1699](https://github.com/albertocavalcante/gvy/issues/1699)) ([08a513f](https://github.com/albertocavalcante/gvy/commit/08a513fe345c0f19c079899598ac1a970ae38119))
* **diagnostics:** correct CodeNarc final-newline positions ([#1747](https://github.com/albertocavalcante/gvy/issues/1747)) ([8a44f2e](https://github.com/albertocavalcante/gvy/commit/8a44f2ea3ca1ec036de92adb9581afef84007186))
* **diagnostics:** expose conversion and ruleset faults ([#1681](https://github.com/albertocavalcante/gvy/issues/1681)) ([66ab255](https://github.com/albertocavalcante/gvy/commit/66ab255048c30bbaf1993906a0cdeef1dcb46aaa))
* **diagnostics:** publish only current document results ([#1193](https://github.com/albertocavalcante/gvy/issues/1193)) ([49c57cb](https://github.com/albertocavalcante/gvy/commit/49c57cb4399303cb75a0ff9312466a7d0e039ec2))
* **diagnostics:** report custom rule failure outcomes ([#1682](https://github.com/albertocavalcante/gvy/issues/1682)) ([591981f](https://github.com/albertocavalcante/gvy/commit/591981fb130faea3f08e8c568f6894d0e9563681))
* **diagnostics:** require Jenkins evidence before the CPS-only profile ([368f748](https://github.com/albertocavalcante/gvy/commit/368f74825b7acf268cd8c3fc9c40445aa047624f))
* **diagnostics:** retain CodeNarc analysis failures ([#1678](https://github.com/albertocavalcante/gvy/issues/1678)) ([5e532e0](https://github.com/albertocavalcante/gvy/commit/5e532e079be2192c432095c3c5e917484905093d))
* **diagnostics:** retain compiled provider context ([#1693](https://github.com/albertocavalcante/gvy/issues/1693)) ([8ce6c11](https://github.com/albertocavalcante/gvy/commit/8ce6c115a240e730fce948ee2411efdde57bd7a3))
* **diagnostics:** retain current analysis outcomes ([#1671](https://github.com/albertocavalcante/gvy/issues/1671)) ([c05fa4a](https://github.com/albertocavalcante/gvy/commit/c05fa4a56ca6854847d864550100c1cb23d78f75))
* **diagnostics:** retain imports for nested types ([#2024](https://github.com/albertocavalcante/gvy/issues/2024)) ([68abe40](https://github.com/albertocavalcante/gvy/commit/68abe40668bc027f2c64a79914f58ad1a6e4460f))
* **diagnostics:** retain provider collection outcomes ([#1662](https://github.com/albertocavalcante/gvy/issues/1662)) ([3b0adc2](https://github.com/albertocavalcante/gvy/commit/3b0adc2f61d9f7948c8e6d4ba6e1632c3b9a29f6))
* **display:** keep binary names for generated classes ([34e3dd2](https://github.com/albertocavalcante/gvy/commit/34e3dd21bda28a504c081d80f1d2dcae07d23ba3))
* **display:** render array and nested types in source form ([c091886](https://github.com/albertocavalcante/gvy/commit/c0918860930cc5476286911a097401a1765a5c09))
* **display:** show varargs for classpath methods ([96e14a3](https://github.com/albertocavalcante/gvy/commit/96e14a325f49d9cff0fda14102c9ac19f280bba4))
* **display:** show varargs parameters as Type... ([f3b94dd](https://github.com/albertocavalcante/gvy/commit/f3b94dde3eaf1e7caec3a0a75f2c9413406e5bcf))
* **docs:** validate browser journeys and accessible layouts ([#2025](https://github.com/albertocavalcante/gvy/issues/2025)) ([ab91272](https://github.com/albertocavalcante/gvy/commit/ab91272bde8c6d6e2d81ff85a6bc05ee3b5cc22e))
* **e2e:** advance automatic document versions ([#1663](https://github.com/albertocavalcante/gvy/issues/1663)) ([630162a](https://github.com/albertocavalcante/gvy/commit/630162abfb561878aabf125f759ef7186f93b60e))
* **e2e:** bound CLI output and confirm capture cleanup ([#1685](https://github.com/albertocavalcante/gvy/issues/1685)) ([c1e8de2](https://github.com/albertocavalcante/gvy/commit/c1e8de2dfe18ceb885e56d7e898a3b38e557c789))
* **e2e:** bound retained failure evidence ([#1807](https://github.com/albertocavalcante/gvy/issues/1807)) ([fcc0786](https://github.com/albertocavalcante/gvy/commit/fcc0786873285a8acb6fcd28e55b64fa4f743fc4))
* **e2e:** enforce scenario assertion schema ([3c7eddb](https://github.com/albertocavalcante/gvy/commit/3c7eddbc0979adb9a66f06d33260d8175a102a88))
* **e2e:** enforce scenario deadlines and CLI cleanup ([#1673](https://github.com/albertocavalcante/gvy/issues/1673)) ([2128b0b](https://github.com/albertocavalcante/gvy/commit/2128b0b605a40532ed82778f6a62c30b9e7a224d))
* **e2e:** guard golden updates and artifact paths ([#1645](https://github.com/albertocavalcante/gvy/issues/1645)) ([74eaa03](https://github.com/albertocavalcante/gvy/commit/74eaa030130a90e4b79261a6a41a7faad7f988b6))
* **e2e:** own session resources and verify server exit ([#1649](https://github.com/albertocavalcante/gvy/issues/1649)) ([0bc083c](https://github.com/albertocavalcante/gvy/commit/0bc083c33192251a64f847041e27cb54504d74c6))
* **e2e:** preserve arrivals and bound notification scans ([#1652](https://github.com/albertocavalcante/gvy/issues/1652)) ([b896edf](https://github.com/albertocavalcante/gvy/commit/b896edf35a2480dbc1ae5007e71c42482efd43ad))
* **e2e:** preserve cleanup failures in xfail results ([#1733](https://github.com/albertocavalcante/gvy/issues/1733)) ([5837981](https://github.com/albertocavalcante/gvy/commit/5837981403707ff5c54ccf8a18a34de2564a3e54))
* **e2e:** preserve custom request results ([#1914](https://github.com/albertocavalcante/gvy/issues/1914)) ([2a762e9](https://github.com/albertocavalcante/gvy/commit/2a762e982c227ee806f3a96cf3d8216bfdee0b4b))
* **e2e:** preserve exact numbers in rendered JSON ([#1790](https://github.com/albertocavalcante/gvy/issues/1790)) ([95e53ab](https://github.com/albertocavalcante/gvy/commit/95e53ab642e3def9055494b6ee2d16011a4727cb))
* **e2e:** preserve golden bytes and whitespace ([#1622](https://github.com/albertocavalcante/gvy/issues/1622)) ([2c3f995](https://github.com/albertocavalcante/gvy/commit/2c3f995c3c1a3afcd14b06b22a19c779e09328fa))
* **e2e:** preserve JSON before SDK union projections ([#1777](https://github.com/albertocavalcante/gvy/issues/1777)) ([4dcfed0](https://github.com/albertocavalcante/gvy/commit/4dcfed0032fb980dee5bb6459554a46553688493))
* **e2e:** preserve null and Kotlin DSL contracts ([#1641](https://github.com/albertocavalcante/gvy/issues/1641)) ([04e21be](https://github.com/albertocavalcante/gvy/commit/04e21be19466cac28be26458b0b3f9f4b0657d89))
* **e2e:** preserve raw JSON protocol payloads ([#1696](https://github.com/albertocavalcante/gvy/issues/1696)) ([6f17c8f](https://github.com/albertocavalcante/gvy/commit/6f17c8f1a0dbc6e2ae162cfeed13bd5fd4aeecd0))
* **e2e:** preserve scenario numeric precision ([#1703](https://github.com/albertocavalcante/gvy/issues/1703)) ([7945e8d](https://github.com/albertocavalcante/gvy/commit/7945e8d347f6b4fb0732a6ab5770859fd15e70bc))
* **e2e:** propagate JSON serialization failures ([b34e250](https://github.com/albertocavalcante/gvy/commit/b34e250462d793aedf2ae407199d91da4e5d99ef))
* **e2e:** propagate notification predicate failures ([#1766](https://github.com/albertocavalcante/gvy/issues/1766)) ([fa059f3](https://github.com/albertocavalcante/gvy/commit/fa059f32d65baec51a7dc2b991de2d0a45d8dcc4))
* **e2e:** reject invalid assertion checks before execution ([#1756](https://github.com/albertocavalcante/gvy/issues/1756)) ([97e6188](https://github.com/albertocavalcante/gvy/commit/97e618888fa5b2f1f9c88628471458262524b43b))
* **e2e:** reject unreadable fixture documents ([#1744](https://github.com/albertocavalcante/gvy/issues/1744)) ([38939c4](https://github.com/albertocavalcante/gvy/commit/38939c4b9c38f237956332767f6ae314128b3578))
* **e2e:** repair repro assertion contracts ([#1656](https://github.com/albertocavalcante/gvy/issues/1656)) ([6cca395](https://github.com/albertocavalcante/gvy/commit/6cca395588f26a4564913f94929f63ab4091e171))
* **e2e:** restore server failure logs after the package rename ([4ae4e38](https://github.com/albertocavalcante/gvy/commit/4ae4e38f0a7e32f470bd508abbbed053aed8ada9))
* **e2e:** wait for owned listener threads to exit ([#1661](https://github.com/albertocavalcante/gvy/issues/1661)) ([4472767](https://github.com/albertocavalcante/gvy/commit/447276739bd06e3060fe4a7703d4bf7fa2a83f93))
* **editor:** enable implementation with the current server ([#1159](https://github.com/albertocavalcante/gvy/issues/1159)) ([50df9aa](https://github.com/albertocavalcante/gvy/commit/50df9aa231e465c812d3eeae4c126173af4e0fd9))
* end hierarchy and parent walks on cycles ([5cd12b8](https://github.com/albertocavalcante/gvy/commit/5cd12b87f7d8623bb13b448d733b4fa3b3a74e96))
* **extension:** publish only verified JAR snapshots ([#1890](https://github.com/albertocavalcante/gvy/issues/1890)) ([4f0033c](https://github.com/albertocavalcante/gvy/commit/4f0033c6462696d43dcb648fc4a9d6cbf6b1f8df))
* **fmt:** consume released cast fix with safety checks ([#1658](https://github.com/albertocavalcante/gvy/issues/1658)) ([702b876](https://github.com/albertocavalcante/gvy/commit/702b876ceb42c2a1e7d2444b4ebf90e239129e47))
* **fmt:** keep whitespace inside strings and comments ([#1593](https://github.com/albertocavalcante/gvy/issues/1593)) ([0be66bc](https://github.com/albertocavalcante/gvy/commit/0be66bca4710e4468073f78f7bd4cc01930fda4e))
* **fmt:** preserve formatter pipeline failure outcomes ([#1737](https://github.com/albertocavalcante/gvy/issues/1737)) ([84a9cc1](https://github.com/albertocavalcante/gvy/commit/84a9cc1446f9753d514eabe4ef595de1b2855028))
* **format:** parse transformed logging expressions ([#2026](https://github.com/albertocavalcante/gvy/issues/2026)) ([82ed11e](https://github.com/albertocavalcante/gvy/commit/82ed11e7fcc4f9be21e33827e62ed03241463cc1))
* **formatting:** preserve cancellation and fatal failures ([#1688](https://github.com/albertocavalcante/gvy/issues/1688)) ([c793f55](https://github.com/albertocavalcante/gvy/commit/c793f555ad3499ae077e7d34801a89859933a823))
* **gls:** bind socket mode to loopback by default ([5098124](https://github.com/albertocavalcante/gvy/commit/50981247a6c770ec2c01e42fba529234d3141f79))
* **gls:** complete Jenkins command expressions after string arguments ([12522c6](https://github.com/albertocavalcante/gvy/commit/12522c6d8bbbbe5e152703875dec58540dbf50e5))
* **gls:** index every workspace file semantically, and keep the index current ([#1155](https://github.com/albertocavalcante/gvy/issues/1155)) ([ba88cbf](https://github.com/albertocavalcante/gvy/commit/ba88cbfbfa62faf038775cda408811295c98e4d2))
* **gls:** keep SymbolIndexingService under the detekt function limit ([af26a68](https://github.com/albertocavalcante/gvy/commit/af26a685fce0bdd7b7b30e476a64368bdcbe8d76))
* **gls:** keep the symbol index in step with file content ([#1153](https://github.com/albertocavalcante/gvy/issues/1153)) ([362d85f](https://github.com/albertocavalcante/gvy/commit/362d85f11c482a1dfb6017bf38a88d79987ba44e))
* **gradle:** ask an unsupporting server for tasks only once ([#1264](https://github.com/albertocavalcante/gvy/issues/1264)) ([50ad521](https://github.com/albertocavalcante/gvy/commit/50ad521d013a4ee3bb0f9da8e608cb4d45b03a92))
* **gradle:** make dependency resolution cancellable ([c8493f9](https://github.com/albertocavalcante/gvy/commit/c8493f9346dbd03bb75ca47d410413b683e3d3e3))
* **gradle:** refresh on build file deletion ([d44f61b](https://github.com/albertocavalcante/gvy/commit/d44f61b1942ba597d18047bc110876ba57f29b89))
* **gradle:** resolve each project's classpath under its own lock ([6a8e59c](https://github.com/albertocavalcante/gvy/commit/6a8e59c66aeb95e3f696b0099d581978a0593c31))
* **gradle:** retain model leases until acknowledgement ([#2018](https://github.com/albertocavalcante/gvy/issues/2018)) ([a8a0db6](https://github.com/albertocavalcante/gvy/commit/a8a0db62eee2780c90ae9bbd900be375a88fdd20))
* **gradle:** retry transient distribution download failures ([d8aa2b5](https://github.com/albertocavalcante/gvy/commit/d8aa2b56bfaa6bc09922fe8941d46157b56b4036))
* **hover:** use compiled types and precise symbol cards ([#1775](https://github.com/albertocavalcante/gvy/issues/1775)) ([a7cce22](https://github.com/albertocavalcante/gvy/commit/a7cce224e4477863584aa92a8f5eee651504b1b7))
* **hover:** use the accepted document snapshot ([#1179](https://github.com/albertocavalcante/gvy/issues/1179)) ([500e1aa](https://github.com/albertocavalcante/gvy/commit/500e1aabcc7f7c368210d72bc81801a577487e82))
* **imports:** preserve source annotations through AST transforms ([#1933](https://github.com/albertocavalcante/gvy/issues/1933)) ([00af40f](https://github.com/albertocavalcante/gvy/commit/00af40f4a28f219b06f1756b189e1cc23ab062e1))
* **index:** skip hidden and build directories when exporting ([#1286](https://github.com/albertocavalcante/gvy/issues/1286)) ([6be2e19](https://github.com/albertocavalcante/gvy/commit/6be2e198d3492735c45a8d6b5a5dfd4b74661cd8))
* **inlay:** prefer the exact owner class for parameter hints ([2177fcf](https://github.com/albertocavalcante/gvy/commit/2177fcf4b4fb91518d070234e06e00f1ea989fa6))
* **inlay:** qualify local receivers from their constructor initializer ([f5ad6a6](https://github.com/albertocavalcante/gvy/commit/f5ad6a61ae3fa17c1fbdf67efd2f32e2a7e592da))
* **install:** verify cached state and preserve failure evidence ([171dd2d](https://github.com/albertocavalcante/gvy/commit/171dd2d574024aae5c11bc5be6d06f4771a37b99))
* **jenkins:** check stage structure only in declarative pipelines ([#1210](https://github.com/albertocavalcante/gvy/issues/1210)) ([98a3681](https://github.com/albertocavalcante/gvy/commit/98a36816b526fa364a8b77f04f89bb880fa9b336))
* **jenkins:** complete directive entry keys inside their directive ([ccbd98d](https://github.com/albertocavalcante/gvy/commit/ccbd98d1394be94c43d75e4a962095880ac23ec3))
* **jenkins:** correct nesting and allowedIn in the declarative schema ([ac447d7](https://github.com/albertocavalcante/gvy/commit/ac447d7308cb7ccb9a26c11ea807f98200b47e9c))
* **jenkins:** do not flag the @Library underscore as unused ([#1287](https://github.com/albertocavalcante/gvy/issues/1287)) ([03087f2](https://github.com/albertocavalcante/gvy/commit/03087f20d7b165a89e63259b9d255a265efdfc22))
* **jenkins:** generate stubs outside the user workspace ([#1203](https://github.com/albertocavalcante/gvy/issues/1203)) ([be58939](https://github.com/albertocavalcante/gvy/commit/be5893970505c49c0b2709a2313a187b6f76e7bc))
* **jenkins:** give each classpath its own immutable stub set ([83ce60e](https://github.com/albertocavalcante/gvy/commit/83ce60e7dfeab5acb167c318b008f211480ac00f))
* **jenkins:** ignore multi-line strings and comments in block detection ([e22a673](https://github.com/albertocavalcante/gvy/commit/e22a673e103127b0c52fe1efb7828d1aecdd61c6))
* **jenkins:** isolate source declarations from legacy jars ([33a2376](https://github.com/albertocavalcante/gvy/commit/33a23764d5089ecd0b8dd67390c3f539cdbcfd68))
* **jenkins:** keep steps and globals out of member completion ([1c0f1d6](https://github.com/albertocavalcante/gvy/commit/1c0f1d6385bd43eb16cc3de28bd91b61c035a6e1))
* **jenkins:** keep the block stack aligned with every brace ([6fc600f](https://github.com/albertocavalcante/gvy/commit/6fc600fc84799c1e8d1172d7abe50d4eea8a2e6d))
* **jenkins:** offer exact declarative completions per block ([53d3150](https://github.com/albertocavalcante/gvy/commit/53d3150c94aa44bcdca4a561ea42f3e99cb208d9))
* **jenkins:** recognise every editor Jenkinsfile pattern ([#1202](https://github.com/albertocavalcante/gvy/issues/1202)) ([aafec2f](https://github.com/albertocavalcante/gvy/commit/aafec2fb76f4ce2ffa262f731fda123f49bf26f6))
* **jenkins:** resolve @Library annotations in Jenkinsfiles ([5ad1e6b](https://github.com/albertocavalcante/gvy/commit/5ad1e6bf9ad84f60ed7c12ce9057ca4831585051))
* **jenkins:** scope classpath-scanned steps to their Jenkinsfile ([822cfb7](https://github.com/albertocavalcante/gvy/commit/822cfb76c15baf9e644f6cec5a142273dca00afe))
* **jenkins:** scope vars/ definition lookup to global calls ([#1209](https://github.com/albertocavalcante/gvy/issues/1209)) ([3d220ad](https://github.com/albertocavalcante/gvy/commit/3d220adb34d7b99895de43b8b631106a70361527))
* **jenkins:** stop offering step keys inside block bodies ([fb29a44](https://github.com/albertocavalcante/gvy/commit/fb29a44b41dbdf8d9b4761a9586dbf6b309ec68c))
* **jenkins:** stop stubs from positioning shared class nodes ([c59fdca](https://github.com/albertocavalcante/gvy/commit/c59fdcaa9fbf52344419d1c1fd51384d9f6a0bf4))
* **jenkins:** track declarative sub-blocks so completion offers them ([6c6f023](https://github.com/albertocavalcante/gvy/commit/6c6f0238047441d3ef7f6f0d8dd98172892a383a))
* **jenkins:** treat map-value closures as scripted in hover ([7e71dbc](https://github.com/albertocavalcante/gvy/commit/7e71dbc593b47033744c0830d179ab4b6ce5e1eb))
* **jupyter:** verify HMAC signatures on incoming kernel messages ([6609917](https://github.com/albertocavalcante/gvy/commit/6609917ab930ca8dd00f3569d6718b8765506c5d))
* keep server log levels accurate in VS Code and fmt ([0f09c10](https://github.com/albertocavalcante/gvy/commit/0f09c10b6ac6c4003b5b94b1f81c5f4589d8cc60))
* **license:** preserve exact benchmark fixture bytes ([#1871](https://github.com/albertocavalcante/gvy/issues/1871)) ([f5acb26](https://github.com/albertocavalcante/gvy/commit/f5acb2645d81bf146ef575f249164c6f0053d5e1))
* **license:** retain licenses in distributed artifacts ([#1824](https://github.com/albertocavalcante/gvy/issues/1824)) ([75d0d5f](https://github.com/albertocavalcante/gvy/commit/75d0d5f430c3332da142f33a3bccf5ba54d374ae))
* **logging:** bound installer diagnostic output ([#1898](https://github.com/albertocavalcante/gvy/issues/1898)) ([e064dd4](https://github.com/albertocavalcante/gvy/commit/e064dd400a9008efb6d19454bba47a7eb905242e))
* **logging:** bound server stderr rendering and routing ([#1926](https://github.com/albertocavalcante/gvy/issues/1926)) ([78dcf94](https://github.com/albertocavalcante/gvy/commit/78dcf942382405e58d90f4728920ff31dbf96890))
* **logging:** quiet routine analysis events ([#1758](https://github.com/albertocavalcante/gvy/issues/1758)) ([0820a29](https://github.com/albertocavalcante/gvy/commit/0820a295e39ee0a926bda85919e90cc571d9c0ab))
* **lsp:** align semantic tokens with captured source ([#1189](https://github.com/albertocavalcante/gvy/issues/1189)) ([3841aa9](https://github.com/albertocavalcante/gvy/commit/3841aa9b6ec75dc2b46237f730e84115ec7ba8fa))
* **lsp:** apply live settings and preserve Windows URIs ([#1156](https://github.com/albertocavalcante/gvy/issues/1156)) ([a77d27c](https://github.com/albertocavalcante/gvy/commit/a77d27cabab4e75d9405d68fcf4b9eb1897450f3))
* **lsp:** bind rename edits to their document snapshot ([#1164](https://github.com/albertocavalcante/gvy/issues/1164)) ([d63bdcf](https://github.com/albertocavalcante/gvy/commit/d63bdcff51142e350bd8a263dadf73b17bc5ecc4))
* **lsp:** fence implementation snapshot acquisition ([#1162](https://github.com/albertocavalcante/gvy/issues/1162)) ([301201b](https://github.com/albertocavalcante/gvy/commit/301201b6c8a0ba10e5b88352ec3cad1ac9468687))
* **lsp:** preserve exact references without AST caches ([#1157](https://github.com/albertocavalcante/gvy/issues/1157)) ([e7535bb](https://github.com/albertocavalcante/gvy/commit/e7535bb5e6d0f33a975f76d7ef8cfcc2249ee83d))
* **lsp:** preserve GDSL startup and first symbols ([#2006](https://github.com/albertocavalcante/gvy/issues/2006)) ([8e7ef09](https://github.com/albertocavalcante/gvy/commit/8e7ef090b5d9f3ad24f94dae249bcee9e5fe6c73))
* **lsp:** preserve scoped references and hierarchy ranges ([#1169](https://github.com/albertocavalcante/gvy/issues/1169)) ([ee2aaab](https://github.com/albertocavalcante/gvy/commit/ee2aaaba4494272616e54b779c0d3be3f5559892))
* **lsp:** preserve workspace implementation navigation ([#1160](https://github.com/albertocavalcante/gvy/issues/1160)) ([3a51524](https://github.com/albertocavalcante/gvy/commit/3a515245bf0fbfe1a79aeb9041bf1b5bb9fb2b2e))
* **lsp:** reject unknown workspace commands instead of returning null ([8f3fca5](https://github.com/albertocavalcante/gvy/commit/8f3fca5652a9bc33de409069656fa3b2a7a50a16))
* **lsp:** resolve generic implementation signatures ([#1161](https://github.com/albertocavalcante/gvy/issues/1161)) ([9d8996c](https://github.com/albertocavalcante/gvy/commit/9d8996c7a0ed8426cc2e26d0828ac63521759e07))
* **lsp:** resolve semantic parameter token spans ([#1163](https://github.com/albertocavalcante/gvy/issues/1163)) ([f7072d2](https://github.com/albertocavalcante/gvy/commit/f7072d2e60e546e7ca47b01ba4c56018a81de918))
* **lsp:** retain client versions and reject stale changes ([#1158](https://github.com/albertocavalcante/gvy/issues/1158)) ([fa6d967](https://github.com/albertocavalcante/gvy/commit/fa6d967581a39746c56a5a1b3bff7e57d2b0459d))
* **lsp:** serialize status messages with kotlinx ([#1724](https://github.com/albertocavalcante/gvy/issues/1724)) ([3441376](https://github.com/albertocavalcante/gvy/commit/344137626bd0514faa087aba62c3c3374f56bb81))
* **lsp:** treat Groovy end columns as exclusive ([5a9d5b6](https://github.com/albertocavalcante/gvy/commit/5a9d5b6d0ab25cecb6f895f2ce9f1fdce602d0c6))
* **navigation:** wait for compilation readiness, not lint jobs ([df85105](https://github.com/albertocavalcante/gvy/commit/df85105a4883cf5002427aeea0daca1d5b384009))
* **parser:** clarify incomplete syntax diagnostics ([#2005](https://github.com/albertocavalcante/gvy/issues/2005)) ([2c7db34](https://github.com/albertocavalcante/gvy/commit/2c7db3414c481689c63859b287ac105183047a44))
* **parser:** clarify unfinished import diagnostics ([#2002](https://github.com/albertocavalcante/gvy/issues/2002)) ([fcfb6e5](https://github.com/albertocavalcante/gvy/commit/fcfb6e5c2444ff283c489487a5626f5e84c6d4be))
* **parser:** count import columns in code points ([a02a46a](https://github.com/albertocavalcante/gvy/commit/a02a46a14c3c93af25096ec64b60a2621385490c))
* **parser:** disable @Grab during analysis compiles ([6b42b05](https://github.com/albertocavalcante/gvy/commit/6b42b05cc77a087b634c4cf9ea2b6eafc5ab3b29))
* **parser:** include the missing class in core linkage problems ([3592b30](https://github.com/albertocavalcante/gvy/commit/3592b3050f4b221539d7f58012d56a48f7ad90dc))
* **parser:** isolate source-owned compiler failures ([#2031](https://github.com/albertocavalcante/gvy/issues/2031)) ([c81e48f](https://github.com/albertocavalcante/gvy/commit/c81e48f2afd31d3ae5421db5ea8442212c5a2269))
* **parser:** keep a broken workspace file from costing every file its types ([#1140](https://github.com/albertocavalcante/gvy/issues/1140)) ([b5b1149](https://github.com/albertocavalcante/gvy/commit/b5b1149b0b86b18202e6dc156510a172ad186d51))
* **parser:** keep debug logging observational ([#1749](https://github.com/albertocavalcante/gvy/issues/1749)) ([c3615f5](https://github.com/albertocavalcante/gvy/commit/c3615f54e015f42b74a6ac8970ce0e947f43d18a))
* **parser:** keep shared default class nodes out of position lookups ([777fa51](https://github.com/albertocavalcante/gvy/commit/777fa51a3a692b4c31748480d04c3d5ea0384739))
* **parser:** keep token and hover offsets in UTF-16 ([#1894](https://github.com/albertocavalcante/gvy/issues/1894)) ([fe0da80](https://github.com/albertocavalcante/gvy/commit/fe0da802deecf1a38253755191dfb523f96355e6))
* **parser:** make core ranges end-exclusive and clamp hover ranges ([6ecf861](https://github.com/albertocavalcante/gvy/commit/6ecf8619d9c2edcdd66a4ed26e38a1bc675b6dd2))
* **parser:** preserve compiler failure causes ([#2015](https://github.com/albertocavalcante/gvy/issues/2015)) ([9cf9d68](https://github.com/albertocavalcante/gvy/commit/9cf9d68cc73de8fb2bd3367e750d93c1af936d54))
* **parser:** preserve intentional script semantics ([#1174](https://github.com/albertocavalcante/gvy/issues/1174)) ([34244fe](https://github.com/albertocavalcante/gvy/commit/34244fe6cf0135f16f22931c9ffb8e00e5ca4114))
* **parser:** report failed compiles with no collected errors ([800d1de](https://github.com/albertocavalcante/gvy/commit/800d1dea83ecdb570fd0ff055bc7c38d64d04862))
* **parser:** report jar-only local transforms as information ([98f0c60](https://github.com/albertocavalcante/gvy/commit/98f0c60a5ddd675e31fb5fe831eee1947fef7ac0))
* **parser:** report public type lookup accurately ([#1178](https://github.com/albertocavalcante/gvy/issues/1178)) ([12e36a0](https://github.com/albertocavalcante/gvy/commit/12e36a091ac6aa8f036b122e1f5441f143c4adc9))
* **parser:** resolve public core source nodes ([#1191](https://github.com/albertocavalcante/gvy/issues/1191)) ([09ab03c](https://github.com/albertocavalcante/gvy/commit/09ab03cdf4e04668d4f78fe8f8d087826c481460))
* **parser:** resolve the identifier ending at the cursor in core nodeAt ([68f5a02](https://github.com/albertocavalcante/gvy/commit/68f5a021f93f7c4e9307a8ee65abcf0d7f45663a))
* **parser:** show compiler message text in diagnostics ([0d7adf7](https://github.com/albertocavalcante/gvy/commit/0d7adf7342bf26cab1af8c220a70b8df9c4193aa))
* **parser:** stop analysis compiles from running @ASTTest code ([19e5d10](https://github.com/albertocavalcante/gvy/commit/19e5d10e5906e97e6cd94928771bfe671f5e72e4))
* **parser:** stop inheritance cycles from hanging hierarchy walks ([a59621c](https://github.com/albertocavalcante/gvy/commit/a59621ca24ccf9c1d3d1b7ecb48a6a557021c992))
* **protocol:** deliver structured failure status ([#1220](https://github.com/albertocavalcante/gvy/issues/1220)) ([6f36553](https://github.com/albertocavalcante/gvy/commit/6f36553aa401822d14f28906f509486212a7e56a))
* **pr:** restore editing and validate helper behavior ([#1866](https://github.com/albertocavalcante/gvy/issues/1866)) ([fe95577](https://github.com/albertocavalcante/gvy/commit/fe955770d8897e809e45cd916fb2e8c870686aa7))
* **pr:** restore editing and validate helper behavior ([#1866](https://github.com/albertocavalcante/gvy/issues/1866)) ([fe95577](https://github.com/albertocavalcante/gvy/commit/fe955770d8897e809e45cd916fb2e8c870686aa7))
* **references:** preserve imported type aliases ([#1172](https://github.com/albertocavalcante/gvy/issues/1172)) ([2620dca](https://github.com/albertocavalcante/gvy/commit/2620dca1b437a598808672ee76cebdd171b94aa4))
* **release:** package matching server and editor artifacts ([#1760](https://github.com/albertocavalcante/gvy/issues/1760)) ([dd3a3a2](https://github.com/albertocavalcante/gvy/commit/dd3a3a20a28a1854c879071092ba241e41343508))
* **release:** restore unified version state ([bb640a2](https://github.com/albertocavalcante/gvy/commit/bb640a2193668612846e8ba427be76ea99c59761))
* **rename:** preserve semantic parameter bindings ([#1166](https://github.com/albertocavalcante/gvy/issues/1166)) ([69b9bea](https://github.com/albertocavalcante/gvy/commit/69b9bead2cfb2fbfd98a35d5db74280d233ade6d))
* **rename:** refuse renames that would break other files ([#1258](https://github.com/albertocavalcante/gvy/issues/1258)) ([401e895](https://github.com/albertocavalcante/gvy/commit/401e89530f1d1757cfe99d2189fa165c15e18263))
* **rename:** refuse when another workspace file may use the target ([89dfbba](https://github.com/albertocavalcante/gvy/commit/89dfbbabf71d4e36a48af653cd034124c0520f52))
* **rename:** stop false refusals without hiding real cross-file uses ([f0307dd](https://github.com/albertocavalcante/gvy/commit/f0307ddbf37d2dc1757aa6704453ced85ca17ecf))
* **sarif:** declare UTF-16 column units ([#1784](https://github.com/albertocavalcante/gvy/issues/1784)) ([ed8df71](https://github.com/albertocavalcante/gvy/commit/ed8df7123ed95bdc3a43f3ccca5f0e171cd87585))
* **security:** apply the analysis policy to library parsing ([d77482b](https://github.com/albertocavalcante/gvy/commit/d77482beee9cf12398055fef0e8f988c8718c8d6))
* **security:** read GDSL as data instead of executing it ([77ba736](https://github.com/albertocavalcante/gvy/commit/77ba7361ea2c5e7454a042dd8ac73c764d520694))
* **security:** stop evaluating workspace CodeNarc rulesets ([a285687](https://github.com/albertocavalcante/gvy/commit/a28568701d64657c68eae240a46af65beb7d4327))
* **semantics:** give every symbol a distinct id and attribute only certain references ([#1130](https://github.com/albertocavalcante/gvy/issues/1130)) ([b63b783](https://github.com/albertocavalcante/gvy/commit/b63b7834369b9324f5b03b2264e3aabc7f36dfcb))
* **semantics:** infer return types of workspace methods ([#1582](https://github.com/albertocavalcante/gvy/issues/1582)) ([6cc3c11](https://github.com/albertocavalcante/gvy/commit/6cc3c118c73497765510728c077d406531937a7f))
* **semantics:** make SemanticDB ranges half-open LSP ranges ([#1154](https://github.com/albertocavalcante/gvy/issues/1154)) ([f46f371](https://github.com/albertocavalcante/gvy/commit/f46f371e6f7c97890090b8888b0ada40858e0838))
* **semantics:** preserve lexical declaration identity ([#1742](https://github.com/albertocavalcante/gvy/issues/1742)) ([6a12845](https://github.com/albertocavalcante/gvy/commit/6a12845227c8294793af7c7f3e94df345ca6afdd))
* **semantics:** release retired semantic contexts ([#1726](https://github.com/albertocavalcante/gvy/issues/1726)) ([8f696d0](https://github.com/albertocavalcante/gvy/commit/8f696d0db8e7a522fc901730c78f7dcef710c3b5))
* **shell:** preserve maintenance script failures ([#1840](https://github.com/albertocavalcante/gvy/issues/1840)) ([ed9a4ae](https://github.com/albertocavalcante/gvy/commit/ed9a4ae3e4881f477603e288986be143fd2cc4d3))
* **spock:** close source-model review follow-ups ([e1bd081](https://github.com/albertocavalcante/gvy/commit/e1bd0812f4243f476c0071cfc6a003fb3e2a08d3))
* **spock:** decide definition targets from the source model ([abf3332](https://github.com/albertocavalcante/gvy/commit/abf3332d1bf49605a82a00a7ffefdaf57ce60edf))
* **spock:** decide test discovery from the source model ([ed4cb60](https://github.com/albertocavalcante/gvy/commit/ed4cb604953df2ed4c7c88a8790368a6ba63d6fb))
* **spock:** keep source edits to confident specs and record fallbacks ([bfbdf4c](https://github.com/albertocavalcante/gvy/commit/bfbdf4c4c700a8a1ba9c40676d5eee3b5ba5c088))
* **spock:** retire unproven structural reports ([#1215](https://github.com/albertocavalcante/gvy/issues/1215)) ([18115cd](https://github.com/albertocavalcante/gvy/commit/18115cdb1377e64c61524edd5df3540cd48bb4b9))
* **test-client:** reject unapplied workspace edits ([#1813](https://github.com/albertocavalcante/gvy/issues/1813)) ([baa3a19](https://github.com/albertocavalcante/gvy/commit/baa3a19aee6a4fad663fe196e0492966aae01843))
* **viz:** guard Groovy ranges and isolate converter IDs ([#1684](https://github.com/albertocavalcante/gvy/issues/1684)) ([1fb2511](https://github.com/albertocavalcante/gvy/commit/1fb25113c80bf59c97ec4bdf9242dce95827e09f))
* **viz:** move desktop parsing and I/O off the UI thread ([#1697](https://github.com/albertocavalcante/gvy/issues/1697)) ([b751eda](https://github.com/albertocavalcante/gvy/commit/b751eda6ab8ed4f0607814f563591153e329c86c))
* **viz:** offer visualizer area in issue wizard ([#1680](https://github.com/albertocavalcante/gvy/issues/1680)) ([370747b](https://github.com/albertocavalcante/gvy/commit/370747b9e363fe528e452c7968a1f6d19269d68c))
* **viz:** preserve caret during AST navigation ([#1692](https://github.com/albertocavalcante/gvy/issues/1692)) ([d102952](https://github.com/albertocavalcante/gvy/commit/d102952cf76916580b11118a7d24d26c9de5183b))
* **viz:** preserve installed apps during replacement ([#1698](https://github.com/albertocavalcante/gvy/issues/1698)) ([ce511d1](https://github.com/albertocavalcante/gvy/commit/ce511d1b9ef107983618eb26ad8bc74002a71419))
* **viz:** provide verified Rewrite source ranges ([#1683](https://github.com/albertocavalcante/gvy/issues/1683)) ([58e9424](https://github.com/albertocavalcante/gvy/commit/58e94245468a9feefb67eb620ee5789ea0a39966))
* **viz:** retain converter metadata ([#1672](https://github.com/albertocavalcante/gvy/issues/1672)) ([a665644](https://github.com/albertocavalcante/gvy/commit/a6656446a7b978fa73c698397a8eceeee3c2967d))
* **viz:** virtualize AST rows and reveal selections ([#1679](https://github.com/albertocavalcante/gvy/issues/1679)) ([3ae2c18](https://github.com/albertocavalcante/gvy/commit/3ae2c18cbb283067fad0d90b19b7c2018b8401b3))
* **vscode:** bound server diagnostic lines ([#1753](https://github.com/albertocavalcante/gvy/issues/1753)) ([2cf7114](https://github.com/albertocavalcante/gvy/commit/2cf71142638198975d3248f5d8ea33ac63c0fda9))
* **vscode:** bound test output and report lost results ([#1764](https://github.com/albertocavalcante/gvy/issues/1764)) ([d8cbe51](https://github.com/albertocavalcante/gvy/commit/d8cbe51c14dc0a6f3b85aab0f94f7d7e2a835acb))
* **vscode:** compare complete semantic versions accurately ([4969ad1](https://github.com/albertocavalcante/gvy/commit/4969ad1b9af4632eccbc0101e28d4c81ac97b34b))
* **vscode:** create specs exclusively and release AST fixtures ([2e21606](https://github.com/albertocavalcante/gvy/commit/2e2160690cbcd904fbd368af8632f9defffc5084))
* **vscode:** enforce AI tool policy on every call ([#1788](https://github.com/albertocavalcante/gvy/issues/1788)) ([c314f06](https://github.com/albertocavalcante/gvy/commit/c314f069d378e26c6603c3eb33c13c777fd9c721))
* **vscode:** keep AI tool settings out of workspace control ([c1bceb8](https://github.com/albertocavalcante/gvy/commit/c1bceb8df39d08d019c48f8238fd5df5a6b085ca))
* **vscode:** keep code-running settings out of workspace control ([bbf6f4f](https://github.com/albertocavalcante/gvy/commit/bbf6f4fd8c4d32fe5d5d8f7c421ffc50299a252a))
* **vscode:** preserve server log severity in the output channel ([#1268](https://github.com/albertocavalcante/gvy/issues/1268)) ([851511f](https://github.com/albertocavalcante/gvy/commit/851511f59b151ba0695bc5c159f11c063186c858))
* **vscode:** render server status text literally ([#1780](https://github.com/albertocavalcante/gvy/issues/1780)) ([d8fb281](https://github.com/albertocavalcante/gvy/commit/d8fb2810282e1e5d42f150ab1001aa253b437e9e))
* **vscode:** share current dependency-tree reads ([#1796](https://github.com/albertocavalcante/gvy/issues/1796)) ([aad1039](https://github.com/albertocavalcante/gvy/commit/aad1039867e3e131c0afc5df135127ec70ed14fc))
* **vscode:** stop reporting success for unsupported server calls ([#1214](https://github.com/albertocavalcante/gvy/issues/1214)) ([39c4961](https://github.com/albertocavalcante/gvy/commit/39c49610fb7576da248758af2d5be48df79ef29a))
* **vscode:** validate test targets and coverage intent ([#1797](https://github.com/albertocavalcante/gvy/issues/1797)) ([e09392e](https://github.com/albertocavalcante/gvy/commit/e09392e53fd8bc9eee5d71e110dfcbb6c79534aa))
* **workspace:** act on the listed spelling of watched source events ([2a31b47](https://github.com/albertocavalcante/gvy/commit/2a31b47b15084de3fe8949c97ebfe96deb669a65))
* **workspace:** add created source files to the workspace model ([a55d23f](https://github.com/albertocavalcante/gvy/commit/a55d23f360b3059197d5b47f72771d285e7e68f9))
* **workspace:** decide workspace containment by the membership key ([2431a25](https://github.com/albertocavalcante/gvy/commit/2431a253c9345f90fb7c8d52c2fdcb9cfbb205df))
* **workspace:** keep scanning past unreadable or vanished entries ([9d55a25](https://github.com/albertocavalcante/gvy/commit/9d55a259d6b5cec2c425b3f87f2138751add96eb))
* **workspace:** keep the source list in sync for folder events ([f1c0dab](https://github.com/albertocavalcante/gvy/commit/f1c0daba314481afda23d6ea569b3e91e962c168))
* **workspace:** match watched paths across symlink, case and Unicode ([8e4e302](https://github.com/albertocavalcante/gvy/commit/8e4e302dc8971839f7e411bdb9c6f47d231f3d26))
* **workspace:** recover dependency resolution after failure ([#1180](https://github.com/albertocavalcante/gvy/issues/1180)) ([5a92cb1](https://github.com/albertocavalcante/gvy/commit/5a92cb1e4ff2b50aa4b7eefb20aa826cb84e358c))
* **workspace:** scan a directory named by several source roots once ([248461e](https://github.com/albertocavalcante/gvy/commit/248461e21b19dfba15252c683c697a5c77466112))
* **workspace:** shadow the listed copy of a buffer opened elsewhere ([328050e](https://github.com/albertocavalcante/gvy/commit/328050e9164601629cb766cc2dc24effb0f18711))
* **workspace:** treat a reordered classpath as a change ([11af241](https://github.com/albertocavalcante/gvy/commit/11af241b1d75464080997e2f4677852b0234616d))
* **zed:** preflight the selected Java executable ([#1835](https://github.com/albertocavalcante/gvy/issues/1835)) ([9f16371](https://github.com/albertocavalcante/gvy/commit/9f1637190fa9e71828f056b26748597ef2bc7798))


### Performance Improvements

* **client:** memory-friendly JVM defaults merged with user vmargs ([6040dde](https://github.com/albertocavalcante/gvy/commit/6040ddef544f4d755ce8e6269678ff8c00084801)), closes [#1566](https://github.com/albertocavalcante/gvy/issues/1566)
* **compilation:** cache canonical document paths per model ([2deb412](https://github.com/albertocavalcante/gvy/commit/2deb412edbe60bed969bc3951aad458b7802ebbe))
* **compilation:** make the global clear O(1) under the fence ([1a20499](https://github.com/albertocavalcante/gvy/commit/1a20499d63c3431a988db6b1fb4e38a1c7a3ed20))
* **compilation:** skip recompiling text that cannot be parsed ([6244e7a](https://github.com/albertocavalcante/gvy/commit/6244e7a1e403f7a2102c390517faf5b3b3dd87ed))
* **completion:** find import candidates through the class index ([cc5164b](https://github.com/albertocavalcante/gvy/commit/cc5164b98554a5c925483258de2b1e9dea98cd57))
* **completion:** index workspace class names for lookups ([bb63580](https://github.com/albertocavalcante/gvy/commit/bb63580d8b57a0518a2da14cc20c5818720ed099))
* **completion:** skip the def retry unless the dummy caused the error ([c0a377b](https://github.com/albertocavalcante/gvy/commit/c0a377bf7faeb1bd56b3ced5a2643d22c8c746e4))
* **definition:** resolve global classes through the class index ([c602570](https://github.com/albertocavalcante/gvy/commit/c602570d76f001263e443f2fa22fcc48d6a4de6e))
* **gls:** bound the compilation cache with LRU eviction ([#1125](https://github.com/albertocavalcante/gvy/issues/1125)) ([ce8ce12](https://github.com/albertocavalcante/gvy/commit/ce8ce12abedd67cb758c1ec58149825e60013e83))
* **gls:** build semantic documents outside the index entry lock ([#1253](https://github.com/albertocavalcante/gvy/issues/1253)) ([f4617a5](https://github.com/albertocavalcante/gvy/commit/f4617a54114397139ae6a8e36b3f85d4b574fa8b))
* **gradle:** idle timeout and release for LSP-spawned Gradle daemons ([050bf57](https://github.com/albertocavalcante/gvy/commit/050bf576450ae364562d017fdc045d40cdae4452)), closes [#1567](https://github.com/albertocavalcante/gvy/issues/1567)
* **inlay:** group workspace methods once per request ([131f3d9](https://github.com/albertocavalcante/gvy/commit/131f3d94b2c4e2e4347086f80b4c3197dd642426))
* **jenkins:** memoize merged Jenkins metadata across requests ([2283919](https://github.com/albertocavalcante/gvy/commit/2283919733cf1d380bf925a5495b4cd192332e72))
* **jenkins:** scan the Jenkins classpath outside the context lock ([98e3b5f](https://github.com/albertocavalcante/gvy/commit/98e3b5ff1f6e4390d96d517274ac1be6da725b8d))
* **semantics:** reuse immutable declaration views ([#1893](https://github.com/albertocavalcante/gvy/issues/1893)) ([0461954](https://github.com/albertocavalcante/gvy/commit/046195408ee1d56e9ee045cd40ae8982ee5452bd))
* **test:** add focused harness verification task ([#1745](https://github.com/albertocavalcante/gvy/issues/1745)) ([cc6d290](https://github.com/albertocavalcante/gvy/commit/cc6d2904feb60ddfbc707aa249fa7bc10e4a6030))
* **test:** relocate pure caches and refresh E2E ([#1763](https://github.com/albertocavalcante/gvy/issues/1763)) ([010b0ba](https://github.com/albertocavalcante/gvy/commit/010b0ba06932dc07f33d6e49c16e2c0509d01a78))

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
