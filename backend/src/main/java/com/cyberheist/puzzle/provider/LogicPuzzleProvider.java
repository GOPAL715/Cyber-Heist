package com.cyberheist.puzzle.provider;

import com.cyberheist.mission.MissionDifficulty;
import com.cyberheist.puzzle.PuzzleChallenge;
import com.cyberheist.puzzle.PuzzleProvider;
import com.cyberheist.puzzle.PuzzleType;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Graph-reachability puzzles: which node cannot be reached from the entry node.
 *
 * <p>A small directed graph is generated as a connected component containing the
 * entry node, plus several disconnected islands. The question asks which node is
 * unreachable, so the options are exactly one island head (the answer) plus
 * reachable decoys.
 *
 * <p><strong>Exactly one unreachable option is offered, at every tier.</strong>
 * Offering two disconnected nodes would make the question genuinely ambiguous
 * - two right answers, no way to tell them apart - so difficulty is raised by
 * the size and shape of the graph instead: harder tiers have a longer reachable
 * component and larger islands, which is work the player actually has to do.
 *
 * <p>That invariant is enforced by construction here and checked independently
 * in {@code LogicPuzzleProviderTest}.
 */
@Component
public class LogicPuzzleProvider implements PuzzleProvider {

    /** Options offered, including the correct one. */
    private static final int OPTION_COUNT = 4;

    /** How many disconnected islands to scatter, so decoys vary per seed. */
    private static final int ISLAND_COUNT = 3;

    @Override
    public PuzzleType type() {
        return PuzzleType.LOGIC;
    }

    @Override
    public PuzzleChallenge create(MissionDifficulty difficulty, long seed) {
        Random random = new Random(seed);

        int pathLength = switch (difficulty) {
            case EASY, MEDIUM -> 4;
            case HARD, ELITE -> 6;
        };
        int maxIslandSize = switch (difficulty) {
            case EASY, MEDIUM -> 2;
            case HARD, ELITE -> 3;
        };

        // Draw the whole label pool first, then deal from it, so the same seed
        // always produces the same graph but different seeds produce visibly
        // different ones. Without this the rendered network barely moved between
        // attempts, which made repeat runs of the same mission feel scripted.
        int islandSize = 2 + random.nextInt(maxIslandSize - 1);
        List<String> labels = new ArrayList<>();
        for (int i = 1; i <= pathLength + ISLAND_COUNT * islandSize; i++) {
            labels.add("N" + i);
        }
        for (int i = labels.size() - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            String tmp = labels.get(i);
            labels.set(i, labels.get(j));
            labels.set(j, tmp);
        }

        List<String> path = new ArrayList<>(labels.subList(0, pathLength));

        List<List<String>> islands = new ArrayList<>();
        for (int islandIndex = 0; islandIndex < ISLAND_COUNT; islandIndex++) {
            int from = pathLength + islandIndex * islandSize;
            islands.add(new ArrayList<>(labels.subList(from, from + islandSize)));
        }

        List<String> nodes = new ArrayList<>(path);
        List<String> edges = new ArrayList<>();
        for (int i = 1; i < path.size(); i++) {
            edges.add(path.get(i - 1) + " -> " + path.get(i));
        }
        // Back edges make the component non-linear, so reachability has to be
        // reasoned about rather than read off the order of the nodes.
        if (path.size() > 3) {
            edges.add(path.get(path.size() - 1) + " -> " + path.get(1));
            edges.add(path.get(1) + " -> " + path.get(path.size() - 1));
        }
        for (List<String> island : islands) {
            nodes.addAll(island);
            for (int i = 1; i < island.size(); i++) {
                edges.add(island.get(i - 1) + " -> " + island.get(i));
            }
        }

        // The one correct option: a node that is genuinely disconnected.
        List<String> islandHeads = new ArrayList<>();
        for (List<String> island : islands) {
            islandHeads.add(island.get(0));
        }
        String answer = islandHeads.get(random.nextInt(islandHeads.size()));

        // Every distractor is reachable, so "which cannot be reached" has exactly
        // one answer no matter which four options the shuffle settles on.
        List<String> decoys = new ArrayList<>(path);
        for (int i = decoys.size() - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            String tmp = decoys.get(i);
            decoys.set(i, decoys.get(j));
            decoys.set(j, tmp);
        }

        List<String> options = PuzzleGeneratorSupport.optionsWithAnswer(
                random,
                answer,
                OPTION_COUNT,
                index -> decoys.get(index % decoys.size()));

        List<String> sequence = new ArrayList<>();
        sequence.add("Nodes: " + String.join(", ", nodes));
        sequence.add("Connections:");
        for (String edge : edges) {
            sequence.add("  " + edge);
        }

        return new PuzzleChallenge(
                PuzzleType.LOGIC,
                "Trace the network",
                "Starting at " + path.get(0) + ", which node cannot be reached?",
                sequence,
                options,
                answer);
    }

    @Override
    public int timeLimitSeconds(MissionDifficulty difficulty) {
        return switch (difficulty) {
            case EASY -> 180;
            case MEDIUM -> 210;
            case HARD -> 270;
            case ELITE -> 330;
        };
    }
}