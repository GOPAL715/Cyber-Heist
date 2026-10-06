package com.cyberheist.puzzle;

import com.cyberheist.mission.MissionDifficulty;
import com.cyberheist.puzzle.provider.PatternPuzzleProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.HashSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Pattern puzzles: a two-axis grid with one cell missing. */
class PatternPuzzleProviderTest {

    private final PatternPuzzleProvider provider = new PatternPuzzleProvider();

    @Test
    @DisplayName("the same seed always produces the same grid")
    void generationIsDeterministic() {
        for (MissionDifficulty difficulty : MissionDifficulty.values()) {
            for (long seed = 1; seed <= 40; seed++) {
                assertThat(provider.create(difficulty, seed))
                        .as("%s seed %d must be reproducible", difficulty, seed)
                        .isEqualTo(provider.create(difficulty, seed));
            }
        }
    }

    @ParameterizedTest
    @EnumSource(MissionDifficulty.class)
    @DisplayName("the grid is square, sized for the tier, with exactly one gap")
    void gridShapeMatchesTier(MissionDifficulty difficulty) {
        for (long seed = 1; seed <= 40; seed++) {
            PuzzleChallenge challenge = provider.create(difficulty, seed);
            List<String> rows = challenge.sequence();

            int expectedSize = switch (difficulty) {
                case EASY, MEDIUM -> 3;
                case HARD, ELITE -> 4;
            };
            assertThat(rows).hasSize(expectedSize);

            int gaps = 0;
            for (String row : rows) {
                assertThat(row.trim().split("\\s+")).hasSize(expectedSize);
                for (String cell : row.trim().split("\\s+")) {
                    if (cell.equals("?")) {
                        gaps++;
                    } else {
                        assertThat(cell).matches("[A-H]");
                    }
                }
            }
            assertThat(gaps).as("exactly one cell must be missing").isEqualTo(1);
        }
    }

    @ParameterizedTest
    @EnumSource(MissionDifficulty.class)
    @DisplayName("the answer is the symbol the stated rule places in the gap")
    void answerFollowsTheRule(MissionDifficulty difficulty) {
        for (long seed = 1; seed <= 40; seed++) {
            PuzzleChallenge challenge = provider.create(difficulty, seed);
            List<String> rows = challenge.sequence();

            int gapRow = -1;
            int gapColumn = -1;
            for (int r = 0; r < rows.size(); r++) {
                String[] cells = rows.get(r).trim().split("\\s+");
                for (int c = 0; c < cells.length; c++) {
                    if (cells[c].equals("?")) {
                        gapRow = r;
                        gapColumn = c;
                    }
                }
            }
            assertThat(gapRow).as("the gap must be in the last row").isEqualTo(rows.size() - 1);

            // The prompt states the rule: each row shifts every symbol back one
            // column. Rather than hardcoding a step, recover it from the grid
            // itself - stepping down a column adds the step, and stepping left
            // across a row adds it too - then check the rule holds everywhere.
            String[] firstRow = rows.get(0).trim().split("\\s+");
            String[] secondRow = rows.get(1).trim().split("\\s+");
            int step = Math.floorMod(secondRow[0].charAt(0) - firstRow[0].charAt(0), 8);

            for (int c = 0; c + 1 < firstRow.length; c++) {
                assertThat(Math.floorMod(firstRow[c].charAt(0) - firstRow[c + 1].charAt(0), 8))
                        .as("seed %d: each row must shift by the same step", seed)
                        .isEqualTo(step);
            }
            for (int r = 0; r + 1 < rows.size(); r++) {
                String[] upper = rows.get(r).trim().split("\\s+");
                String[] lower = rows.get(r + 1).trim().split("\\s+");
                for (int c = 0; c < upper.length; c++) {
                    if (r + 1 == gapRow && c == gapColumn) {
                        continue;
                    }
                    assertThat(Math.floorMod(lower[c].charAt(0) - upper[c].charAt(0), 8))
                            .as("seed %d: each column must count down by the same step", seed)
                            .isEqualTo(step);
                }
            }

            String[] rowAboveGap = rows.get(gapRow - 1).trim().split("\\s+");
            char expected = (char) ('A' + Math.floorMod(rowAboveGap[gapColumn].charAt(0) - 'A' + step, 8));
            assertThat(challenge.expectedAnswer())
                    .as("seed %d: the rule must fix the missing symbol", seed)
                    .isEqualTo(String.valueOf(expected));
        }
    }

    @Test
    @DisplayName("options are distinct symbols and include the answer")
    void optionsAreWellFormed() {
        for (MissionDifficulty difficulty : MissionDifficulty.values()) {
            for (long seed = 1; seed <= 40; seed++) {
                PuzzleChallenge challenge = provider.create(difficulty, seed);
                assertThat(challenge.options()).hasSize(4);
                assertThat(new HashSet<>(challenge.options())).hasSize(4);
                assertThat(challenge.options()).contains(challenge.expectedAnswer());
                assertThat(challenge.options()).allMatch(option -> option.matches("[A-H]"));
            }
        }
    }
}