# Declarative Pipeline schema automation

The [Jenkins Declarative delivery record](JENKINS_DECLARATIVE_DELIVERY_PLAN.md) supersedes this proposal. It owns the
approved implementation sequence, compatibility inputs, evidence gates and unresolved work for
[#607](https://github.com/albertocavalcante/gvy/issues/607),
[#516](https://github.com/albertocavalcante/gvy/issues/516), [#588](https://github.com/albertocavalcante/gvy/issues/588)
and [#1461](https://github.com/albertocavalcante/gvy/issues/1461).

The earlier proposal contained obsolete module paths, unverified release examples and a speculative compatibility
matrix. Its suggested standalone generator and annotation-only extraction are not the approved architecture. Extend
`tools/jenkins-extractor` with locked, isolated descriptor extraction; retain native Groovy parsing and a shared
snapshot-bound Declarative view in the server. The AST JSON schema alone does not define the Groovy DSL.
