package io.atlasarc.governance

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CycleGovernanceMatcherTest {
    private val matcher = CycleGovernanceMatcher()

    @Test
    fun `narrow governance covers only matching concrete references so red can still win`() {
        val references = listOf(
            ref("r-covered", identity("a", "a.Origin"), identity("b", "b.Approved")),
            ref("r-red", identity("a", "a.Origin"), identity("b", "b.Other")),
        )
        val record = record(
            scope = GovernanceScope.TYPE,
            ownerSide = GovernanceOwnerSide.TARGET,
            source = identity("a"),
            target = identity("b", "b.Approved"),
        )

        val result = matcher.match(document("narrow-type" to record), snapshot(jvmSource(), references))

        assertEquals(setOf("r-covered"), result.coverage.keys)
        assertFalse("r-red" in result.coverage)
        assertEquals(GovernanceRecordStatus.ACTIVE, result.records.getValue("narrow-type").status)
    }

    @Test
    fun `debt wins overlapping broad and narrow records independent of insertion order`() {
        val references = listOf(
            ref("r-special", identity("a", "a.Origin"), identity("b", "b.Special")),
            ref("r-other", identity("a", "a.Origin"), identity("b", "b.Other")),
        )
        val broad = record(
            scope = GovernanceScope.PACKAGE,
            source = identity("a"),
            target = identity("b"),
            kind = CycleGovernanceKind.INTENTIONAL,
        )
        val narrowDebt = record(
            scope = GovernanceScope.TYPE,
            ownerSide = GovernanceOwnerSide.TARGET,
            source = identity("a"),
            target = identity("b", "b.Special"),
            kind = CycleGovernanceKind.DEBT,
        )
        val evidence = snapshot(jvmSource(), references)

        val first = matcher.match(document("broad" to broad, "narrow" to narrowDebt), evidence)
        val second = matcher.match(document("narrow" to narrowDebt, "broad" to broad), evidence)

        assertEquals(first, second)
        assertEquals(CycleGovernanceKind.DEBT, first.coverage.getValue("r-special").effectiveKind)
        assertEquals(CycleGovernanceKind.INTENTIONAL, first.coverage.getValue("r-other").effectiveKind)
    }

    @Test
    fun `module-qualified package governance covers only one split-package module`() {
        val orders = ref(
            "orders-ref",
            identity("shared.left", module = "orders"),
            identity("shared.right", module = "orders"),
        )
        val billing = ref(
            "billing-ref",
            identity("shared.left", module = "billing"),
            identity("shared.right", module = "billing"),
        )
        val qualified = record(
            source = identity("shared.left", module = "orders"),
            target = identity("shared.right", module = "orders"),
        )

        val result = matcher.match(document("orders-only" to qualified), snapshot(jvmSource(), listOf(orders, billing)))

        assertEquals(GovernanceRecordStatus.ACTIVE, result.records.getValue("orders-only").status)
        assertEquals(setOf("orders-ref"), result.coverage.keys)
    }

    @Test
    fun `repository governance applies when JVM evidence is acquired from a module source`() {
        val moduleSourceId = "jvm:module:orders"
        val orders = ref(
            "orders-ref",
            identity("shared.left", module = "orders"),
            identity("shared.right", module = "orders"),
        ).copy(analysisSourceId = moduleSourceId)
        val ordersRecord = record(
            source = identity("shared.left", module = "orders"),
            target = identity("shared.right", module = "orders"),
        )
        val billingRecord = record(
            source = identity("shared.left", module = "billing"),
            target = identity("shared.right", module = "billing"),
        )
        val moduleEvidence = jvmSource(
            id = moduleSourceId,
            repositoryComplete = false,
            includedJvmModules = setOf("orders"),
        )

        val result = matcher.match(
            document("orders" to ordersRecord, "billing" to billingRecord),
            snapshot(moduleEvidence, listOf(orders)),
        )

        assertEquals(GovernanceRecordStatus.ACTIVE, result.records.getValue("orders").status)
        assertEquals(GovernanceRecordStatus.NOT_IN_ANALYSIS, result.records.getValue("billing").status)
        assertEquals(setOf("orders-ref"), result.coverage.keys)
        assertEquals(0, result.validationCount)
    }

    @Test
    fun `records for another backend are neutral during one IDE analysis source`() {
        val typeScriptRecord = record(
            sourceId = "ts:manifest:web",
            backend = GovernanceBackend.TYPESCRIPT_ARTIFACT,
            language = GovernanceLanguage.TYPESCRIPT,
            scope = GovernanceScope.SOURCE_FOLDER,
            source = identity("apps/web/src/a"),
            target = identity("apps/web/src/b"),
        )
        val evidence = snapshot(
            jvmSource(repositoryComplete = true),
            listOf(ref("jvm-ref", identity("a"), identity("b"))),
        ).copy(evaluationComplete = false)

        val result = matcher.match(document("typescript" to typeScriptRecord), evidence)

        assertEquals(GovernanceRecordStatus.NOT_IN_ANALYSIS, result.records.getValue("typescript").status)
        assertEquals(0, result.validationCount)
    }

    @Test
    fun `reference governance maps a stored source-specific id onto current JVM evidence`() {
        val moduleSourceId = "jvm:module:orders"
        val source = identity("shared.left", "shared.left.Origin", module = "orders")
        val target = identity("shared.right", "shared.right.Target", module = "orders")
        val storedId = GovernanceIds.referenceId(
            JVM_ID,
            GovernanceBackend.JVM_BYTECODE,
            GovernanceLanguage.JAVA,
            GovernanceLanguage.JAVA,
            source,
            target,
            GovernanceDependencyKind.METHOD_CALL,
        )
        val currentId = GovernanceIds.referenceId(
            moduleSourceId,
            GovernanceBackend.JVM_BYTECODE,
            GovernanceLanguage.JAVA,
            GovernanceLanguage.JAVA,
            source,
            target,
            GovernanceDependencyKind.METHOD_CALL,
        )
        val currentReference = ref(currentId, source, target).copy(analysisSourceId = moduleSourceId)
        val record = record(
            scope = GovernanceScope.REFERENCE,
            source = source,
            target = target,
        ).copy(referenceIds = setOf(storedId))

        val result = matcher.match(
            document("portable-reference" to record),
            snapshot(
                jvmSource(
                    id = moduleSourceId,
                    repositoryComplete = false,
                    includedJvmModules = setOf("orders"),
                ),
                listOf(currentReference),
            ),
        )

        assertEquals(GovernanceRecordStatus.ACTIVE, result.records.getValue("portable-reference").status)
        assertEquals(listOf(currentId), result.records.getValue("portable-reference").matchedReferenceIds)
        assertEquals(setOf(currentId), result.coverage.keys)
    }

    @Test
    fun `exact reference resolves even when its declaration endpoints no longer materialize`() {
        val source = identity("retired.source", "retired.source.Owner", module = "orders")
        val target = identity("retired.target", "retired.target.Target", module = "orders")
        val exact = record(
            scope = GovernanceScope.REFERENCE,
            source = source,
            target = target,
            kind = CycleGovernanceKind.DEBT,
        ).copy(referenceIds = setOf("retired-reference"))
        val evidence = snapshot(
            jvmSource(repositoryComplete = true),
            references = emptyList(),
            extraNodes = emptyList(),
        )

        val resolved = matcher.match(document("retired" to exact), evidence)
            .records.getValue("retired")

        assertEquals(GovernanceRecordStatus.RESOLVED, resolved.status)
        assertTrue(resolved.matchedReferenceIds.isEmpty())
    }

    @Test
    fun `exact reference stays neutral outside a partial analysis and reactivates when evidence returns`() {
        val source = identity("shared.left", "shared.left.Owner", module = "orders")
        val target = identity("shared.right", "shared.right.Target", module = "orders")
        val reference = ref("orders-reference", source, target)
        val exact = record(
            scope = GovernanceScope.REFERENCE,
            source = source,
            target = target,
            kind = CycleGovernanceKind.DEBT,
        ).copy(referenceIds = setOf(reference.id))
        val outside = snapshot(
            jvmSource(
                id = "jvm:module:billing",
                repositoryComplete = false,
                includedJvmModules = setOf("billing"),
            ),
            references = emptyList(),
        ).copy(evaluationComplete = false)

        val notInAnalysis = matcher.match(document("orders" to exact), outside)
            .records.getValue("orders")
        val active = matcher.match(document("orders" to exact), snapshot(jvmSource(), listOf(reference)))
            .records.getValue("orders")

        assertEquals(GovernanceRecordStatus.NOT_IN_ANALYSIS, notInAnalysis.status)
        assertEquals(GovernanceRecordStatus.ACTIVE, active.status)
        assertEquals(listOf(reference.id), active.matchedReferenceIds)
    }

    @Test
    fun `reference id disambiguates concrete evidence when declaration metadata is necessarily incomplete`() {
        val source = identity("a", "a.Origin", sourceFile = "src/main/java/a/Origin.java")
        val target = identity("b", "b.Target", sourceFile = "src/main/java/b/Target.java")
        val reference = ref("exact-reference", source, target)
        val exact = record(
            scope = GovernanceScope.REFERENCE,
            source = source,
            target = target,
        ).copy(referenceIds = setOf(reference.id))
        val additionalDeclarationEvidence = listOf(
            GovernanceEvidenceNode(
                JVM_ID,
                GovernanceBackend.JVM_BYTECODE,
                GovernanceLanguage.JAVA,
                source.copy(member = GovernanceMemberIdentity("first", "()V")),
            ),
            GovernanceEvidenceNode(
                JVM_ID,
                GovernanceBackend.JVM_BYTECODE,
                GovernanceLanguage.JAVA,
                source.copy(member = GovernanceMemberIdentity("second", "()V")),
            ),
        )

        val match = matcher.match(
            document("exact" to exact),
            snapshot(jvmSource(), listOf(reference), additionalDeclarationEvidence),
        ).records.getValue("exact")

        assertEquals(GovernanceRecordStatus.ACTIVE, match.status)
        assertEquals(listOf(reference.id), match.matchedReferenceIds)
    }

    @Test
    fun `unqualified package governance fails ambiguous for a split package`() {
        val references = listOf(
            ref(
                "orders-ref",
                identity("shared.left", module = "orders"),
                identity("shared.right", module = "orders"),
            ),
            ref(
                "billing-ref",
                identity("shared.left", module = "billing"),
                identity("shared.right", module = "billing"),
            ),
            ref(
                "cross-module-ref",
                identity("shared.left", module = "billing"),
                identity("shared.right", module = "orders"),
            ),
        )
        val unqualified = record(
            source = identity("shared.left"),
            target = identity("shared.right"),
        )

        val result = matcher.match(document("ambiguous-split-package" to unqualified), snapshot(jvmSource(), references))

        val match = result.records.getValue("ambiguous-split-package")
        assertEquals(GovernanceRecordStatus.AMBIGUOUS, match.status)
        assertTrue(match.diagnostics.any { it.contains("source selector 'shared.left'") })
        assertTrue(match.diagnostics.any { it.contains("billing, orders") })
        assertTrue(match.diagnostics.any { it.contains("Add the intended module") })
        assertEquals(
            listOf(
                GovernanceModulePairCandidate("billing", "billing", 1),
                GovernanceModulePairCandidate("billing", "orders", 1),
                GovernanceModulePairCandidate("orders", "orders", 1),
            ),
            match.moduleCandidates,
            "module triage must reflect real dependency pairs rather than a source-target cartesian product",
        )
        assertTrue(result.coverage.isEmpty(), "an unqualified split-package record must fail closed")
    }

    @Test
    fun `module repair candidates exclude pairings already governed by another record`() {
        val references = listOf(
            ref("billing-ref", identity("shared.left", module = "billing"), identity("shared.right", module = "billing")),
            ref("cross-module-ref", identity("shared.left", module = "billing"), identity("shared.right", module = "orders")),
            ref("orders-ref", identity("shared.left", module = "orders"), identity("shared.right", module = "orders")),
        )
        val records = mapOf(
            "billing-cycle" to record(
                source = identity("shared.left", module = "billing"),
                target = identity("shared.right", module = "billing"),
            ),
            "orders-cycle" to record(
                source = identity("shared.left", module = "orders"),
                target = identity("shared.right", module = "orders"),
            ),
            "ambiguous-cycle" to record(
                source = identity("shared.left"),
                target = identity("shared.right"),
            ),
        )

        val match = matcher.match(CycleGovernanceDocument(records = records), snapshot(jvmSource(), references))
            .records.getValue("ambiguous-cycle")

        assertEquals(
            listOf(GovernanceModulePairCandidate("billing", "orders", 1)),
            match.moduleCandidates,
            "a repair card must not propose a selector that would duplicate an existing record",
        )
    }

    @Test
    fun `acceptance review keeps one actionable card for every deliberately broken record`() {
        val billingLeft = identity("acceptance.shared.left", module = "billing")
        val billingRight = identity("acceptance.shared.right", module = "billing")
        val ordersLeft = identity("acceptance.shared.left", module = "orders")
        val ordersRight = identity("acceptance.shared.right", module = "orders")
        val reportingInput = identity("acceptance.reporting.input", module = "reporting")
        val reportingOutput = identity("acceptance.reporting.output", module = "reporting")
        val references = listOf(
            ref("billing-cycle", billingLeft, billingRight),
            ref("orders-cycle", ordersLeft, ordersRight),
            ref("reporting-cycle", reportingInput, reportingOutput),
            ref("billing-to-orders", billingLeft, ordersRight),
            ref("reporting-output-to-orders", reportingOutput, ordersRight),
            ref("reporting-input-to-billing", reportingInput, billingLeft),
        )
        val records = mapOf(
            "accept-billing" to record(source = billingLeft, target = billingRight),
            "accept-orders" to record(source = ordersLeft, target = ordersRight),
            "accept-reporting" to record(source = reportingInput, target = reportingOutput),
            "stale-ambiguous" to record(
                source = identity("acceptance.shared.left"),
                target = identity("acceptance.shared.right"),
            ),
            "stale-missing-source" to record(
                source = identity("acceptance.retired.output", module = "reporting"),
                target = ordersRight,
            ),
            "stale-missing-target" to record(
                source = reportingInput,
                target = identity("acceptance.retired.left", module = "billing"),
            ),
        )

        val matches = matcher.match(CycleGovernanceDocument(records = records), snapshot(jvmSource(), references)).records

        assertEquals(
            listOf(GovernanceModulePairCandidate("billing", "orders", 1)),
            matches.getValue("stale-ambiguous").moduleCandidates,
        )
        assertEquals(
            listOf(reportingOutput),
            matches.getValue("stale-missing-source").retargetCandidates.map { it.identity },
        )
        assertEquals(
            listOf(billingLeft),
            matches.getValue("stale-missing-target").retargetCandidates.map { it.identity },
        )
    }

    @Test
    fun `unqualified package governance fails ambiguous when attribution is incomplete`() {
        val references = listOf(
            ref(
                "orders-ref",
                identity("shared.left", module = "orders"),
                identity("shared.right", module = "orders"),
            ),
            ref("unknown-ref", identity("shared.left"), identity("shared.right")),
        )
        val unqualified = record(source = identity("shared.left"), target = identity("shared.right"))

        val result = matcher.match(document("ambiguous-attribution" to unqualified), snapshot(jvmSource(), references))

        assertEquals(GovernanceRecordStatus.AMBIGUOUS, result.records.getValue("ambiguous-attribution").status)
        assertTrue(result.coverage.isEmpty(), "mixed known and unknown module attribution must fail closed")
    }

    @Test
    fun `incoming and outgoing ownership use the language of the owning side`() {
        val crossLanguage = ref(
            id = "cross",
            source = identity("k", "k.Caller"),
            target = identity("j", "j.Target"),
            sourceLanguage = GovernanceLanguage.KOTLIN,
            targetLanguage = GovernanceLanguage.JAVA,
        )
        val sourceOwned = record(
            language = GovernanceLanguage.KOTLIN,
            scope = GovernanceScope.TYPE,
            ownerSide = GovernanceOwnerSide.SOURCE,
            source = identity("k", "k.Caller"),
            target = identity("j"),
        )
        val targetOwned = record(
            language = GovernanceLanguage.JAVA,
            scope = GovernanceScope.TYPE,
            ownerSide = GovernanceOwnerSide.TARGET,
            source = identity("k"),
            target = identity("j", "j.Target"),
        )

        val result = matcher.match(
            document("source-owned" to sourceOwned, "target-owned" to targetOwned),
            snapshot(jvmSource(languages = setOf(GovernanceLanguage.JAVA, GovernanceLanguage.KOTLIN)), listOf(crossLanguage)),
        )

        assertEquals(listOf("source-owned", "target-owned"), result.coverage.getValue("cross").recordIds)
    }

    @Test
    fun `Java member descriptor disambiguates overloads while a name-only selector fails closed`() {
        val firstTarget = identity("b", "b.Target", member = GovernanceMemberIdentity("load", "(I)V"))
        val secondTarget = identity("b", "b.Target", member = GovernanceMemberIdentity("load", "(Ljava/lang/String;)V"))
        val references = listOf(
            ref("r-int", identity("a", "a.Origin"), firstTarget, kind = GovernanceDependencyKind.METHOD_CALL),
            ref("r-string", identity("a", "a.Origin"), secondTarget, kind = GovernanceDependencyKind.METHOD_CALL),
        )
        val exact = record(
            scope = GovernanceScope.MEMBER,
            ownerSide = GovernanceOwnerSide.TARGET,
            source = identity("a"),
            target = firstTarget,
            dependencyKind = GovernanceDependencyKind.METHOD_CALL,
        )
        val nameOnly = exact.copy(target = identity("b", "b.Target", member = GovernanceMemberIdentity("load")))

        val result = matcher.match(
            document("exact-overload" to exact, "name-only" to nameOnly),
            snapshot(jvmSource(), references),
        )

        assertEquals(GovernanceRecordStatus.ACTIVE, result.records.getValue("exact-overload").status)
        assertEquals(listOf("r-int"), result.records.getValue("exact-overload").matchedReferenceIds)
        val nameOnlyMatch = result.records.getValue("name-only")
        assertEquals(GovernanceRecordStatus.INVALID, nameOnlyMatch.status)
        assertTrue(nameOnlyMatch.diagnostics.single().startsWith("missing-member-descriptor:"))
    }

    @Test
    fun `Kotlin package constructor property and function scopes match concrete evidence`() {
        val members = listOf(
            GovernanceMemberIdentity("<init>", "()V"),
            GovernanceMemberIdentity("getValue", "()Ljava/lang/String;"),
            GovernanceMemberIdentity("calculate", "()I"),
        )
        val references = members.map { member ->
            ref(
                id = "k-${member.name}",
                source = identity("k.source", "k.source.Owner", member = member),
                target = identity("k.target", "k.target.Target"),
                sourceLanguage = GovernanceLanguage.KOTLIN,
                targetLanguage = GovernanceLanguage.KOTLIN,
            )
        }
        val records = linkedMapOf<String, CycleGovernanceRecord>()
        records["kotlin-package"] = record(
            language = GovernanceLanguage.KOTLIN,
            scope = GovernanceScope.PACKAGE,
            source = identity("k.source"),
            target = identity("k.target"),
        )
        members.forEachIndexed { index, member ->
            records["kotlin-member-$index"] = record(
                language = GovernanceLanguage.KOTLIN,
                scope = GovernanceScope.MEMBER,
                ownerSide = GovernanceOwnerSide.SOURCE,
                source = identity("k.source", "k.source.Owner", member = member),
                target = identity("k.target"),
            )
        }

        val result = matcher.match(
            CycleGovernanceDocument(records = records),
            snapshot(jvmSource(languages = setOf(GovernanceLanguage.KOTLIN)), references),
        )

        assertTrue(result.records.values.all { it.status == GovernanceRecordStatus.ACTIVE })
        members.forEachIndexed { index, member ->
            assertTrue("kotlin-member-$index" in result.coverage.getValue("k-${member.name}").recordIds)
        }
    }

    @Test
    fun `TypeScript governance follows semantic evidence across acquisition roots`() {
        val runtime = tsRef(
            "runtime",
            "ts:manifest:shop",
            identity("apps/shop/src/checkout", sourceFile = "apps/shop/src/checkout/cart.ts"),
            identity("packages/payments/src", sourceFile = "packages/payments/src/public.ts"),
            GovernanceDependencyKind.RUNTIME_IMPORT,
        )
        val typeOnly = runtime.copy(id = "type-only", dependencyKind = GovernanceDependencyKind.TYPE_ONLY_IMPORT)
        val otherRoot = runtime.copy(id = "other-root", analysisSourceId = "ts:manifest:admin")
        val folder = record(
            sourceId = "ts:manifest:shop",
            backend = GovernanceBackend.TYPESCRIPT_ARTIFACT,
            language = GovernanceLanguage.TYPESCRIPT,
            scope = GovernanceScope.SOURCE_FOLDER,
            source = identity("apps/shop/src/checkout"),
            target = identity("packages/payments/src"),
            dependencyKind = GovernanceDependencyKind.RUNTIME_IMPORT,
        )
        val file = folder.copy(
            scope = GovernanceScope.SOURCE_FILE,
            ownerSide = GovernanceOwnerSide.SOURCE,
            source = runtime.source,
            target = runtime.target,
            dependencyKind = GovernanceDependencyKind.TYPE_ONLY_IMPORT,
        )
        val sources = listOf(tsSource("ts:manifest:shop"), tsSource("ts:manifest:admin"))

        val result = matcher.match(document("folder-runtime" to folder, "file-type" to file), snapshot(sources, listOf(runtime, typeOnly, otherRoot)))

        assertEquals(listOf("folder-runtime"), result.coverage.getValue("runtime").recordIds)
        assertEquals(listOf("file-type"), result.coverage.getValue("type-only").recordIds)
        assertEquals(listOf("folder-runtime"), result.coverage.getValue("other-root").recordIds)
    }

    @Test
    fun `stale partial unsupported invalid missing and resolved records each get one fail-closed status`() {
        val validRef = ref("r-one", identity("a", "a.A"), identity("b", "b.B"))
        val repairRef = ref(
            "repair-source",
            identity("new/orders", "new.orders.Order"),
            identity("b", "b.B"),
        )
        val nodes = listOf(
            GovernanceEvidenceNode(JVM_ID, GovernanceBackend.JVM_BYTECODE, GovernanceLanguage.JAVA, identity("a", "a.A")),
            GovernanceEvidenceNode(JVM_ID, GovernanceBackend.JVM_BYTECODE, GovernanceLanguage.JAVA, identity("b", "b.B")),
            GovernanceEvidenceNode(JVM_ID, GovernanceBackend.JVM_BYTECODE, GovernanceLanguage.JAVA, identity("c", "c.C")),
            GovernanceEvidenceNode(JVM_ID, GovernanceBackend.JVM_BYTECODE, GovernanceLanguage.JAVA, identity("new/orders", "new.orders.Order")),
        )
        val base = record(scope = GovernanceScope.TYPE, source = identity("a", "a.A"), target = identity("b", "b.B"))
        val records = mapOf(
            "missing-source" to base.copy(source = identity("old/orders", "old.orders.Order")),
            "missing-target" to base.copy(target = identity("gone", "gone.Target")),
            "resolved-edge" to base.copy(target = identity("c", "c.C")),
            "partial-reference" to base.copy(scope = GovernanceScope.REFERENCE, referenceIds = setOf("r-one", "r-two")),
            "unsupported-scope" to base.copy(
                scope = GovernanceScope.MEMBER,
                source = identity("a", "a.A", member = GovernanceMemberIdentity("run", "()V")),
            ),
            "invalid-reason" to base.copy(reason = ""),
        )
        val sourceWithoutMember = jvmSource(scopes = setOf(GovernanceScope.PACKAGE, GovernanceScope.TYPE, GovernanceScope.REFERENCE))
        val result = matcher.match(CycleGovernanceDocument(records = records), snapshot(sourceWithoutMember, listOf(validRef, repairRef), nodes))

        assertEquals(GovernanceRecordStatus.MISSING_SOURCE, result.records.getValue("missing-source").status)
        assertTrue(result.records.getValue("missing-source").retargetCandidates.isNotEmpty())
        assertEquals(GovernanceRecordStatus.MISSING_TARGET, result.records.getValue("missing-target").status)
        assertEquals(GovernanceRecordStatus.RESOLVED, result.records.getValue("resolved-edge").status)
        assertEquals(GovernanceRecordStatus.PARTIAL, result.records.getValue("partial-reference").status)
        assertEquals(GovernanceRecordStatus.UNSUPPORTED, result.records.getValue("unsupported-scope").status)
        assertEquals(GovernanceRecordStatus.INVALID, result.records.getValue("invalid-reason").status)
        assertFalse("r-one" in result.coverage, "partial and otherwise non-active records must not suppress evidence")

        val stale = matcher.match(
            document("stale-record" to base),
            snapshot(jvmSource(fresh = false), listOf(validRef)),
        )
        assertEquals(GovernanceRecordStatus.UNSUPPORTED, stale.records.getValue("stale-record").status)
        assertTrue(stale.coverage.isEmpty())
    }

    @Test
    fun `package retarget suggestions exclude concrete types in the matching package`() {
        val module = "billing"
        val nodes = listOf(
            GovernanceEvidenceNode(
                JVM_ID,
                GovernanceBackend.JVM_BYTECODE,
                GovernanceLanguage.JAVA,
                identity("acceptance.shared.left", module = module),
            ),
            GovernanceEvidenceNode(
                JVM_ID,
                GovernanceBackend.JVM_BYTECODE,
                GovernanceLanguage.JAVA,
                identity("acceptance.shared.right", module = module),
            ),
            GovernanceEvidenceNode(
                JVM_ID,
                GovernanceBackend.JVM_BYTECODE,
                GovernanceLanguage.JAVA,
                identity(
                    "acceptance.shared.right",
                    type = "acceptance.shared.right.BillingRight",
                    sourceFile = "billing/src/main/java/acceptance/shared/right/BillingRight.java",
                    module = module,
                ),
            ),
        )
        val record = record(
            source = identity("acceptance.shared.left", module = module),
            target = identity("acceptance.retired.right", module = module),
        )
        val repairReference = ref(
            "repair-package",
            identity("acceptance.shared.left", module = module),
            identity("acceptance.shared.right", module = module),
        )

        val result = matcher.match(
            document("missing-package" to record),
            snapshot(jvmSource(), listOf(repairReference), nodes),
        ).records.getValue("missing-package")

        assertEquals(GovernanceRecordStatus.MISSING_TARGET, result.status)
        assertEquals(
            listOf(identity("acceptance.shared.right", module = module)),
            result.retargetCandidates.map { it.identity },
        )
    }

    @Test
    fun `case semantics are explicit and deterministic`() {
        val reference = tsRef(
            "case-ref",
            "ts:manifest:web",
            identity("Apps/Web/src", sourceFile = "Apps/Web/src/App.ts"),
            identity("Packages/API/src", sourceFile = "Packages/API/src/index.ts"),
            GovernanceDependencyKind.RUNTIME_IMPORT,
        )
        val record = record(
            sourceId = "ts:manifest:web",
            backend = GovernanceBackend.TYPESCRIPT_ARTIFACT,
            language = GovernanceLanguage.TYPESCRIPT,
            scope = GovernanceScope.SOURCE_FILE,
            source = identity("apps/web/src", sourceFile = "apps/web/src/app.ts"),
            target = identity("packages/api/src", sourceFile = "packages/api/src/index.ts"),
        )
        val source = tsSource("ts:manifest:web")

        val sensitive = matcher.match(document("case-record" to record), snapshot(source, listOf(reference), caseSensitive = true))
        val insensitive = matcher.match(document("case-record" to record), snapshot(source, listOf(reference), caseSensitive = false))

        assertEquals(GovernanceRecordStatus.MISSING_SOURCE, sensitive.records.getValue("case-record").status)
        assertEquals(GovernanceRecordStatus.ACTIVE, insensitive.records.getValue("case-record").status)
    }

    private fun document(vararg records: Pair<String, CycleGovernanceRecord>) =
        CycleGovernanceDocument(records = linkedMapOf(*records))

    private fun record(
        sourceId: String = JVM_ID,
        backend: GovernanceBackend = GovernanceBackend.JVM_BYTECODE,
        language: GovernanceLanguage = GovernanceLanguage.JAVA,
        scope: GovernanceScope = GovernanceScope.PACKAGE,
        ownerSide: GovernanceOwnerSide = GovernanceOwnerSide.SOURCE,
        source: GovernanceIdentity = identity("a"),
        target: GovernanceIdentity = identity("b"),
        dependencyKind: GovernanceDependencyKind? = null,
        kind: CycleGovernanceKind = CycleGovernanceKind.INTENTIONAL,
    ) = CycleGovernanceRecord(
        analysisSource = GovernanceAnalysisSource(sourceId, backend, language),
        scope = scope,
        ownerSide = ownerSide,
        source = source,
        target = target,
        dependencyKind = dependencyKind,
        kind = kind,
        reason = "Reviewed architecture decision.",
    )

    private fun identity(
        unit: String,
        type: String? = null,
        sourceFile: String? = null,
        member: GovernanceMemberIdentity? = null,
        module: String? = null,
    ) = GovernanceIdentity(unit, type, sourceFile, member, module)

    private fun ref(
        id: String,
        source: GovernanceIdentity,
        target: GovernanceIdentity,
        sourceLanguage: GovernanceLanguage = GovernanceLanguage.JAVA,
        targetLanguage: GovernanceLanguage = GovernanceLanguage.JAVA,
        kind: GovernanceDependencyKind? = GovernanceDependencyKind.METHOD_CALL,
    ) = GovernanceEvidenceReference(
        id,
        JVM_ID,
        GovernanceBackend.JVM_BYTECODE,
        sourceLanguage,
        targetLanguage,
        source,
        target,
        kind,
    )

    private fun tsRef(
        id: String,
        sourceId: String,
        source: GovernanceIdentity,
        target: GovernanceIdentity,
        kind: GovernanceDependencyKind,
    ) = GovernanceEvidenceReference(
        id,
        sourceId,
        GovernanceBackend.TYPESCRIPT_ARTIFACT,
        GovernanceLanguage.TYPESCRIPT,
        GovernanceLanguage.TYPESCRIPT,
        source,
        target,
        kind,
    )

    private fun jvmSource(
        id: String = JVM_ID,
        languages: Set<GovernanceLanguage> = setOf(GovernanceLanguage.JAVA),
        scopes: Set<GovernanceScope> = setOf(GovernanceScope.PACKAGE, GovernanceScope.TYPE, GovernanceScope.MEMBER, GovernanceScope.REFERENCE),
        fresh: Boolean = true,
        repositoryComplete: Boolean = true,
        includedJvmModules: Set<String> = emptySet(),
    ) = GovernanceEvidenceSource(
        id,
        GovernanceBackend.JVM_BYTECODE,
        languages,
        scopes,
        fresh,
        "JVM evidence is stale.",
        repositoryComplete,
        includedJvmModules,
    )

    private fun tsSource(id: String) = GovernanceEvidenceSource(
        id,
        GovernanceBackend.TYPESCRIPT_ARTIFACT,
        setOf(GovernanceLanguage.TYPESCRIPT),
        setOf(GovernanceScope.SOURCE_FOLDER, GovernanceScope.SOURCE_FILE, GovernanceScope.REFERENCE),
    )

    private fun snapshot(
        source: GovernanceEvidenceSource,
        references: List<GovernanceEvidenceReference>,
        extraNodes: List<GovernanceEvidenceNode> = emptyList(),
        caseSensitive: Boolean = true,
    ) = snapshot(listOf(source), references, extraNodes, caseSensitive)

    private fun snapshot(
        sources: List<GovernanceEvidenceSource>,
        references: List<GovernanceEvidenceReference>,
        extraNodes: List<GovernanceEvidenceNode> = emptyList(),
        caseSensitive: Boolean = true,
    ): GovernanceEvidenceSnapshot {
        val nodes = references.flatMap { reference ->
            listOf(
                GovernanceEvidenceNode(reference.analysisSourceId, reference.backend, reference.sourceLanguage, reference.source),
                GovernanceEvidenceNode(reference.analysisSourceId, reference.backend, reference.targetLanguage, reference.target),
            )
        } + extraNodes
        return GovernanceEvidenceSnapshot(sources, nodes.distinct(), references, caseSensitive)
    }

    private companion object {
        const val JVM_ID = "jvm:whole-project"
    }
}
