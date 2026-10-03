package com.linggango.sync.policy;

import com.linggango.sync.adapter.AdapterRule;
import com.linggango.sync.adapter.SyncAdapter;
import com.linggango.sync.config.SyncPolicy;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the classification rules. These are the tests that matter most for safety: they
 * assert that gameplay-critical data cannot be moved off the server by accident.
 */
class DataClassifierTest {

    @Test
    void gameplayCriticalCategoriesAreServerAuthoritative() {
        Map<String, SyncPolicy> policies = DataClassifier.defaultCategoryPolicies();

        for (String category : List.of("player_state", "inventories", "containers", "world_blocks",
                "block_entities", "mobs", "bosses", "projectiles", "redstone",
                "create_contraptions", "progression", "machine_inventory")) {
            assertEquals(SyncPolicy.SERVER_AUTHORITATIVE, policies.get(category),
                    category + " must never be optimised");
        }
    }

    @Test
    void purelyLocalCategoriesAreClientLocal() {
        Map<String, SyncPolicy> policies = DataClassifier.defaultCategoryPolicies();

        for (String category : List.of("gui_state", "gui_preferences", "keybinds", "visuals",
                "particles", "cosmetics", "waypoints", "map_cache", "local_recipes",
                "hud", "search_history", "tooltips")) {
            assertEquals(SyncPolicy.CLIENT_LOCAL, policies.get(category),
                    category + " is presentation and should never be synchronised");
        }
    }

    @Test
    void noDefaultPolicyIsMissing() {
        Map<String, SyncPolicy> policies = DataClassifier.defaultCategoryPolicies();
        assertFalse(policies.isEmpty());
        policies.forEach((key, value) -> assertTrue(value != null, key + " must have a policy"));
    }

    @Test
    void unknownCategoryFallsBackToServerAuthoritative() {
        assertEquals(SyncPolicy.SERVER_AUTHORITATIVE,
                DataClassifier.resolve("some_unknown_mod", "some_unknown_category"));
    }

    @Test
    void perModOverrideBeatsCategoryDefault() {
        SyncPolicy resolved = DataClassifier.resolve(
                Map.of("gui_data", SyncPolicy.ON_DEMAND),
                Map.of(),
                Map.of("create", Map.of("gui_data", SyncPolicy.CLIENT_LOCAL)),
                "create", "gui_data");

        assertEquals(SyncPolicy.CLIENT_LOCAL, resolved);
    }

    @Test
    void categoryDefaultAppliesWhenNoModOverrideExists() {
        SyncPolicy resolved = DataClassifier.resolve(
                Map.of("cosmetics", SyncPolicy.CLIENT_LOCAL),
                Map.of(),
                Map.of("create", Map.of("gui_data", SyncPolicy.ON_DEMAND)),
                "create", "cosmetics");

        assertEquals(SyncPolicy.CLIENT_LOCAL, resolved);
    }

    @Test
    void namespaceWildcardAppliesToUnlistedCategories() {
        SyncPolicy resolved = DataClassifier.resolve(
                Map.of("machines", SyncPolicy.ON_DEMAND),
                Map.of(),
                Map.of("create", Map.of("*", SyncPolicy.SERVER_AUTHORITATIVE)),
                "create", "machines");

        assertEquals(SyncPolicy.SERVER_AUTHORITATIVE, resolved,
                "a namespace-wide refusal must win over the generic category default");
    }

    @Test
    void namespaceIsCaseInsensitive() {
        SyncPolicy resolved = DataClassifier.resolve(
                Map.of(),
                Map.of(),
                Map.of("create", Map.of("gui_data", SyncPolicy.CLIENT_LOCAL)),
                "Create", "gui_data");

        assertEquals(SyncPolicy.CLIENT_LOCAL, resolved);
    }

    @Test
    void policyParsingIsForgivingButFailsClosed() {
        assertEquals(SyncPolicy.SERVER_AUTHORITATIVE, SyncPolicy.parse("server_authoritative"));
        assertEquals(SyncPolicy.SERVER_AUTHORITATIVE, SyncPolicy.parse("SERVER-AUTHORITATIVE"));
        assertEquals(SyncPolicy.ON_DEMAND, SyncPolicy.parse("on_demand"));
        assertEquals(SyncPolicy.CLIENT_LOCAL, SyncPolicy.parse("  client-local  "));
        assertEquals(SyncPolicy.SERVER_AUTHORITATIVE, SyncPolicy.parse("nonsense"));
        assertEquals(SyncPolicy.SERVER_AUTHORITATIVE, SyncPolicy.parse(null));
    }

    @Test
    void everyBuiltInAdapterRuleResolvesToSomethingDefined() {
        Adapters.registerDefaults();
        for (SyncAdapter adapter : Adapters.all()) {
            for (AdapterRule rule : adapter.rules()) {
                assertEquals(rule.defaultPolicy(), SyncPolicy.parse(rule.defaultPolicy().name()));
            }
        }
    }

    @Test
    void adaptersDeclareARationaleForEveryRule() {
        Adapters.registerDefaults();
        for (SyncAdapter adapter : Adapters.all()) {
            assertFalse(adapter.rules().isEmpty(), adapter.id() + " must declare at least one rule");
            for (AdapterRule rule : adapter.rules()) {
                assertFalse(rule.rationale() == null || rule.rationale().isBlank(),
                        adapter.id() + ':' + rule.dataType() + " must explain itself");
            }
        }
    }

    @Test
    void gameplayStateIsNeverDeclaredSafeForClientLocalStorage() {
        Adapters.registerDefaults();

        // The precise set of data types that must never be optimised by any adapter.
        Set<String> forbidden = Set.of("inventory", "storage_contents", "crafting_cpu",
                "contraptions", "contraption_storage", "trains", "spell_effects", "mana",
                "damage", "xp", "waystone_destinations", "server_recipes", "fission_reactor");

        for (SyncAdapter adapter : Adapters.all()) {
            for (AdapterRule rule : adapter.rules()) {
                if (!forbidden.contains(rule.dataType())) {
                    continue;
                }
                assertEquals(SyncPolicy.SERVER_AUTHORITATIVE, rule.defaultPolicy(),
                        adapter.id() + ':' + rule.dataType() + " must not be optimised");
                assertFalse(rule.safe(), adapter.id() + ':' + rule.dataType() + " must be marked as refused");
            }
        }
    }

    @Test
    void registeredDescriptorsAreMirroredIntoTheRegistry() {
        com.linggango.sync.api.SyncRegistry.clear();
        Adapters.registerDefaults();

        assertTrue(com.linggango.sync.api.SyncRegistry.size() > 0);
        assertTrue(com.linggango.sync.api.SyncRegistry.lookup("ae2", "network_browsing").isPresent());
    }
}
