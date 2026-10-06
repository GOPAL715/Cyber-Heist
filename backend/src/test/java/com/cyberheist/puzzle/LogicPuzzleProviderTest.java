package com.cyberheist.puzzle;

import com.cyberheist.mission.MissionDifficulty;
import com.cyberheist.puzzle.provider.LogicPuzzleProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Logic puzzles: exactly one offered node is genuinely unreachable. */
class LogicPuzzleProviderTest {

    private final LogicPuzzleProvider provider = new LogicPuzzleProvider();

    @Test
    @DisplayName("the same seed always produces the same graph")
    void generationIsDeterministic() {
        for (MissionDifficulty difficulty : MissionDifficulty.values()) {
            for (long seed = 1; seed <= 40; seed++) {
                assertThat(provider.create(difficulty, seed))
                        .isEqualTo(provider.create(difficulty, seed));
            }
        }
    }

    @ParameterizedTest
    @EnumSource(MissionDifficulty.class)
    @DisplayName("exactly one option is unreachable, and it is the answer")
    void answerIsTheOnlyUnreachableOption(MissionDifficulty difficulty) {
        for (long seed = 1; seed <= 40; seed++) {
            PuzzleChallenge challenge = provider.create(difficulty, seed);
            String where = difficulty + " seed " + seed;

            Map<String, List<String>> edges = parseEdges(challenge.sequence());
            String entry = entryNode(challenge.sequence());
            List<String> reachable = reachableFrom(edges, entry);

            List<String> unreachableOptions = challenge.options().stream()
                    .filter(option -> !reachable.contains(option))
                    .toList();

            assertThat(unreachableOptions)
                    .as("%s: the question must have exactly one right answer", where)
                    .containsExactly(challenge.expectedAnswer());
        }
    }

    @ParameterizedTest
    @EnumSource(MissionDifficulty.class)
    @DisplayName("options are distinct nodes and include the answer")
    void optionsAreWellFormed(MissionDifficulty difficulty) {
        for (long seed = 1; seed <= 40; seed++) {
            PuzzleChallenge challenge = provider.create(difficulty, seed);

            assertThat(challenge.options()).hasSize(4);
            assertThat(new HashSet<>(challenge.options())).hasSize(4);
            assertThat(challenge.options()).contains(challenge.expectedAnswer());
            assertThat(challenge.options()).allMatch(option -> option.matches("N\\d+"));
        }
    }

    @Test
    @DisplayName("harder tiers build a bigger graph, not an ambiguous one")
    void harderTiersAreBiggerButStillUnambiguous() {
        for (long seed = 1; seed <= 20; seed++) {
            PuzzleChallenge easy = provider.create(MissionDifficulty.EASY, seed);
            PuzzleChallenge hard = provider.create(MissionDifficulty.HARD, seed);

            assertThat(countNodes(hard))
                    .as("seed %d: HARD must have more nodes than EASY", seed)
                    .isGreaterThan(countNodes(easy));

            assertThat(hard.options())
                    .as("seed %d: harder tiers still offer exactly one unreachable node", seed)
                    .hasSize(4)
                    .contains(hard.expectedAnswer());
        }
    }

    private static int countNodes(PuzzleChallenge challenge) {
        return challenge.sequence().get(0).replace("Nodes: ", "").split(",").length;
    }

    // ------------------------------------------------------------------
    // Helpers: parse the rendered graph back into a structure and walk it,
    // independently of how the provider built it.
    // ------------------------------------------------------------------

    private static Map<String, List<String>> parseEdges(List<String> sequence) {
        Map<String, List<String>> edges = new HashMap<>();
        for (String line : sequence) {
            String trimmed = line.trim();
            if (!trimmed.contains("->")) {
                continue;
            }
            String[] parts = trimmed.split("->");
            edges.computeIfAbsent(parts[0].strip(), key -> new ArrayList<>())
                    .add(parts[1].strip());
        }
        return edges;
    }

    private static String entryNode(List<String> sequence) {
        return sequence.get(0).replace("Nodes: ", "").split(",")[0].strip();
    }

    private static List<String> reachableFrom(Map<String, List<String>> edges, String entry) {
        List<String> seen = new ArrayList<>();
        java.util.Deque<String> queue = new java.util.ArrayDeque<>();
        queue.add(entry);

        while (!queue.isEmpty()) {
            String node = queue.poll();
            if (seen.contains(node)) {
                continue;
            }
            seen.add(node);
            queue.addAll(edges.getOrDefault(node, List.of()));
        }
        return seen;
    }
}